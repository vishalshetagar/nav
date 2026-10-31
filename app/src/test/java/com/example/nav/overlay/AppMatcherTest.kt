package com.example.nav.overlay

import org.junit.Assert.*
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf("com.video" to "YouTube", "com.music" to "YouTube Music", "com.browser" to "Google Chrome")
    @Test fun exactLabelWinsAndUniqueWordsWork() {
        assertEquals("com.video", AppMatcher.match("youtube", apps))
        assertEquals("com.browser", AppMatcher.match("Chrome", apps))
    }
    @Test fun rejectsAmbiguousAndPartialNames() {
        assertNull(AppMatcher.match("Google", apps + ("com.mail" to "Google Mail")))
        assertNull(AppMatcher.match("tube", apps))
        assertNull(AppMatcher.match("", apps))
        assertNull(AppMatcher.match("Chrome and send a message", apps))
    }
}
