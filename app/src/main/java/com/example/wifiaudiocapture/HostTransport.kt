package com.example.wifiaudiocapture

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/** Reliable bounded control; independent bounded UDP senders; capture never sends. */
class HostTransport(val legacy: Boolean, private val status: (String) -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val session = SecureRandom().nextLong().let { if (it == 0L) 1L else it }
    val stream = SecureRandom().nextInt()
    private val clients = ConcurrentHashMap<SocketAddress, Peer>()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(8)
    private val registry = PeerRegistry<SocketAddress>()
    private val dropCounter = AtomicLong()
    private var udp: DatagramSocket? = null
    private var server: ServerSocket? = null
    val drops: Long get() = dropCounter.get()
    val count: Int get() = clients.size
    val advertisedPort: Int get() = if (legacy) CaptureService.PORT else ControlProtocol.PORT
    private inner class Peer(val address: SocketAddress, val name: String) : AutoCloseable {
        private val queue = Channel<ByteArray>(8, BufferOverflow.DROP_OLDEST, onUndeliveredElement = { dropCounter.incrementAndGet() })
        private val sender = if (legacy) null else DatagramSocket()
        private val job = scope.launch {
            for (bytes in queue) {
                try { (sender ?: udp)?.send(DatagramPacket(bytes, bytes.size, address)) }
                catch (_: Exception) { if (!isActive) break }
            }
        }
        fun offer(bytes: ByteArray) { queue.trySend(bytes) }
        override fun close() { queue.cancel(); sender?.close(); job.cancel() }
    }
    init {
        try {
            if (legacy) {
                udp = DatagramSocket(CaptureService.PORT).apply { soTimeout = 1000 }
                scope.launch { legacyJoins() }
            } else {
                server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(ControlProtocol.PORT), 8) }
                scope.launch {
                    while (isActive) {
                        val client = try { server!!.accept() } catch (_: Exception) { break }
                        if (!local(client.inetAddress) || !slots.tryAcquire()) { client.close(); continue }
                        connections.add(client)
                        launch { try { handle(client) } finally { connections.remove(client); client.close(); slots.release() } }
                    }
                }
            }
        } catch (e: Exception) { close(); throw e }
    }
    private suspend fun handle(client: Socket) {
        client.soTimeout = 6000; client.tcpNoDelay = true
        val input = client.getInputStream(); val output = client.getOutputStream()
        var peer: Peer? = null
        try {
            require(ControlProtocol.read(input) == "INFO 2") { "Unsupported control protocol" }
            ControlProtocol.write(output, "SESSION 2 $session $stream PCM16 48000 1")
            val join = ControlProtocol.parseJoin(ControlProtocol.read(input)) ?: error("Incompatible join")
            require(join.session == session) { "Stale session. Refresh nearby sessions." }
            synchronized(clients) {
                require(clients.size < ControlProtocol.MAX_PEERS) { "Room full (5 listeners)" }
                val address = InetSocketAddress(client.inetAddress, join.port)
                require(!clients.containsKey(address)) { "Already joined" }
                peer = Peer(address, join.name)
                clients[address] = peer!!
            }
            ControlProtocol.write(output, "WELCOME 2 $session $stream PCM16 48000 1")
            report()
            while (currentCoroutineContext().isActive) {
                val p = ControlProtocol.read(input).split(' ')
                val receive = System.nanoTime()
                if (p.size == 1 && p[0] == "LEAVE") break
                require(p.size == 2 && p[0] == "PING" && p[1].toLongOrNull() != null)
                ControlProtocol.write(output, "PONG ${p[1]} $receive ${System.nanoTime()}")
            }
        } catch (e: Exception) {
            try { ControlProtocol.write(output, "ERROR ${(e.message ?: "Disconnected").replace('\n', ' ').take(120)}") } catch (_: Exception) { }
        } finally {
            peer?.let { clients.remove(it.address, it); it.close() }; report()
        }
    }
    private suspend fun legacyJoins() {
        val bytes = ByteArray(128)
        while (currentCoroutineContext().isActive) {
            try {
                val packet = DatagramPacket(bytes, bytes.size); udp!!.receive(packet)
                if (!local(packet.address)) continue
                when (String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)) {
                    "JOIN_WIFI_AUDIO_V1" -> if (registry.join(packet.socketAddress, android.os.SystemClock.elapsedRealtime())) {
                        clients.computeIfAbsent(packet.socketAddress) { Peer(it, "Legacy listener") }
                    }
                    "LEAVE_WIFI_AUDIO_V1" -> { registry.leave(packet.socketAddress); clients.remove(packet.socketAddress)?.close() }
                }
            } catch (_: SocketTimeoutException) { }
            catch (_: Exception) { if (!currentCoroutineContext().isActive) break }
            registry.expire(android.os.SystemClock.elapsedRealtime()).forEach { clients.remove(it)?.close() }; report()
        }
    }
    fun publish(pcm: ByteArray, sequence: Int, mediaSample: Long, captureNs: Long) {
        val bytes = if (legacy) java.nio.ByteBuffer.allocate(4 + pcm.size).putInt(sequence).put(pcm).array()
                    else AudioPacket(session, stream, sequence, mediaSample, captureNs, pcm).encode()
        clients.values.forEach { it.offer(bytes) }
    }
    private fun report() = status("Sharing • ${clients.size}/5 listeners • ${if (legacy) "Phase 2 PCM" else "PCM v2"} • sender drops $drops")
    override fun close() {
        scope.cancel(); server?.close(); udp?.close()
        connections.forEach { try { it.close() } catch (_: Exception) { } }; connections.clear()
        clients.values.forEach { it.close() }; clients.clear(); registry.clear()
    }
    companion object {
        fun local(address: InetAddress): Boolean = !address.isLoopbackAddress &&
            (address.isSiteLocalAddress || address.isLinkLocalAddress)
    }
}
