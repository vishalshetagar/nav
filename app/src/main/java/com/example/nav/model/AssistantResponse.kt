package com.example.nav.model

enum class ActionType { TAP, SCROLL, BACK, OPEN_APP, EXPLAIN, CLARIFY, COMPLETE }

data class AssistantAction(
    val type: ActionType,
    val instruction: String,
    val target: String = "",
    val value: String = ""
) {
    val canExecute: Boolean get() = type in setOf(ActionType.TAP, ActionType.SCROLL, ActionType.BACK, ActionType.OPEN_APP)
}

sealed interface AgentResult {
    data class Success(val action: AssistantAction, val tokensUsed: Int = 0) : AgentResult
    data class Failure(val message: String) : AgentResult
}
