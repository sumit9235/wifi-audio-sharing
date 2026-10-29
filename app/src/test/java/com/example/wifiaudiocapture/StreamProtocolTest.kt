package com.example.wifiaudiocapture
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class StreamProtocolTest {
    private fun packet(seq: Int, time: Long = 1_000_000_000L) = AudioPacket(42, 7, seq, 480L, time, ByteArray(960) { it.toByte() })
    @Test fun packetRoundTripAndBounds() {
        val source = packet(Int.MAX_VALUE)
        val bytes = source.encode()
        val result = AudioPacket.decode(bytes)!!
        assertEquals(source.sequence, result.sequence); assertEquals(source.session, result.session)
        assertArrayEquals(source.pcm, result.pcm)
        assertNull(AudioPacket.decode(bytes, bytes.size-1)); assertNull(AudioPacket.decode(bytes + 0))
        for (index in listOf(0, 4, 5, 6, 40, 42)) {
            val bad = bytes.clone(); bad[index] = (bad[index].toInt() xor 16).toByte()
            assertNull("header byte $index", AudioPacket.decode(bad))
        }
    }
    @Test fun sequenceWraparound() {
        assertEquals(1, AudioPacket.distance(Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(1, AudioPacket.distance(0, -1))
        assertEquals(-1, AudioPacket.distance(-1, 0))
        val b = JitterBuffer()
        b.offer(packet(-1), 1_000_000_000); b.offer(packet(0, 1_010_000_000), 1_010_000_000)
        assertEquals(-1, b.poll(1_080_000_000, 0)!!.packet!!.sequence)
        assertEquals(0, b.poll(1_090_000_000, 0)!!.packet!!.sequence)
    }
    @Test fun reorderLossDuplicatesAndCapacity() {
        val b = JitterBuffer()
        b.offer(packet(0), 1_000_000_000)
        b.offer(packet(2, 1_020_000_000), 1_020_000_000)
        b.offer(packet(1, 1_010_000_000), 1_021_000_000)
        b.offer(packet(1, 1_010_000_000), 1_022_000_000)
        assertNull(b.poll(1_079_000_000, 0))
        assertEquals(0, b.poll(1_100_000_000, 0)!!.packet!!.sequence)
        assertEquals(1, b.poll(1_110_000_000, 0)!!.packet!!.sequence)
        assertEquals(2, b.poll(1_120_000_000, 0)!!.packet!!.sequence)
        assertTrue(b.poll(1_130_000_000, 0)!!.silence)
        repeat(10_000) { b.offer(packet(it + 10, 2_000_000_000L + it*10_000_000L), 2_000_000_000L + it*10_000_000L); assertTrue(b.depth() <= 24) }
        assertTrue(b.dropped > 0)
        assertTrue(b.targetMs in 80..180)
    }
    @Test fun clockEstimateAndInvalidSamples() {
        val c = ClockSync()
        assertTrue(c.observe(100, 1110, 1120, 130))
        assertEquals(1000L, c.offsetNs); assertEquals(20L, c.roundTripNs)
        assertFalse(c.observe(100, 1110, 1120, 90))
        assertFalse(c.observe(100, 1120, 1110, 130))
    }
    @Test fun boundedJoinCompatibility() {
        val line = ControlProtocol.hello(42, 40000, "Phone")
        assertEquals(ControlProtocol.Join(42, 40000, "Phone"), ControlProtocol.parseJoin(line))
        assertNull(ControlProtocol.parseJoin(line.replace("HELLO 2", "HELLO 9")))
        assertNull(ControlProtocol.parseJoin(line.replace("40000", "0")))
        assertNull(ControlProtocol.parseJoin(line.replace("PCM16", "OPUS")))
        try { ControlProtocol.read(ByteArrayInputStream(ByteArray(300) { 65 })); fail("unbounded message") } catch (_: IllegalStateException) { }
    }
    @Test fun fifteenMinuteSyntheticLossAndJitterRemainBounded() {
        val b = JitterBuffer()
        val payload = ByteArray(960)
        var maxDepth = 0
        var played = 0
        repeat(90_000) { seq ->
            val capture = 1_000_000_000L + seq * 10_000_000L
            val arrival = capture + 3_000_000 + (seq % 5) * 500_000L
            if (seq % 97 != 0) b.offer(AudioPacket(42, 7, seq, seq * 480L, capture, payload), arrival)
            maxDepth = maxOf(maxDepth, b.depth())
            b.poll(arrival, 0)?.let { if (it.packet != null) played++ }
            assertTrue(b.depth() <= 24)
        }
        assertTrue(played > 88_000)
        assertTrue(maxDepth < 20)
        assertTrue(b.targetMs in 80..90)
    }
}
