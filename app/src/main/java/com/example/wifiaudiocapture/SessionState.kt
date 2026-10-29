package com.example.wifiaudiocapture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Ephemeral process state; no audio, address history or room credentials persisted. */
object SessionState {
    data class Pending(val id: String, val name: String, val address: String)
    data class State(
        val hosting: Boolean = false, val listening: Boolean = false,
        val startedMs: Long = 0, val hostMessage: String = "Ready to share",
        val listenerMessage: String = "Choose a nearby session",
        val peers: List<String> = emptyList(), val pending: List<Pending> = emptyList()
    )
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    fun update(transform: (State) -> State) { mutable.update(transform) }
}
