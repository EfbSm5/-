package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.apps.AppCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RootExecutorTest {
    @Test
    fun launchUsesValidatedExplicitComponent() {
        val app = RootPilotApp("com.example.notes", "记事本", "com.example.notes.Main\$Alias")
        assertEquals(
            "am start -W --user current -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -n 'com.example.notes/com.example.notes.Main\$Alias'",
            RootCommandBuilder.openApp(app),
        )
        assertThrows(IllegalArgumentException::class.java) { app.copy(activityName = "Bad';id") }
        assertThrows(IllegalArgumentException::class.java) { app.copy(packageName = "x.y;id") }
        assertThrows(IllegalArgumentException::class.java) { RootCommandBuilder.selectInputMethod("bad/id;echo") }
        assertEquals("ime set --user current 'com.example.ime/.Service'", RootCommandBuilder.selectInputMethod("com.example.ime/.Service"))
    }

    @Test fun launchRequiresSystemSuccessNotJustExitCode() {
        assertTrue(RootCommandBuilder.launchReportedSuccess("Starting\nStatus: ok\nComplete"))
        assertFalse(RootCommandBuilder.launchReportedSuccess("Error: Activity not found"))
        assertFalse(RootCommandBuilder.launchReportedSuccess("Status: ok\nError: Permission denied"))
        assertFalse(RootCommandBuilder.launchReportedSuccess("Starting activity"))
    }

    @Test fun staleAppIsRejectedBeforeRootCommand() = runTest {
        val app = RootPilotApp("com.example.notes", "记事本", "com.example.notes.Main")
        val executor = SuRootExecutor(appCatalog = AppCatalog { emptyList() })
        assertTrue(executor.execute(ExecutableRootAction.OpenApp(app)) is RootExecutionResult.Failure)
        val changedEntry = SuRootExecutor(appCatalog = AppCatalog { listOf(app.copy(activityName = "NewEntry")) })
        assertTrue(changedEntry.execute(ExecutableRootAction.OpenApp(app)) is RootExecutionResult.Failure)
    }

    @Test fun unicodeNeverTravelsThroughShell() = runTest {
        val received = mutableListOf<String>()
        val executor = SuRootExecutor(typeText = { text, _ -> received += text; RootExecutionResult.Success() })
        val text = "中文 ; $(id) ' \n😀"
        assertTrue(executor.execute(ExecutableRootAction.Type(text)) is RootExecutionResult.Success)
        assertEquals(listOf(text), received)
        assertTrue(executor.execute(ExecutableRootAction.Type("\u0000")) is RootExecutionResult.Failure)
        assertEquals(1, received.size)
    }

    @Test fun confirmedTypePreparesBeforeApprovalAndNeverCommitsOnRejection() = runTest {
        val events = mutableListOf<String>()
        val executor = SuRootExecutor(typeText = { _, confirm ->
            events += "prepare"
            if (confirm("target.app")) {
                events += "commit"
                RootExecutionResult.Success()
            } else RootExecutionResult.Failure("rejected")
        })
        assertTrue(executor.executeConfirmed(ExecutableRootAction.Type("中文")) {
            assertEquals("target.app", it)
            events += "approve"
            true
        } is RootExecutionResult.Success)
        assertEquals(listOf("prepare", "approve", "commit"), events)
        events.clear()
        assertTrue(executor.executeConfirmed(ExecutableRootAction.Type("中文")) {
            events += "reject"
            false
        } is RootExecutionResult.Failure)
        assertEquals(listOf("prepare", "reject"), events)
    }
}
