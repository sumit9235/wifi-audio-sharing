package com.example.wifiaudiocapture

/** Monotonic time is supplied by the caller to make expiry deterministic. */
class PeerRegistry<K>(private val capacity: Int = 5, private val timeoutMs: Long = 6000) {
    private val peers = LinkedHashMap<K, Long>()
    @Synchronized fun join(key: K, now: Long): Boolean {
        expire(now)
        if (key !in peers && peers.size >= capacity) return false
        peers[key] = now
        return true
    }
    @Synchronized fun leave(key: K) { peers.remove(key) }
    @Synchronized fun expire(now: Long): Set<K> {
        val expired = peers.filterValues { now - it > timeoutMs }.keys.toSet()
        expired.forEach { peers.remove(it) }
        return expired
    }
    @Synchronized fun keys(): Set<K> = peers.keys.toSet()
    @Synchronized fun clear() = peers.clear()
}
