package com.chistyl.tvremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/** Discovers Android TV Remote services. Pairing and TLS are intentionally separate stages. */
class TvDiscoveryManager(
    context: Context,
    private val onDevice: (TvDevice) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val active = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val serviceTypes = listOf("_androidtvremote2._tcp.", "_androidtvremote._tcp.")

    @Synchronized fun start() {
        if (active.isNotEmpty()) return
        runCatching {
            multicastLock = wifi.createMulticastLock("TVRemote-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Unable to acquire multicast lock", it) }
        serviceTypes.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { onStatus("正在搜尋 $serviceType") }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolve failed ${info.serviceName}: $errorCode")
                        }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            onDevice(TvDevice(info.serviceName, info.serviceType, info.host, info.port))
                        }
                    })
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) { onStatus("裝置離線：${serviceInfo.serviceName}") }
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    onStatus("搜尋啟動失敗：$serviceType ($errorCode)")
                    runCatching { nsd.stopServiceDiscovery(this) }
                    active.remove(serviceType)
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    runCatching { nsd.stopServiceDiscovery(this) }
                }
            }
            active[type] = listener
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    @Synchronized fun stop() {
        active.values.toList().forEach { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
        active.clear()
        runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
        multicastLock = null
        onStatus("已停止搜尋")
    }

    companion object { private const val TAG = "TvDiscovery" }
}
