package dev.moku.mobile.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dev.moku.tsunagu.embedded.Embedded
import dev.moku.tsunagu.embedded.SyncDiscovery
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Android won't let Tsunagu's own mDNS sockets join multicast, so other devices are found and this one is announced
// through NsdManager, the way iOS does it with Bonjour.
object SyncNsd : SyncDiscovery {
    private const val TYPE = "_moku._tcp"
    private const val TAG = "SyncNsd"

    private var nsd: NsdManager? = null
    private val found = ConcurrentHashMap<String, JSONObject>()
    private val resolver = Executors.newSingleThreadExecutor()
    private var discovery: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var advertised = ""
    @Volatile private var registeredName: String? = null

    /** Must run before the core starts; Tsunagu reads its discovery once, at start. */
    fun install(context: Context) {
        if (nsd != null) return
        nsd = context.applicationContext.getSystemService(NsdManager::class.java)
        Embedded.setSyncDiscovery(this)
    }

    /** Keeps the list of nearby devices fresh while the app is in front. */
    @Synchronized
    fun resume() {
        val nsd = nsd ?: return
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName != registeredName) resolver.execute { resolve(info) }
            }
            // Short-lived answers expire within seconds and come right back, so a device stays listed until discovery
            // stops; pairing with one that really left just fails to reach it.
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start: $errorCode")
                synchronized(this@SyncNsd) { discovery = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discovery", it); discovery = null }
        advertise()
    }

    @Synchronized
    fun pause() {
        val listener = discovery ?: return
        discovery = null
        runCatching { nsd?.stopServiceDiscovery(listener) }
        found.clear()
    }

    /** Announces this device once it's named; call again after naming or renaming it. */
    @Synchronized
    fun advertise() {
        val nsd = nsd ?: return
        val advert = runCatching { Embedded.syncAdvert() }.getOrDefault("")
        if (advert == advertised) return
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        registeredName = null
        advertised = advert
        if (advert.isEmpty()) return
        val json = runCatching { JSONObject(advert) }.getOrNull() ?: return
        val info = NsdServiceInfo().apply {
            serviceName = json.getString("name")
            serviceType = json.getString("type")
            port = json.getInt("port")
            val txt = json.getJSONObject("txt")
            txt.keys().forEach { setAttribute(it, txt.getString(it)) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "couldn't announce this device: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { Log.w(TAG, "announce", it) }
    }

    override fun browse(waitMillis: Long): String {
        if (discovery == null) resume()
        // Devices found while the app was open are already resolved; the wait catches ones that just appeared.
        if (found.isEmpty()) Thread.sleep(waitMillis.coerceIn(0, 1_500))
        return JSONArray(found.values.toList()).toString()
    }

    private fun resolve(info: NsdServiceInfo) {
        val nsd = nsd ?: return
        val done = CountDownLatch(1)
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val node = resolved.attributes["node"]?.decodeToString()
                val ip = ipv4(resolved)
                if (!node.isNullOrEmpty() && ip != null) {
                    val name = resolved.attributes["name"]?.decodeToString() ?: node
                    found[resolved.serviceName] = JSONObject()
                        .put("node", node)
                        .put("name", name)
                        .put("address", "http://${ip.hostAddress}:${resolved.port}")
                }
                done.countDown()
            }
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                done.countDown()
            }
        })
        // Older Android resolves one service at a time, so the next waits for this one.
        done.await(5, TimeUnit.SECONDS)
    }

    private fun ipv4(info: NsdServiceInfo): InetAddress? {
        val all = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else @Suppress("DEPRECATION") listOfNotNull(info.host)
        return all.firstOrNull { it is Inet4Address }
    }
}
