package com.example.adb

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.server.PadForgeInputServer
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Built-in Wireless ADB Engine extending AbsAdbConnectionManager (libadb-android).
 * Generates and stores its own 2048-bit RSA identity & X.509 certificate in app-private storage,
 * pairs directly with Android 11+ Wireless Debugging, and boots PadForgeInputServer from this APK
 * without requiring any external third-party Shizuku application.
 */
class BuiltInAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: X509Certificate

    init {
        api = Build.VERSION.SDK_INT
        val (key, cert) = loadOrCreateCredentials(context.applicationContext)
        privateKey = key
        certificate = cert
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "PadForge"

    /**
     * Pairs with Android 11+ Wireless Debugging trying the mDNS-discovered host first
     * and falling back to loopback (127.0.0.1) so pairing works across all OEMs (OnePlus, Pixel, Samsung, Xiaomi).
     *
     * Uses [WirelessAdbPairingClient] backed by standalone `org.conscrypt:conscrypt-android` so
     * no hidden-API reflection on `com.android.org.conscrypt.Conscrypt` is ever performed.
     */
    fun pairWireless(port: Int, pairingCode: String, host: String? = null): Boolean {
        require(port in 1..65535) { "Invalid pairing port ($port)" }
        val cleanCode = pairingCode.trim()
        require(cleanCode.length == 6 && cleanCode.all { it.isDigit() }) {
            "Pairing code must be 6 digits"
        }

        val candidateHosts = buildList {
            if (!host.isNullOrBlank() && host != LOOPBACK_HOST) {
                add(host)
            }
            add(LOOPBACK_HOST)
        }

        var lastError: Throwable? = null
        for (candidate in candidateHosts) {
            try {
                if (WirelessAdbPairingClient.pair(
                        host = candidate,
                        port = port,
                        pairingCode = cleanCode,
                        privateKey = privateKey,
                        certificate = certificate,
                        deviceName = deviceName
                    )
                ) {
                    return true
                }
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Wireless pairing attempt to $candidate:$port failed: ${t.message}")
            }
        }
        if (lastError != null) throw lastError
        return false
    }

    /**
     * Connects to local Wireless Debugging and launches PadForgeInputServer
     * inside the privileged UID 2000 shell process directly from our installed APK.
     */
    fun launchBuiltInDaemon(
        context: Context,
        connectHost: String?,
        connectPort: Int?,
        daemonPort: Int,
        sessionToken: String
    ): Result<Unit> {
        return runCatching {
            require(daemonPort in 1024..65535) { "Invalid daemon port" }
            require(HEX_TOKEN_REGEX.matches(sessionToken)) { "Invalid session token format" }

            // Android 11-16 APK paths look like /data/app/~~abc==/com.pkg-xyz==/base.apk
            val apkPath = context.applicationInfo.sourceDir
            require(
                apkPath.isNotBlank() &&
                    apkPath.endsWith(".apk") &&
                    SAFE_PATH_REGEX.matches(apkPath) &&
                    File(apkPath).exists()
            ) {
                "Unable to verify APK path for built-in daemon launch"
            }

            // Always drop any stale dead TLS connection before establishing a fresh one
            runCatching { disconnect() }

            val connected = establishAdbConnection(context.applicationContext, connectHost, connectPort)
            check(connected) {
                "Could not connect to Wireless Debugging. Make sure PadForge is paired under Wireless debugging -> Paired devices."
            }

            // Step 1: Terminate any previous daemon in its own stream (bracket regex prevents matching itself)
            runCatching {
                openStream("shell:pkill -f '[c]om.example.server.PadForgeInputServer'").use { killStream ->
                    killStream.openInputStream().bufferedReader().readText()
                }
            }

            // Step 2: Launch fresh authenticated PadForgeInputServer detached via setsid/nohup
            val launchCmd = buildString {
                append("(setsid /system/bin/app_process ")
                append("-Djava.class.path='")
                append(apkPath)
                append("' /system/bin ")
                append(PadForgeInputServer::class.java.name)
                append(" --port=")
                append(daemonPort)
                append(" --token=")
                append(sessionToken)
                append(" </dev/null >/dev/null 2>&1 & ) || ")
                append("(nohup /system/bin/app_process ")
                append("-Djava.class.path='")
                append(apkPath)
                append("' /system/bin ")
                append(PadForgeInputServer::class.java.name)
                append(" --port=")
                append(daemonPort)
                append(" --token=")
                append(sessionToken)
                append(" </dev/null >/dev/null 2>&1 & ); ")
                append("sleep 0.4")
            }

            openStream("shell:$launchCmd").use { stream ->
                runCatching {
                    stream.openInputStream().bufferedReader().readText()
                }
            }
            Log.i(TAG, "Built-in PadForgeInputServer launch command dispatched cleanly")
        }
    }

    private fun establishAdbConnection(
        appContext: Context,
        preferredHost: String?,
        preferredPort: Int?
    ): Boolean {
        var lastError: Throwable? = null

        if (preferredPort != null && preferredPort in 1..65535) {
            val hosts = buildList {
                if (!preferredHost.isNullOrBlank() && preferredHost != LOOPBACK_HOST) {
                    add(preferredHost)
                }
                add(LOOPBACK_HOST)
            }
            for (host in hosts) {
                try {
                    if (connect(host, preferredPort)) {
                        return true
                    }
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "ADB connect to $host:$preferredPort failed: ${t.message}")
                    runCatching { disconnect() }
                }
            }
        }

        // Fallback to libadb-android TLS / auto-connect discovery
        try {
            if (connectTls(appContext, AUTO_CONNECT_TIMEOUT_MS)) {
                return true
            }
        } catch (t: Throwable) {
            lastError = t
            Log.w(TAG, "ADB connectTls fallback failed: ${t.message}")
            runCatching { disconnect() }
        }

        try {
            if (autoConnect(appContext, AUTO_CONNECT_TIMEOUT_MS)) {
                return true
            }
        } catch (t: Throwable) {
            lastError = t
            Log.w(TAG, "ADB autoConnect fallback failed: ${t.message}")
        }

        if (lastError != null) {
            val rawMsg = lastError.message.orEmpty()
            if (rawMsg.contains("connection closed", ignoreCase = true) ||
                rawMsg.contains("EOF", ignoreCase = true) ||
                rawMsg.contains("reset", ignoreCase = true) ||
                rawMsg.contains("BAD_CERTIFICATE", ignoreCase = true) ||
                rawMsg.contains("alert", ignoreCase = true)
            ) {
                throw IllegalStateException(
                    "Wireless Debugging rejected the connection. First tap 'Pair Code' -> 'Pair via Notification' so PadForge appears under Paired devices."
                )
            }
            throw lastError
        }
        return false
    }

    private fun loadOrCreateCredentials(context: Context): Pair<PrivateKey, X509Certificate> {
        val keyFile = File(context.filesDir, PRIVATE_KEY_FILE)
        val certFile = File(context.filesDir, CERT_FILE)

        if (keyFile.exists() && certFile.exists()) {
            val loaded = runCatching {
                val keySpec = PKCS8EncodedKeySpec(keyFile.readBytes())
                val priv = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                val certFactory = CertificateFactory.getInstance("X.509")
                val cert = certFactory.generateCertificate(
                    ByteArrayInputStream(certFile.readBytes())
                ) as X509Certificate
                priv to cert
            }.getOrNull()
            if (loaded != null) return loaded
        }

        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val subject = X500Name("CN=PadForge Built-in Engine")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)
        val notAfter = Date(now + 10L * 365L * 86_400_000L)
        val serial = BigInteger(64, SecureRandom()).abs()

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val x509Cert = JcaX509CertificateConverter().getCertificate(certHolder)

        keyFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(x509Cert.encoded)
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)
        keyFile.setWritable(false, false)
        keyFile.setWritable(true, true)

        return keyPair.private to x509Cert
    }

    companion object {
        private const val TAG = "BuiltInAdbManager"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val AUTO_CONNECT_TIMEOUT_MS = 6_000L
        private const val PRIVATE_KEY_FILE = "padforge_adb_key.pk8"
        private const val CERT_FILE = "padforge_adb_cert.der"

        // Allows Android 11+ randomized /data/app/~~hash==/pkg-hash==/base.apk paths while blocking shell metacharacters
        private val SAFE_PATH_REGEX = Regex("^[A-Za-z0-9_./~=+@\\-]+$")
        private val HEX_TOKEN_REGEX = Regex("^[0-9a-f]{64}$")

        @Volatile
        private var instance: BuiltInAdbManager? = null

        fun getInstance(context: Context): BuiltInAdbManager {
            return instance ?: synchronized(this) {
                instance ?: BuiltInAdbManager(context.applicationContext).also { instance = it }
            }
        }

        fun generateSessionToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
