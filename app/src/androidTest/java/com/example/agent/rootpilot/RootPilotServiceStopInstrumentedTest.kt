package com.example.agent.rootpilot

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.log.InMemoryAgentLogRepository
import com.example.agent.rootpilot.log.RunTrace
import com.example.agent.rootpilot.loop.ActionApproval
import com.example.agent.rootpilot.loop.AgentLoopEvent
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.SavedTodoResult
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.ui.RootPilotOverlay
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises service state transitions without onCreate, a model client, or real root commands. */
@RunWith(AndroidJUnit4::class)
class RootPilotServiceStopInstrumentedTest {
    @Test
    fun stopRetainsJobAndSnapshotUntilNonCancellableCleanupCompletes() = withFixture { fixture ->
        val decision = CompletableDeferred<Boolean>()
        fixture.onMain {
            fixture.set("pendingApproval", ActionApproval(decision))
            fixture.state.value = fixture.state.value.copy(
                status = RootPilotStatus.WAITING_CONFIRMATION,
                pendingAction = RootPilotAction.Tap(500, 250, "test"),
            )
        }
        fixture.startBlockedJob()
        val snapshot = fixture.snapshotFile.readText()

        fixture.stop()
        withTimeout(TIMEOUT_MS) { fixture.cleanupEntered.await() }
        assertFalse(withTimeout(TIMEOUT_MS) { decision.await() })
        fixture.assertStopping(snapshot)
        assertEquals(1, fixture.executor.cancelCount.get())

        val oneShotExecuted = AtomicBoolean(false)
        fixture.onMain {
            fixture.call("startRun", false, 2, false)
            val work: suspend (RunTrace) -> Unit = { oneShotExecuted.set(true) }
            fixture.call("startOneShot", 3, work)
            fixture.call("stopAgent", 4)
        }
        fixture.assertStopping(snapshot)
        assertEquals(1, fixture.executor.cancelCount.get())

        // A result already in flight must not publish a terminal state or clear recovery data.
        listOf(
            AgentLoopEvent.Capturing(99),
            AgentLoopEvent.RequestingModel(99),
            AgentLoopEvent.AwaitingConfirmation(
                99, RootPilotAction.Tap(1, 1, "test"), ActionApproval(CompletableDeferred()),
            ),
            AgentLoopEvent.Executing(99, RootPilotAction.Tap(1, 1, "test")),
            AgentLoopEvent.WaitingScreen(99),
            AgentLoopEvent.Completed("test", modelReported = true),
            AgentLoopEvent.Failed("test", modelReported = true),
            AgentLoopEvent.Stopped,
        ).forEach { event ->
            fixture.event(event)
            fixture.assertStopping(snapshot)
            assertFalse(fixture.state.value.modelReportedResult)
            assertEquals(0, fixture.state.value.step)
        }

        // A completion from a different job cannot release the active run's stop barrier.
        val unrelated = Job().apply { complete() }
        fixture.onMain { fixture.call("finishJob", unrelated) }
        fixture.assertStopping(snapshot)

        fixture.releaseAndFinish()
        fixture.assertStopped()
        assertFalse(oneShotExecuted.get())
    }

    @Test
    fun successfulTodoEventsSurviveCancellationWithoutBecomingModelReportedResults() = withFixture { fixture ->
        val first = SavedTodoResult("test saved before stop", null)
        val late = SavedTodoResult("test saved during cleanup", "2026-09-22T09:00:00+08:00")
        fixture.event(AgentLoopEvent.TodoSaved(first.title, first.dueAt))
        assertEquals(listOf(first), fixture.state.value.savedTodos)
        assertFalse(fixture.state.value.modelReportedResult)

        fixture.startBlockedJob()
        val snapshot = fixture.snapshotFile.readText()
        fixture.stop()
        withTimeout(TIMEOUT_MS) { fixture.cleanupEntered.await() }
        fixture.event(AgentLoopEvent.TodoSaved(late.title, late.dueAt))
        fixture.event(AgentLoopEvent.Completed("model claim", modelReported = true))
        fixture.assertStopping(snapshot)
        assertEquals(listOf(first, late), fixture.state.value.savedTodos)
        assertFalse(fixture.state.value.modelReportedResult)

        fixture.releaseAndFinish()
        fixture.assertStopped()
        assertEquals(listOf(first, late), fixture.state.value.savedTodos)
        assertFalse(fixture.state.value.modelReportedResult)
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val directory = Files.createTempDirectory(context.cacheDir.toPath(), "service-stop-test-").toFile()
        val snapshotFile = directory.resolve("run.json")
        private val store = RootPilotRunStore(snapshotFile)
        private val service = RootPilotService()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val stateLock = requireNotNull(field("stateLock").get(null))
        @Suppress("UNCHECKED_CAST")
        val state = field("_uiState").get(null) as MutableStateFlow<RootPilotUiState>
        private var previousState: RootPilotUiState? = null
        private var overlay: RootPilotOverlay? = null
        val executor = ForbiddenExecutor()
        val cleanupEntered = CompletableDeferred<Unit>()
        private val release = CompletableDeferred<Unit>()
        private val completion = CompletableDeferred<Unit>()
        private lateinit var job: Job

        fun initialize() {
            onMain {
                synchronized(stateLock) {
                    check(state.value.status in setOf(RootPilotStatus.IDLE, RootPilotStatus.STOPPED,
                        RootPilotStatus.COMPLETED, RootPilotStatus.FAILED)) {
                        "Run service stop tests without an active application task"
                    }
                    previousState = state.value
                    state.value = RootPilotUiState(
                        config = RootPilotConfig(task = "fixture", allowScreenUpload = true),
                        status = RootPilotStatus.EXECUTING,
                    )
                }
                val isolatedContext = object : ContextWrapper(context) {
                    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                        PackageManager.PERMISSION_DENIED
                }
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, isolatedContext)
                set("rootExecutor", executor)
                set("runStore", store)
                set("logRepository", InMemoryAgentLogRepository())
                overlay = RootPilotOverlay(isolatedContext, {}, {})
                set("overlay", requireNotNull(overlay))
                store.write(RootPilotRunSnapshot(
                    baseUrl = "https://example.invalid", model = "fixture", task = "fixture",
                    manualConfirmation = true, allowScreenUpload = true,
                    status = RootPilotStatus.EXECUTING.name, step = 0,
                ))
            }
        }

