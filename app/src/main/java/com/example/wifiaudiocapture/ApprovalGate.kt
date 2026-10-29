package com.example.wifiaudiocapture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Pending requests are bounded and never auto-approved after host/process restart. */
class ApprovalGate(private val changed: (List<SessionState.Pending>) -> Unit) : AutoCloseable {
    private data class Request(val info: SessionState.Pending, val result: CompletableDeferred<Boolean>)
    private val pending = linkedMapOf<String, Request>()
    private var closed = false
    suspend fun request(name: String, address: String, timeoutMs: Long = 30_000): Boolean {
        val r = synchronized(this) {
            if (closed || pending.size >= 3) return false
            val id = UUID.randomUUID().toString()
            Request(SessionState.Pending(id, name, address), CompletableDeferred()).also { pending[id] = it; publish() }
        }
        return try { withTimeoutOrNull(timeoutMs) { r.result.await() } ?: false }
        finally { synchronized(this) { pending.remove(r.info.id); publish() } }
    }
    @Synchronized fun decide(id: String, allow: Boolean) { pending[id]?.result?.complete(allow) }
    @Synchronized private fun publish() { changed(pending.values.map { it.info }) }
    @Synchronized override fun close() {
        closed = true; pending.values.forEach { it.result.complete(false) }; pending.clear(); publish()
    }
}
