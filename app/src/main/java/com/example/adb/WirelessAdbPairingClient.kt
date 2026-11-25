package com.example.adb

import android.util.Base64
import android.util.Log
import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Zero-reflection Android 11+ Wireless Debugging (ADB over Wi-Fi) TLS 1.3 + SPAKE2 pairing client.
 *
 * Uses the standalone bundled Conscrypt provider ([org.conscrypt.Conscrypt.newProvider]) to create
 * the TLSv1.3 [SSLSocket] and calls [org.conscrypt.Conscrypt.exportKeyingMaterial] directly,
 * avoiding all hidden-API reflection against `com.android.org.conscrypt.Conscrypt` that fails on
 * Android 14–16.
 */
object WirelessAdbPairingClient {

    private const val TAG = "WirelessAdbPairing"

    // AOSP pairing_connection.cpp protocol constants
    private const val PROTOCOL_VERSION: Byte = 1
    private const val PACKET_TYPE_SPAKE2_MSG: Byte = 0
    private const val PACKET_TYPE_PEER_INFO: Byte = 1
    private const val MAX_PAYLOAD_SIZE = 16 * 1024
    private const val HEADER_SIZE = 6

    // AOSP tls_connection.cpp: sizeof("adb-label") == 10 (includes NUL terminator)
    private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
    private const val EXPORTED_KEY_SIZE = 64

