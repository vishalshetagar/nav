package com.example.nav

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nav.accessibility.GuideAccessibilityService
import com.example.nav.model.*
import com.example.nav.overlay.AssistantRuntime
import com.example.nav.overlay.FloatingBubbleService
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var automation: UiAutomation
    private var oldServices: String? = null
    private var oldEnabled: String? = null
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(150)
        assertTrue("Timed out waiting for accessibility state", predicate())
    }
    private fun screen(): ScreenContext? {
        var result: ScreenContext? = null
        instrumentation.runOnMainSync { result = GuideAccessibilityService.getCurrentContext() }
        return result
    }
    @Before fun setUp() {
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val resolver = instrumentation.targetContext.contentResolver
        oldServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        oldEnabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val service = "com.example.nav/com.example.nav.accessibility.GuideAccessibilityService"
        // Instrumentation restarts the target process; clear stale accessibility bindings first.
        shell("settings delete secure enabled_accessibility_services")
        SystemClock.sleep(500)
        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
        waitFor { GuideAccessibilityService.connected }
        shell("am start -W -f 268468224 -n com.example.nav.test/com.example.nav.TargetActivity")
        waitFor { screen()?.visibleTexts?.contains("Fixture ready") == true }
        automation.waitForIdle(500, 5000)
    }
    @After fun tearDown() {
        instrumentation.targetContext.stopService(Intent(instrumentation.targetContext, FloatingBubbleService::class.java))
        if (::automation.isInitialized) {
            shell("input keyevent KEYCODE_HOME")
            if (oldServices.isNullOrBlank()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $oldServices")
            if (oldEnabled == null) shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $oldEnabled")
        }
    }
    @Test fun readsNestedLabelsAndExcludesPasswordsAndDisabledActions() {
        val context = screen()!!
        assertEquals("com.example.nav.test", context.appPackage)
        assertTrue(context.clickableElements.any { it.text == "Nested control label" })
        assertFalse(context.clickableElements.any { it.text.equals("Disabled control", ignoreCase = true) })
        assertFalse(context.visibleTexts.any { it.contains("super-secret") })
        val duplicates = context.clickableElements.filter { it.text.equals("Details", ignoreCase = true) }
        assertEquals(context.clickableElements.map { it.text }.toString(), 2, duplicates.size)
        assertNotEquals(duplicates[0].nodePath, duplicates[1].nodePath)
    }
    @Test fun clicksExactDuplicateAndRejectsStaleSnapshot() {
        val before = screen()!!
        val second = before.clickableElements.filter { it.text.equals("Details", ignoreCase = true) }[1]
        val action = AssistantAction(ActionType.TAP, "Tap the second Details button", "e2")
        var clicked = false
        instrumentation.runOnMainSync { clicked = GuideAccessibilityService.execute(action, before, second) }
        assertTrue(clicked)
        waitFor { screen()?.visibleTexts?.contains("Second selected") == true }
        instrumentation.runOnMainSync { clicked = GuideAccessibilityService.execute(action, before, second) }
        assertFalse("An action from an old screen must be rejected", clicked)
    }
    @Test fun scrollChangesObservation() {
        val before = screen()!!
        assertTrue(before.scrollable)
        var scrolled = false
        instrumentation.runOnMainSync {
            scrolled = GuideAccessibilityService.execute(AssistantAction(ActionType.SCROLL, "Scroll down", value = "down"), before, null)
        }
        assertTrue(scrolled)
        waitFor { screen()?.fingerprint() != before.fingerprint() }
    }
    private fun launchAssistant() {
        shell("am start -W -n com.example.nav/.MainActivity")
        instrumentation.runOnMainSync {
            instrumentation.targetContext.startForegroundService(Intent(instrumentation.targetContext, FloatingBubbleService::class.java))
        }
    }

    private fun assistantWindows() = automation.windows.filter {
        it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.root?.packageName?.toString() == "com.example.nav"
    }

    private fun panelHasText(text: String): Boolean {
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0): Boolean {
            if (node == null || depth > 40) return false
            if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString()?.contains(text) == true) return true
            return (0 until node.childCount).any { contains(node.getChild(it), depth + 1) }
        }
        // Compose virtual nodes are traversable even when the provider's text-search API returns no matches.
        return assistantWindows().any { contains(it.root) }
    }

    @Test fun floatingPanelWaitsForDelayedAccessibilityConnection() {
        shell("settings delete secure enabled_accessibility_services")
        waitFor { !GuideAccessibilityService.connected }
        launchAssistant()
        waitFor { AssistantRuntime.state.value.phase == AssistantRuntime.Phase.STARTING }
        SystemClock.sleep(500)
        shell("settings put secure enabled_accessibility_services com.example.nav/com.example.nav.accessibility.GuideAccessibilityService")
        waitFor { AssistantRuntime.state.value.phase == AssistantRuntime.Phase.RUNNING }
        waitFor { assistantWindows().size == 1 }
        waitFor { panelHasText("Tap to speak") }
    }

    @Test fun reopeningAssistantShowsOneVisiblePanel() {
        launchAssistant()
        waitFor { AssistantRuntime.state.value.phase == AssistantRuntime.Phase.RUNNING }
        waitFor { assistantWindows().size == 1 }
        launchAssistant()
        waitFor { assistantWindows().size == 1 }
        waitFor { panelHasText("Tap to speak") }
    }

    @Test fun voiceIslandExpandsAndCollapsesWithoutEditableFieldsOrKeyboardFocus() {
        launchAssistant()
        waitFor { panelHasText("Tap to speak") }
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?, match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (match(node)) return node
            for (i in 0 until node.childCount) find(node.getChild(i), match)?.let { return it }
            return null
        }
        val compact = android.graphics.Rect().also { assistantWindows().single().getBoundsInScreen(it) }
        val clickable = find(assistantWindows().single().root) { it.isClickable }!!
        assertTrue(clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitFor { panelHasText("Minimize NaV") }
        automation.waitForIdle(500, 5000)
        assertNull(find(assistantWindows().single().root) { it.isEditable })
        assertFalse("Voice overlay must not take focus from the app", assistantWindows().single().isFocused)
        val expanded = android.graphics.Rect().also { assistantWindows().single().getBoundsInScreen(it) }
        assertTrue(expanded.height() > compact.height())
        val label = find(assistantWindows().single().root) { it.contentDescription?.toString() == "Minimize NaV" }!!
        val collapse = find(label) { it.isClickable } ?: label.parent
        assertNotNull("Minimize must expose a clickable accessibility node: $label", collapse)
        assertTrue("Minimize click failed: $collapse", collapse!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        waitFor { !panelHasText("Minimize NaV") }
        assertFalse(panelHasText("Ask NaV"))
    }

    @Test fun unavailableAccessibilityReportsFailureInsteadOfSilentlyStopping() {
        shell("settings delete secure enabled_accessibility_services")
        waitFor { !GuideAccessibilityService.connected }
        launchAssistant()
        waitFor { AssistantRuntime.state.value.phase == AssistantRuntime.Phase.FAILED }
        assertTrue(AssistantRuntime.state.value.message.contains("not connected"))
        assertTrue(assistantWindows().isEmpty())
    }

}
