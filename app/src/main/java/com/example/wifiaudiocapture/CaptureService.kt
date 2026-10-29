package com.example.wifiaudiocapture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val START_ACTION = "com.example.wifiaudiocapture.START"
        const val STOP_ACTION = "com.example.wifiaudiocapture.STOP"
        const val RESULT_ACTION = "com.example.wifiaudiocapture.RESULT"
        const val PORT = 39555
        const val SAMPLE_RATE = 48000
        const val SAMPLES_PER_PACKET = 480 // 10 ms mono PCM, < MTU
        private const val CHANNEL = "share_audio"
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var socket: DatagramSocket? = null
    private var captureThread: Thread? = null
    private var registrationThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap<SocketAddress, Long>()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Live audio sharing", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) { stopSharing(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START_ACTION || running.get()) return START_NOT_STICKY
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Wi-Fi Music Sharing")
            .setContentText("Broadcasting playback audio to your local Wi-Fi")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("resultData", Intent::class.java)
            else intent.getParcelableExtra("resultData") as? Intent
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            socket = DatagramSocket(PORT).apply { soTimeout = 1000 }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSharing(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minBuffer > 0)
            record = AudioRecord.Builder().setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192)).build()
            require(record!!.state == AudioRecord.STATE_INITIALIZED)
            running.set(true)
            registrationThread = Thread({ registerListeners() }, "wifi-client-register").apply { start() }
            captureThread = Thread({ streamAudio() }, "wifi-audio-capture").apply { start() }
            message("Broadcasting on port $PORT. Friends can join using your host IP.")
        } catch (e: Exception) {
            message("Host failed: ${e.message}")
            stopSharing(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun registerListeners() {
        val buf = ByteArray(40)
        while (running.get()) {
            try {
                val p = DatagramPacket(buf, buf.size)
                socket?.receive(p)
                val command = String(p.data, p.offset, p.length, Charsets.US_ASCII)
                if (command == "JOIN_WIFI_AUDIO_V1") clients[p.socketAddress] = SystemClock.elapsedRealtime()
                if (command == "LEAVE_WIFI_AUDIO_V1") clients.remove(p.socketAddress)
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) { if (!running.get()) break }
        }
    }

    private fun streamAudio() {
        try {
            val audio = record ?: return
            audio.startRecording()
            val samples = ShortArray(SAMPLES_PER_PACKET)
            var seq = 0
            while (running.get()) {
                var count = 0
                while (count < samples.size && running.get()) {
                    val n = audio.read(samples, count, samples.size - count, AudioRecord.READ_BLOCKING)
                    if (n < 0) error("AudioRecord read error $n")
                    if (n > 0) count += n
                }
                if (count != samples.size) continue
                val bytes = ByteArray(4 + count * 2)
                bytes[0] = (seq ushr 24).toByte()
                bytes[1] = (seq ushr 16).toByte()
                bytes[2] = (seq ushr 8).toByte()
                bytes[3] = seq.toByte()
                for (i in samples.indices) {
                    bytes[4 + i * 2] = samples[i].toByte()
                    bytes[5 + i * 2] = (samples[i].toInt() ushr 8).toByte()
                }
                seq++
                val now = SystemClock.elapsedRealtime()
                clients.entries.forEach { (addr, lastSeen) ->
                    if (now - lastSeen > 6000) clients.remove(addr)
                    else try { socket?.send(DatagramPacket(bytes, bytes.size, addr)) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { message("Audio stopped: ${e.message}") }
        finally { stopSharing(); stopSelf() }
    }

    private fun message(value: String) {
        sendBroadcast(Intent(RESULT_ACTION).setPackage(packageName).putExtra("message", value))
    }
    private fun stopSharing() {
        if (!running.getAndSet(false) && record == null && socket == null) return
        socket?.close(); socket = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release(); record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        clients.clear()
    }
    override fun onDestroy() { stopSharing(); super.onDestroy() }
}
