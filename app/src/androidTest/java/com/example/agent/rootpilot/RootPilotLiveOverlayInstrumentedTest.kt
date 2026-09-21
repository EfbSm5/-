package com.example.agent.rootpilot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.loop.AgentLoopEvent
import com.example.agent.rootpilot.loop.AgentLoopRequest
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
import com.example.agent.rootpilot.ui.RootPilotOverlay
import com.example.agent.rootpilot.ui.createMarkdownRenderer
import com.example.agent.rootpilot.ui.modelPreviewText
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Loop/HTTP/Overlay integration; excludes Service lifecycle and full-screen/root capture. */
@RunWith(AndroidJUnit4::class)
class RootPilotLiveOverlayInstrumentedTest {
    @Test
    fun singleFixtureUploadStreamsIntoSecureNonExecutableOverlay() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootpilotLiveOverlay") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("overlay_permission_required", Settings.canDrawOverlays(context))
        assertTrue("resolve_saved_task_first", !context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists())
        assertTrue("idle_service_required", RootPilotService.uiState.value.status in setOf(
            RootPilotStatus.IDLE, RootPilotStatus.STOPPED, RootPilotStatus.COMPLETED, RootPilotStatus.FAILED,
        ))
        // onResume may recover an interrupted IME session; this probe must not trigger that work.
        assertTrue("resolve_ime_recovery_first",
            com.example.agent.rootpilot.input.FileImeRestoreStore(context).read() == null)
        val saved = try {
            RootPilotApiConfigStore.create(context).read()
        } catch (_: Exception) {
            throw AssertionError("saved_config_unavailable")
        }
        assertTrue("saved_config_required", saved != null)
        val config = requireNotNull(saved).applyTo(RootPilotConfig(
            task = "只分析这张 RootPilot 静态测试页截图，简短说明看到的测试文字，然后直接返回 finish 成功。" +
                "不要点击、滑动、输入、打开应用、等待或创建待办；不要请求任何手机动作。",
            manualConfirmation = true,
            allowScreenUpload = true,
        ))
        assertTrue("official_https_endpoint_required", config.baseUrl == "https://api.deepseek.com")
        assertTrue("flash_model_required", config.model == "deepseek-flash")
        assertTrue("saved_token_required", config.apiKey.isNotBlank())

        val captures = AtomicInteger()
        val requests = AtomicInteger()
        val blockedRequests = AtomicInteger()
        val executeAttempts = AtomicInteger()
        val updates = AtomicInteger()
        var activity: RootPilotActivity? = null
        var overlay: RootPilotOverlay? = null
        var running: Deferred<Unit>? = null
        var completed = false
        var expanded = false
        var previousSnapshot = ModelStreamSnapshot()
        var state = RootPilotUiState()
        fun report(stage: String) = instrumentation.sendStatus(2, Bundle().apply {
            putString("stage", stage)
            putInt("captures", captures.get())
            putInt("requests", requests.get())
            putInt("blocked_requests", blockedRequests.get())
            putInt("stream_updates", updates.get())
            putInt("execute_attempts", executeAttempts.get())
        })

