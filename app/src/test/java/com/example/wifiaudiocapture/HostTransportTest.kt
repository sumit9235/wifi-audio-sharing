package com.example.wifiaudiocapture

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*

/** Real loopback TCP/UDP test; Android audio/discovery are deliberately not simulated. */
class HostTransportTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private class Client(val tcp: Socket, val udp: DatagramSocket) : AutoCloseable {
        override fun close() { tcp.close(); udp.close() }
    }
    private fun connect(host: HostTransport): Client {
        val udp = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 2000 }
        val tcp = Socket(loopback, host.advertisedPort).apply { soTimeout = 2000 }
        try {
            ControlProtocol.write(tcp.getOutputStream(), "INFO 2")
            assertTrue(ControlProtocol.read(tcp.getInputStream()).startsWith("SESSION 2 ${host.session}"))
            ControlProtocol.write(tcp.getOutputStream(), ControlProtocol.hello(host.session, udp.localPort, "Phone"))
            val response = ControlProtocol.read(tcp.getInputStream())
            require(response.startsWith("WELCOME 2")) { response }
            return Client(tcp, udp)
        } catch (e: Exception) { tcp.close(); udp.close(); throw e }
    }
    @Test fun threeListenersDisconnectIsolationCapacityAndClock() = runBlocking {
        val host = HostTransport(false, {}, approve = { _, _ -> true }, bindAddress = loopback, controlPort = 0, allowPeer = { it.isLoopbackAddress })
        val peers = mutableListOf<Client>()
        try {
            repeat(3) { peers += connect(host) }
            assertEquals(3, host.count)
            peers.forEach {
                ControlProtocol.write(it.tcp.getOutputStream(), "PING 123")
                assertTrue(ControlProtocol.read(it.tcp.getInputStream()).startsWith("PONG 123 "))
            }
            val pcm = ByteArray(960) { 42 }
            host.publish(pcm, 0, 0, System.nanoTime())
            peers.forEach {
                val bytes = ByteArray(1500); val p = DatagramPacket(bytes, bytes.size); it.udp.receive(p)
                val decoded = AudioPacket.decode(bytes, p.length)!!
                assertEquals(host.session, decoded.session); assertArrayEquals(pcm, decoded.pcm)
            }
            peers.removeAt(0).close()
            withTimeout(2000) { while (host.count != 2) delay(5) }
            repeat(3) { peers += connect(host) }
            assertEquals(5, host.count)
            try { connect(host).close(); fail("sixth listener joined") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Room full")) }
            host.publish(pcm, 1, 480, System.nanoTime())
            peers.forEach {
                val bytes = ByteArray(1500); val p = DatagramPacket(bytes, bytes.size); it.udp.receive(p)
                assertEquals(1, AudioPacket.decode(bytes, p.length)!!.sequence)
            }
        } finally { peers.forEach { it.close() }; host.close() }
        assertEquals(0, host.count)
    }
    @Test fun deniedJoinReceivesNoAudio() {
        val host = HostTransport(false, {}, approve = { _, _ -> false }, bindAddress = loopback, controlPort = 0, allowPeer = { it.isLoopbackAddress })
        try {
            try { connect(host).close(); fail("unapproved listener joined") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Host declined")) }
            assertEquals(0, host.count)
        } finally { host.close() }
    }
}
