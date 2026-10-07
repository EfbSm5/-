package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayTextInputPolicyTest {
    private class Access : VirtualTextInputAccess {
        val connection = Any()
        var connected = true
        var current = true
        var valid = true
        var screen = SCREEN
        var editor = EDITOR
        var querying = false
        var queries = 0
        var targetReads = 0
        var submissions = 0
        var accepted = true
        var failRestoreAt = 0
        var targetDelay = 0L
        var afterTarget: () -> Unit = {}
        var afterSubmit: () -> Unit = {}
        override fun connection(): Any? = connection.takeIf { connected }
        override fun connected(connection: Any) = connected && connection === this.connection
        override suspend fun <T> query(connection: Any, collect: suspend () -> T): T {
            check(!querying)
            val index = ++queries
            querying = true
            try { return collect() }
            finally {
                querying = false
                if (index == failRestoreAt) error("fixed_restore_failure")
            }
        }
        override suspend fun target(connection: Any, session: DisplaySession, packageName: String): VirtualTextTarget {
            assertTrue(querying)
            assertEquals(SESSION, session)
            assertEquals(PACKAGE, packageName)
            targetReads++
            if (targetDelay > 0) delay(targetDelay)
            val result = editor
            afterTarget()
            return result
        }
        override suspend fun setText(target: VirtualTextTarget, text: String, stillBound: () -> Boolean): Boolean {
            assertTrue(querying)
            assertEquals(SAMPLE, text)
            if (!stillBound()) return false
            submissions++
            afterSubmit()
            return accepted
        }
    }

    private suspend fun execute(access: Access, confirm: suspend (String) -> Boolean = { true },
        text: String = SAMPLE, timeout: Long = 3_000): RootExecutionResult =
        VirtualDisplayTextInputPolicy(OWN, access, timeout).type(text, SESSION,
            { access.current }, { access.valid }, { access.screen }, confirm)

    @Test fun submitsOnceAfterUnlockedConfirmationWithTargetPackage() = runBlocking {
        val access = Access()
        var approvals = 0
        val result = execute(access, confirm = {
            assertFalse(access.querying)
            assertEquals(PACKAGE, it)
            approvals++
            true
        })
        assertTrue(result is RootExecutionResult.Success)
        assertEquals(1, approvals)
        assertEquals(1, access.submissions)
        assertEquals(2, access.queries)
    }

    @Test fun rejectingApprovalDoesNotSubmit() = runBlocking {
        val access = Access()
        assertTrue(execute(access, confirm = { false }) is RootExecutionResult.Failure)
        assertEquals(0, access.submissions)
        assertEquals(1, access.queries)
    }

    @Test fun invalidTextNeverReadsOrConfirms() = runBlocking {
        for (text in listOf("", "a".repeat(129), "\u0000", "\uD83D")) {
            val access = Access()
            assertTrue(execute(access, confirm = { fail("unexpected_approval"); true }, text = text) is RootExecutionResult.Failure)
            assertEquals(0, access.queries)
            assertEquals(0, access.submissions)
        }
    }

    @Test fun missingServiceRejectsWithoutQueries() = runBlocking {
        val access = Access().apply { connected = false }
        assertTrue(execute(access) is RootExecutionResult.Failure)
        assertEquals(0, access.queries)
    }

    @Test fun foreignUnknownAndProtectedScreenRejectBeforeTreeRead() = runBlocking {
        for (screen in listOf(SCREEN.copy(displayId = 0), SCREEN.copy(sessionId = "foreign"),
            SCREEN.copy(foregroundActivity = null), SCREEN.copy(focusedWindowId = null),
            SCREEN.copy(keyboardVisible = null), SCREEN.copy(focusedPackage = "other"),
            SCREEN.copy(foregroundPackage = OWN, focusedPackage = OWN))) {
            val access = Access().apply { this.screen = screen }
            assertTrue(execute(access) is RootExecutionResult.Failure)
            assertEquals(0, access.queries)
        }
    }

    @Test fun ineligibleEditorsAndUnknownEmptyStatusRejectWithoutApproval() = runBlocking {
        for (editor in listOf(EDITOR.copy(empty = null), EDITOR.copy(empty = false),
            EDITOR.copy(visible = false), EDITOR.copy(enabled = false), EDITOR.copy(editable = false),
            EDITOR.copy(focused = false), EDITOR.copy(ordinaryText = false), EDITOR.copy(setTextSupported = false),
            EDITOR.copy(packageName = "other"), EDITOR.copy(windowId = -1), EDITOR.copy(rootReached = false),
            EDITOR.copy(ancestors = emptyList()), EDITOR.copy(ancestors = List(34) { ANCESTOR }),
            EDITOR.copy(ancestors = listOf(ANCESTOR.copy(password = true))),
            EDITOR.copy(ancestors = listOf(ANCESTOR.copy(sensitive = true))),
            EDITOR.copy(ancestors = listOf(ANCESTOR.copy(windowId = 10))),
            EDITOR.copy(ancestors = listOf(ANCESTOR.copy(packageName = null))))) {
            val access = Access().apply { this.editor = editor }
            assertTrue(execute(access, confirm = { fail("unexpected_approval"); true }) is RootExecutionResult.Failure)
            assertEquals(0, access.submissions)
        }
    }

    @Test fun changedEditorRootOrWindowAfterApprovalRejects() = runBlocking {
        for (fresh in listOf(EDITOR.copy(nodeIdentity = Any()), EDITOR.copy(rootIdentity = Any()),
            EDITOR.copy(windowId = 10, ancestors = listOf(ANCESTOR.copy(windowId = 10))))) {
            val access = Access()
            assertTrue(execute(access, confirm = { access.editor = fresh; true }) is RootExecutionResult.Failure)
            assertEquals(0, access.submissions)
        }
    }

    @Test fun nonemptyEditorAfterApprovalIsNotOverwritten() = runBlocking {
        val access = Access()
        assertTrue(execute(access, confirm = { access.editor = EDITOR.copy(empty = false); true }) is RootExecutionResult.Failure)
        assertEquals(0, access.submissions)
    }

    @Test fun sessionReplacementAfterApprovalRejects() = runBlocking {
        val access = Access()
        assertTrue(execute(access, confirm = { access.current = false; true }) is RootExecutionResult.Failure)
        assertEquals(0, access.submissions)
    }

    @Test fun invalidSessionAfterApprovalRejects() = runBlocking {
        val access = Access()
        assertTrue(execute(access, confirm = { access.valid = false; true }) is RootExecutionResult.Failure)
        assertEquals(0, access.submissions)
    }

    @Test fun serviceReconnectAfterApprovalRejects() = runBlocking {
        val access = Access()
        assertTrue(execute(access, confirm = { access.connected = false; true }) is RootExecutionResult.Failure)
        assertEquals(0, access.submissions)
    }

    @Test fun changedScreenOrKeyboardAfterApprovalRejects() = runBlocking {
        for (screen in listOf(SCREEN.copy(focusedWindowId = "changed"), SCREEN.copy(keyboardVisible = true))) {
            val access = Access()
            assertTrue(execute(access, confirm = { access.screen = screen; true }) is RootExecutionResult.Failure)
            assertEquals(0, access.submissions)
        }
    }

    @Test fun changeDuringFreshTargetScanRejectsBeforeAction() = runBlocking {
        val access = Access()
        access.afterTarget = { if (access.targetReads == 2) access.screen = SCREEN.copy(focusedWindowId = "changed") }
        assertTrue(execute(access) is RootExecutionResult.Failure)
        assertEquals(0, access.submissions)
    }

    @Test fun failedPreparationFlagRestoreNeverConfirms() = runBlocking {
        val access = Access().apply { failRestoreAt = 1 }
        assertTrue(execute(access, confirm = { fail("unexpected_approval"); true }) is RootExecutionResult.Failure)
        assertEquals(0, access.submissions)
        assertFalse(access.querying)
    }

    @Test fun failedPostSubmitRestoreReportsPossibleEffectWithoutReplay() = runBlocking {
        val access = Access().apply { failRestoreAt = 2 }
        val result = execute(access) as RootExecutionResult.Failure
        assertTrue(result.message.contains("可能已生效"))
        assertEquals(1, access.submissions)
    }

    @Test fun rejectedActionReportsPossibleEffectWithoutReplay() = runBlocking {
        val access = Access().apply { accepted = false }
        assertTrue(execute(access) is RootExecutionResult.Failure)
        assertEquals(1, access.submissions)
    }

    @Test fun postSubmitWindowChangeIsFailureNotReplay() = runBlocking {
        val access = Access()
        access.afterSubmit = { access.screen = SCREEN.copy(focusedWindowId = "changed") }
        assertTrue(execute(access) is RootExecutionResult.Failure)
        assertEquals(1, access.submissions)
    }

    @Test fun cancellationWhileConfirmingDoesNotSubmit() = runBlocking {
        val access = Access()
        val policy = VirtualDisplayTextInputPolicy(OWN, access)
        var cancelled = false
        try {
            policy.type(SAMPLE, SESSION, { true }, { true }, { SCREEN }) { policy.cancel(); true }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, access.submissions)
        assertFalse(access.querying)
    }

    @Test fun cancellationAfterSubmitDoesNotReplay() = runBlocking {
        val access = Access()
        val policy = VirtualDisplayTextInputPolicy(OWN, access)
        access.afterSubmit = { policy.cancel() }
        var cancelled = false
        try { policy.type(SAMPLE, SESSION, { true }, { true }, { SCREEN }) { true } }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, access.submissions)
        assertFalse(access.querying)
    }

    @Test fun preparationTimeoutDoesNotConfirmOrSubmit() = runBlocking {
        val access = Access().apply { targetDelay = 100 }
        assertTrue(execute(access, confirm = { fail("unexpected_approval"); true }, timeout = 20) is RootExecutionResult.Failure)
        assertEquals(0, access.submissions)
        assertFalse(access.querying)
    }

    private companion object {
        const val OWN = "com.example.agent"
        const val PACKAGE = "com.example.fixture"
        const val SAMPLE = "中文🙂\n第二行"
        val SESSION = DisplaySession(7, "session")
        val SCREEN = ScreenObservation(PACKAGE, "$PACKAGE.Editor", PACKAGE, "window", false, 1, 7, "session")
        val ANCESTOR = VirtualTextAncestor(PACKAGE, 9, false, false)
        val EDITOR = VirtualTextTarget(Any(), Any(), PACKAGE, 9, true, true, true, true, true, true,
            true, listOf(ANCESTOR), true)
    }
}
