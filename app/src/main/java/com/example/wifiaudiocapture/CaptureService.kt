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
    private var transport: HostTransport? = null
    private var discovery: SessionDiscovery? = null
    private var captureThread: Thread? = null
    private val running = AtomicBoolean(false)

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
            transport = HostTransport(intent.getBooleanExtra("legacy", false), ::message)
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
            discovery = SessionDiscovery(this, {}, ::message).also { it.advertise(Build.MODEL, transport!!.advertisedPort) }
            captureThread = Thread({ streamAudio() }, "wifi-audio-capture").apply { start() }
            message("Sharing started. Find this session nearby or join by IP.")
        } catch (e: Exception) {
            message("Host failed: ${e.message}")
            stopSharing(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun streamAudio() {
        try {
            val audio = record ?: return
            audio.startRecording()
            val samples = ShortArray(SAMPLES_PER_PACKET)
            var seq = 0
            var mediaSample = 0L
            val timestamp = AudioTimestamp()
            var timestampValid = false
            var stampAt = 0L
            while (running.get()) {
                var count = 0
                while (count < samples.size && running.get()) {
                    val n = audio.read(samples, count, samples.size - count, AudioRecord.READ_BLOCKING)
                    if (n < 0) error("AudioRecord read error $n")
                    if (n > 0) count += n
                }
                if (count != samples.size) continue
                val pcm = ByteArray(count * 2)
                for (i in samples.indices) {
                    pcm[i * 2] = samples[i].toByte()
                    pcm[i * 2 + 1] = (samples[i].toInt() ushr 8).toByte()
                }
                val now = System.nanoTime()
                if (now - stampAt > 1_000_000_000L) {
                    timestampValid = audio.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    stampAt = now
                }
                val captureNs = if (timestampValid) timestamp.nanoTime +
                    (mediaSample - timestamp.framePosition) * 1_000_000_000L / SAMPLE_RATE
                    else now - AudioPacket.FRAME_NS
                transport?.publish(pcm, seq++, mediaSample, captureNs.coerceAtLeast(0))
                mediaSample += count
            }
        } catch (e: Exception) { message("Audio stopped: ${e.message}") }
        finally { stopSharing(); stopSelf() }
    }

    private fun message(value: String) {
        sendBroadcast(Intent(RESULT_ACTION).setPackage(packageName).putExtra("message", value))
    }
    private fun stopSharing() {
        if (!running.getAndSet(false) && record == null && transport == null) return
        discovery?.close(); discovery = null
        transport?.close(); transport = null
        try { record?.stop() } catch (_: Exception) {}
        record?.release(); record = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }
    override fun onDestroy() { stopSharing(); super.onDestroy() }
}
