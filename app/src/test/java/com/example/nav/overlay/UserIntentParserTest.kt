package com.example.nav.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserIntentParserTest {
    @Test
    fun parsesDirectOpenCommandsWithoutInference() {
        assertEquals("YouTube", UserIntentParser.explicitAppLaunch("open YouTube"))
        assertEquals("Google Maps", UserIntentParser.explicitAppLaunch("Please launch Google Maps app!"))
        assertEquals("WhatsApp", UserIntentParser.explicitAppLaunch("START WhatsApp."))
    }

    @Test
    fun leavesNavigationQuestionsForTheAssistant() {
        assertNull(UserIntentParser.explicitAppLaunch("How do I open YouTube?"))
        assertNull(UserIntentParser.explicitAppLaunch("Help me find my order"))
    }
}
