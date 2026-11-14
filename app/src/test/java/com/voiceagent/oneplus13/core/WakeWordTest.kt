package com.voiceagent.oneplus13.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test fun stripsLongFormWithoutLeavingItsPrefix() {
        assertTrue(WakeWord.contains("Эй Ваня, открой музыку"))
        assertEquals("открой музыку", WakeWord.strip("Эй Ваня, открой музыку"))
    }

    @Test fun doesNotMatchInsideOtherWords() {
        assertFalse(WakeWord.contains("название и диваня"))
        assertFalse(WakeWord.contains("ваня123"))
    }

    @Test fun acceptsShortFormAndKeepsCommand() {
        assertEquals("как дела", WakeWord.strip("Ваня, как дела"))
        assertEquals("", WakeWord.strip("ваня"))
    }
}
