package com.example.nav.model

import java.security.MessageDigest

/** Excludes capture time; includes state, text, bounds and window so stale actions fail closed. */
fun ScreenContext.fingerprint(): String {
    val data = buildString {
        append(appPackage).append('|').append(windowId).append('|').append(scrollable)
        visibleTexts.forEach { append('|').append(it.length).append(':').append(it) }
        allElements.forEach {
            append('|').append(it.id).append(':').append(it.bounds.flattenToString())
                .append(':').append(it.enabled).append(':').append(it.checked)
        }
        clickableElements.forEach {
            append('|').append(it.nodePath).append(':').append(it.text).append(':').append(it.bounds.flattenToString())
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
}
