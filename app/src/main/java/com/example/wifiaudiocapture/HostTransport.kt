package com.example.wifiaudiocapture

import android.os.SystemClock
import java.net.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Each listener has its own bounded sender. Slow peers cannot block capture. */
class HostTransport(private val status: (String) -> Unit) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val registry = PeerRegistry<SocketAddress>()
    private val peers = ConcurrentHashMap<SocketAddress, Sender>()
    private val socket = DatagramSocket(CaptureService.PORT).apply { soTimeout = 1000 }
    @Volatile var drops = 0L; private set
    val count: Int get() = peers.size
    private inner class Sender(val address: SocketAddress) : AutoCloseable {
        val queue = ArrayBlockingQueue<ByteArray>(8)
        val active = AtomicBoolean(true)
        val udp = DatagramSocket()
        val worker = Thread({
            while (running.get() && active.get()) {
                try {
                    val bytes = queue.take()
                    // Send from the well-known socket: V1 listeners validate the source port.
                    socket.send(DatagramPacket(bytes, bytes.size, address))
                } catch (_: InterruptedException) { break }
                catch (_: Exception) { if (!active.get() || !running.get()) break }
            }
        }, "audio-peer").apply { start() }
        override fun close() { active.set(false); worker.interrupt(); udp.close(); queue.clear() }
    }
    private val receiver = Thread({
        val bytes = ByteArray(128)
        while (running.get()) {
            try {
                val packet = DatagramPacket(bytes, bytes.size)
                socket.receive(packet)
                if (!packet.address.isSiteLocalAddress && !packet.address.isLinkLocalAddress) continue
                when (String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)) {
                    "JOIN_WIFI_AUDIO_V1" -> if (registry.join(packet.socketAddress, SystemClock.elapsedRealtime())) {
                        peers.computeIfAbsent(packet.socketAddress) { Sender(it) }
                    }
                    "LEAVE_WIFI_AUDIO_V1" -> { registry.leave(packet.socketAddress); peers.remove(packet.socketAddress)?.close() }
                }
            } catch (_: SocketTimeoutException) { }
            catch (_: Exception) { if (!running.get()) break }
            registry.expire(SystemClock.elapsedRealtime()).forEach { peers.remove(it)?.close() }
            status("Sharing • ${peers.size}/5 listeners • queue drops $drops")
        }
    }, "audio-joins").apply { start() }
    fun publish(bytes: ByteArray) {
        peers.values.forEach { peer ->
            if (!peer.queue.offer(bytes)) {
                peer.queue.poll(); peer.queue.offer(bytes); drops++
            }
        }
    }
    override fun close() {
        running.set(false); socket.close(); receiver.interrupt()
        peers.values.forEach { it.close() }; peers.clear(); registry.clear()
    }
}
