package com.example.wifiaudiocapture

import android.media.*
import kotlinx.coroutines.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Receiver, control clock exchange and playout are independently cancellable. */
class AudioListener(private val host: String, private val status: (String) -> Unit,
                    private val legacy: Boolean = false, private val targetMs: Int = 80,
                    private val name: String = "Listener", private val onStopped: () -> Unit = {}, private val network: android.net.Network? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    @Volatile private var udp: DatagramSocket? = null
    @Volatile private var control: Socket? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile var volume = 1f
        set(value) { field = value.coerceIn(0f, 1f); runCatching { track?.setVolume(field) }; baseline?.volume = field }
    private var baseline: LegacyAudioListener? = null
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (legacy) {
            baseline = LegacyAudioListener(host, status, onStopped, network).also { it.volume = volume; it.start() }; return
        }
        scope.launch {
            try { listen() }
            catch (e: Exception) { if (running.get()) status("Disconnected: ${e.message}. Check Wi-Fi and tap Join to retry.") }
            finally { running.set(false); control?.close(); udp?.close(); onStopped() }
        }
    }
    private suspend fun listen() = coroutineScope {
        val address = InetAddress.getByName(host)
        require(address is Inet4Address && HostTransport.local(address)) { "Enter a local Wi-Fi IPv4 address" }
        val socket = DatagramSocket().apply { soTimeout = 1000; receiveBufferSize = 64 * 1024; network?.bindSocket(this) }; udp = socket
        val tcp = Socket(); control = tcp
        network?.bindSocket(tcp)
        tcp.connect(InetSocketAddress(address, ControlProtocol.PORT), 4000)
        tcp.soTimeout = 6000; tcp.tcpNoDelay = true
        val input = tcp.getInputStream(); val output = tcp.getOutputStream()
        ControlProtocol.write(output, "INFO 2")
        val info = ControlProtocol.read(input).split(' ')
        require(info.size == 7 && info[0] == "SESSION" && info[1] == "2" && info[4] == "PCM16" && info[5] == "48000" && info[6] == "1") { "Incompatible host; install the same APK on both phones" }
        val session = info[2].toLong(); val stream = info[3].toInt()
        ControlProtocol.write(output, ControlProtocol.hello(session, socket.localPort, name))
        status("Waiting for host approval (up to 30 seconds)…")
        tcp.soTimeout = 35000
        val welcome = ControlProtocol.read(input)
        require(welcome == "WELCOME 2 $session $stream PCM16 48000 1") { welcome.removePrefix("ERROR ") }
        tcp.soTimeout = 6000
        val clock = ClockSync()
        // Establish a clock estimate before receiving/playout rather than shift clocks mid-buffer.
        suspend fun sync() {
            val t1 = System.nanoTime()
            ControlProtocol.write(output, "PING $t1")
            val p = ControlProtocol.read(input).split(' '); val t4 = System.nanoTime()
            require(p.size == 4 && p[0] == "PONG" && p[1].toLong() == t1) { "Invalid clock response" }
            require(clock.observe(t1, p[2].toLong(), p[3].toLong(), t4)) { "Invalid clock sample" }
        }
        repeat(4) { sync() }
        status("Connected to $host • PCM16 48 kHz mono • 768 kbps")
        val minBuffer = AudioTrack.getMinBufferSize(AudioPacket.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0)
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioPacket.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(minBuffer * 2, AudioPacket.PCM_BYTES * 4)).build()
        try {
            require(player.state == AudioTrack.STATE_INITIALIZED)
            track = player; player.setVolume(volume)
            player.setBufferSizeInFrames(max(minBuffer / 2, AudioPacket.SAMPLES * 2))
            if (android.os.Build.VERSION.SDK_INT >= 31) player.setStartThresholdInFrames(AudioPacket.SAMPLES)
            val warmupMs = if (android.os.Build.VERSION.SDK_INT >= 31) 10
                           else (player.bufferSizeInFrames * 1000 / AudioPacket.RATE)
            val actualTarget = max(targetMs, warmupMs + 20)
            check(actualTarget <= 180) { "This output needs a large startup buffer. Try wired audio or Phase 2 compatibility" }
            val jitter = JitterBuffer(actualTarget)
            val queueLimitNs = maxOf(40_000_000L, warmupMs * 1_000_000L)
            val received = java.util.concurrent.atomic.AtomicLong()
            val invalid = java.util.concurrent.atomic.AtomicLong()
            val decodeNs = java.util.concurrent.atomic.AtomicLong()
            val networkAgeNs = java.util.concurrent.atomic.AtomicLong()
            var lastReceive = System.nanoTime()
            launch {
                while (isActive) { delay(2000); sync() }
            }
            launch {
                val bytes = ByteArray(1500)
                while (isActive) {
                    try {
                        val datagram = DatagramPacket(bytes, bytes.size); socket.receive(datagram)
                        if (datagram.address != address) continue
                        val decodeStart = System.nanoTime()
                        val packet = AudioPacket.decode(bytes, datagram.length)
                        decodeNs.addAndGet(System.nanoTime() - decodeStart)
                        if (packet == null || packet.session != session || packet.stream != stream) { invalid.incrementAndGet(); continue }
                        val now = System.nanoTime()
                        if (packet.mediaSample % AudioPacket.SAMPLES != 0L ||
                            packet.sequence != (packet.mediaSample / AudioPacket.SAMPLES).toInt() ||
                            packet.captureNs !in (now + clock.offsetNs - 6_000_000_000L)..(now + clock.offsetNs + 1_000_000_000L)) {
                            invalid.incrementAndGet(); continue
                        }
                        networkAgeNs.set(now + clock.offsetNs - packet.captureNs)
                        jitter.offer(packet, now); received.incrementAndGet(); lastReceive = now
                    } catch (_: SocketTimeoutException) {
                        check(System.nanoTime() - lastReceive < 6_000_000_000L) { "No audio for 6 seconds. Host stopped or Wi-Fi changed" }
                    }
                }
            }
            val silence = ByteArray(AudioPacket.PCM_BYTES)
            var writtenFrames = 0L
            var lastStatus = 0L
            var lastTimestampCheck = 0L
            var presentation = "warming up"
            val timestamp = AudioTimestamp()
            var resetFade = true
            var lastSample: Short = 0
            player.play()
            while (isActive) {
                val now = System.nanoTime()
                val played = player.playbackHeadPosition.toLong() and 0xffffffffL
                val queuedFrames = (writtenFrames - played).coerceAtLeast(0)
                val queuedNs = queuedFrames * 1_000_000_000L / AudioPacket.RATE
                if (queuedNs > queueLimitNs) { delay(2); continue }
                val frame = jitter.poll(now, if (clock.ready(now)) clock.offsetNs else null, queuedNs)
                if (frame == null) { delay(2); continue }
                if (frame.reset) resetFade = true
                val pcm = (frame.packet?.pcm ?: silence).copyOf()
                // Short ramp masks abrupt silence insertion/resync edges (no codec PLC claim).
                if (frame.silence || resetFade) {
                    for (i in 0 until 48) {
                        val raw = ((pcm[i*2].toInt() and 255) or (pcm[i*2+1].toInt() shl 8)).toShort().toInt()
                        val mixed = (lastSample.toInt() * (48-i) + raw * i) / 48
                        pcm[i*2] = mixed.toByte(); pcm[i*2+1] = (mixed shr 8).toByte()
                    }
                    resetFade = frame.silence
                }
                lastSample = ((pcm[958].toInt() and 255) or (pcm[959].toInt() shl 8)).toShort()
                var offset = 0
                while (offset < pcm.size && isActive) {
                    val n = player.write(pcm, offset, pcm.size-offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(n >= 0) { "Audio output error $n" }
                    if (n == 0) delay(2) else { offset += n; writtenFrames += n / 2 }
                }
                if (now - lastTimestampCheck > 10_000_000_000L) {
                    presentation = if (player.getTimestamp(timestamp)) "frame ${timestamp.framePosition}" else "unavailable on route"
                    lastTimestampCheck = now
                }
                if (now - lastStatus > 1_000_000_000L) {
                    val age = frame.packet?.let { (now + clock.offsetNs - it.captureNs + queuedNs) / 1_000_000 }
                    val uncertainty = if (clock.ready(now)) "±${clock.roundTripNs/2/1_000_000} ms" else "clock stale"
                    status("PCM v2 • 768 kbps • packets ${received.get()} • invalid ${invalid.get()}\nBuffer ${jitter.targetMs} ms / ${jitter.depth()} packets • jitter ${"%.1f".format(jitter.jitterMs())} ms\nDrops ${jitter.dropped} • late ${jitter.late} • missing ${jitter.underruns} • output underruns ${player.underrunCount} • resync ${jitter.resyncs}\nEstimated captured-to-enqueued-output age ${age ?: "—"} ms $uncertainty; excludes acoustic/device delay\nCapture-to-receive estimate ${networkAgeNs.get()/1_000_000} ms • packet decode ${decodeNs.get()/maxOf(1,received.get())/1000} µs\nAudioTrack queued ${queuedNs/1_000_000} ms • buffer ${player.bufferSizeInFrames} frames • presentation $presentation")
                    lastStatus = now
                }
            }
        } finally {
            // Cancel child receivers by closing blocking sockets before coroutineScope awaits them.
            coroutineContext.cancelChildren(); socket.close(); tcp.close()
            try { player.pause(); player.flush() } catch (_: Exception) { }
            track = null; player.release()
        }
    }
    fun stop() {
        running.set(false); baseline?.stop(); baseline = null
        scope.cancel(); control?.close(); udp?.close()
    }
}
