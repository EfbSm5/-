package com.example.agent.rootpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccessibilityEnabledDiagnosticTest {
    @Test fun capturedNullIsNotAnUncompletedRead() {
        assertEquals(AccessibilityEnabledDiagnostic.NULL, AccessibilityEnabledDiagnostic.classify(null))
        assertNotEquals(AccessibilityEnabledDiagnostic.UNKNOWN, AccessibilityEnabledDiagnostic.classify(null))
    }

    @Test fun onlyExactZeroAndOneHaveNumericClassifications() {
        assertEquals(AccessibilityEnabledDiagnostic.ZERO, AccessibilityEnabledDiagnostic.classify("0"))
        assertEquals(AccessibilityEnabledDiagnostic.ONE, AccessibilityEnabledDiagnostic.classify("1"))
    }

    @Test fun otherValuesAreNotNormalizedOrReturned() {
        for (value in listOf("", "null", "00", "01", " 0", "1 ", "2", "PRIVATE_SYNTHETIC_VALUE")) {
            assertEquals(AccessibilityEnabledDiagnostic.OTHER, AccessibilityEnabledDiagnostic.classify(value))
        }
    }
}
