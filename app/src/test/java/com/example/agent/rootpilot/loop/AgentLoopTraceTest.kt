package com.example.agent.rootpilot.loop

import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.planning.TodoRepository
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.log.*
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentLoopTraceTest {
    private val secret = "SENTINEL_PRIVATE_PAYLOAD"
    private val finish = """{"action":"finish","success":true,"message":"$secret"}"""
    private val todo = """{"action":"create_todo","title":"$secret","reason":"$secret"}"""
    private val request = AgentLoopRequest(
        RootPilotConfig(apiKey = secret, task = secret, allowScreenUpload = true, manualConfirmation = true),
        maxSteps = 5,
    )

    @Test
    fun successLogsActualPolicyCoordinatesAndNeverLeaksInputs() = runTest {
        val log = Log()
        val root = Root()
        val loop = loop(client(
            """{"action":"tap","x":500,"y":250,"reason":"$secret"}""",
            """{"action":"type","text":"$secret","reason":"$secret"}""",
            finish,
        ), root)
        loop.run(request, log.trace) {
            if (it is AgentLoopEvent.AwaitingConfirmation) {
                it.approval.approve()
                it.approval.approve()
            }
        }
        val tap = log.rows.single { it["stage"] == "execution" && it["event"] == "start" && it["actionType"] == "tap" }
        assertEquals("500", tap["normalizedX"])
        assertEquals("250", tap["normalizedY"])
        val actual = root.actions.first() as ExecutableRootAction.Tap
        assertEquals(actual.x.toString(), tap["physicalX"])
        assertEquals(actual.y.toString(), tap["physicalY"])
        assertEquals(2, log.rows.count { it["event"] == "confirmed" })
        assertEquals(3, log.rows.count { it["stage"] == "model" && it["event"] == "result" })
        assertTrue(log.rows.filter { it["stage"] == "model" || it["stage"] == "screenshot" }
            .all { it["actionType"] == "none" })
        log.assertEnd("success", "none")
        log.assertSafe(secret)
    }

    @Test
    fun todoSavedAndRepeatedApprovalAreLoggedOnlyOnce() = runTest {
        val log = Log()
        var writes = 0
        loop(client(todo, finish), repository = repository { writes++ }).run(request, log.trace) {
            if (it is AgentLoopEvent.AwaitingConfirmation) {
                assertTrue(it.approval.approve(it.approval.token))
                assertFalse(it.approval.approve(it.approval.token))
                it.approval.approve()
            }
        }
        assertEquals(1, writes)
        assertEquals(1, log.rows.count { it["event"] == "todo_saved" })
        assertEquals(1, log.rows.count { it["event"] == "confirmed" })
        log.assertEnd("success", "none")
        log.assertSafe(secret)
    }

    @Test
    fun ordinarySinkFailureDoesNotChangeSuccessfulSave() = runTest {
        var writes = 0
        val events = mutableListOf<AgentLoopEvent>()
        loop(client(todo, finish), repository = repository { writes++ }).run(
            request, RunTrace(sink = { error(secret) }),
        ) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
        }
        assertEquals(1, writes)
        assertTrue(events.last() is AgentLoopEvent.Completed)
    }

    @Test
    fun sinkCancellationDoesNotChangeSuccessfulSaveOrHistory() = runTest {
        var writes = 0
        val lines = mutableListOf<String>()
        val events = mutableListOf<AgentLoopEvent>()
        val requests = mutableListOf<DeepSeekVisionRequest>()
        val client = object : DeepSeekClient {
            override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
                requests += request
                return DeepSeekActionResult.Success(if (requests.size == 1) todo else finish)
            }
        }
        val trace = RunTrace(sink = {
            lines += it
            throw CancellationException(secret)
        })
        loop(client, repository = repository { writes++ }).run(request, trace) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
        }
        assertEquals(1, writes)
        assertTrue(events.last() is AgentLoopEvent.Completed)
        assertTrue(requests.last().history.single().contains("result=success"))
        assertTrue(requests.last().history.single().contains(secret))
        val end = lines.map { Json.parseToJsonElement(it).jsonObject }
            .single { it["event"]?.jsonPrimitive?.content == "run_end" }
        assertEquals("success", end["status"]?.jsonPrimitive?.content)
        assertFalse(lines.joinToString().contains(secret))
    }

    @Test
    fun rejectedApprovalDoesNotExecuteOrSave() = runTest {
        val log = Log()
        loop(client(todo), repository = repository { fail("must not write") }).run(request, log.trace) {
            if (it is AgentLoopEvent.AwaitingConfirmation) {
                it.approval.reject()
                it.approval.approve()
            }
        }
        assertEquals(1, log.rows.count { it["event"] == "rejected" })
        assertFalse(log.rows.any { it["stage"] == "execution" })
        log.assertEnd("cancelled", "user_rejected")
    }

    @Test
    fun parseRetryAndFinalFailureUseOnlyFixedCodes() = runTest {
        val log = Log()
        loop(client(secret, secret)).run(request, log.trace) {}
        assertEquals(1, log.rows.count { it["event"] == "parse_retry" })
        log.assertEnd("failed", "parse_failed")
        assertEquals("parse", log.rows.last()["stage"])
        log.assertSafe(secret)
    }

    @Test
    fun failuresAtEachBoundaryDoNotLeakExternalMessages() = runTest {
        val cases = listOf(
            "screenshot_failed" to loop(client(finish), screen = screenshot { ScreenshotCaptureResult.Failure(secret) }),
            "model_failed" to loop(object : DeepSeekClient {
                override suspend fun requestAction(request: DeepSeekVisionRequest) = DeepSeekActionResult.Failure(secret)
            }),
            "execution_failed" to loop(client("""{"action":"tap","x":1,"y":2,"reason":"$secret"}"""), Root(fail = true)),
            "todo_save_failed" to loop(client(todo), repository = repository { error(secret) }),
            "model_reported_failure" to loop(client("""{"action":"finish","success":false,"message":"$secret"}""")),
        )
        for ((reason, loop) in cases) {
            val log = Log()
            loop.run(request, log.trace) {
                if (it is AgentLoopEvent.AwaitingConfirmation) it.approval.approve()
            }
            log.assertEnd("failed", reason)
            log.assertSafe(secret)
        }
    }

    @Test
    fun thrownErrorIsRethrownAndEndsRunWithoutExceptionText() = runTest {
        val log = Log()
        try {
            loop(object : DeepSeekClient {
                override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult = error(secret)
            }).run(request, log.trace) {}
            fail("expected exception")
        } catch (error: IllegalStateException) {
            assertEquals(secret, error.message)
        }
        log.assertEnd("failed", "unexpected_error")
        assertEquals("model", log.rows.last()["stage"])
        log.assertSafe(secret)
    }

    @Test
    fun cancellationWhileWaitingEndsOnceWithoutConfirmation() = runTest {
        val log = Log()
        val ready = CompletableDeferred<Unit>()
        val job = async {
            loop(client(todo), repository = repository {}).run(request, log.trace) {
                if (it is AgentLoopEvent.AwaitingConfirmation) ready.complete(Unit)
            }
        }
        ready.await()
        job.cancelAndJoin()
        log.assertEnd("cancelled", "cancelled")
        assertEquals("approval", log.rows.last()["stage"])
        assertFalse(log.rows.any { it["event"] == "confirmed" })
    }

    @Test
    fun stopRequestDoesNotClaimBlockedRunEndedAndNewRunHasIndependentIdentity() = runTest {
        val old = Log()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = async {
            loop(object : DeepSeekClient {
                override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return DeepSeekActionResult.Success(finish)
                }
            }).run(request, old.trace) {}
        }
        entered.await()
        old.trace.record(TraceEvent.STOP_REQUESTED, TraceStatus.REQUESTED, stage = TraceStage.SERVICE)
        job.cancel()
        runCurrent()
        assertFalse(old.rows.any { it["event"] == "run_end" })
        val next = Log()
        loop(client(finish)).run(request, next.trace) {}
        assertNotEquals(old.trace.runId, next.trace.runId)
        next.assertEnd("success", "none")
        release.complete(Unit)
        job.join()
        old.assertEnd("cancelled", "cancelled")
        assertTrue(old.rows.all { it["runId"] == old.trace.runId })
        assertTrue(next.rows.all { it["runId"] == next.trace.runId })
    }

    private fun client(vararg actions: String) = object : DeepSeekClient {
        private val queue = ArrayDeque(actions.toList())
        override suspend fun requestAction(request: DeepSeekVisionRequest) = DeepSeekActionResult.Success(queue.removeFirst())
    }

    private fun repository(write: () -> Unit) = object : TodoRepository {
        override suspend fun addAll(todos: List<CreateTodo>) = write()
        override suspend fun list() = emptyList<CreateTodo>()
    }

    private fun loop(
        client: DeepSeekClient,
        root: Root = Root(),
        repository: TodoRepository? = null,
        screen: ScreenshotProvider? = null,
    ): AgentLoop {
        var count = 0
        return AgentLoop(screen ?: screenshot {
            ScreenshotCaptureResult.Success(ScreenshotFrame(
                bytes = "$secret${count++}".toByteArray(), width = 100, height = 100,
                physicalWidth = 1200, physicalHeight = 2600, dataUrl = secret,
            ))
        }, client, root, todoRepository = repository)
    }

    private fun screenshot(capture: suspend () -> ScreenshotCaptureResult) = object : ScreenshotProvider {
        override suspend fun capture() = capture.invoke()
    }

    private class Root(private val fail: Boolean = false) : RootExecutor {
        val actions = mutableListOf<ExecutableRootAction>()
        override suspend fun checkRoot() = RootExecutionResult.Success()
        override suspend fun captureScreen() = RootScreenshotResult.Failure("SENTINEL_PRIVATE_PAYLOAD")
        override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
            actions += action
            return if (fail) RootExecutionResult.Failure("SENTINEL_PRIVATE_PAYLOAD") else RootExecutionResult.Success()
        }
        override fun cancel() = Unit
    }

    private class Log {
        val lines = mutableListOf<String>()
        private var time = 0L
        val trace = RunTrace(TraceClock { time++ }, lines::add)
        val rows: List<Map<String, String>> get() = lines.map { line ->
            Json.parseToJsonElement(line).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }
        fun assertEnd(status: String, reason: String) {
            val end = rows.single { it["event"] == "run_end" }
            assertEquals(status, end["status"])
            assertEquals(reason, end["reasonCode"])
            assertEquals("run_start", rows.first()["event"])
            assertEquals("run_end", rows.last()["event"])
        }
        fun assertSafe(secret: String) {
            assertFalse(lines.joinToString().contains(secret))
            val required = setOf("runId", "step", "event", "status", "stage", "elapsedMs", "actionType", "result", "reasonCode")
            rows.forEach { assertTrue(it.keys.containsAll(required)) }
        }
    }
}
