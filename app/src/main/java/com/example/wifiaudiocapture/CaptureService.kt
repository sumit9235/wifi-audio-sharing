package com.example.wifiaudiocapture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** The Phase 2 AudioPlaybackCapture consent/configuration is deliberately retained. */
class CaptureService : Service() {
    companion object {
        const val START_ACTION = "com.example.wifiaudiocapture.START"
        const val STOP_ACTION = "com.example.wifiaudiocapture.STOP"
        const val APPROVE = "com.example.wifiaudiocapture.APPROVE"
        const val PORT = 39555
        const val SAMPLE_RATE = 48000
        const val SAMPLES_PER_PACKET = 480
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var stopping = false
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var captureJob: Job? = null
    private var transport: HostTransport? = null
    private var discovery: SessionDiscovery? = null
    private var resources: SessionResources? = null
    private val approvals = ApprovalGate { pending ->
        SessionState.update { it.copy(pending = pending) }
        main.post { updateNotification() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); ServiceNotifications.create(this) }
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP_ACTION -> { end("Sharing stopped"); return START_NOT_STICKY }
            APPROVE -> {
                approvals.decide(intent.getStringExtra("id") ?: "", intent.getBooleanExtra("allow", false))
                return START_NOT_STICKY
            }
        }
        if (intent?.action != START_ACTION || running.get() || stopping) return START_NOT_STICKY
        try {
            val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("resultData", Intent::class.java)
                       else intent.getParcelableExtra("resultData") as? Intent
            require(code == Activity.RESULT_OK && data != null) { "Fresh Android capture consent is required" }
            startForeground(1, ServiceNotifications.build(this, true, "Starting playback capture…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            check(!SessionState.state.value.listening) { "Disconnect the listener before sharing" }
            val legacy = intent.getBooleanExtra("legacy", false)
            SessionState.update { it.copy(hosting = true, startedMs = SystemClock.elapsedRealtime(), hostMessage = "Starting sharing…") }
            transport = HostTransport(legacy, ::message, { name, address -> approvals.request(name, address) }, { peers ->
                if (!stopping) { SessionState.update { it.copy(peers = peers) }; main.post { updateNotification() } }
            }, WifiRoute.network(this), WifiRoute.address(this))
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(code, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!stopping) end("Android stopped capture. Start sharing again to grant fresh consent.") }
            }, main)
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0) { "48 kHz PCM capture is unavailable" }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Audio capture permission was revoked. Grant it in the app and retry")
            val audio = AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(maxOf(min * 2, if (legacy) 8192 else 3840)).build()
            record = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Audio capture initialization failed" }
            resources = SessionResources(this) { main.post { end("Wi-Fi disconnected. Reconnect, then start sharing with fresh consent.") } }
            discovery = SessionDiscovery(this, {}, ::message).also {
                it.advertise(intent.getStringExtra("name") ?: "Music room", transport!!.advertisedPort)
            }
            running.set(true)
            captureJob = scope.launch {
                try { streamAudio(audio) }
                catch (e: Exception) { if (running.get()) message("Audio stopped: ${e.message}") }
                finally { main.post { if (!stopping) end(null) } }
            }.also { job -> job.invokeOnCompletion { audio.release() } }
            message(if (legacy) "Phase 2 compatibility • manual IP • no join approval • trusted LAN only"
                    else "Sharing live. Friends can find this session; approve their join requests here.")
            updateNotification()
        } catch (e: Exception) { end("Cannot share: ${e.message}") }
        return START_NOT_STICKY
    }
    private suspend fun streamAudio(audio: AudioRecord) {
        audio.startRecording()
        val samples = ShortArray(SAMPLES_PER_PACKET)
        var seq = 0; var mediaSample = 0L
        val timestamp = AudioTimestamp()
        var timestampValid = false; var stampAt = 0L
        var lastReport = System.nanoTime(); var windowFrames = 0; var encodeNs = 0L
        while (running.get() && currentCoroutineContext().isActive) {
            var count = 0
            while (count < samples.size && running.get()) {
                val n = audio.read(samples, count, samples.size-count, AudioRecord.READ_BLOCKING)
                check(n >= 0) { "AudioRecord read error $n" }
                if (n > 0) count += n else yield()
            }
            if (count != samples.size) break
            val begin = System.nanoTime()
            val pcm = ByteArray(count * 2)
            for (i in samples.indices) { pcm[i*2] = samples[i].toByte(); pcm[i*2+1] = (samples[i].toInt() ushr 8).toByte() }
            if (begin - stampAt > 1_000_000_000L) {
                timestampValid = audio.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                stampAt = begin
            }
            val captureNs = if (timestampValid) timestamp.nanoTime + (mediaSample-timestamp.framePosition) * 1_000_000_000L / SAMPLE_RATE
                            else begin - AudioPacket.FRAME_NS
            transport?.publish(pcm, seq++, mediaSample, captureNs.coerceAtLeast(0))
            mediaSample += count; windowFrames++; encodeNs += System.nanoTime() - begin
            if (begin - lastReport > 1_000_000_000L) {
                val seconds = (begin-lastReport)/1e9
                val t = transport
                message("${t?.count ?: 0}/5 listeners • ${if (t?.legacy == true) "Phase 2 PCM" else "PCM v2"}\nCapture ${"%.1f".format(windowFrames/seconds)} packets/s • ${"%.0f".format(windowFrames*480/seconds)} samples/s\nPacketize/enqueue ${encodeNs / maxOf(windowFrames,1) / 1000} µs/frame • 960 audio bytes/packet • ${"%.0f".format(windowFrames*960/seconds)} payload bytes/s per listener\nBounded sender drops ${t?.drops ?: 0} • capture clock ${if (timestampValid) "AudioRecord timestamp" else "read estimate"}")
                lastReport = begin; windowFrames = 0; encodeNs = 0
            }
        }
    }
    private fun message(text: String) { SessionState.update { it.copy(hostMessage = text) } }
    private fun updateNotification() {
        if (!SessionState.state.value.hosting || stopping) return
        val s = SessionState.state.value
        getSystemService(NotificationManager::class.java).notify(1, ServiceNotifications.build(this, true,
            "${s.peers.size}/5 listeners • ${s.pending.size} waiting for approval. Tap to manage."))
    }
    private fun end(text: String?) {
        text?.let { message(it) }
        stopSharing(); stopSelf()
    }
    /** Called on main. AudioRecord release waits for its worker to finish. */
    private fun stopSharing() {
        if (stopping) return
        stopping = true; running.set(false); approvals.close()
        discovery?.close(); discovery = null; transport?.close(); transport = null
        resources?.close(); resources = null
        try { record?.stop() } catch (_: Exception) { }
        if (captureJob == null) record?.release()
        record = null; scope.cancel()
        val oldProjection = projection; projection = null
        try { oldProjection?.stop() } catch (_: Exception) { }
        SessionState.update { it.copy(hosting = false, pending = emptyList(), peers = emptyList(), startedMs = 0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { stopSharing(); super.onDestroy() }
}
