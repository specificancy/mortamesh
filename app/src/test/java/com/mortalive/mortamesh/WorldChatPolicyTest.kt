package com.mortalive.mortamesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldChatPolicyTest {
    @Test
    fun countsUnicodeCodePointsRatherThanUtf16Units() {
        val fiveHundredEmoji = "😀".repeat(500)
        assertEquals(500, WorldChatPolicy.codePointCount(fiveHundredEmoji))
        assertTrue(WorldChatPolicy.isValidPostText(fiveHundredEmoji))
        assertFalse(WorldChatPolicy.isValidPostText(fiveHundredEmoji + "x"))
    }

    @Test
    fun enforcesThe500CharacterBoundary() {
        assertTrue(WorldChatPolicy.isValidPostText("a".repeat(500)))
        assertFalse(WorldChatPolicy.isValidPostText("a".repeat(501)))
    }

    @Test
    fun rejectsEmptyAndUnsafeControlTextButAllowsLineBreaks() {
        assertFalse(WorldChatPolicy.isValidPostText(""))
        assertFalse(WorldChatPolicy.isValidPostText("   "))
        assertFalse(WorldChatPolicy.isValidPostText("hello\u0000world"))
        assertTrue(WorldChatPolicy.isValidPostText("line one\nline two"))
    }

    @Test
    fun rejectsUnpairedSurrogates() {
        assertFalse(WorldChatPolicy.isValidPostText("\uD83D"))
        assertFalse(WorldChatPolicy.isValidPostText("\uDE00"))
    }
}
