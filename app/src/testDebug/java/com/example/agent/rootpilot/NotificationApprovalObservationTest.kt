package com.example.agent.rootpilot

import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.planning.TodoRepository
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.log.InMemoryAgentLogRepository
import com.example.agent.rootpilot.history.RunHistoryRecord
import com.example.agent.rootpilot.history.RunHistoryRepository
import com.example.agent.rootpilot.history.RunHistoryStorage
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationApprovalObservationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun notificationWinnerRecordsExactRunAndTokenBeforeLoopResumes() = runTest {
        val fixture = fixture()
        fixture.start()
        runCurrent()
        val token = requireNotNull(fixture.controller.approval).token
        val runId = fixture.runId()
        fixture.controller.confirmNotificationAction(token)
        val success = requireNotNull(NotificationApprovalObservation.lastSuccess)
        assertEquals(runId, success.runId)
        assertEquals(digest(token), success.approvalTokenDigest)
        assertEquals(0, fixture.executions)
        // A competing overlay approval and notification replay cannot change the winner.
        fixture.controller.confirmAction()
        fixture.controller.confirmNotificationAction(token)
        assertSame(success, NotificationApprovalObservation.lastSuccess)
        advanceUntilIdle()
        assertEquals(1, fixture.executions)
        assertEquals(RootPilotStatus.COMPLETED, fixture.shared.uiState.value.status)
        fixture.controller.destroy()
    }

    @Test fun overlayWinnerCannotBorrowValidNotificationLifecycleOrStaleRunSuccess() = runTest {
        val prior = fixture()
        prior.start()
        runCurrent()
        prior.controller.confirmNotificationAction(requireNotNull(prior.controller.approval).token)
        advanceUntilIdle()
        val priorSuccess = requireNotNull(NotificationApprovalObservation.lastSuccess)
        prior.controller.destroy()

        val fixture = fixture()
        fixture.start()
        runCurrent()
        val token = requireNotNull(fixture.controller.approval).token
        val runId = fixture.runId()
        fixture.controller.confirmAction()
        fixture.controller.confirmNotificationAction(token)
        advanceUntilIdle()
        val lifecycle = NotificationManualConfirmationEvidence()
        val activity = Any()
        lifecycle.arm(0)
        lifecycle.beginWait(1)
        lifecycle.onCreated(activity, true, 2)
        lifecycle.onResumed(activity, true, 3)
        lifecycle.onDestroyed(activity, true, 4)
        assertTrue(lifecycle.snapshot(4).complete)
        assertEquals(RootPilotStatus.COMPLETED, fixture.shared.uiState.value.status)
        assertEquals(1, fixture.executions)
        assertSame(priorSuccess, NotificationApprovalObservation.lastSuccess)
        assertNotEquals(runId, NotificationApprovalObservation.lastSuccess?.runId)
        fixture.controller.destroy()
    }

    @Test fun wrongTokenAndForeignControllerProduceNoSuccess() = runTest {
        val fixture = fixture()
        fixture.start()
        runCurrent()
        val token = requireNotNull(fixture.controller.approval).token
        val before = NotificationApprovalObservation.lastSuccess
        fixture.controller.confirmNotificationAction("wrong")
        fixture.controller.confirmNotificationAction(null)
        val other = fixture(shared = fixture.shared)
        other.controller.confirmNotificationAction(token)
        runCurrent()
        assertEquals(0, fixture.executions)
        assertSame(before, NotificationApprovalObservation.lastSuccess)
        assertNotEquals(fixture.runId(), NotificationApprovalObservation.lastSuccess?.runId)
        other.controller.destroy()
        fixture.controller.destroy()
        advanceUntilIdle()
    }

    private fun digest(token: String) = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun TestScope.fixture(shared: RootPilotTaskState = RootPilotTaskState().apply {
        mutableState.value = RootPilotUiState(config = RootPilotConfig(task = "fixture", allowScreenUpload = true))
    }) = Fixture(this, shared, temporary.newFolder().resolve("run.json"))

    private class Fixture(scope: TestScope, val shared: RootPilotTaskState, file: File) {
        var executions = 0
        private val logs = InMemoryAgentLogRepository()
        private val history = RunHistoryRepository(object : RunHistoryStorage {
            override fun read() = emptyList<RunHistoryRecord>()
            override fun write(records: List<RunHistoryRecord>) = Unit
            override fun clear() = Unit
        }, scope.backgroundScope)
        private val root = object : RootExecutor {
            override suspend fun checkRoot() = RootExecutionResult.Success()
            override suspend fun observeScreen() = com.example.agent.rootpilot.screen.ScreenObservation(
                "com.example.fixture", "com.example.fixture.Main", "com.example.fixture", "abc", false, 1L,
            )
            override suspend fun captureScreen(): RootScreenshotResult = error("No real screenshots")
            override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
                executions++
                return RootExecutionResult.Success()
            }
            override fun cancel() = Unit
        }
        val controller = RootPilotRunController(
            taskState = shared,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler)),
            rootExecutor = root,
            loop = AgentLoop(
                screenshotProvider = object : ScreenshotProvider {
                    override suspend fun capture() = ScreenshotCaptureResult.Success(
                        ScreenshotFrame(byteArrayOf(1), 100, 200, "fixture"))
                },
                deepSeekClient = object : DeepSeekClient {
                    override suspend fun requestAction(request: DeepSeekVisionRequest) =
                        DeepSeekActionResult.Success("""{"action":"tap","x":500,"y":250,"reason":"test"}""")
                },
                rootExecutor = root,
                appCatalog = AppCatalog { emptyList() },
                todoRepository = object : TodoRepository {
                    override suspend fun addAll(todos: List<CreateTodo>) = Unit
                    override suspend fun list() = emptyList<CreateTodo>()
                },
            ),
            runStore = RootPilotRunStore(file),
            logRepository = logs,
            history = history,
            host = object : RootPilotRunHost {
                override fun stateChanged() = Unit
                override suspend fun renderOverlay(state: RootPilotUiState) = Unit
                override fun hideOverlay() = Unit
                override fun idle(startId: Int) = Unit
            },
        )
        fun start() {
            controller.commandStarted(1)
            controller.startRun(singleStep = true, startId = 1)
        }
        fun runId(): String = history.state.value.records.single().id
    }
}
