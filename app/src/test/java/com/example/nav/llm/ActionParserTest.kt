package com.example.nav.llm

import com.example.nav.model.ActionType
import org.junit.Assert.*
import org.junit.Test

class ActionParserTest {
    private fun reply(action: String = "tap", target: String = "e2", value: String = "") =
        """{"action":"$action","instruction":"Tap the second Settings button","target":"$target","value":"$value"}"""

    @Test fun duplicateLabelsAreAddressedById() {
        val action = ActionParser.parse(reply(), setOf("e1", "e2"), false)
        assertEquals(ActionType.TAP, action.type)
        assertEquals("e2", action.target)
    }
    @Test fun rejectsUnknownTargetsAndUnsupportedActions() {
        listOf(reply(target = "e3"), reply(action = "type"), reply(target = "Settings"), reply(value = "hidden command")).forEach {
            assertThrows(Exception::class.java) { ActionParser.parse(it, setOf("e1", "e2"), false) }
        }
    }
    @Test fun refusesScrollOnNonScrollableScreen() {
        assertThrows(Exception::class.java) { ActionParser.parse(reply("scroll", "", "down"), emptySet(), false) }
        assertEquals(ActionType.SCROLL, ActionParser.parse(reply("scroll", "", "down"), emptySet(), true).type)
        assertThrows(Exception::class.java) { ActionParser.parse(reply("scroll", "", "sideways"), emptySet(), true) }
    }
    @Test fun rejectsProseTruncatedJsonMissingFieldsAndWrongTypes() {
        listOf("Click on [Settings]", "{}", reply().dropLast(1), reply().replace("\"e2\"", "42"), reply().replace("}", ",\"extra\":true}")).forEach {
            assertThrows(Exception::class.java) { ActionParser.parse(it, setOf("e2"), true) }
        }
    }
    @Test fun explanationCannotSmuggleATarget() {
        assertThrows(Exception::class.java) { ActionParser.parse(reply("explain"), setOf("e2"), true) }
        assertFalse(ActionParser.parse(reply("complete", ""), emptySet(), false).canExecute)
    }
}
