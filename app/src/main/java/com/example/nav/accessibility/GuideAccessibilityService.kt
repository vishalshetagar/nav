package com.example.nav.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.nav.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads only the foreground app. Screen data is collected on demand, never sent from events. */
class GuideAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: GuideAccessibilityService? = null
        const val ACTION_SCREEN_CHANGED = "com.example.nav.SCREEN_CHANGED"
        const val EXTRA_KEYBOARD_VISIBLE = "keyboard_visible"
        private val connection = MutableStateFlow(false)
        val connectionState = connection.asStateFlow()
        val connected: Boolean get() = instance != null
        fun overlayWindowManager(): android.view.WindowManager? = instance?.getSystemService(WINDOW_SERVICE) as? android.view.WindowManager
        fun getCurrentContext(): ScreenContext? = instance?.extractCurrentContext()
        fun execute(action: AssistantAction, expected: ScreenContext, target: ClickableElement?): Boolean =
            instance?.executeObservedAction(action, expected, target) ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connection.value = true
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        instance = null
        connection.value = false
        if (com.example.nav.overlay.AssistantRuntime.state.value.phase == com.example.nav.overlay.AssistantRuntime.Phase.RUNNING) {
            com.example.nav.overlay.AssistantRuntime.failed("Android disconnected NaV accessibility. Re-enable NaV in Accessibility settings, then start it again.")
        }
        stopService(Intent(this, com.example.nav.overlay.FloatingBubbleService::class.java))
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) return
        sendBroadcast(Intent(ACTION_SCREEN_CHANGED).setPackage(packageName).putExtra(
            EXTRA_KEYBOARD_VISIBLE, windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ))
    }

    private fun foregroundRoot(): AccessibilityNodeInfo? {
        if (getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) return null
        // Include system dialogs and notification shade, but never our overlay or the keyboard.
        for (window in windows.sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isActive }
            .thenByDescending { it.isFocused }
            .thenByDescending { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.thenByDescending { it.layer })) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION && window.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() != packageName) return root
            release(root)
        }
        return rootInActiveWindow?.let {
            if (it.packageName?.toString() == packageName) { release(it); null } else it
        }
    }

    private fun privateNode(node: AccessibilityNodeInfo): Boolean = node.isPassword ||
        (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)

    private fun extractCurrentContext(): ScreenContext? {
        val root = foregroundRoot() ?: return null
        try {
            val texts = mutableListOf<String>()
            val controls = mutableListOf<ClickableElement>()
            val elements = mutableListOf<UIElement>()
            var visited = 0
            var truncated = false
            fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
                if (++visited > 1500 || depth > 40 || elements.size >= 250) { truncated = true; return }
                // Do not descend into password/sensitive containers or derive their text labels.
                if (privateNode(node)) return
                val bounds = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && !bounds.isEmpty) {
                    val text = node.text?.toString()?.take(160)
                    val description = node.contentDescription?.toString()?.take(160)
                    text?.takeIf { it.isNotBlank() }?.let(texts::add)
                    description?.takeIf { it.isNotBlank() }?.let(texts::add)
                    val checked = if (node.isCheckable) node.isChecked else null
                    elements.add(UIElement(text, description, Rect(bounds), node.isClickable && node.isEnabled,
                        node.isScrollable && node.isEnabled, node.className?.toString(), node.viewIdResourceName, node.isEnabled, checked))
                    if (node.isEnabled && node.isClickable) {
                        val label = text?.takeIf { it.isNotBlank() } ?: description?.takeIf { it.isNotBlank() }
                            ?: descendantLabel(node) ?: node.viewIdResourceName?.substringAfterLast('/') ?: "Unlabelled control"
                        controls.add(ClickableElement(label.take(160), Rect(bounds), description,
                            node.viewIdResourceName, node.className?.toString(), path, checked, node.isEditable))
                    }
                }
                for (i in 0 until node.childCount.coerceAtMost(100)) {
                    if (visited >= 1500 || elements.size >= 250) { truncated = true; break }
                    val child = node.getChild(i) ?: continue
                    try { visit(child, "$path/$i", depth + 1) } finally { release(child) }
                }
                if (node.childCount > 100) truncated = true
            }
            visit(root, "0", 0)
            val appPackage = root.packageName?.toString() ?: return null
            val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(appPackage, 0)).toString() }
                .getOrDefault(appPackage)
            return ScreenContext(appPackage, appName, texts.distinct(), controls, elements,
                elements.any { it.isScrollable }, windowId = root.windowId, truncated = truncated)
        } finally { release(root) }
    }

    private fun descendantLabel(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth >= 3) return null
        for (i in 0 until node.childCount.coerceAtMost(20)) {
            val child = node.getChild(i) ?: continue
            try {
                if (!child.isVisibleToUser || privateNode(child)) continue
                val label = child.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: descendantLabel(child, depth + 1)
                if (label != null) return label.take(160)
            } finally { release(child) }
        }
        return null
    }

    private fun executeObservedAction(action: AssistantAction, expected: ScreenContext, target: ClickableElement?): Boolean {
        val fresh = extractCurrentContext() ?: return false
        if (fresh.fingerprint() != expected.fingerprint()) return false
        if (action.type == ActionType.BACK) return performGlobalAction(GLOBAL_ACTION_BACK)
        var node = foregroundRoot() ?: return false
        try {
            if (node.windowId != expected.windowId || node.packageName?.toString() != expected.appPackage) return false
            if (action.type == ActionType.TAP && target != null) {
                // Resolve the observed path, never fuzzy-match a label or guess screen coordinates.
                for (part in target.nodePath.split('/').drop(1)) {
                    val child = node.getChild(part.toIntOrNull() ?: return false) ?: return false
                    release(node)
                    node = child
                }
                if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || !node.isClickable || privateNode(node)) return false
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds != target.bounds || node.viewIdResourceName != target.id) return false
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: descendantLabel(node) ?: node.viewIdResourceName?.substringAfterLast('/') ?: "Unlabelled control"
                if (label.take(160) != target.text) return false
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (action.type == ActionType.SCROLL) {
                val actionId = if (action.value == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                var visited = 0
                fun scroll(current: AccessibilityNodeInfo, depth: Int = 0): Boolean {
                    if (++visited > 1500 || depth > 40 || privateNode(current)) return false
                    if (current.isVisibleToUser && current.isEnabled && current.isScrollable &&
                        current.actionList.any { it.id == actionId } && current.performAction(actionId)) return true
                    for (i in 0 until current.childCount.coerceAtMost(100)) {
                        val child = current.getChild(i) ?: continue
                        try { if (scroll(child, depth + 1)) return true } finally { release(child) }
                    }
                    return false
                }
                return scroll(node)
            }
            return false
        } finally { release(node) }
    }

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) { node.recycle() }
}
