package com.example.nav.overlay

internal object UserIntentParser {
    private val openAppPattern = Regex(
        pattern = "^(?:please\\s+)?(?:open|launch|start)\\s+(.+?)(?:\\s+app)?[.!?]*$",
        option = RegexOption.IGNORE_CASE
    )

    fun explicitAppLaunch(intent: String): String? =
        openAppPattern.matchEntire(intent.trim())
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
}
