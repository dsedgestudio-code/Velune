package com.nikhil.yt.together

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a session on the same Wi-Fi from its code alone: the host advertises a service named after the
 * room id (never the code), and a guest looks for that name. If nothing answers quickly, the guest uses
 * the online relay instead, so people never have to pick "LAN" or "online".
 */
class TogetherNsd(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null

    fun advertise(roomId: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = serviceName(roomId)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registration = listener }
    }

    fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    /** Host and port of the session on this network, or null if it isn't here. */
    @Suppress("DEPRECATION") // resolveService: the callback form is the one available back to API 26.
    suspend fun find(roomId: String, timeoutMs: Long = 2_500): Pair<String, Int>? {
        val wanted = serviceName(roomId)
        val result = CompletableDeferred<Pair<String, Int>?>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName != wanted) return
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            result.complete(host to info.port)
                        }
                    })
                }
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { result.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        if (runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }.isFailure) return null
        return try {
            withTimeoutOrNull(timeoutMs) { result.await() }
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }

    private fun serviceName(roomId: String) = "spectra-${roomId.take(16)}"

    private companion object {
        const val SERVICE_TYPE = "_spectra._tcp."
    }
}
