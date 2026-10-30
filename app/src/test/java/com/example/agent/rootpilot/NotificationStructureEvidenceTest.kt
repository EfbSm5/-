package com.example.agent.rootpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStructureEvidenceTest {
    private val receipt = "321b21cd-4137-4e24-a14a-56d6a94b94f1"
    private fun node(resource: String? = "com.android.systemui:id/title", clazz: String? = "android.widget.TextView",
        pkg: String? = "com.android.systemui") = NotificationStructureEvidence.Node(resource, clazz, pkg,
        enabled = true, clickable = false, scrollable = false)
    private fun output(value: NotificationStructureEvidence.Node): String =
        NotificationStructureEvidence.capture(0, { null as Int? }, { value }).json(receipt, 123).toString(Charsets.UTF_8)

    @Test fun allowedMetadataAndIdentityArePreserved() {
        val json = output(node())
        assertTrue(json.contains("\"receiptId\":\"$receipt\""))
        assertTrue(json.contains("\"mainPid\":123"))
        assertTrue(json.contains("com.android.systemui:id/title"))
        assertTrue(json.contains("android.widget.TextView"))
        assertTrue(json.contains("\"enabled\":true,\"clickable\":false,\"scrollable\":false"))
        assertTrue(json.contains("\"truncated\":false"))
        assertTrue(json.contains("\"structureOnlyNotAcceptance\":true"))
    }

    @Test fun androidResourcesAndJavaInnerClassAreAllowed() {
        val json = output(node("android:id/content", "android.view.View\$Inner", "android"))
        assertTrue(json.contains("android:id/content"))
        assertTrue(json.contains("android.view.View\$Inner"))
        assertFalse(json.contains("REDACTED"))
    }

    @Test fun invalidAndSensitiveValuesNeverReachSerializedEvidence() {
        val secrets = listOf("private message\nsecret", "secret\"payload", "https://private/path",
            "com.example.private:id/title", "password=secret", "android:id/path/secret", "", "x".repeat(129))
        for (secret in secrets) {
            val json = output(node(secret, secret, secret))
            assertEquals(3, Regex("REDACTED").findAll(json).count())
            if (secret.isNotEmpty()) assertFalse(json.contains(secret))
            assertFalse(json.contains("private"))
        }
    }

    @Test fun nullMetadataIsExplicitlyRedactedNotNullOrMissing() {
        val json = output(node(null, null, null))
        assertEquals(3, Regex("REDACTED").findAll(json).count())
        assertFalse(json.contains("null"))
    }

    @Test fun fieldLength128IsAcceptedBut129IsRedacted() {
        val prefix = "android:id/"
        assertTrue(output(node(prefix + "a".repeat(128 - prefix.length))).contains(prefix))
        assertFalse(output(node(prefix + "a".repeat(129 - prefix.length))).contains(prefix))
        val classPrefix = "android."
        assertTrue(output(node(clazz = classPrefix + "A".repeat(128 - classPrefix.length))).contains(classPrefix + "A"))
        assertFalse(output(node(clazz = classPrefix + "A".repeat(129 - classPrefix.length))).contains(classPrefix + "A"))
    }

    @Test fun twelveNodesAreCapturedWithoutDescribingThirteenthOrReadingArbitraryContents() {
        var descriptions = 0
        var parents = 0
        val snapshot = NotificationStructureEvidence.capture(0, { parents++; it + 1 }) {
            descriptions++
            check(it < 12)
            node()
        }
        val json = snapshot.json(receipt, 123).toString(Charsets.UTF_8)
        assertEquals(12, descriptions)
        assertEquals(12, parents)
        assertEquals(12, Regex("\"depth\":").findAll(json).count())
        assertTrue(json.contains("\"truncated\":true"))
        assertTrue(json.toByteArray().size <= NotificationStructureEvidence.MAX_BYTES)
    }

    @Test fun absentExpectedRowResourceAndEarlyNullParentAreSuccessfulBoundedEvidence() {
        val json = NotificationStructureEvidence.capture(0, { if (it < 2) it + 1 else null }) {
            node("android:id/content")
        }.json(receipt, 123).toString(Charsets.UTF_8)
        assertEquals(3, Regex("\"depth\":").findAll(json).count())
        assertTrue(json.contains("\"truncated\":false"))
        assertFalse(json.contains("expandableNotificationRow"))
    }

    @Test fun twelveTerminalNodesAreNotMarkedTruncated() {
        val json = NotificationStructureEvidence.capture(0, { if (it < 11) it + 1 else null }) { node() }
            .json(receipt, 123).toString(Charsets.UTF_8)
        assertEquals(12, Regex("\"depth\":").findAll(json).count())
        assertTrue(json.contains("\"truncated\":false"))
    }

    @Test fun invalidReceiptProducesOnlyFixedIdentityFailure() {
        val snapshot = NotificationStructureEvidence.capture(0, { null as Int? }) { node() }
        for (invalid in listOf("private-receipt", "x".repeat(36), receipt.uppercase())) {
            val failure = runCatching { snapshot.json(invalid, 123) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("STRUCTURE_IDENTITY_INVALID", failure?.message)
        }
    }

    @Test fun invalidPidProducesOnlyFixedIdentityFailure() {
        val snapshot = NotificationStructureEvidence.capture(0, { null as Int? }) { node() }
        for (pid in listOf(0, -1)) {
            assertEquals("STRUCTURE_IDENTITY_INVALID", runCatching { snapshot.json(receipt, pid) }.exceptionOrNull()?.message)
        }
    }

    @Test fun longPressFailureAddsFixedFailureOnlyContractWithoutAcceptanceProofs() {
        val snapshot = NotificationStructureEvidence.capture(0, { null as Int? }) { node() }
        val failure = snapshot.expansionFailureJson(receipt, 123).toString(Charsets.UTF_8)
        assertTrue(failure.contains("\"failureOnly\":true"))
        assertTrue(failure.contains("\"failureSite\":\"AFTER_BIND\""))
        assertTrue(failure.contains("\"failureReason\":\"IDENTITY_ROW_UNRELIABLE\""))
        assertTrue(failure.contains("\"gestureKind\":\"LONG_CLICK\""))
        assertTrue(failure.contains("\"structureOnlyNotAcceptance\":true"))
        for (forbidden in listOf("identityProofs", "expansionProofs", "ownRowExpanded", "expandStatus",
            "text", "description", "children", "siblings")) assertFalse(failure.contains("\"$forbidden\""))
        val ordinary = snapshot.json(receipt, 123).toString(Charsets.UTF_8)
        for (field in listOf("failureOnly", "failureSite", "failureReason", "gestureKind")) assertFalse(ordinary.contains(field))
    }
    @Test fun longPressFailureUsesSameTwelveParentBoundRedactionAndSizeCap() {
        var described = 0
        var parents = 0
        val bytes = NotificationStructureEvidence.capture(0, { parents++; it + 1 }) {
            check(it < 12); described++
            node("private-resource", "private-description", "private-package")
        }.expansionFailureJson(receipt, 123)
        val json = bytes.toString(Charsets.UTF_8)
        assertEquals(12, described); assertEquals(12, parents)
        assertEquals(36, Regex("REDACTED").findAll(json).count())
        assertEquals(12, Regex("\"depth\":").findAll(json).count())
        assertTrue(json.contains("\"truncated\":true")); assertFalse(json.contains("private"))
        assertTrue(bytes.size <= NotificationStructureEvidence.MAX_BYTES)
    }
    @Test fun longPressFailureCannotSerializeInvalidCorrelationOrPid() {
        val snapshot = NotificationStructureEvidence.capture(0, { null as Int? }) { node() }
        for ((id, pid) in listOf("../other" to 123, receipt.uppercase() to 123, receipt to 0)) {
            assertEquals("STRUCTURE_IDENTITY_INVALID", runCatching {
                snapshot.expansionFailureJson(id, pid)
            }.exceptionOrNull()?.message)
        }
    }
    @Test fun worstCaseAllowedMetadataStillFitsOverallBound() {
        val resource = "com.android.systemui:id/" + "x".repeat(105)
        val clazz = "android." + "C".repeat(120)
        val bytes = NotificationStructureEvidence.capture(0, { it + 1 }) { node(resource, clazz) }.json(receipt, 123)
        assertTrue(bytes.size <= NotificationStructureEvidence.MAX_BYTES)
    }
}
