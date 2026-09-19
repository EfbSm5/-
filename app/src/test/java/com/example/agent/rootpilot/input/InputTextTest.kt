package com.example.agent.rootpilot.input

import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class InputTextTest {
    @Test fun acceptsLiteralTextWithoutShellRestrictions() {
        for (text in listOf("中文", " a b ", "\t\n", "😀", "'\"; $(id) & |", "a".repeat(128))) {
            assertTrue(InputText.isValid(text))
        }
    }

    @Test fun rejectsInvalidUnicodeControlsAndLength() {
        for (text in listOf("", "a".repeat(129), "\u0000", "\r", "\u007f", "\uD83D", "\uDE00", "\uD83Da")) {
            assertFalse(InputText.isValid(text))
        }
        assertTrue(InputText.isValid("😀".repeat(64)))
        assertFalse(InputText.isValid("😀".repeat(65)))
    }

    @Test fun rejectsAllPasswordVariationsWithFlags() {
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            assertTrue(InputConnectionBridge.isPassword(InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        }
        assertTrue(InputConnectionBridge.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(InputConnectionBridge.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(InputConnectionBridge.isPassword(InputType.TYPE_CLASS_NUMBER))
    }
}
