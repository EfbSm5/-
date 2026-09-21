package com.example.agent.rootpilot

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.chat.ChatController
import com.example.agent.rootpilot.deepseek.ChatTurn
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekChatClient
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import com.example.agent.rootpilot.model.RootPilotConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only: makes four billed text requests, without modifying saved configuration. */
@RunWith(AndroidJUnit4::class)
class DeepSeekLiveChatInstrumentedTest {
    @Test
    fun savedConfigurationStreamsMultiTurnAndCancelsOnDevice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootpilotLiveChat") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val saved = try {
            RootPilotApiConfigStore.create(instrumentation.targetContext).read()
        } catch (_: Exception) {
            throw AssertionError("saved_config_unavailable")
        }
        assertTrue("saved_config_required", saved != null)
        val config = saved!!.applyTo(RootPilotConfig())
        assertTrue("direct_endpoint_required", config.baseUrl.trimEnd('/') == "https://api.deepseek.com")
        assertTrue("saved_token_required", config.apiKey.isNotBlank())
        assertTrue("flash_model_required", config.model == "deepseek-flash")
        val parentJob = SupervisorJob()
        val scope = CoroutineScope(parentJob + Dispatchers.Default)
        val fragments = AtomicInteger()
        var firstFragment = CompletableDeferred<Unit>()
        var lastSnapshot = ModelStreamSnapshot()
        val transport = HttpDeepSeekClient()
        val client = object : DeepSeekChatClient {
            override suspend fun streamChat(
                config: RootPilotConfig,
                messages: List<ChatTurn>,
                effort: ThinkingEffort,
                onUpdate: suspend (ModelStreamSnapshot) -> Unit,
            ): DeepSeekActionResult = transport.streamChat(config, messages, effort) {
                onUpdate(it)
                if (it.content.isNotEmpty() || it.reasoning.isNotEmpty()) {
                    if (it != lastSnapshot) fragments.incrementAndGet()
                    lastSnapshot = it
                    firstFragment.complete(Unit)
                }
            }
        }
        val controller = ChatController(scope, client)
        fun report(stage: String) = instrumentation.sendStatus(2, Bundle().apply {
            putString("stage", stage)
            putInt("stream_updates", fragments.get())
        })
        suspend fun complete(effort: ThinkingEffort, prompt: String) {
            fragments.set(0)
            firstFragment = CompletableDeferred()
            lastSnapshot = ModelStreamSnapshot()
            controller.setEffort(effort)
            controller.updateDraft(prompt)
            report("request_${effort.wireValue}")
            controller.send(config, configured = true)
            val result = withTimeout(125_000) { controller.state.first { !it.generating } }
            assertTrue("chat_request_failed_${effort.wireValue}: ${result.error}", result.error == null)
            assertTrue("complete_reply_required", result.messages.last().complete)
            assertTrue("stream_updates_required", fragments.get() > 0)
            if (effort == ThinkingEffort.NONE) assertTrue("incremental_output_required", fragments.get() > 1)
            if (effort == ThinkingEffort.NONE) assertTrue(result.messages.last().reasoning.isEmpty())
            else assertTrue("reasoning_required", result.messages.last().reasoning.isNotEmpty())
            report("completed_${effort.wireValue}")
        }
        try {
            complete(ThinkingEffort.NONE,
                "Remember marker RP_STREAM_CHECK. Reply with a short Markdown heading, two bullets, " +
                    "a quote, and a fenced Kotlin code block showing val result = 2. Under 100 words.")
            complete(ThinkingEffort.HIGH, "What exact marker did I ask you to remember? Reply only with that marker.")
            assertTrue("multi_turn_marker_missing", controller.state.value.messages.last().content.contains("RP_STREAM_CHECK"))
            complete(ThinkingEffort.MAX, "Compute 19 times 23. Give only the numeric result.")
            assertTrue("numeric_answer_missing", controller.state.value.messages.last().content.contains("437"))

            controller.setEffort(ThinkingEffort.LOW)
            controller.updateDraft("Write a detailed 2000-word tutorial on Kotlin collections with ten code examples.")
            fragments.set(0)
            firstFragment = CompletableDeferred()
            lastSnapshot = ModelStreamSnapshot()
            report("request_low_cancel")
            controller.send(config, configured = true)
            withTimeout(125_000) { firstFragment.await() }
            assertTrue("request_finished_before_cancel", controller.state.value.generating)
            controller.stop()
            val stopped = withTimeout(5_000) { controller.state.first { !it.generating } }
            assertFalse("cancelled_reply_must_be_incomplete", stopped.messages.last().complete)
            assertTrue("stop_result_required", stopped.error?.startsWith("已停止生成") == true)
            assertEquals(8, stopped.messages.size)
            report("cancelled_low")
            controller.newConversation()
            assertTrue(controller.state.value.messages.isEmpty())
            report("new_conversation_empty")
        } finally {
            controller.stop()
            scope.cancel()
            val cleaned = withContext(NonCancellable) {
                withTimeoutOrNull(5_000) { parentJob.join(); true } == true
            }
            assertTrue("request_cleanup_timeout", cleaned)
        }
    }
}
