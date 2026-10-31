package com.example.nav.model

data class GuidanceStep(
    val instruction: String,
    val targetElement: String,
    val appPackage: String,
    val screenContextSummary: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class GuidanceSession(
    val goal: String,
    val history: MutableList<GuidanceStep> = mutableListOf(),
    var stepCount: Int = 0
)
