package com.example.nav.llm

import com.example.nav.model.ClickableElement
import com.example.nav.model.GuidanceStep
import com.example.nav.model.ScreenContext
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** One bounded observation; no repeated accessibility tree or unbounded chat transcript. */
internal class PromptBuilder {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private fun bounded(value: String, maxChars: Int): String {
        var text = value
        while (gson.toJson(text).length > maxChars) text = text.dropLast(maxOf(1, text.length / 8))
        return text
    }

    fun build(context: ScreenContext, goal: String, history: List<GuidanceStep>, language: String): Observation {
        val targets = linkedMapOf<String, ClickableElement>()
        val controls = JsonArray()
        val text = JsonArray()
        val root = JsonObject().apply {
            addProperty("goal", bounded(goal.take(800), 1100))
            addProperty("language", language.take(16))
            addProperty("app", bounded(context.appName.take(80), 160))
            addProperty("package", bounded(context.appPackage.take(120), 160))
            addProperty("scrollable", context.scrollable)
            addProperty("truncated", context.truncated)
            add("previous_steps", gson.toJsonTree(history.takeLast(3).map { bounded(it.instruction.take(160), 250) }))
            add("controls", controls)
            add("screen_text", text)
        }
        var truncated = context.truncated
        for ((index, element) in context.clickableElements.withIndex()) {
            val id = "e${index + 1}"
            val control = JsonObject().apply {
                addProperty("id", id)
                addProperty("label", element.text.take(100))
                addProperty("role", element.className?.substringAfterLast('.')?.take(40) ?: "control")
                add("bounds", gson.toJsonTree(listOf(element.bounds.left, element.bounds.top, element.bounds.right, element.bounds.bottom)))
                element.checked?.let { addProperty("checked", it) }
                if (element.editable) addProperty("editable", true)
            }
            controls.add(control)
            if (controls.size() > 36 || gson.toJson(root).length > MAX_CHARS - 900) {
                controls.remove(controls.size() - 1)
                truncated = true
                break
            }
            targets[id] = element
        }
        val labels = targets.values.map { it.text }.toSet()
        for (label in context.visibleTexts.distinct().filter { it !in labels }) {
            text.add(label.take(120))
            if (text.size() > 20 || gson.toJson(root).length > MAX_CHARS) {
                text.remove(text.size() - 1)
                truncated = true
                break
            }
        }
        root.addProperty("truncated", truncated)
        return Observation(gson.toJson(root), targets, context)
    }

    companion object { const val MAX_CHARS = 6400 }
}

internal data class Observation(
    val json: String,
    val targets: Map<String, ClickableElement>,
    val context: ScreenContext
)
