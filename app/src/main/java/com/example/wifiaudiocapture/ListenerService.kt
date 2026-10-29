package com.example.wifiaudiocapture

import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*

/** Playback belongs to a foreground service, not the screen's lifecycle. */
class ListenerService : Service() {
    companion object {
        const val START = "com.example.wifiaudiocapture.LISTEN"
        const val STOP = "com.example.wifiaudiocapture.DISCONNECT"
        const val VOLUME = "com.example.wifiaudiocapture.VOLUME"
    }
    private var listener: AudioListener? = null
    private var resources: SessionResources? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var desiredVolume = 1f
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { end("Audio output changed. Rejoin when ready.") }
    }
    private var receiverRegistered = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); ServiceNotifications.create(this); audio = getSystemService(AudioManager::class.java)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { end("Disconnected"); return START_NOT_STICKY }
        if (intent?.action == VOLUME) {
            desiredVolume = intent.getFloatExtra("volume", 1f).coerceIn(0f, 1f)
            listener?.volume = desiredVolume
            if (listener == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START || listener != null) return START_NOT_STICKY
        try {
            startForeground(2, ServiceNotifications.build(this, false, "Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            check(!SessionState.state.value.hosting) { "Stop sharing before listening" }
            val host = intent.getStringExtra("host") ?: error("Select a host")
            desiredVolume = intent.getFloatExtra("volume", 1f).coerceIn(0f, 1f)
            SessionState.update { it.copy(listening = true, listenerMessage = "Connecting to $host…") }
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener({ change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> listener?.volume = desiredVolume
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener?.volume = desiredVolume * 0.2f
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> end("Playback interrupted by another app/call. Tap Join to resume.")
                    }
                }, main).build()
            focus = request
            check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus unavailable. Finish the call or other playback and retry" }
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(noisy, filter)
            receiverRegistered = true
            resources = SessionResources(this) { main.post { end("Wi-Fi disconnected. Reconnect and join again.") } }
            listener = AudioListener(host, { message -> SessionState.update { it.copy(listenerMessage = message) } },
                intent.getBooleanExtra("legacy", false), intent.getIntExtra("targetMs", 80).coerceIn(80,160),
                intent.getStringExtra("name") ?: "Listener", { main.post { end(null) } }, WifiRoute.network(this))
                .also { it.volume = desiredVolume; it.start() }
        } catch (e: Exception) { end("Cannot listen: ${e.message}") }
        return START_NOT_STICKY
    }
    private fun end(message: String?) {
        message?.let { text -> SessionState.update { it.copy(listenerMessage = text) } }
        stopSelf()
    }
    override fun onDestroy() {
        listener?.stop(); listener = null
        resources?.close(); resources = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        if (receiverRegistered) { unregisterReceiver(noisy); receiverRegistered = false }
        SessionState.update { it.copy(listening = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
