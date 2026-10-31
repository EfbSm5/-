package com.example.agent.rootpilot

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.chat.ChatController
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import com.example.agent.rootpilot.model.RootPilotConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only: one billed Search request plus one text request, without modifying saved configuration. */
@RunWith(AndroidJUnit4::class)
class DeepSeekLiveSearchInstrumentedTest {
    @Test
    fun savedConfigurationSearchesOnceAndAnswersWithSources() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootpilotLiveSearch") == "true")
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
        val transport = HttpDeepSeekClient()
        val controller = ChatController(scope, transport, search = transport)
        try {
            controller.setEffort(ThinkingEffort.NONE)
            controller.setWebSearch(true)
            assertTrue("web_search_toggle_required", controller.state.value.webSearch)
            controller.updateDraft("DeepSeek Search API 官方文档 计费方式")
            controller.send(config, configured = true)
            val result = withTimeout(130_000) { controller.state.first { !it.generating } }
            val answer = result.messages.last()
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stage", "completed")
                putBoolean("error", result.error != null)
                putInt("sources", answer.sources.size)
                putInt("content_length", answer.content.length)
            })
            assertTrue("search_chat_failed: ${result.error}", result.error == null)
            assertTrue("complete_reply_required", answer.complete && answer.searched)
            assertTrue("sources_required", answer.sources.isNotEmpty())
            assertTrue("web_sources_required", answer.sources.all { it.url.startsWith("http") })
            assertTrue("answer_required", answer.content.isNotBlank())
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
