package com.example.agent.rootpilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.log.InMemoryAgentLogRepository
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
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tests production controller coroutines on Android, not the Service lifecycle end to end. */
@RunWith(AndroidJUnit4::class)
class RootPilotServiceStopInstrumentedTest {
    @Test
    fun stopRetainsJobAndSnapshotUntilNonCancellableCleanupCompletes() = withFixture { fixture ->
        val session = fixture.newSession()
        fixture.start(session, 1)
        val snapshot = fixture.snapshotFile.readText()

        fixture.stop(session, 2)
        withTimeout(TIMEOUT_MS) { session.executor.cleanupEntered.await() }
        fixture.assertStopping(session, snapshot)
        assertEquals(1, session.executor.cancelCount)
        assertTrue(session.host.idleStartIds.isEmpty())

        session.controller.commandStarted(3)
        session.controller.startRun(singleStep = true, startId = 3)
        fixture.stop(session, 4)
        fixture.assertStopping(session, snapshot)
        assertEquals(1, session.executor.executeCount)
        assertEquals(1, session.executor.cancelCount)
        assertTrue(session.host.idleStartIds.isEmpty())

        fixture.releaseAndFinish(session)
        assertEquals(RootPilotStatus.STOPPED, fixture.state.uiState.value.status)
        assertFalse(session.controller.busy)
        assertFalse(fixture.state.uiState.value.running)
        assertNull(fixture.state.owner)
        assertNull(fixture.store.read())
        assertFalse(fixture.snapshotFile.exists())
        assertEquals(listOf(4), session.host.idleStartIds)
    }

    @Test
    fun destructionDuringCleanupRetainsOwnerUntilRecoveryIsRequired() = withFixture { fixture ->
        val original = fixture.newSession()
        fixture.start(original, 1)
        val snapshot = fixture.snapshotFile.readText()
        fixture.stop(original, 2)
        withTimeout(TIMEOUT_MS) { original.executor.cleanupEntered.await() }

        original.controller.destroy()
        val replacement = fixture.newSession()
        fixture.assertStopping(original, snapshot)
        assertTrue(replacement.controller.busy)

        replacement.controller.commandStarted(3)
        replacement.controller.startRun(singleStep = true, startId = 3)
        fixture.assertStopping(original, snapshot)
        assertTrue(replacement.controller.busy)
        assertEquals(0, replacement.executor.executeCount)
        assertEquals(listOf(3), replacement.host.idleStartIds)

        fixture.releaseAndFinish(original)
        assertEquals(RootPilotStatus.RECOVERY_REQUIRED, fixture.state.uiState.value.status)
        assertFalse(original.controller.busy)
        assertFalse(replacement.controller.busy)
        assertFalse(fixture.state.uiState.value.running)
        assertNull(fixture.state.owner)
        assertNotNull(fixture.store.read())
        assertEquals(snapshot, fixture.snapshotFile.readText())
        assertTrue(original.host.idleStartIds.isEmpty())

        // Releasing ownership must not automatically replay the interrupted action.
        replacement.controller.commandStarted(4)
        replacement.controller.startRun(singleStep = true, startId = 4)
        assertEquals(RootPilotStatus.RECOVERY_REQUIRED, fixture.state.uiState.value.status)
        assertFalse(replacement.controller.busy)
        assertEquals(0, replacement.executor.executeCount)
        assertEquals(snapshot, fixture.snapshotFile.readText())
        assertEquals(listOf(3, 4), replacement.host.idleStartIds)
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val fixture = Fixture()
            try {
                block(fixture)
            } finally {
                fixture.close()
            }
        }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = Files.createTempDirectory(context.cacheDir.toPath(), "controller-stop-test-").toFile()
        val snapshotFile = directory.resolve("run.json")
        val store = RootPilotRunStore(snapshotFile)
        val state = RootPilotTaskState().apply {
            mutableState.value = RootPilotUiState(
                config = RootPilotConfig(
                    baseUrl = "https://example.invalid",
                    model = "fixture",
                    task = "fixture",
                    manualConfirmation = false,
                    allowScreenUpload = true,
                ),
            )
        }
        private val sessions = mutableListOf<Session>()

        fun newSession(): Session {
            val job = SupervisorJob()
            val executor = CleanupExecutor()
            val host = FakeHost()
            val controller = RootPilotRunController(
                taskState = state,
                scope = CoroutineScope(job + Dispatchers.Main.immediate),
                rootExecutor = executor,
                loop = AgentLoop(FakeScreenshotProvider(), FakeModelClient(), executor),
                runStore = store,
                logRepository = InMemoryAgentLogRepository(),
                host = host,
            )
            return Session(controller, executor, host, job).also(sessions::add)
        }

        suspend fun start(session: Session, startId: Int) {
            session.controller.commandStarted(startId)
            session.controller.startRun(singleStep = true, startId = startId)
            withTimeout(TIMEOUT_MS) { session.executor.entered.await() }
            assertEquals(RootPilotStatus.EXECUTING, state.uiState.value.status)
            assertEquals(RootPilotStatus.EXECUTING.name, store.read()?.status)
            assertSame(session.controller, state.owner)
        }

        fun stop(session: Session, startId: Int) {
            session.controller.commandStarted(startId)
            session.controller.stopAgent(startId)
        }

        fun assertStopping(session: Session, snapshot: String) {
            assertEquals(RootPilotStatus.STOPPING, state.uiState.value.status)
            assertTrue(session.controller.busy)
            assertTrue(state.uiState.value.running)
            assertSame(session.controller, state.owner)
            assertNull(state.uiState.value.pendingAction)
            assertEquals(snapshot, snapshotFile.readText())
        }

        suspend fun releaseAndFinish(session: Session) {
            val runs = session.job.children.toList()
            session.executor.release.complete(Unit)
            withTimeout(TIMEOUT_MS) { runs.forEach { it.join() } }
        }

        suspend fun close() = withContext(NonCancellable) {
            try {
                sessions.forEach { it.executor.release.complete(Unit) }
                sessions.forEach { it.controller.destroy() }
                withTimeout(TIMEOUT_MS) { sessions.forEach { it.job.join() } }
            } finally {
                check(directory.deleteRecursively()) { "Cannot remove controller stop fixture" }
            }
        }
    }

    private data class Session(
        val controller: RootPilotRunController,
        val executor: CleanupExecutor,
        val host: FakeHost,
        val job: Job,
    )

    private class FakeHost : RootPilotRunHost {
        val idleStartIds = mutableListOf<Int>()
        override fun stateChanged() = Unit
        override suspend fun renderOverlay(state: RootPilotUiState) = Unit
        override fun hideOverlay() = Unit
        override fun idle(startId: Int) { idleStartIds += startId }
    }

    private class FakeScreenshotProvider : ScreenshotProvider {
        override suspend fun capture() = ScreenshotCaptureResult.Success(
            ScreenshotFrame(byteArrayOf(1), 100, 100, "data:image/jpeg;base64,AQ=="),
        )
    }

    private class FakeModelClient : DeepSeekClient {
        override suspend fun requestAction(request: DeepSeekVisionRequest) = DeepSeekActionResult.Success(
            """{"action":"tap","x":500,"y":500,"reason":"fixture"}""",
        )
    }

    private class CleanupExecutor : RootExecutor {
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executeCount = 0
        var cancelCount = 0

        override suspend fun checkRoot(): RootExecutionResult = error("Unexpected root check")
        override suspend fun captureScreen(): RootScreenshotResult = error("Unexpected root screenshot")
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
            executeCount++
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupEntered.complete(Unit)
                    release.await()
                }
            }
        }

        override fun cancel() { cancelCount++ }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
