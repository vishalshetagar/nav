package com.example.nav.llm

/** Groq reset headers use values such as 2m59.56s; Retry-After uses seconds. */
object RateLimitPolicy {
    fun durationMillis(value: String?): Long {
        val parts = Regex("(\\d+(?:\\.\\d+)?)(ms|h|m|s)").findAll(value.orEmpty())
        return parts.sumOf {
            val multiplier = when (it.groupValues[2]) { "h" -> 3_600_000; "m" -> 60_000; "s" -> 1000; else -> 1 }
            (it.groupValues[1].toDouble() * multiplier).toLong()
        }.coerceIn(0, 86_400_000)
    }

    fun retryMillis(retryAfter: String?, tokenReset: String?): Long = maxOf(
        ((retryAfter?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 86400.0) * 1000).toLong(),
        durationMillis(tokenReset), 30_000L
    )
}
