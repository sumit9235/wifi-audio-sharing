package com.example.wifiaudiocapture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalGateTest {
    @Test fun approvalTimeoutCapacityAndShutdown() = runBlocking {
        var requests = emptyList<SessionState.Pending>()
        val gate = ApprovalGate { requests = it }
        val yes = async(start = CoroutineStart.UNDISPATCHED) { gate.request("Phone", "192.168.1.2") }
        gate.decide(requests.single().id, true); assertTrue(yes.await()); assertTrue(requests.isEmpty())
        val no = async(start = CoroutineStart.UNDISPATCHED) { gate.request("Other", "192.168.1.3") }
        gate.decide(requests.single().id, false); assertFalse(no.await())
        assertFalse(gate.request("Timeout", "192.168.1.4", 5))
        val pending = (1..3).map { async(start = CoroutineStart.UNDISPATCHED) { gate.request("Phone $it", "192.168.1.$it") } }
        assertFalse(gate.request("Fourth", "192.168.1.5"))
        gate.close(); pending.forEach { assertFalse(it.await()) }
        assertTrue(requests.isEmpty()); assertFalse(gate.request("Closed", "192.168.1.6"))
    }
}
