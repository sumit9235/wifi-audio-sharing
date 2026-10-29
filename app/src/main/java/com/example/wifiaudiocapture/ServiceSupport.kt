package com.example.wifiaudiocapture

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.PowerManager

object ServiceNotifications {
    const val CHANNEL = "live_audio_v2"
    fun create(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Live Wi-Fi audio", NotificationManager.IMPORTANCE_LOW))
    }
    fun build(context: Context, host: Boolean, message: String): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).putExtra("page", if (host) "host" else "listen"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = if (host) Intent(context, CaptureService::class.java).setAction(CaptureService.STOP_ACTION)
                         else Intent(context, ListenerService::class.java).setAction(ListenerService.STOP)
        val stop = PendingIntent.getService(context, if (host) 1 else 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (host) "Sharing Wi-Fi audio" else "Listening to Wi-Fi audio")
            .setContentText(message).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, if (host) "Stop sharing" else "Disconnect", stop).build()).build()
    }
}

/** Hold only during explicit sharing/listening. Network loss stops, never silently recaptures. */
@Suppress("DEPRECATION")
class SessionResources(context: Context, private val lost: () -> Unit) : AutoCloseable {
    private val power = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiAudio:stream").apply { setReferenceCounted(false) }
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WifiAudio:stream").apply { setReferenceCounted(false) }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var closed = false
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) { if (!closed) lost() }
    }
    init {
        try {
            power.acquire(6 * 60 * 60 * 1000L); wifi.acquire()
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            registered = true
        } catch (e: Exception) { close(); throw e }
    }
    override fun close() {
        closed = true
        if (registered) { connectivity.unregisterNetworkCallback(callback); registered = false }
        if (wifi.isHeld) wifi.release()
        if (power.isHeld) power.release()
    }
}

/** Route LAN sockets over Wi-Fi even when Android prefers cellular for internet. */
@Suppress("DEPRECATION")
object WifiRoute {
    fun network(context: Context): Network? {
        val c = context.getSystemService(ConnectivityManager::class.java)
        return c.allNetworks.firstOrNull { c.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }
    fun address(context: Context): java.net.InetAddress? {
        val c = context.getSystemService(ConnectivityManager::class.java)
        return network(context)?.let { c.getLinkProperties(it) }?.linkAddresses
            ?.firstOrNull { it.address is java.net.Inet4Address }?.address
    }
}
