package com.example.nav.model

data class ScreenContext(
    val appPackage: String,
    val appName: String,
    val visibleTexts: List<String>,
    val clickableElements: List<ClickableElement>,
    val allElements: List<UIElement>,
    val scrollable: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val windowId: Int = -1,
    val truncated: Boolean = false
)
