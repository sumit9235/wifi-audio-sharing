package com.example.wifiaudiocapture

/** NTP-style four timestamps, monotonic nanoseconds; choose the least-delay sample.
 * Offset converts listener clock to host clock. Half RTT is an uncertainty bound,
 * not a claim that Wi-Fi paths are symmetric or clocks perfectly synchronized. */
class ClockSync {
    @Volatile var offsetNs = 0L; private set
    @Volatile var roundTripNs = Long.MAX_VALUE; private set
    @Volatile var updatedNs = 0L; private set
    @Synchronized fun observe(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        if (t4 < t1 || t3 < t2) return false
        val rtt = (t4 - t1) - (t3 - t2)
        if (rtt < 0 || rtt > 1_000_000_000L) return false
        if (rtt <= roundTripNs || t4 - updatedNs > 10_000_000_000L) {
            offsetNs = ((t2 - t1) + (t3 - t4)) / 2
            roundTripNs = rtt; updatedNs = t4
        }
        return true
    }
    fun ready(now: Long): Boolean = updatedNs != 0L && now - updatedNs < 15_000_000_000L
}
