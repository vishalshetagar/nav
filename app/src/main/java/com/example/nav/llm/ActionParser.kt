package com.example.nav.llm

import com.example.nav.model.ActionType
import com.example.nav.model.AssistantAction
import com.google.gson.JsonParser

/** Validate the wire response locally too: invented targets must never become actions. */
object ActionParser {
    val responseFormat: Map<String, Any> = mapOf(
        "type" to "json_schema",
        "json_schema" to mapOf(
            "name" to "navigation_step", "strict" to true,
            "schema" to mapOf(
                "type" to "object", "additionalProperties" to false,
                "required" to listOf("action", "instruction", "target", "value"),
                "properties" to mapOf(
                    "action" to mapOf("type" to "string", "enum" to ActionType.entries.map { it.name.lowercase() }),
                    "instruction" to mapOf("type" to "string"),
                    "target" to mapOf("type" to "string"),
                    "value" to mapOf("type" to "string")
                )
            )
        )
    )

    fun parse(json: String, targetIds: Set<String>, scrollable: Boolean): AssistantAction {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.keySet() == setOf("action", "instruction", "target", "value"))
        fun string(key: String): String {
            val value = root[key]
            require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
            return value.asString.trim()
        }
        val type = ActionType.valueOf(string("action").uppercase())
        val instruction = string("instruction")
        val target = string("target")
        val value = string("value")
        require(instruction.isNotBlank() && instruction.length <= 600)
        require(value.length <= 120)
        if (type == ActionType.TAP) require(target in targetIds) else require(target.isEmpty())
        when (type) {
            ActionType.SCROLL -> require(scrollable && value in setOf("up", "down"))
            ActionType.OPEN_APP -> require(value.isNotBlank())
            else -> require(value.isEmpty())
        }
        return AssistantAction(type, instruction, target, value)
    }
}