        suspend fun startBlockedJob() {
            val started = CompletableDeferred<Unit>()
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        release.await()
                    }
                }
            }
            onMain { set("activeJob", job) }
            job.invokeOnCompletion {
                try {
                    call("finishJob", job)
                    completion.complete(Unit)
                } catch (failure: Throwable) {
                    completion.completeExceptionally(failure)
                }
            }
            job.start()
            withTimeout(TIMEOUT_MS) { started.await() }
        }

        fun stop() = onMain { call("stopAgent", 1) }

        fun assertStopping(snapshot: String) = onMain {
            assertEquals(RootPilotStatus.STOPPING, state.value.status)
            assertSame(job, field("activeJob").get(service))
            assertTrue(job.isCancelled)
            assertFalse(job.isCompleted)
            assertNull(state.value.pendingAction)
            assertNull(field("pendingApproval").get(service))
            assertEquals(snapshot, snapshotFile.readText())
        }

        suspend fun releaseAndFinish() {
            release.complete(Unit)
            withTimeout(TIMEOUT_MS) {
                job.join()
                completion.await()
            }
        }

        fun assertStopped() = onMain {
            assertTrue(job.isCompleted)
            assertNull(field("activeJob").get(service))
            assertNull(state.value.pendingAction)
            assertEquals(RootPilotStatus.STOPPED, state.value.status)
            assertFalse(snapshotFile.exists())
        }

        suspend fun event(event: AgentLoopEvent) = withContext(Dispatchers.Main.immediate) {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = RootPilotService::class.java.getDeclaredMethod(
                    "handleEvent", AgentLoopEvent::class.java, Continuation::class.java,
                ).apply { isAccessible = true }
                try {
                    method.invoke(service, event, continuation)
                } catch (failure: InvocationTargetException) {
                    throw requireNotNull(failure.cause)
                }
            }
        }

        suspend fun close() = withContext(NonCancellable) {
            try {
                if (::job.isInitialized) {
                    job.cancel()
                    releaseAndFinish()
                }
            } finally {
                scope.cancel()
                (field("serviceScope").get(service) as CoroutineScope).cancel()
                try {
                    onMain {
                        try {
                            overlay?.hide()
                        } finally {
                            synchronized(stateLock) { previousState?.let { state.value = it } }
                        }
                    }
                } finally {
                    check(directory.deleteRecursively()) { "Cannot remove service stop fixture" }
                }
            }
        }

        fun onMain(block: () -> Unit) {
            // Propagate assertions on the test thread so failures still enter fixture cleanup.
            var result: Result<Unit>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            requireNotNull(result).getOrThrow()
        }

        fun set(name: String, value: Any) = field(name).set(service, value)

        fun call(name: String, vararg arguments: Any): Any? {
            val method = RootPilotService::class.java.declaredMethods.single { it.name == name }
                .apply { isAccessible = true }
            return try {
                method.invoke(service, *arguments)
            } catch (failure: InvocationTargetException) {
                throw requireNotNull(failure.cause)
            }
        }

        private fun field(name: String) = RootPilotService::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
    }

    private class ForbiddenExecutor : RootExecutor {
        val cancelCount = AtomicInteger()
        override suspend fun checkRoot(): RootExecutionResult = error("Unexpected root check")
        override suspend fun captureScreen(): RootScreenshotResult = error("Unexpected screenshot")
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult =
            error("Unexpected device action")
        override fun cancel() { cancelCount.incrementAndGet() }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