    // AOSP pairing_auth.cpp: sizeof("adb pair client") == 16, sizeof("adb pair server") == 16
    private val SPAKE2_CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.UTF_8)
    private val SPAKE2_SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.UTF_8)

    // AOSP aes_128_gcm.cpp: sizeof(kHkdfInfo) - 1 == 32 (no NUL terminator)
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.UTF_8)
    private const val AES_KEY_SIZE_BYTES = 16
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_SIZE = 12

    // AOSP pairing_connection.h: PeerInfo is 1-byte type + 8191-byte data = 8192 bytes total
    private const val PEER_INFO_SIZE = 8192
    private const val PEER_INFO_TYPE_ADB_RSA_PUB_KEY: Byte = 0

    // AOSP android_pubkey.h: 2048-bit RSA = 64 x 32-bit words -> 524 bytes binary struct
    private const val ANDROID_PUBKEY_MODULUS_WORDS = 64
    private const val ANDROID_PUBKEY_STRUCT_BYTES = 4 + 4 + (ANDROID_PUBKEY_MODULUS_WORDS * 4) * 2 + 4

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val SOCKET_TIMEOUT_MS = 10_000

    /**
     * Creates a TLSv1.3 [SSLContext] backed by the standalone bundled Conscrypt provider so that
     * every [SSLSocket] created from it supports [Conscrypt.exportKeyingMaterial] directly.
     */
    fun createConscryptSslContext(
        privateKey: PrivateKey,
        certificate: X509Certificate
    ): SSLContext {
        val sslContext = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
        val keyManager = createClientKeyManager(privateKey, certificate)
        val trustManager = createPairingTrustManager()
        sslContext.init(
            arrayOf<KeyManager>(keyManager),
            arrayOf<TrustManager>(trustManager),
            SecureRandom()
        )
        return sslContext
    }

    /**
     * Executes the complete Android 11+ Wireless ADB pairing handshake over TLSv1.3 + SPAKE2.
     *
     * @return `true` if `adbd` accepted our RSA public key and returned a valid encrypted PeerInfo.
     */
    fun pair(
        host: String,
        port: Int,
        pairingCode: String,
        privateKey: PrivateKey,
        certificate: X509Certificate,
        deviceName: String
    ): Boolean {
        require(host.isNotBlank()) { "Pairing host must not be blank" }
        require(port in 1..65535) { "Invalid pairing port: $port" }
        val cleanCode = pairingCode.trim()
        require(cleanCode.length == 6 && cleanCode.all { it.isDigit() }) {
            "Pairing code must be 6 digits"
        }

        val rsaPublicKey = certificate.publicKey as? RSAPublicKey
            ?: throw IllegalArgumentException("ADB pairing requires an RSA X.509 certificate")

        val sslContext = createConscryptSslContext(privateKey, certificate)

        Socket().use { rawSocket ->
            rawSocket.tcpNoDelay = true
            rawSocket.soTimeout = SOCKET_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

            val sslSocket = sslContext.socketFactory.createSocket(
                rawSocket,
                host,
                port,
                true
            ) as SSLSocket

            sslSocket.use { tlsSocket ->
                tlsSocket.soTimeout = SOCKET_TIMEOUT_MS
                tlsSocket.enabledProtocols = arrayOf("TLSv1.3")
                tlsSocket.startHandshake()

                // Direct call to bundled Conscrypt — zero reflection on hidden Android APIs
                val exportedKeyMaterial = Conscrypt.exportKeyingMaterial(
                    tlsSocket,
                    EXPORTED_KEY_LABEL,
                    null,
                    EXPORTED_KEY_SIZE
                ) ?: throw IOException("Conscrypt returned null TLS keying material")

                val codeBytes = cleanCode.toByteArray(Charsets.UTF_8)
                val spake2Password = ByteArray(codeBytes.size + exportedKeyMaterial.size)
                System.arraycopy(codeBytes, 0, spake2Password, 0, codeBytes.size)
                System.arraycopy(
                    exportedKeyMaterial,
                    0,
                    spake2Password,
                    codeBytes.size,
                    exportedKeyMaterial.size
                )

                val input = DataInputStream(tlsSocket.inputStream)
                val output = DataOutputStream(tlsSocket.outputStream)

                val spake2 = Spake2Context(
                    Spake2Role.Alice,
                    SPAKE2_CLIENT_NAME,
                    SPAKE2_SERVER_NAME
                )

                try {
                    // Step 1: Exchange SPAKE2 messages
                    val ourSpake2Msg = spake2.generateMessage(spake2Password)
                    writePacket(output, PACKET_TYPE_SPAKE2_MSG, ourSpake2Msg)

                    val (peerMsgType, peerSpake2Msg) = readPacket(input)
                    if (peerMsgType != PACKET_TYPE_SPAKE2_MSG) {
                        throw IOException("Expected SPAKE2 packet (0), received type $peerMsgType")
                    }

                    val sharedKeyMaterial = spake2.processMessage(peerSpake2Msg)
                    val aesKey = deriveAes128GcmKey(sharedKeyMaterial)

                    // Step 2: Build and encrypt our 8192-byte PeerInfo containing the ADB RSA public key
                    val adbPubKeyBytes = encodeAndroidRsaPublicKeyWithName(rsaPublicKey, deviceName)
                    val ourPeerInfo = ByteArray(PEER_INFO_SIZE)
                    ourPeerInfo[0] = PEER_INFO_TYPE_ADB_RSA_PUB_KEY
                    val copyLen = minOf(adbPubKeyBytes.size, PEER_INFO_SIZE - 1)
                    System.arraycopy(adbPubKeyBytes, 0, ourPeerInfo, 1, copyLen)

                    val encryptedPeerInfo = encryptAesGcm(aesKey, sequence = 0L, plaintext = ourPeerInfo)
                    writePacket(output, PACKET_TYPE_PEER_INFO, encryptedPeerInfo)

                    // Step 3: Read and decrypt peer's PeerInfo to confirm adbd accepted our key
                    val (peerInfoType, peerEncryptedInfo) = readPacket(input)
                    if (peerInfoType != PACKET_TYPE_PEER_INFO) {
                        throw IOException("Expected PEER_INFO packet (1), received type $peerInfoType")
                    }

                    val decryptedPeerInfo = decryptAesGcm(aesKey, sequence = 0L, ciphertext = peerEncryptedInfo)
                    if (decryptedPeerInfo.size != PEER_INFO_SIZE) {
                        throw IOException(
                            "Invalid decrypted PeerInfo size (${decryptedPeerInfo.size} != $PEER_INFO_SIZE)"
                        )
                    }

                    Log.i(TAG, "Wireless ADB TLSv1.3 + SPAKE2 pairing succeeded on $host:$port")
                    return true
                } finally {
                    spake2Password.fill(0)
                    exportedKeyMaterial.fill(0)
                    runCatching { spake2.destroy() }
                }
            }
        }
    }

    private fun writePacket(output: DataOutputStream, type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(PROTOCOL_VERSION)
        header.put(type)
        header.putInt(payload.size)
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    private fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val headerBytes = ByteArray(HEADER_SIZE)
        input.readFully(headerBytes)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val version = header.get()
        val type = header.get()
        val payloadSize = header.int

        if (version != PROTOCOL_VERSION) {
            throw IOException("Unsupported ADB pairing protocol version: $version")
        }
        if (payloadSize < 0 || payloadSize > MAX_PAYLOAD_SIZE) {
            throw IOException("Invalid ADB pairing packet size: $payloadSize")
        }

        val payload = ByteArray(payloadSize)
        input.readFully(payload)
        return type to payload
    }

    private fun deriveAes128GcmKey(sharedSecret: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, null, HKDF_INFO))
        val key = ByteArray(AES_KEY_SIZE_BYTES)
        hkdf.generateBytes(key, 0, key.size)
        return key
    }

    private fun buildGcmNonce(sequence: Long): ByteArray {
        val nonce = ByteArray(GCM_NONCE_SIZE)
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence)
        return nonce
    }

    private fun encryptAesGcm(key: ByteArray, sequence: Long, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, buildGcmNonce(sequence))
        )
        return cipher.doFinal(plaintext)
    }

    private fun decryptAesGcm(key: ByteArray, sequence: Long, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, buildGcmNonce(sequence))
        )
        return cipher.doFinal(ciphertext)
    }

    /**
     * Encodes a 2048-bit [RSAPublicKey] into AOSP's `android_pubkey` base64 wire format
     * followed by `" <deviceName>\u0000"`.
     */
    internal fun encodeAndroidRsaPublicKeyWithName(
        publicKey: RSAPublicKey,
        deviceName: String
    ): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        val wordBase = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(ANDROID_PUBKEY_MODULUS_WORDS * 32)
        val rr = r.multiply(r).mod(modulus)
        val n0Inv = wordBase.subtract(modulus.mod(wordBase).modInverse(wordBase)).mod(wordBase)

        val buffer = ByteBuffer.allocate(ANDROID_PUBKEY_STRUCT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(ANDROID_PUBKEY_MODULUS_WORDS)
        buffer.putInt(n0Inv.toInt())

        var nCursor = modulus
        for (i in 0 until ANDROID_PUBKEY_MODULUS_WORDS) {
            buffer.putInt(nCursor.and(wordBase.subtract(BigInteger.ONE)).toInt())
            nCursor = nCursor.shiftRight(32)
        }

        var rrCursor = rr
        for (i in 0 until ANDROID_PUBKEY_MODULUS_WORDS) {
            buffer.putInt(rrCursor.and(wordBase.subtract(BigInteger.ONE)).toInt())
            rrCursor = rrCursor.shiftRight(32)
        }

        buffer.putInt(exponent.toInt())

        val base64Key = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        val safeName = deviceName.ifBlank { "PadForge" }.replace('\u0000', ' ')
        return "$base64Key $safeName\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun createClientKeyManager(
        privateKey: PrivateKey,
        certificate: X509Certificate
    ): X509ExtendedKeyManager {
        val alias = "padforge-adb"
        return object : X509ExtendedKeyManager() {
            override fun getClientAliases(
                keyType: String?,
                issuers: Array<out java.security.Principal>?
            ): Array<String>? = if (keyType == "RSA") arrayOf(alias) else null

            override fun chooseClientAlias(
                keyTypes: Array<out String>?,
                issuers: Array<out java.security.Principal>?,
                socket: Socket?
            ): String? = if (keyTypes?.any { it == "RSA" } == true) alias else null

            override fun getServerAliases(
                keyType: String?,
                issuers: Array<out java.security.Principal>?
            ): Array<String>? = null

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out java.security.Principal>?,
                socket: Socket?
            ): String? = null

            override fun getCertificateChain(aliasName: String?): Array<X509Certificate>? =
                if (aliasName == alias) arrayOf(certificate) else null

            override fun getPrivateKey(aliasName: String?): PrivateKey? =
                if (aliasName == alias) privateKey else null
        }
    }

    /**
     * Android's local adbd pairing daemon presents an ephemeral self-signed TLS certificate;
     * peer identity and MITM protection are cryptographically enforced by SPAKE2 bound to the
     * TLS session via [Conscrypt.exportKeyingMaterial].
     */
    private fun createPairingTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
