package com.example.wifiaudiocapture

import kotlin.math.abs
import kotlin.math.max

/** Bounded reorder window. Deadlines use the shared host clock when available.
 * Playback never waits for retransmissions; late frames are dropped. */
class JitterBuffer(val minimumMs: Int = 80, private val capacity: Int = 24) {
    data class Frame(val packet: AudioPacket?, val silence: Boolean, val reset: Boolean = false)
    private val packets = HashMap<Int, AudioPacket>()
    private var expected: Int? = null
    private var anchorSeq = 0
    private var anchorCapture = 0L
    private var localStart = 0L
    private var lastTransit: Long? = null
    private var jitterNs = 0.0
    private var lastTune = 0L
    private var pendingReset = false
    var targetMs = minimumMs; private set
    var dropped = 0L; private set
    var late = 0L; private set
    var underruns = 0L; private set
    var resyncs = 0L; private set
    @Synchronized fun depth(): Int = packets.size
    @Synchronized fun offer(packet: AudioPacket, arrivalNs: Long) {
        val transit = arrivalNs - packet.captureNs
        lastTransit?.let { jitterNs += (abs(transit - it) - jitterNs) / 16.0 }
        lastTransit = transit
        if (arrivalNs - lastTune > 1_000_000_000L) {
            val desired = (minimumMs + 4 * jitterNs / 1_000_000).toInt().coerceIn(minimumMs, 180)
            targetMs = if (desired > targetMs) desired else max(minimumMs, targetMs - 1)
            lastTune = arrivalNs
        }
        var first = expected
        if (first == null) {
            expected = packet.sequence; first = packet.sequence
            anchorSeq = packet.sequence; anchorCapture = packet.captureNs; localStart = arrivalNs
        }
        val delta = AudioPacket.distance(packet.sequence, first)
        if (delta < 0) { late++; return }
        if (delta >= capacity) {
            dropped += packets.size; packets.clear()
            expected = packet.sequence; anchorSeq = packet.sequence
            anchorCapture = packet.captureNs; localStart = arrivalNs
            resyncs++; pendingReset = true
        }
        if (packets.containsKey(packet.sequence)) { dropped++; return }
        if (packets.size >= capacity) { dropped++; return }
        packets[packet.sequence] = packet
        // Follow the capture sample clock, including its drift relative to the phone clock.
        if (AudioPacket.distance(packet.sequence, anchorSeq) >= 0) {
            anchorSeq = packet.sequence; anchorCapture = packet.captureNs
            localStart = arrivalNs
        }
    }
    @Synchronized fun poll(nowNs: Long, hostOffsetNs: Long?, outputQueueNs: Long = 0): Frame? {
        var seq = expected ?: return null
        val targetNs = targetMs * 1_000_000L
        fun deadline(s: Int): Long {
            val elapsed = AudioPacket.distance(s, anchorSeq).toLong() * AudioPacket.FRAME_NS
            return if (hostOffsetNs != null) (packets[s]?.captureNs ?: (anchorCapture + elapsed)) + targetNs - hostOffsetNs
                   else localStart + elapsed + targetNs
        }
        if (nowNs + outputQueueNs < deadline(seq)) return null
        // Drop old media after a stall rather than play the backlog and accumulate latency.
        var skipped = 0
        while (nowNs + outputQueueNs - deadline(seq) > 30_000_000 && skipped < capacity) {
            packets.remove(seq); dropped++; seq++; skipped++
        }
        if (skipped > 0) { expected = seq; resyncs++; pendingReset = true }
        if (skipped == capacity && packets.isEmpty()) {
            expected = null; return null
        }
        val packet = packets.remove(seq)
        expected = seq + 1
        if (packet == null) underruns++
        val reset = pendingReset; pendingReset = false
        return Frame(packet, packet == null, reset)
    }
    @Synchronized fun jitterMs(): Double = jitterNs / 1_000_000
}