        try {
            withContext(Dispatchers.Main.immediate) {
                assertTrue("existing_overlay_must_be_closed", headers().isEmpty())
            }
            val launched = instrumentation.startActivitySync(
                Intent(context, RootPilotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as RootPilotActivity
            activity = launched
            val laidOut = CompletableDeferred<Unit>()
            lateinit var fixture: TextView
            withContext(Dispatchers.Main.immediate) {
                fixture = TextView(launched).apply {
                    text = "RootPilot 流式预览测试页\n\n这是静态、非敏感的测试内容。\n蓝色方块，数字 123。\n仅分析，不执行手机动作。"
                    textSize = 24f
                    setTextColor(Color.BLUE)
                    setBackgroundColor(Color.WHITE)
                    setPadding(32, 80, 32, 32)
                    addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                            oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                            if (v.width > 0 && v.height > 0) {
                                v.removeOnLayoutChangeListener(this)
                                laidOut.complete(Unit)
                            }
                        }
                    })
                }
                launched.setContentView(fixture)
                overlay = RootPilotOverlay(launched,
                    onConfirm = { error("probe_never_approves_actions") },
                    onStop = { running?.cancel() },
                )
            }
            withTimeout(10_000) { laidOut.await() }
            val screenshots = object : ScreenshotProvider {
                override suspend fun capture(): ScreenshotCaptureResult {
                    if (captures.incrementAndGet() != 1) return ScreenshotCaptureResult.Failure("capture_limit")
                    return withContext(Dispatchers.Main.immediate) {
                        assertTrue("fixture_not_visible", fixture.isShown && fixture.width > 0 && fixture.height > 0)
                        val bitmap = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
                        try {
                            // Only this TextView is drawn: no decor, system UI, or other windows.
                            fixture.draw(Canvas(bitmap))
                            val bytes = ByteArrayOutputStream().use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                                output.toByteArray()
                            }
                            ScreenshotCaptureResult.Success(ScreenshotFrame(
                                bytes, fixture.width, fixture.height,
                                "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                            ))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            val transport = HttpDeepSeekClient(requestTimeoutMillis = 90_000)
            val client = object : DeepSeekClient {
                override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult =
                    error("streaming_overload_required")

                override suspend fun requestAction(request: DeepSeekVisionRequest,
                    onUpdate: suspend (ModelStreamSnapshot) -> Unit): DeepSeekActionResult {
                    if (!requests.compareAndSet(0, 1)) {
                        blockedRequests.incrementAndGet()
                        return DeepSeekActionResult.Failure("single_upload_only")
                    }
                    report("request")
                    return transport.requestAction(request, onUpdate)
                }
            }
            val root = object : RootExecutor {
                override suspend fun checkRoot(): RootExecutionResult = error("root_check_forbidden")
                override suspend fun captureScreen(): RootScreenshotResult = error("root_capture_forbidden")
                override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
                    executeAttempts.incrementAndGet()
                    return RootExecutionResult.Failure("device_action_forbidden")
                }
                override suspend fun executeConfirmed(action: ExecutableRootAction,
                    confirm: suspend (String?) -> Boolean): RootExecutionResult {
                    executeAttempts.incrementAndGet()
                    return RootExecutionResult.Failure("device_action_forbidden")
                }
                override fun cancel() = Unit
            }
            val previewRenderer = withContext(Dispatchers.Main.immediate) { createMarkdownRenderer(launched) }
            running = async(Dispatchers.Default) {
                AgentLoop(screenshots, client, root).run(AgentLoopRequest(config, maxSteps = 1)) { event ->
                    withContext(Dispatchers.Main.immediate) {
                        val panelOverlay = requireNotNull(overlay)
                        when (event) {
                            is AgentLoopEvent.Capturing -> {
                                panelOverlay.hide()
                                state = state.copy(status = RootPilotStatus.CAPTURING, step = event.step)
                                report("capturing")
                            }
                            is AgentLoopEvent.ScreenshotCaptured -> state = state.copy(frame = event.frame)
                            is AgentLoopEvent.RequestingModel -> {
                                state = state.copy(status = RootPilotStatus.REQUESTING_MODEL, step = event.step)
                                panelOverlay.render(state)
                                report("requesting_model")
                            }
                            is AgentLoopEvent.ModelOutput -> {
                                state = state.copy(modelStream = event.snapshot)
                                panelOverlay.render(state)
                                if (event.snapshot.content.isNotBlank() || event.snapshot.reasoning.isNotBlank()) {
                                    val header = headers().single()
                                    if (!expanded) {
                                        assertTrue("expand_failed", header.performClick())
                                        expanded = true
                                    }
                                    assertTrue("preview_collapsed", header.stateDescription.toString().contains("已展开"))
                                    val panel = header.parent as LinearLayout
                                    val scroll = descendants(panel).filterIsInstance<ScrollView>().single()
                                    val detail = scroll.getChildAt(0) as TextView
                                    assertEquals(View.VISIBLE, scroll.visibility)
                                    val expected = previewRenderer.toMarkdown(modelPreviewText(event.snapshot)).toString()
                                    assertTrue("native_preview_text_mismatch", detail.text.toString() == expected)
                                    assertFalse("confirmation_must_stay_disabled",
                                        descendants(panel).filterIsInstance<Button>().single { it.text == "确认" }.isEnabled)
                                    val params = panel.layoutParams as WindowManager.LayoutParams
                                    assertTrue("secure_overlay_required", params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                                    if (event.snapshot != previousSnapshot) updates.incrementAndGet()
                                    report("stream_preview")
                                }
                                previousSnapshot = event.snapshot
                            }
                            is AgentLoopEvent.AwaitingConfirmation -> {
                                event.approval.reject()
                                panelOverlay.hide()
                                report("approval_rejected")
                            }
                            is AgentLoopEvent.Completed -> {
                                completed = true
                                panelOverlay.hide()
                                report("completed")
                            }
                            is AgentLoopEvent.Failed, AgentLoopEvent.Stopped -> {
                                panelOverlay.hide()
                                report("not_completed")
                            }
                            is AgentLoopEvent.Executing, is AgentLoopEvent.WaitingScreen,
                            is AgentLoopEvent.TodoSaved -> error("unexpected_action_event")
                        }
                    }
                }
            }
            withTimeout(120_000) { requireNotNull(running).await() }
            assertTrue("model_must_finish_without_actions", completed)
            assertTrue("multiple_real_stream_updates_required", updates.get() >= 2)
            assertEquals(1, captures.get())
            assertEquals(1, requests.get())
            assertEquals(0, blockedRequests.get())
            assertEquals(0, executeAttempts.get())
            withContext(Dispatchers.Main.immediate) {
                assertTrue("terminal_overlay_not_removed", headers().isEmpty())
            }
            report("verified_loop_http_overlay_only")
        } finally {
            withContext(NonCancellable) {
                running?.cancel()
                try {
                    withTimeout(10_000) { running?.join() }
                } finally {
                    withTimeout(10_000) {
                        withContext(Dispatchers.Main.immediate) {
                            try {
                                overlay?.hide()
                            } finally {
                                activity?.finish()
                            }
                            assertTrue("cleanup_overlay_not_removed", headers().isEmpty())
                        }
                    }
                }
            }
        }
    }

    private fun headers(): List<TextView> = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::descendants).filterIsInstance<TextView>()
        .filter { it.contentDescription == "RootPilot 悬浮窗，拖动移动，点击展开或收起" }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
