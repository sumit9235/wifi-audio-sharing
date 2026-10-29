package com.example.wifiaudiocapture
import org.junit.Assert.*
import org.junit.Test
class PeerRegistryTest {
    @Test fun boundedIdempotentLifecycle() {
        val peers = PeerRegistry<String>(3, 6000)
        repeat(3) { assertTrue(peers.join("p$it", 0)) }
        assertFalse(peers.join("fourth", 1))
        assertTrue(peers.join("p0", 5000))
        assertEquals(setOf("p1", "p2"), peers.expire(6001))
        peers.leave("p0"); peers.leave("p0")
        assertTrue(peers.join("new", 7000))
        peers.clear(); assertTrue(peers.keys().isEmpty())
    }
}
