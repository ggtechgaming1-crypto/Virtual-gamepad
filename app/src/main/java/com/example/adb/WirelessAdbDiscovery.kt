package com.example.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.Executors

data class AdbEndpoint(
    val host: String,
    val port: Int
)

/**
 * Discovers local Android 11+ Wireless Debugging mDNS endpoints (both IPv4 host and port):
 * - `_adb-tls-pairing._tcp.` for one-time pairing auto-detection
 * - `_adb-tls-connect._tcp.` for one-tap Wireless Debugging connection
 */
class WirelessAdbDiscovery(context: Context) {

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    private val _pairingEndpoint = MutableStateFlow<AdbEndpoint?>(null)
    val pairingEndpoint: StateFlow<AdbEndpoint?> = _pairingEndpoint.asStateFlow()

    private val _connectEndpoint = MutableStateFlow<AdbEndpoint?>(null)
    val connectEndpoint: StateFlow<AdbEndpoint?> = _connectEndpoint.asStateFlow()

    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null
    private val activeInfoCallbacks = java.util.concurrent.CopyOnWriteArrayList<Any>()

    fun startDiscovery() {
        val manager = nsdManager ?: return
        if (pairingListener == null) {
            val listener = createListener(SERVICE_PAIRING) { endpoint ->
                _pairingEndpoint.value = endpoint
            }
            pairingListener = listener
            runCatching {
                manager.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                pairingListener = null
                Log.w(TAG, "Pairing mDNS discovery failed: ${it.message}")
            }
        }
        if (connectListener == null) {
            val listener = createListener(SERVICE_CONNECT) { endpoint ->
                _connectEndpoint.value = endpoint
            }
            connectListener = listener
            runCatching {
                manager.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                connectListener = null
                Log.w(TAG, "Connect mDNS discovery failed: ${it.message}")
            }
        }
    }

    fun stopDiscovery() {
        val manager = nsdManager ?: return
        pairingListener?.let { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        connectListener?.let { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activeInfoCallbacks.forEach { cb ->
                if (cb is NsdManager.ServiceInfoCallback) {
                    runCatching { manager.unregisterServiceInfoCallback(cb) }
                }
            }
            activeInfoCallbacks.clear()
        }
        pairingListener = null
        connectListener = null
    }

    private fun createListener(
        serviceType: String,
        onEndpointResolved: (AdbEndpoint?) -> Unit
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "Start discovery failed ($serviceType): $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains(serviceType.removeSuffix(".")) != true) return
                resolveService(serviceInfo, onEndpointResolved)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onEndpointResolved(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(
        serviceInfo: NsdServiceInfo,
        onEndpointResolved: (AdbEndpoint?) -> Unit
    ) {
        val manager = nsdManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                    override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                        val port = updatedInfo.port
                        if (port in 1..65535) {
                            val host = updatedInfo.hostAddresses
                                .firstOrNull { it is Inet4Address }
                                ?.hostAddress
                                ?: updatedInfo.hostAddresses.firstOrNull()?.hostAddress
                                ?: LOOPBACK_HOST
                            onEndpointResolved(AdbEndpoint(host, port))
                        }
                    }
                    override fun onServiceLost() {
                        onEndpointResolved(null)
                    }
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                activeInfoCallbacks.add(callback)
                manager.registerServiceInfoCallback(
                    serviceInfo,
                    resolveExecutor,
                    callback
                )
            }
        } else {
            runCatching {
                manager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) = Unit
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val port = resolved.port
                            if (port in 1..65535) {
                                val host = resolved.host?.hostAddress ?: LOOPBACK_HOST
                                onEndpointResolved(AdbEndpoint(host, port))
                            }
                        }
                    }
                )
            }
        }
    }

    companion object {
        private const val TAG = "WirelessAdbDiscovery"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."
        private const val SERVICE_CONNECT = "_adb-tls-connect._tcp."
    }
}
