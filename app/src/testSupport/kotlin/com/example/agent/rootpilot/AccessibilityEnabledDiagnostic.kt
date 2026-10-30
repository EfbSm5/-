package com.example.agent.rootpilot

/** Test-only value classification; UNKNOWN is reserved for a read that did not complete. */
internal enum class AccessibilityEnabledDiagnostic {
    UNKNOWN, NULL, ZERO, ONE, OTHER;

    companion object {
        fun classify(value: String?): AccessibilityEnabledDiagnostic = when (value) {
            null -> NULL
            "0" -> ZERO
            "1" -> ONE
            else -> OTHER
        }
    }
}
