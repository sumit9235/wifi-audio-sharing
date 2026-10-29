package com.example.wifiaudiocapture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

/** A basic one-host Wi-Fi PCM listener. No relay or internet connection required. */
class AudioListener(private val host: String, private val status: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ listen() }, "wifi-audio-listener").apply { start() }
    }

    private fun listen() {
        var track: AudioTrack? = null
        try {
            val address = InetAddress.getByName(host)
            require(address is Inet4Address) { "Use the host phone's IPv4 address" }
            val target = InetSocketAddress(address, CaptureService.PORT)
            val udp = DatagramSocket().apply { soTimeout = 1000 }
            socket = udp
            val minBuffer = AudioTrack.getMinBufferSize(CaptureService.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minBuffer > 0)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(CaptureService.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 19200))
                .build()
            require(track.state == AudioTrack.STATE_INITIALIZED)
            track.play()
            val packetBytes = ByteArray(4 + 2 * CaptureService.SAMPLES_PER_PACKET)
            val join = "JOIN_WIFI_AUDIO_V1".toByteArray(Charsets.US_ASCII)
            var lastJoin = 0L
            var received = 0
            var lastSeq: Long? = null
            while (running.get()) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastJoin >= 2000) {
                    udp.send(DatagramPacket(join, join.size, target))
                    lastJoin = now
                }
                try {
                    val packet = DatagramPacket(packetBytes, packetBytes.size)
                    udp.receive(packet)
                    if (packet.address != address || packet.port != CaptureService.PORT || packet.length != packetBytes.size) continue
                    val b = packet.data
                    val seq = ((b[0].toLong() and 255) shl 24) or ((b[1].toLong() and 255) shl 16) or
                        ((b[2].toLong() and 255) shl 8) or (b[3].toLong() and 255)
                    if (lastSeq != null && seq <= lastSeq && lastSeq - seq < 0x7fffffffL) continue
                    lastSeq = seq
                    track.write(b, 4, packet.length - 4, AudioTrack.WRITE_BLOCKING)
                    received++
                    if (received == 5) status("Connected. Playing live audio from $host")
                } catch (_: SocketTimeoutException) {
                    if (received == 0) status("Waiting for host audio at $host. Check IP and Wi-Fi isolation.")
                }
            }
            val leave = "LEAVE_WIFI_AUDIO_V1".toByteArray(Charsets.US_ASCII)
            try { udp.send(DatagramPacket(leave, leave.size, target)) } catch (_: Exception) {}
        } catch (e: Exception) {
            if (running.get()) status("Listener error: ${e.message}")
        } finally {
            running.set(false)
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            socket?.close(); socket = null
        }
    }

    fun stop() { running.set(false); socket?.close() }
}
