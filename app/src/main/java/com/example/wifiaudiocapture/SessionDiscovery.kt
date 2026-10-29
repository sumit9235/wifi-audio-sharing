package com.example.wifiaudiocapture

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** All NSD state is serialized on main. Resolves are queued for Android 10–13. */
@Suppress("DEPRECATION")
class SessionDiscovery(context: Context, private val changed: (List<Session>) -> Unit,
                       private val status: (String) -> Unit) : AutoCloseable {
    data class Session(val name: String, val host: String, val port: Int)
    companion object { const val TYPE = "_wifiaudio._udp." }
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = context.applicationContext.getSystemService(WifiManager::class.java)
        .createMulticastLock("wifi-audio-discovery").apply { setReferenceCounted(false) }
    private val sessions = linkedMapOf<String, Session>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val found = mutableSetOf<String>()
    private var resolving = false
    private var closed = false
    private var generation = 0
    private var browser: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var networkRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { if (!closed) { status("Wi-Fi available. Refresh sessions if needed.") } } }
        override fun onLost(network: Network) { main.post {
            sessions.clear(); found.clear(); changed(emptyList())
            if (!closed) status("Wi-Fi changed or disconnected. Reconnect and refresh; the host may need to restart sharing.")
        } }
    }
    fun advertise(name: String, port: Int = CaptureService.PORT) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { status("Visible as ${info.serviceName}") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { status("Discovery advertisement failed ($error). Join by IP is available.") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        val info = NsdServiceInfo().apply {
            serviceName = "Wi-Fi Audio ${name.take(24)}"
            serviceType = TYPE
            this.port = port
            setAttribute("version", "1")
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { status("Discovery unavailable: ${e.message}. Join by IP.") }
    }
    fun browse() {
        stopBrowser(); closed = false
        lock.acquire()
        if (!networkRegistered) {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback)
            networkRegistered = true
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { status("Looking for sessions on this Wi-Fi…") }
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (browser !== this || closed || !found.add(info.serviceName)) return@post
                pending.add(info); resolveNext()
            } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post {
                found.remove(info.serviceName); sessions.remove(info.serviceName); changed(sessions.values.toList())
            } }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { status("Discovery failed ($error). Try refresh or Join by IP.") }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        browser = listener
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { status("Discovery unavailable. Check Wi-Fi or join by IP: ${e.message}") }
    }
    private fun resolveNext() {
        if (closed || resolving || pending.isEmpty()) return
        val info = pending.removeFirst()
        val epoch = generation
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, error: Int) { main.post {
                resolving = false
                if (epoch == generation) found.remove(service.serviceName)
                resolveNext()
            } }
            override fun onServiceResolved(service: NsdServiceInfo) { main.post {
                resolving = false
                if (!closed && epoch == generation && service.serviceName in found) {
                    val address = service.host?.hostAddress
                    if (address != null && !address.contains(':')) {
                        sessions[service.serviceName] = Session(service.serviceName, address, service.port)
                        changed(sessions.values.toList())
                    }
                }
                resolveNext()
            } }
        })
    }
    private fun stopBrowser() {
        generation++
        browser?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) { } }
        browser = null; pending.clear(); found.clear(); sessions.clear(); changed(emptyList())
        if (lock.isHeld) lock.release()
    }
    override fun close() {
        closed = true; stopBrowser()
        registration?.let { try { nsd.unregisterService(it) } catch (_: Exception) { } }; registration = null
        if (networkRegistered) { connectivity.unregisterNetworkCallback(networkCallback); networkRegistered = false }
    }
}
