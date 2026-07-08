package com.jp.paperplayer.party

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.jp.paperplayer.model.data.DiscoveredParty

/**
 * Thin wrapper around NsdManager for advertising (host) and discovering (guest)
 * parties on the LAN. Holds a multicast lock while discovery runs — some devices
 * drop multicast packets without it.
 */
class NsdHelper(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun registerService(serviceName: String, port: Int, onError: (String) -> Unit) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = PartyProtocol.SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                Log.d(TAG, "Registered as ${registered.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                onError("Could not advertise the party (error $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregisterService() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
    }

    fun startDiscovery(
        onFound: (DiscoveredParty) -> Unit,
        onLost: (serviceName: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith("_paperplayer.")) return
                @Suppress("DEPRECATION")
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        onFound(DiscoveredParty(resolved.serviceName, host, resolved.port))
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                onLost(info.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                releaseMulticastLock()
                onError("Could not search for parties (error $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(PartyProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("paperplayer_party").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private companion object {
        const val TAG = "NsdHelper"
    }
}
