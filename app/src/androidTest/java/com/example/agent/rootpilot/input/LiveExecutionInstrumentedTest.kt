package com.example.agent.rootpilot.input

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.RootPilotApiConfigStore
import com.example.agent.rootpilot.RootPilotRunStore
import com.example.agent.rootpilot.RootPilotService
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.loop.AgentLoopEvent
import com.example.agent.rootpilot.loop.AgentLoopRequest
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.SuRootExecutor
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Live loop/HTTP/IME only. The separate fixture exports its own View, never the display. */
@RunWith(AndroidJUnit4::class)
class LiveExecutionInstrumentedTest {
    @Test fun fixtureChannelRendersOnlyKnownContent() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fixtureChannelAcceptance") == "true")
        val fixture = FixtureClient(InstrumentationRegistry.getInstrumentation().targetContext)
        fixture.verifyIdentity()
        var failure: Throwable? = null
        try {
            fixture.launch()
            val initial = fixture.awaitReady()
            assertTrue("fixture_not_empty", initial.getBoolean("empty"))
            assertFalse(initial.getBoolean("matches"))
            val frame = fixture.capture()
            assertTrue(frame.width > 0 && frame.height > 0)
            var invalidCommandRejected = false
            try { fixture.call("unknown") } catch (_: IllegalStateException) { invalidCommandRejected = true }
            assertTrue("unknown_command_accepted", invalidCommandRejected)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { withContext(NonCancellable) { fixture.call("finish") } }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    @Test fun liveModelTypesFixtureAndObservesCompletion() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFixtureExecution") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = FixtureClient(context)
        fixture.verifyIdentity()
        val ime = AndroidImeEnvironment(context)
        val originalIme = ime.currentId()
        assertTrue("input_method_required", ime.isEnabled(ime.ownId))
        assertNull("resolve_ime_recovery_first", FileImeRestoreStore(context).read())
        assertFalse("resolve_saved_task_first", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
        assertFalse("idle_service_required", RootPilotService.uiState.value.running)
        val saved = try { RootPilotApiConfigStore.create(context).read() } catch (_: Exception) {
            throw AssertionError("saved_config_unavailable")
        }
        assertTrue("saved_config_required", saved != null)
        assertTrue("deepseek_endpoint_required", saved!!.baseUrl.trimEnd('/') == "https://api.deepseek.com")
        var captures = 0
        var typed = 0
        var observedInput = false
        var completed = false
        var failure: Throwable? = null
        fun report(stage: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("executionStage", stage)
            putInt("captures", captures)
            putInt("typedActions", typed)
        })
        val input = AndroidImeEnvironment.createInput(context)
        val actual = SuRootExecutor(typeText = input::type)
        try {
            fixture.launch()
            assertTrue("fixture_not_empty", fixture.awaitReady().getBoolean("empty"))
            val screenshots = object : ScreenshotProvider {
                override suspend fun capture(): ScreenshotCaptureResult {
                    check(++captures <= 4) { "capture_budget_exceeded" }
                    val snapshot = fixture.call("snapshot")
                    if (typed == 1 && snapshot.getBoolean("matches")) observedInput = true
                    return ScreenshotCaptureResult.Success(fixture.frame(snapshot))
                }
            }
            val guarded = object : RootExecutor by actual {
                override suspend fun observeScreen() = actual.observeScreen().also {
                    check(it.foregroundPackage == FIXTURE_PACKAGE &&
                        it.foregroundActivity == FIXTURE_ACTIVITY &&
                        it.focusedPackage == FIXTURE_PACKAGE) { "fixture_window_required" }
                }
                override suspend fun captureScreen() = error("device_capture_forbidden")
                override suspend fun execute(action: ExecutableRootAction): RootExecutionResult = error("unconfirmed_action_forbidden")
                override suspend fun executeConfirmed(action: ExecutableRootAction, confirm: suspend (String?) -> Boolean): RootExecutionResult {
                    // A View-only image has no physical screen coordinates: all positional actions are forbidden.
                    if (action !is ExecutableRootAction.Type || action.text != EXPECTED || typed != 0) {
                        return RootExecutionResult.Failure("action_outside_fixture_scope")
                    }
                    return actual.executeConfirmed(action) { target ->
                        val state = fixture.call("state")
                        val editorMatches = withContext(Dispatchers.Main.immediate) {
                            InputConnectionBridge.editor.value?.info?.let {
                                it.packageName == FIXTURE_PACKAGE && it.fieldId == android.R.id.edit
                            } == true
                        }
                        if (target != FIXTURE_PACKAGE || !editorMatches ||
                            !state.getBoolean("ready") || !state.getBoolean("empty")) false else confirm(target)
                    }.also { if (it is RootExecutionResult.Success) typed++ }
                }
            }
            val config = saved.applyTo(RootPilotConfig(
                task = "当前是专用测试页，第一个输入框已经聚焦。请输入“$EXPECTED”，不要重复输入；看到该文本后报告成功并结束。不点击、不切换应用、不发送、不保存。",
                manualConfirmation = true, allowScreenUpload = true,
            ))
            withTimeout(150_000) {
                AgentLoop(screenshots, HttpDeepSeekClient(requestTimeoutMillis = 60_000), guarded)
                    .run(AgentLoopRequest(config, maxSteps = 4)) { event ->
                        when (event) {
                            is AgentLoopEvent.Capturing -> report("capturing_fixture")
                            is AgentLoopEvent.RequestingModel -> report("requesting_model")
                            is AgentLoopEvent.AwaitingConfirmation -> {
                                if ((event.action as? RootPilotAction.Type)?.text == EXPECTED && typed == 0) {
                                    report("confirming_fixture_input")
                                    event.approval.approve()
                                } else event.approval.reject()
                            }
                            is AgentLoopEvent.Executing -> report("executing_fixture_input")
                            is AgentLoopEvent.Completed -> { completed = true; report("completed") }
                            is AgentLoopEvent.Failed -> report("failed")
                            else -> Unit
                        }
                    }
            }
            assertTrue("model_did_not_complete", completed)
            assertEquals("exactly_one_input_required", 1, typed)
            assertTrue("followup_observation_required", observedInput && captures >= 2)
            assertTrue("fixture_text_mismatch", fixture.call("state").getBoolean("matches"))
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                actual.cancel()
                var cleanupFailure: Throwable? = null
                fun checkCleanup(check: () -> Unit) {
                    try { check() } catch (error: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                    }
                }
                checkCleanup { assertEquals("original_ime_not_restored", originalIme, ime.currentId()) }
                checkCleanup { assertNull("ime_recovery_record_remaining", FileImeRestoreStore(context).read()) }
                checkCleanup { assertFalse("unexpected_task_snapshot", context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists()) }
                try { fixture.call("finish") } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                }
                cleanupFailure?.let { if (failure != null) failure.addSuppressed(it) else throw it }
            }
        }
    }

    internal class FixtureClient(private val context: Context) {
        private val uri = Uri.parse("content://com.example.rootpilot.fixture.state")
        fun verifyIdentity() {
            val provider = context.packageManager.resolveContentProvider(uri.authority!!, 0)
            check(provider?.packageName == FIXTURE_PACKAGE &&
                context.packageManager.checkSignatures(context.packageName, FIXTURE_PACKAGE) == PackageManager.SIGNATURE_MATCH) {
                "signed_fixture_required"
            }
        }
        fun launch() {
            context.startActivity(Intent().setClassName(FIXTURE_PACKAGE, FIXTURE_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        suspend fun call(method: String): Bundle = withContext(Dispatchers.IO) {
            val result = try { context.contentResolver.call(uri, method, null, null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = when (error) {
                    is SecurityException -> "permission_denied"
                    is IllegalArgumentException -> when (error.message) {
                        "fixture_command_not_allowed" -> "invalid_command"
                        "Unknown authority com.example.rootpilot.fixture.state" -> "unknown_provider"
                        else -> "invalid_argument"
                    }
                    is IllegalStateException -> "remote_state"
                    is NullPointerException -> "missing_result"
                    else -> "transport_failure"
                }
                throw IllegalStateException("fixture_call_failed:$method:$reason")
            }
            result ?: throw IllegalStateException("fixture_call_failed:$method:missing_result")
        }
        suspend fun awaitReady(): Bundle {
            repeat(5) { attempt ->
                val state = call("state")
                if (state.getBoolean("ready")) return state
                if (attempt < 4) delay(1_000)
            }
            error("fixture_not_ready")
        }
        suspend fun capture(): ScreenshotFrame = frame(call("snapshot"))
        fun frame(snapshot: Bundle): ScreenshotFrame {
            check(snapshot.getBoolean("ready")) { "fixture_not_ready" }
            val png = requireNotNull(snapshot.getByteArray("png")) { "fixture_image_missing" }
            check(png.isNotEmpty() && png.size <= 512 * 1024) { "fixture_image_limit" }
            val width = snapshot.getInt("width")
            val height = snapshot.getInt("height")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            check(width in 1..1280 && height in 1..1280 && bounds.outWidth == width && bounds.outHeight == height) {
                "fixture_image_dimensions"
            }
            return ScreenshotFrame(png, width, height, "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
        }
    }

    companion object {
        private const val FIXTURE_PACKAGE = "com.example.rootpilot.fixture"
        private const val FIXTURE_ACTIVITY = "$FIXTURE_PACKAGE.ExecutionFixtureActivity"
        private const val EXPECTED = "执行模式验收通过"
    }
}
