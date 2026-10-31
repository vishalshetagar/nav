package com.example.nav.voice

/** Exact local commands: longer tasks mentioning these words never count as consent. */
enum class VoiceCommand {
    CONFIRM, NEXT, REPEAT, STOP, CLOSE;

    companion object {
        fun parse(text: String): VoiceCommand? = when (
            text.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        ) {
            "do this step", "confirm this step" -> CONFIRM
            "next", "next step", "try again" -> NEXT
            "repeat", "repeat that" -> REPEAT
            "stop", "cancel", "stop task", "cancel task" -> STOP
            "close nav", "close assistant", "exit assistant" -> CLOSE
            else -> null
        }
    }
}
