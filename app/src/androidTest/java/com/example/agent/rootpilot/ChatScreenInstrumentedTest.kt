package com.example.agent.rootpilot

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.chat.ChatMessage
import com.example.agent.rootpilot.chat.ChatUiState
import com.example.agent.rootpilot.ui.ChatScreen
import com.example.agent.rootpilot.ui.RootPilotTheme
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: RootPilotActivity

    @Before
    fun launchRealActivity() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, RootPilotActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as RootPilotActivity
    }

    @After
    fun finishActivity() {
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
    }

    private fun setContent(content: @Composable () -> Unit) {
        instrumentation.runOnMainSync { activity.setContent(content = content) }
    }

    @Test
    fun streamingDisablesSendEffortAndNewConversationButKeepsStopAndReasoningToggle() {
        var stopped = 0
        val state = mutableStateOf(ChatUiState(draft = "后续问题", generating = true,
            messages = listOf(ChatMessage(1, "assistant", "# 测试标题\n正文", "测试思考"))))
        setContent {
            RootPilotTheme {
                ChatScreen(state.value, true, {}, {}, {}, { stopped++ }, {})
            }
        }
        compose.onNodeWithTag("chat_send").assertIsNotEnabled()
        compose.onNodeWithTag("chat_new").assertIsNotEnabled()
        listOf("NONE", "LOW", "HIGH", "MAX").forEach {
            compose.onNodeWithTag("chat_effort_$it").assertIsNotEnabled()
        }
        compose.onNodeWithTag("chat_reasoning_body_1").assertDoesNotExist()
        compose.onNodeWithTag("chat_reasoning_1").performClick()
        compose.onNodeWithTag("chat_reasoning_body_1").assertExists()
        compose.onNodeWithTag("chat_stop").performClick()
        compose.runOnIdle {
            assertEquals(1, stopped)
            state.value = state.value.copy(generating = false)
        }
        compose.onNodeWithTag("chat_send").assertIsEnabled()
        compose.onNodeWithTag("chat_new").assertIsEnabled()
        compose.onNodeWithTag("chat_stop").assertDoesNotExist()
    }

    @Test
    fun unconfiguredChatCannotSend() {
        setContent { RootPilotTheme { ChatScreen(ChatUiState(draft = "测试"), false, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("chat_send").assertIsNotEnabled()
    }

    @Test
    fun readingOlderMessagesIsNotPulledBackToStreamingTail() {
        val state = mutableStateOf(ChatUiState(messages = (1L..12L).map {
            ChatMessage(it, "assistant", "## 消息 $it\n" + "测试正文\n\n".repeat(10), complete = true)
        }, generating = true))
        setContent { RootPilotTheme { ChatScreen(state.value, true, {}, {}, {}, {}, {}) } }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val positionBefore = compose.onNodeWithTag("chat_messages").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        // A long last message lets the viewport move away from its tail without changing its key.
        compose.runOnIdle {
            state.value = state.value.copy(messages = state.value.messages.dropLast(1) +
                state.value.messages.last().copy(content = state.value.messages.last().content + "新增段落\n\n".repeat(30)))
        }
        compose.waitForIdle()
        val positionAfter = compose.onNodeWithTag("chat_messages").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(positionBefore, positionAfter, 0.001f)
    }

    @Test
    fun previousIncompleteAssistantMessageIsNotMarkedAsGeneratingAgain() {
        setContent { RootPilotTheme { ChatScreen(
            ChatUiState(messages = listOf(ChatMessage(1, "assistant", "前次内容"),
                ChatMessage(2, "user", "新问题", complete = true), ChatMessage(3, "assistant")), generating = true),
            true, {}, {}, {}, {}, {},
        ) } }
        compose.onNodeWithTag("chat_generating_1").assertDoesNotExist()
        compose.onNodeWithTag("chat_incomplete_1").assertExists()
        compose.onNodeWithTag("chat_generating_3").assertExists()
    }

    @Test
    fun effortAndUnicodeDraftDelegateOnceWithoutSendingNetworkRequests() {
        val state = mutableStateOf(ChatUiState())
        var effortChanges = 0
        var sends = 0
        setContent {
            RootPilotTheme {
                ChatScreen(state.value, true,
                    onDraftChange = { state.value = state.value.copy(draft = it) },
                    onEffortChange = { effortChanges++; state.value = state.value.copy(effort = it) },
                    onSend = { sends++ }, onStop = {}, onNewConversation = {},
                )
            }
        }
        compose.onNodeWithTag("chat_effort_MAX").performClick().assertIsSelected()
        compose.onNodeWithTag("chat_effort_LOW").assertIsNotSelected()
        compose.onNodeWithTag("chat_draft").performTextReplacement("你好🙂\n第二行")
        compose.onNodeWithTag("chat_send").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(ThinkingEffort.MAX, state.value.effort)
            assertEquals(1, effortChanges)
            assertEquals("你好🙂\n第二行", state.value.draft)
            assertEquals(1, sends)
        }
    }
}
