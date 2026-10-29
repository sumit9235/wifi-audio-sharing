package com.example.wifiaudiocapture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class CaptureService : Service() {
    companion object {
        const val START_ACTION = "com.example.wifiaudiocapture.START"
        const val STOP_ACTION = "com.example.wifiaudiocapture.STOP"
        const val RESULT_ACTION = "com.example.wifiaudiocapture.RESULT"
        private const val CHANNEL = "capture"
    }
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private val sampleRate = 48000

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Audio capture test", NotificationManager.IMPORTANCE_LOW)
        )
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            running.set(false)
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START_ACTION || running.get()) return START_NOT_STICKY
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Audio capture test")
            .setContentText("Recording internal playback for up to 10 seconds")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION") val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("resultData", Intent::class.java)
        else intent.getParcelableExtra("resultData") as? Intent
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { running.set(false) }
            }, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minBuffer > 0) { "AudioRecord buffer not supported" }
            recorder = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            require(recorder!!.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            running.set(true)
            worker = Thread { capture() }.apply { start() }
        } catch (e: Exception) {
            broadcast("Capture error: ${e.message}", "")
            cleanup()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun capture() {
        val file = File(getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "revanced-capture.wav")
        var written = 0L
        var energy = 0.0
        var count = 0L
        var error: String? = null
        try {
            val audio = recorder ?: kotlin.error("Recorder unavailable")
            audio.startRecording()
            FileOutputStream(file).use { out ->
                out.write(ByteArray(44))
                val samples = ShortArray(4096)
                val until = SystemClock.elapsedRealtime() + 10_000L
                while (running.get() && SystemClock.elapsedRealtime() < until) {
                    val n = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) kotlin.error("AudioRecord read error $n")
                    if (n == 0) continue
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = samples[i].toInt()
                        bytes[i * 2] = (v and 255).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 255).toByte()
                        energy += v.toDouble() * v
                    }
                    count += n
                    out.write(bytes)
                    written += bytes.size
                }
            }
            writeWavHeader(file, written)
        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        finally {
            running.set(false)
            val rms = if (count > 0) sqrt(energy / count) / 32768.0 else 0.0
            val assessment = when {
                error != null -> "Recording failed: $error"
                written == 0L -> "No samples received."
                rms < 0.0002 -> "Recorded ${written / 1024} KB, but nearly silent (RMS ${"%.5f".format(rms)}). Source capture may be blocked."
                else -> "Recorded ${written / 1024} KB. Audio present (RMS ${"%.5f".format(rms)}). Tap Listen to verify."
            }
            broadcast(assessment, file.absolutePath)
            cleanup()
            stopSelf()
        }
    }
    private fun broadcast(message: String, path: String) {
        sendBroadcast(Intent(RESULT_ACTION).setPackage(packageName).putExtra("message", message).putExtra("path", path))
    }
    private fun cleanup() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker = null
    }
    private fun writeWavHeader(file: File, dataLength: Long) {
        RandomAccessFile(file, "rw").use { f ->
            fun ascii(s: String) { f.write(s.toByteArray(Charsets.US_ASCII)) }
            fun le16(n: Int) { f.write(n and 255); f.write((n shr 8) and 255) }
            fun le32(n: Long) { repeat(4) { f.write(((n shr (it * 8)) and 255).toInt()) } }
            ascii("RIFF"); le32(dataLength + 36); ascii("WAVE")
            ascii("fmt "); le32(16); le16(1); le16(1); le32(sampleRate.toLong())
            le32(sampleRate.toLong() * 2); le16(2); le16(16)
            ascii("data"); le32(dataLength)
        }
    }
    override fun onDestroy() { running.set(false); super.onDestroy() }
}
