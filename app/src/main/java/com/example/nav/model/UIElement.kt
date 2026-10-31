package com.example.nav.model

import android.graphics.Rect

data class ClickableElement(
    val text: String,
    val bounds: Rect,
    val contentDescription: String?,
    val id: String? = null,
    val className: String? = null,
    val nodePath: String = "",
    val checked: Boolean? = null,
    val editable: Boolean = false
)

data class UIElement(
    val text: String?,
    val contentDescription: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val className: String?,
    val id: String? = null,
    val enabled: Boolean = true,
    val checked: Boolean? = null
)
