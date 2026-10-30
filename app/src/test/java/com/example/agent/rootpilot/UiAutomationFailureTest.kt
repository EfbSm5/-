package com.example.agent.rootpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UiAutomationFailureTest {
    @Test fun connectionShutdownLiteral() {
        assertEquals(UiAutomationFailure.UI_AUTOMATION_CONNECTION_SHUTDOWN, classify("Connection shutdown!"))
    }

    @Test fun alreadyConnectedLiteral() {
        assertEquals(UiAutomationFailure.UI_AUTOMATION_ALREADY_CONNECTED, classify("Already connected."))
    }

    @Test fun alreadyConnectedObjectPrefix() {
        assertEquals(UiAutomationFailure.UI_AUTOMATION_ALREADY_CONNECTED,
            classify("UiAutomation connected, UiAutomation@fixture"))
    }

    @Test fun registeredAospConcatenationDoesNotRequireAnExtraSpace() {
        val client = "android.accessibilityservice.IAccessibilityServiceClient\$Stub\$Proxy@fixture"
        assertEquals(UiAutomationFailure.UI_AUTOMATION_ALREADY_REGISTERED,
            classify("UiAutomationService " + client + "already registered!"))
    }

    @Test fun registeredSpacedFormRemainsSupported() {
        assertEquals(UiAutomationFailure.UI_AUTOMATION_ALREADY_REGISTERED,
            classify("UiAutomationService fixture already registered!"))
    }

    @Test fun registrationFailurePrefix() {
        assertEquals(UiAutomationFailure.UI_AUTOMATION_REGISTRATION_FAILED,
            classify("Error while registering UiTestAutomationService for user 0."))
    }

    @Test fun disconnectWhileConnectingPrefix() {
        assertEquals(UiAutomationFailure.UI_AUTOMATION_DISCONNECT_CONNECTING,
            classify("Cannot call disconnect() while connecting UiAutomation@fixture"))
    }

    @Test fun unknownMessagesPreserveGenericFallback() {
        for (message in listOf("vendor-specific failure", "", "Already disconnected.", "Not connected!")) {
            assertNull(classify(message))
        }
    }

    @Test fun nullMessagePreservesGenericFallback() {
        assertNull(UiAutomationFailure.classify(IllegalStateException()))
    }

    @Test fun similarButDifferentLiteralsDoNotMatch() {
        for (message in listOf("prefix Connection shutdown!", "Connection shutdown! suffix",
            "UiAutomationService fixture registered!", "OtherService fixturealready registered!",
            "UiAutomationService fixturealready registered! suffix", "Already connected")) {
            assertNull(classify(message))
        }
    }

    @Test fun otherThrowableTypesAreNotReclassifiedEvenWithKnownMessages() {
        for (error in listOf(RuntimeException("Connection shutdown!"),
            SecurityException("Already connected."), NullPointerException("Already connected."))) {
            assertNull(UiAutomationFailure.classify(error))
        }
    }

    @Test fun resultsContainOnlyFixedCodesNotMessagePayloads() {
        val privateMarker = "PRIVATE_SYNTHETIC_MARKER"
        val messages = listOf("UiAutomation connected, $privateMarker",
            "UiAutomationService ${privateMarker}already registered!",
            "Error while registering UiTestAutomationService $privateMarker",
            "Cannot call disconnect() while connecting $privateMarker")
        for (message in messages) {
            val code = requireNotNull(classify(message))
            assertFalse(code.name.contains(privateMarker))
            assertEquals(code.name, code.toString())
        }
    }

    private fun classify(message: String) = UiAutomationFailure.classify(IllegalStateException(message))
}
