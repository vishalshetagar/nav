package com.example.nav.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test fun recognizesSpokenControlsWithoutAnApiRequest() {
        assertEquals(VoiceCommand.CONFIRM, VoiceCommand.parse("Do this step."))
        assertEquals(VoiceCommand.NEXT, VoiceCommand.parse(" Next   step! "))
        assertEquals(VoiceCommand.STOP, VoiceCommand.parse("Cancel task"))
        assertEquals(VoiceCommand.REPEAT, VoiceCommand.parse("Repeat that"))
        assertEquals(VoiceCommand.CLOSE, VoiceCommand.parse("Close NaV"))
    }

    @Test fun requiresExplicitConfirmationRatherThanWordsWithinAnotherRequest() {
        listOf("yes", "do it", "don't do this step", "how do I do this step", "stop notifications", "open next app").forEach {
            assertNull(it, VoiceCommand.parse(it))
        }
    }
}
