package com.example.agent.rootpilot

import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real activity directly, without ActivityScenario's cross-package helper activity. */
@RunWith(AndroidJUnit4::class)
class RootPilotLiveUiInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun realChatSendsRendersReasoningAndStopsWithoutTaskActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootpilotLiveChat") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, RootPilotActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as RootPilotActivity
        lateinit var model: RootPilotViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(activity, RootPilotViewModel.Factory(activity.applicationContext))
                .get(RootPilotViewModel::class.java)
        }
        fun stage(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stage", value) })
        try {
            compose.waitUntil(15_000) { !model.apiState.value.busy }
            assertTrue("saved_config_required", model.apiState.value.configured && !model.apiState.value.editing)
            assertTrue("direct_flash_required", model.uiState.value.config.baseUrl.trimEnd('/') == "https://api.deepseek.com" &&
                model.uiState.value.config.model == "deepseek-flash")
            compose.onNodeWithTag("open_chat").performClick()
            compose.onNodeWithTag("chat_effort_HIGH").performClick()
            compose.onNodeWithTag("chat_draft").performTextInput(
                "Reply with a short Markdown heading, two bullets, a quote and a fenced Kotlin code block. Under 100 words.",
            )
            compose.onNodeWithTag("chat_send").performClick()
            compose.waitUntil(125_000) { !model.chatState.value.generating }
            assertTrue("live_ui_request_failed", model.chatState.value.error == null)
            val message = model.chatState.value.messages.last()
            assertTrue("completed_answer_required", message.complete && message.content.isNotBlank())
            assertTrue("reasoning_required", message.reasoning.isNotBlank())
            compose.onNodeWithTag("chat_messages").assertIsDisplayed()
            compose.onNodeWithTag("chat_reasoning_${message.id}").performScrollTo().performClick()
            compose.onNodeWithTag("chat_reasoning_body_${message.id}").assertExists()
            compose.onNodeWithTag("chat_reasoning_${message.id}").performClick()
            compose.onNodeWithTag("chat_reasoning_body_${message.id}").assertDoesNotExist()
            stage("live_ui_answer_and_reasoning_passed")
            val original = activity
            instrumentation.runOnMainSync { original.recreate() }
            compose.waitUntil(15_000) {
                var replacement: RootPilotActivity? = null
                instrumentation.runOnMainSync {
                    replacement = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<RootPilotActivity>()
                        .firstOrNull { it !== original }
                }
                replacement?.let { activity = it }
                replacement != null
            }
            compose.onNodeWithTag("chat_new").assertIsDisplayed()
            instrumentation.runOnMainSync {
                assertTrue("view_model_must_survive_recreation",
                    ViewModelProvider(activity, RootPilotViewModel.Factory(activity.applicationContext))
                        .get(RootPilotViewModel::class.java) === model)
            }
            assertTrue("chat_must_survive_recreation", model.chatState.value.messages.last() == message)
            stage("live_ui_recreation_passed")
            File(activity.cacheDir, "rootpilot-live-chat.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("chat_new").performClick()
            compose.runOnIdle { assertTrue(model.chatState.value.messages.isEmpty()) }
            compose.onNodeWithTag("chat_draft").performTextInput("Write a detailed 3000-word Kotlin tutorial with 20 code examples.")
            compose.onNodeWithTag("chat_send").performClick()
            compose.waitUntil(125_000) {
                model.chatState.value.let { state ->
                    state.generating && state.messages.lastOrNull()?.let {
                        it.content.isNotEmpty() || it.reasoning.isNotEmpty()
                    } == true
                }
            }
            compose.onNodeWithTag("open_settings").assertIsNotEnabled()
            compose.onNodeWithTag("back_to_task").assertIsNotEnabled()
            compose.onNodeWithTag("chat_stop").performClick()
            compose.waitUntil(5_000) { !model.chatState.value.generating }
            assertTrue("cancelled_reply_required", model.chatState.value.messages.last().complete.not())
            stage("live_ui_stop_passed")
            compose.onNodeWithTag("chat_new").performClick()
            compose.onNodeWithTag("back_to_task").performClick()
            compose.onNodeWithTag("task_input").assertIsDisplayed()
            stage("live_ui_new_conversation_and_return_passed")
        } finally {
            try {
                instrumentation.runOnMainSync { model.stopChat() }
                compose.waitUntil(5_000) { !model.chatState.value.generating }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test
    fun realActivityNavigatesBetweenTaskChatAndSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, RootPilotActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.sendStatus(2, Bundle().apply { putString("stage", "real_activity_started") })
            compose.onNodeWithTag("open_chat").performClick()
            compose.onNodeWithTag("chat_new").assertIsDisplayed()
            instrumentation.sendStatus(2, Bundle().apply { putString("stage", "chat_visible") })
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("api_status").assertIsDisplayed()
            compose.onNodeWithTag("back_to_task").performClick()
            compose.onNodeWithTag("task_input").assertIsDisplayed()
            instrumentation.sendStatus(2, Bundle().apply { putString("stage", "navigation_passed") })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
