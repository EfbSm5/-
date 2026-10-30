package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NotificationExpansionTimeoutEvidenceTest {
    private val system = "com.android.systemui"
    private val receipt = "e8a31412-7a29-4645-9732-8f2c2b4907c8"
    private val none = NotificationExpansionEvidence.Code.NONE
    private val missing = NotificationExpansionEvidence.Code.EXPANSION_ACTION_MISSING
    private class Tree(val node: NotificationIdentityEvidence.Node, val children: MutableList<Tree> = mutableListOf())
    private fun node(id: String, clickable: Boolean = false) = NotificationIdentityEvidence.Node(
        "$system:id/$id", "android.widget.FrameLayout", system, true, clickable, false)
    private fun tree(confirm: Boolean = false, stop: Boolean = false): Tree {
        val content = Tree(node("status_bar_latest_event_content"), mutableListOf(
            Tree(node("title").copy(className = "android.widget.TextView", titleMatches = true)),
            Tree(node("app_name_text").copy(textEqualsAppLabel = true))))
        if (confirm) content.children += Tree(node("action0", true).copy(confirmActionMatches = true))
        if (stop) content.children += Tree(node("action0", true).copy(stopActionMatches = true))
        return Tree(node("unresolved_row", true).copy(longClickable = true), mutableListOf(
            Tree(node("chrome")), Tree(node("expanded"), mutableListOf(Tree(node("padding")), content))))
    }
    private fun snapshot(confirm: Boolean = false, stop: Boolean = false, modal: Boolean = true): NotificationIdentityEvidence.Snapshot<Tree> {
        val root = tree(confirm, stop)
        return if (modal) NotificationIdentityEvidence.captureModalContentPublic(root, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
            { it.children.size }, { it.children }, {}, { it.node })
        else NotificationIdentityEvidence.capture(root, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
            { it.children.size }, { n, i -> n.children[i] }, { it.node })
    }
    private fun copy(value: NotificationIdentityEvidence.Snapshot<Tree>, entries: List<NotificationIdentityEvidence.Entry> = value.entries,
        truncated: Boolean = value.truncated, phase: NotificationIdentityEvidence.Phase = value.phase,
        captureRootPath: String? = value.captureRootPath) = NotificationIdentityEvidence.Snapshot(
        phase, truncated, entries, value.expandNodes, value.modalBinding, value.captureBudget, captureRootPath)
    private fun candidate(value: NotificationIdentityEvidence.Snapshot<Tree>, code: NotificationExpansionEvidence.Code = NotificationExpansionEvidence.after(value)) =
        NotificationExpansionTimeoutEvidence.Candidate<Tree>().also { it.observe(value, code) }
    private fun bytes(value: NotificationIdentityEvidence.Snapshot<Tree>) = candidate(value).bytes(receipt, 1, true, true)
    private fun rejects(value: NotificationIdentityEvidence.Snapshot<Tree>, code: NotificationExpansionEvidence.Code = missing) {
        val candidate = candidate(value, code)
        assertFalse(candidate.available)
        assertThrows(IllegalArgumentException::class.java) { candidate.bytes(receipt, 1, true, true) }
    }

    @Test fun missingBothExportsHistoricalFullRowWithoutAcceptanceOrRawStrings() {
        val snapshot = snapshot()
        val text = String(bytes(snapshot), Charsets.UTF_8)
        assertTrue(text.contains("\"observedDecision\":\"ACTION_MISSING\""))
        for (key in listOf("failureOnly", "noAcceptance", "historicalObservation", "ownershipRechecked", "observerVerified"))
            assertTrue(text.contains("\"$key\":true"))
        assertTrue(text.contains("\"failureReason\":\"EXPANSION_POST_TIMEOUT\""))
        assertTrue(text.contains("\"captureRootPath\":\"0/1/1\""))
        assertEquals(snapshot.entries.size, Regex("\\\"path\\\":").findAll(text).count())
        assertTrue(text.contains("\"confirmActionMatches\":false"))
        assertTrue(text.contains("\"stopActionMatches\":false"))
        for (key in listOf("expandStatus", "identityProofs", "expansionProofs", "ownRowExpanded", "noOwnActivityLaunch", "\"text\":", "\"description\":"))
            assertFalse(text.contains(key))
        assertTrue(text.toByteArray().size <= NotificationIdentityEvidence.MAX_BYTES)
    }

    @Test fun eitherSingleActionCanBeMissingButExistingActionMustBeAvailable() {
        for ((confirm, stop) in listOf(true to false, false to true)) {
            val value = snapshot(confirm, stop)
            assertTrue(String(bytes(value)).contains("\"observedDecision\":\"ACTION_MISSING\""))
            for (disabled in listOf(true, false)) {
                val entries = value.entries.map { entry -> if (entry.node.confirmActionMatches || entry.node.stopActionMatches)
                    entry.copy(node = if (disabled) entry.node.copy(enabled = false) else entry.node.copy(clickable = false)) else entry }
                val bad = copy(value, entries)
                assertEquals(missing, NotificationExpansionEvidence.after(bad))
                rejects(bad)
            }
        }
    }

    @Test fun bothUniqueActionsAreHistoricalNoneNotPassInEitherCaptureMode() {
        for (modal in listOf(true, false)) {
            val value = snapshot(true, true, modal)
            assertEquals(none, NotificationExpansionEvidence.after(value))
            val text = String(bytes(value))
            assertTrue(text.contains("\"observedDecision\":\"NONE\""))
            assertTrue(text.contains("\"confirmActionMatches\":true"))
            assertTrue(text.contains("\"stopActionMatches\":true"))
            assertFalse(text.contains("\"passed\""))
        }
    }

    @Test fun timeoutEvidenceAcceptsEitherTitleIdButRejectsCrossNamespaceDuplicates() {
        for (modal in listOf(true, false)) for (id in listOf("android:id/title", "$system:id/title")) {
            val original = snapshot(modal = modal)
            val value = copy(original, original.entries.map { entry ->
                if (entry.node.titleMatches) entry.copy(node = entry.node.copy(resourceName = id)) else entry
            })
            assertTrue(candidate(value).available)
            val title = value.entries.single { it.node.titleMatches }
            val otherId = if (id == "android:id/title") "$system:id/title" else "android:id/title"
            for (matches in listOf(true, false)) {
                val duplicate = title.copy(path = "0/2", depth = 1,
                    node = title.node.copy(resourceName = otherId, titleMatches = matches))
                rejects(copy(value, value.entries + duplicate))
            }
        }
    }
    @Test fun everyQueryInvalidatesPriorSampleWhileNoQueryRetainsHistoricalSample() {
        val candidate = candidate(snapshot())
        assertTrue(candidate.available)
        val initial = candidate.bytes(receipt, 1, true, true)
        assertArrayEquals(initial, candidate.bytes(receipt, 1, true, true))
        candidate.beginQuery()
        assertFalse(candidate.available)
        assertThrows(IllegalArgumentException::class.java) { candidate.bytes(receipt, 1, true, true) }
        // A query exception supplies no observe call; it cannot restore the old snapshot.
        assertFalse(candidate.available)
        candidate.observe(snapshot(true, true), none)
        assertTrue(candidate.available)
        candidate.observe(copy(snapshot(), truncated = true), missing)
        assertFalse(candidate.available)
    }

    @Test fun suppliedDecisionsMustExactlyMatchExistingCompleteAfterDecision() {
        rejects(snapshot(), none)
        rejects(snapshot(true, true), missing)
        for (code in NotificationExpansionEvidence.Code.entries.filter { it != none && it != missing }) rejects(snapshot(), code)
        val value = snapshot(true, true)
        val action = value.entries.single { it.node.confirmActionMatches }
        rejects(copy(value, value.entries + action.copy(path = "0/2", depth = 1)), NotificationExpansionEvidence.Code.EXPANSION_ACTION_AMBIGUOUS)
        rejects(copy(value, value.entries.map { if (it == action) it.copy(node = it.node.copy(enabled = false)) else it }),
            NotificationExpansionEvidence.Code.EXPANSION_ACTION_UNAVAILABLE)
    }

    @Test fun observerAndOwnershipGuardsAndReceiptAreNeverManufactured() {
        val candidate = candidate(snapshot())
        for ((own, observer) in listOf(false to false, false to true, true to false))
            assertThrows(IllegalArgumentException::class.java) { candidate.bytes(receipt, 1, own, observer) }
        for (bad in listOf("", "../escape", receipt.uppercase()))
            assertThrows(IllegalArgumentException::class.java) { candidate.bytes(bad, 1, true, true) }
        assertThrows(IllegalArgumentException::class.java) { candidate.bytes(receipt, 0, true, true) }
    }

    @Test fun onlyExactTimeoutWithEveryCleanupAndObservationGuardCanPublish() {
        fun publish(longPress: Boolean = true, reason: String = "EXPANSION_POST_TIMEOUT", cleanup: String = "NONE",
            baseline: Boolean = true, own: Boolean = true, observer: Boolean = true, bytes: Boolean = true) =
            NotificationExpansionTimeoutEvidence.publishable(longPress, reason, cleanup, baseline, own, observer, bytes)
        assertTrue(publish())
        assertFalse(publish(longPress = false)); assertFalse(publish(baseline = false))
        assertFalse(publish(own = false)); assertFalse(publish(observer = false)); assertFalse(publish(bytes = false))
        assertFalse(publish(cleanup = "STRUCTURE_NOTIFICATION_REMOVE_FAILED"))
        for (reason in listOf("NONE", "IDENTITY_ROW_UNRELIABLE", "EXPANSION_ACTION_MISSING", "EXPANSION_OWN_ACTIVITY_LAUNCHED"))
            assertFalse(publish(reason = reason))
    }

    @Test fun invalidScopeIdentityClassPackageAndPathCannotRetainOldSample() {
        for (modal in listOf(true, false)) {
            val value = snapshot(modal = modal)
            val title = value.entries.single { it.node.titleMatches }
            val changes = listOf(title.copy(node = title.node.copy(packageName = "other.app")),
                title.copy(node = title.node.copy(className = "REDACTED")), title.copy(node = title.node.copy(className = "x\".Bad")),
                title.copy(node = title.node.copy(resourceName = "bad\"resource")), title.copy(node = title.node.copy(titleMatches = false)),
                title.copy(path = "0/01", depth = 1), title.copy(path = "0/8/9", depth = 2), title.copy(depth = 99))
            for (change in changes) rejects(copy(value, value.entries.map { if (it == title) change else it }))
            rejects(copy(value, value.entries.reversed()))
            rejects(copy(value, value.entries + title))
            rejects(copy(value, truncated = true))
            rejects(copy(value, phase = NotificationIdentityEvidence.Phase.CURRENT))
            rejects(copy(value, value.entries + title.copy(path = "0/2", depth = 1, node = title.node.copy(titleMatches = false))))
            val name = value.entries.single { it.node.resourceName == "$system:id/app_name_text" }
            rejects(copy(value, value.entries + name.copy(path = "0/2", depth = 1)))
        }
    }

    @Test fun redactedResourceIsAllowedButExtraNodeBudgetAndActionAliasAreNot() {
        val value = snapshot()
        val chrome = value.entries.single { it.node.resourceName == "$system:id/chrome" }
        val redacted = copy(value, value.entries.map { if (it == chrome) it.copy(node = it.node.copy(resourceName = "REDACTED")) else it })
        assertTrue(candidate(redacted).available)
        val tooMany = value.entries + List(33) { i -> chrome.copy(path = "0/${i + 2}", depth = 1) }
        rejects(copy(value, tooMany))
        val confirm = snapshot(true)
        rejects(copy(confirm, confirm.entries.map { if (it.node.confirmActionMatches) it.copy(node = it.node.copy(stopActionMatches = true)) else it }))
    }

    @Test fun noIdIntermediateContainersAndUnsafeResourcesAreExportedOnlyAsRedacted() {
        for (modal in listOf(true, false)) for (bad in listOf(null, "other.app:id/private_value", "bad\"resource", "x".repeat(129))) {
            val root = tree()
            root.children[0] = Tree(node("chrome").copy(resourceName = bad), mutableListOf(Tree(node("decoration"))))
            val captured = if (modal) NotificationIdentityEvidence.captureModalContentPublic(root, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
                { it.children.size }, { it.children }, {}, { it.node })
            else NotificationIdentityEvidence.capture(root, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
                { it.children.size }, { n, i -> n.children[i] }, { it.node })
            assertEquals("REDACTED", captured.entries.single { it.path == "0/0" }.node.resourceName)
            // Also defend the serializer independently of capture's existing sanitization.
            val raw = copy(captured, captured.entries.map { if (it.path == "0/0") it.copy(node = it.node.copy(resourceName = bad)) else it })
            val text = String(bytes(raw))
            assertTrue(text.contains("\"resourceName\":\"REDACTED\""))
            assertTrue(text.contains("\"path\":\"0/0/0\""))
            assertFalse(text.contains("\"resourceName\":\"null\""))
            if (bad != null) assertFalse(text.contains(bad))
        }
    }

    @Test fun exact32NodesAndExistingModalRelativeDepthLimitsArePreserved() {
        val value = snapshot()
        val leaf = value.entries.first().node.copy(resourceName = "$system:id/spacer", clickable = false, longClickable = false)
        val added = List(32 - value.entries.size) { i -> NotificationIdentityEvidence.Entry("0/0/$i", 2, leaf) }
        val exact = copy(value, value.entries.take(2) + added + value.entries.drop(2))
        assertEquals(32, exact.entries.size)
        assertTrue(candidate(exact).available)
        rejects(copy(exact, exact.entries.take(2) + added + NotificationIdentityEvidence.Entry("0/0/${added.size}", 2, leaf) + value.entries.drop(2)))
        for (modal in listOf(true, false)) {
            val root = snapshot(modal = modal)
            val maxDepth = if (modal) 9 else 7
            var path = "0/1/1/2"
            val chain = (3..maxDepth).map { depth ->
                NotificationIdentityEvidence.Entry(path, depth, leaf).also { path += "/0" }
            }
            val boundary = copy(root, root.entries + chain)
            assertTrue(candidate(boundary).available)
            rejects(copy(root, boundary.entries + NotificationIdentityEvidence.Entry(path, maxDepth + 1, leaf)))
        }
    }

    @Test fun timeoutWriterUsesSeparateExclusiveDirectoryAnd32KiBLimit() {
        val base = Files.createTempDirectory("rootpilot-timeout-writer-")
        val data = bytes(snapshot())
        NotificationIdentityEvidenceFile.writeExpansionTimeout(base, receipt, data)
        val target = base.resolve("rootpilot-notification-expansion-timeout").resolve("$receipt.json")
        assertArrayEquals(data, Files.readAllBytes(target))
        assertFalse(Files.exists(base.resolve("rootpilot-notification-identity")))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            NotificationIdentityEvidenceFile.writeExpansionTimeout(base, receipt, data)
        }
        val second = "321b21cd-4137-4e24-a14a-56d6a94b94f1"
        NotificationIdentityEvidenceFile.writeExpansionTimeout(base, second, ByteArray(32_768) { 32 })
        assertThrows(RuntimeException::class.java) {
            NotificationIdentityEvidenceFile.writeExpansionTimeout(base, "adfb8f0f-bf7a-4985-89e8-bc34a5ed02a3", ByteArray(32_769))
        }
    }

    @Test fun timeoutWriterRejectsSymlinkDirectoryAndDestinationWithoutFollowing() {
        val base = Files.createTempDirectory("rootpilot-timeout-link-")
        val other = Files.createTempDirectory("rootpilot-timeout-other-")
        val directory = base.resolve("rootpilot-notification-expansion-timeout")
        Files.createSymbolicLink(directory, other)
        assertThrows(RuntimeException::class.java) { NotificationIdentityEvidenceFile.writeExpansionTimeout(base, receipt, bytes(snapshot())) }
        val plain = Files.createTempDirectory("rootpilot-timeout-plain-")
        val plainDirectory = Files.createDirectory(plain.resolve("rootpilot-notification-expansion-timeout"))
        Files.createSymbolicLink(plainDirectory.resolve("$receipt.json"), other.resolve("missing"))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            NotificationIdentityEvidenceFile.writeExpansionTimeout(plain, receipt, bytes(snapshot()))
        }
        assertFalse(Files.exists(other.resolve("missing")))
    }
}
