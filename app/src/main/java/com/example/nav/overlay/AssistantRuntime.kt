package com.example.nav.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable startup result; enabling a permission is not the same as attaching a window. */
object AssistantRuntime {
    enum class Phase { STOPPED, STARTING, RUNNING, FAILED }
    data class State(val phase: Phase = Phase.STOPPED, val message: String = "Assistant is stopped.")
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    fun starting() { mutableState.value = State(Phase.STARTING, "Connecting to accessibility and opening NaV...") }
    fun running() { mutableState.value = State(Phase.RUNNING, "NaV is open. Use the floating panel to ask for help.") }
    fun failed(message: String) { mutableState.value = State(Phase.FAILED, message) }
    fun stopped() {
        if (mutableState.value.phase != Phase.FAILED) mutableState.value = State()
    }
}
