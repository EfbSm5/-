package com.example.agent.rootpilot

import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.planning.TodoRepository
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.log.InMemoryAgentLogRepository
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.SavedTodoResult
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RootPilotRunControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun normalCompletionReleasesOwnershipAndClearsSnapshot() = runTest {
        val fixture = fixture()
        fixture.start()
        runCurrent()
        assertEquals(RootPilotStatus.WAITING_CONFIRMATION, fixture.state.status)
        assertTrue(fixture.state.running)
        assertEquals(0, fixture.executions)
        fixture.controller.confirmAction()
        advanceUntilIdle()
        assertEquals(1, fixture.executions)
        assertEquals(RootPilotStatus.COMPLETED, fixture.state.status)
        assertFalse(fixture.controller.busy)
        assertFalse(fixture.state.running)
        assertFalse(fixture.file.exists())
        assertEquals(listOf(1), fixture.host.idleIds)
        fixture.controller.destroy()
        assertEquals(RootPilotStatus.COMPLETED, fixture.state.status)
    }

    @Test fun stopRetainsOwnershipAndSnapshotUntilCleanupCompletes() = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.execute = {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
            }
        }
        fixture.start()
        runCurrent()
        fixture.controller.confirmAction()
        runCurrent()
        val snapshot = fixture.file.readText()
        fixture.controller.stopAgent(1)
        runCurrent()
        assertTrue(entered.isCompleted)
        assertTrue(fixture.controller.busy)
        assertEquals(RootPilotStatus.STOPPING, fixture.state.status)
        assertEquals(snapshot, fixture.file.readText())
        fixture.start(2)
        fixture.controller.testRoot(3)
        fixture.controller.stopAgent(3)
        assertEquals(1, fixture.executions)
        assertEquals(0, fixture.rootChecks)
        assertEquals(1, fixture.cancels)
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(fixture.controller.busy)
        assertFalse(fixture.file.exists())
        assertEquals(RootPilotStatus.STOPPED, fixture.state.status)
        assertEquals(listOf(2), fixture.host.idleIds)
        fixture.controller.destroy()
    }

    @Test fun destructionKeepsGlobalLeaseUntilCleanupAndDoesNotReplayOnRecreation() = runTest {
        val first = fixture()
        val release = CompletableDeferred<Unit>()
        first.execute = {
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }
        first.start()
        runCurrent()
        first.controller.confirmAction()
        runCurrent()
        val snapshot = first.file.readText()
        val hostCalls = first.host.changes
        first.controller.destroy()
        runCurrent()
        val replacement = fixture(first.shared, first.file)
        replacement.controller.restoreInterruptedRun()
        replacement.start()
        runCurrent()
        assertTrue(replacement.controller.busy)
        assertEquals(RootPilotStatus.STOPPING, replacement.state.status)
        assertEquals(0, replacement.captures)
        assertEquals(snapshot, first.file.readText())
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(replacement.controller.busy)
        assertEquals(RootPilotStatus.RECOVERY_REQUIRED, replacement.state.status)
        assertEquals(hostCalls, first.host.changes)
        assertTrue(first.host.idleIds.isEmpty())
        assertEquals(snapshot, first.file.readText())
        replacement.controller.restoreInterruptedRun()
        replacement.start()
        runCurrent()
        assertEquals(0, replacement.captures)
        replacement.controller.discardInterruptedRun(2)
        replacement.start(3)
        runCurrent()
        assertEquals(1, replacement.captures)
        // A duplicate old destroy must never affect the replacement's task.
        first.controller.destroy()
        assertEquals(RootPilotStatus.WAITING_CONFIRMATION, replacement.state.status)
        replacement.controller.stopAgent(3)
        advanceUntilIdle()
        replacement.controller.destroy()
    }

    @Test fun destroyedAwaitingConfirmationRejectsOldApprovalAndPreservesRecovery() = runTest {
        val fixture = fixture()
        fixture.start()
        runCurrent()
        val approval = requireNotNull(fixture.controller.approval)
        fixture.controller.destroy()
        advanceUntilIdle()
        assertFalse(approval.await())
        assertEquals(0, fixture.executions)
        assertNull(fixture.state.pendingAction)
        assertEquals(RootPilotStatus.RECOVERY_REQUIRED, fixture.state.status)
        assertTrue(fixture.file.exists())
        assertFalse(fixture.state.running)
    }

    @Test fun destroyedOneShotEndsStoppedWithoutInventingRecoverableTask() = runTest {
        val fixture = fixture()
        val release = CompletableDeferred<Unit>()
        fixture.check = {
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }
        fixture.controller.testRoot(1)
        runCurrent()
        fixture.controller.destroy()
        runCurrent()
        assertTrue(fixture.controller.busy)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(RootPilotStatus.STOPPED, fixture.state.status)
        assertFalse(fixture.controller.busy)
        assertFalse(fixture.file.exists())
    }

    @Test fun initialSnapshotWriteFailurePreventsCaptureAndModelRequest() = runTest {
        val fixture = fixture()
        fixture.breakStore()
        fixture.start()
        advanceUntilIdle()
        assertEquals(0, fixture.captures)
        assertEquals(0, fixture.modelCalls)
        assertEquals(0, fixture.executions)
        fixture.assertRecoveryFailure()
        fixture.start(2, recovering = true)
        advanceUntilIdle()
        assertEquals(0, fixture.captures)
        fixture.controller.destroy()
    }

    @Test fun snapshotFailureImmediatelyBeforeExecutionPreventsSideEffect() = runTest {
        val fixture = fixture()
        fixture.start()
        runCurrent()
        assertEquals(RootPilotStatus.WAITING_CONFIRMATION, fixture.state.status)
        fixture.breakStore()
        fixture.controller.confirmAction()
        advanceUntilIdle()
        assertEquals(0, fixture.executions)
        assertNull(fixture.controller.approval)
        fixture.assertRecoveryFailure()
        fixture.controller.destroy()
    }

    @Test fun corruptSnapshotIsNotTreatedAsAbsentAndOnlyExplicitDiscardClearsIt() = runTest {
        val fixture = fixture()
        fixture.file.writeText("private fixture invalid snapshot")
        fixture.controller.restoreInterruptedRun()
        fixture.assertRecoveryFailure()
        fixture.controller.stopAgent(1)
        assertTrue(fixture.file.exists())
        fixture.start(2, recovering = true)
        fixture.controller.testRoot(3)
        advanceUntilIdle()
        assertEquals(0, fixture.captures)
        assertEquals(0, fixture.rootChecks)
        fixture.controller.discardInterruptedRun(4)
        assertFalse(fixture.file.exists())
        assertEquals(RootPilotStatus.IDLE, fixture.state.status)
        fixture.start(5)
        runCurrent()
        assertEquals(1, fixture.captures)
        fixture.controller.stopAgent(5)
        advanceUntilIdle()
        fixture.controller.destroy()
    }

    @Test fun clearFailureDoesNotReportSuccessfulDiscard() = runTest {
        val fixture = fixture()
        fixture.breakStore()
        fixture.controller.restoreInterruptedRun()
        fixture.controller.discardInterruptedRun(1)
        fixture.assertRecoveryFailure()
        assertTrue(fixture.file.isDirectory)
        fixture.controller.destroy()
    }

    @Test fun completionClearFailureKeepsRecoveryWarningWithoutRepeatingAction() = runTest {
        val fixture = fixture()
        var reachedWaitingScreen = false
        fixture.host.afterRender = { state ->
            if (state.status == RootPilotStatus.WAITING_SCREEN) {
                reachedWaitingScreen = true
                fixture.breakStore()
            }
        }
        fixture.start()
        runCurrent()
        fixture.controller.confirmAction()
        advanceUntilIdle()
        assertEquals(1, fixture.executions)
        assertTrue(reachedWaitingScreen)
        fixture.assertRecoveryFailure()
        assertTrue(fixture.state.logs.any { it.contains("snapshot_clear_failed") })
        assertFalse(fixture.state.logs.any { it.contains("snapshot_write_failed") })
        fixture.start(2)
        advanceUntilIdle()
        assertEquals(1, fixture.executions)
        fixture.controller.destroy()
    }

    @Test fun snapshotFailureStaysLatchedWhenExecutorCleanupReplacesException() = runTest {
        val fixture = fixture()
        fixture.start()
        runCurrent()
        fixture.breakStore()
        fixture.confirmationCleanup = { throw java.io.IOException("private cleanup detail") }
        fixture.controller.confirmAction()
        advanceUntilIdle()
        assertEquals(0, fixture.executions)
        fixture.assertRecoveryFailure()
        assertTrue(fixture.state.logs.any { it.contains("snapshot_write_failed") })
        assertFalse(fixture.state.logs.any { it.contains("private cleanup detail") })
        fixture.start(2, recovering = true)
        runCurrent()
        assertEquals(0, fixture.executions)
        assertTrue(fixture.file.isDirectory)
        fixture.controller.destroy()
    }

    @Test fun savedTodoReceiptSurvivesCancellationAndLateCompletionCannotOverwriteStop() = runTest {
        val fixture = fixture()
        fixture.response = """{"action":"create_todo","title":"fixture","reason":"test"}"""
        val release = CompletableDeferred<Unit>()
        fixture.saveTodos = { withContext(NonCancellable) { release.await() } }
        fixture.start()
        runCurrent()
        fixture.controller.confirmAction()
        runCurrent()
        fixture.controller.stopAgent(1)
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(SavedTodoResult("fixture", null)), fixture.state.savedTodos)
        assertEquals(RootPilotStatus.STOPPED, fixture.state.status)
        assertFalse(fixture.state.modelReportedResult)
        assertFalse(fixture.file.exists())
        fixture.controller.destroy()
    }

    @Test fun notificationApprovalMustMatchCurrentToken() = runTest {
        val fixture = fixture()
        fixture.start()
        runCurrent()
        fixture.controller.confirmNotificationAction("stale")
        runCurrent()
        assertEquals(0, fixture.executions)
        fixture.controller.confirmNotificationAction(requireNotNull(fixture.controller.approval).token)
        advanceUntilIdle()
        assertEquals(1, fixture.executions)
        fixture.controller.destroy()
    }

    private fun TestScope.fixture(
        shared: RootPilotTaskState = RootPilotTaskState().apply {
            mutableState.value = RootPilotUiState(
                config = RootPilotConfig(task = "fixture", allowScreenUpload = true),
            )
        },
        file: File = temporary.newFolder().resolve("run.json"),
    ) = Fixture(this, shared, file)

    private class Fixture(scope: TestScope, val shared: RootPilotTaskState, val file: File) {
        val state get() = shared.uiState.value
        var captures = 0
        var modelCalls = 0
        var executions = 0
        var rootChecks = 0
        var cancels = 0
        var response = """{"action":"tap","x":500,"y":250,"reason":"test"}"""
        var execute: suspend () -> RootExecutionResult = { RootExecutionResult.Success() }
        var check: suspend () -> RootExecutionResult = { RootExecutionResult.Success() }
        var saveTodos: suspend () -> Unit = {}
        var confirmationCleanup: () -> Unit = {}
        val host = Host()
        private val root = object : RootExecutor {
            override suspend fun checkRoot(): RootExecutionResult { rootChecks++; return check() }
            override suspend fun captureScreen(): RootScreenshotResult = error("No real screenshots")
            override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
                executions++
                return execute()
            }
            override suspend fun executeConfirmed(
                action: ExecutableRootAction, confirm: suspend (String?) -> Boolean,
            ): RootExecutionResult = try {
                if (confirm(null)) execute(action) else RootExecutionResult.Failure("rejected")
            } finally {
                confirmationCleanup()
            }
            override fun cancel() { cancels++ }
        }
        val controller = RootPilotRunController(
            taskState = shared,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)),
            rootExecutor = root,
            loop = AgentLoop(
                screenshotProvider = object : ScreenshotProvider {
                    override suspend fun capture(): ScreenshotCaptureResult {
                        captures++
                        return ScreenshotCaptureResult.Success(ScreenshotFrame(byteArrayOf(1), 100, 200, "fixture"))
                    }
                },
                deepSeekClient = object : DeepSeekClient {
                    override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
                        modelCalls++
                        return DeepSeekActionResult.Success(response)
                    }
                },
                rootExecutor = root,
                todoRepository = object : TodoRepository {
                    override suspend fun addAll(todos: List<CreateTodo>) = saveTodos()
                    override suspend fun list(): List<CreateTodo> = emptyList()
                },
            ),
            runStore = RootPilotRunStore(file),
            logRepository = InMemoryAgentLogRepository(),
            host = host,
        )

        fun start(id: Int = 1, recovering: Boolean = false) {
            controller.commandStarted(id)
            controller.startRun(singleStep = true, startId = id, recovering = recovering)
        }
        fun breakStore() {
            if (file.isFile) check(file.delete())
            check(file.mkdir())
            file.resolve("occupied").writeText("fixture")
        }
        fun assertRecoveryFailure() {
            assertEquals(RootPilotStatus.RECOVERY_REQUIRED, state.status)
            assertFalse(controller.busy)
            assertTrue(shared.recoveryBlocked)
            assertNotNull(state.errorMessage)
            assertFalse(state.errorMessage.orEmpty().contains("private fixture"))
        }
    }

    private class Host : RootPilotRunHost {
        var changes = 0
        val idleIds = mutableListOf<Int>()
        var afterRender: (RootPilotUiState) -> Unit = {}
        override fun stateChanged() { changes++ }
        override suspend fun renderOverlay(state: RootPilotUiState) { afterRender(state) }
        override fun hideOverlay() {}
        override fun idle(startId: Int) { idleIds += startId }
    }
}
