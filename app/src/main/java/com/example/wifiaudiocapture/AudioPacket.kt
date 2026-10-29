package com.example.wifiaudiocapture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Network-order header; little-endian PCM16 payload. One packet is 10 ms. */
data class AudioPacket(val session: Long, val stream: Int, val sequence: Int,
                       val mediaSample: Long, val captureNs: Long, val pcm: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER + pcm.size).order(ByteOrder.BIG_ENDIAN).apply {
        require(pcm.size == PCM_BYTES && mediaSample >= 0 && captureNs >= 0)
        putInt(MAGIC); put(VERSION); put(PCM16); putShort(0)
        putLong(session); putInt(stream); putInt(sequence); putLong(mediaSample); putLong(captureNs)
        putShort(pcm.size.toShort()); putShort(SAMPLES.toShort()); put(pcm)
    }.array()
    companion object {
        const val MAGIC = 0x57415332
        const val VERSION: Byte = 2
        const val PCM16: Byte = 1
        const val HEADER = 44
        const val SAMPLES = 480
        const val RATE = 48000
        const val PCM_BYTES = SAMPLES * 2
        const val FRAME_NS = 10_000_000L
        fun decode(bytes: ByteArray, length: Int = bytes.size): AudioPacket? {
            if (length != HEADER + PCM_BYTES || length > bytes.size) return null
            val b = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
            if (b.int != MAGIC || b.get() != VERSION || b.get() != PCM16 || b.short.toInt() != 0) return null
            val session = b.long; val stream = b.int; val seq = b.int
            val sample = b.long; val time = b.long
            if (b.short.toInt() != PCM_BYTES || b.short.toInt() != SAMPLES || sample < 0 || time < 0) return null
            return AudioPacket(session, stream, seq, sample, time, ByteArray(PCM_BYTES).also { b.get(it) })
        }
        /** Signed modular distance, valid for windows smaller than half uint32 space. */
        fun distance(sequence: Int, reference: Int): Int = sequence - reference
    }
}
