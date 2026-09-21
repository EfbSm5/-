package com.example.agent.rootpilot.chat

import com.example.agent.rootpilot.deepseek.ChatTurn
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekChatClient
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import com.example.agent.rootpilot.model.RootPilotConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private val config = RootPilotConfig()

    @Test
    fun changingApiKeyClearsVisibleConversationAndDoesNotForwardOldHistory() =
        assertConfigChangeDropsHistory(config.copy(apiKey = "synthetic-test-account"))

    @Test
    fun changingBaseUrlClearsVisibleConversationAndDoesNotForwardOldHistory() =
        assertConfigChangeDropsHistory(config.copy(baseUrl = "https://other.example.invalid"))

    @Test
    fun changingModelClearsVisibleConversationAndDoesNotForwardOldHistory() =
        assertConfigChangeDropsHistory(config.copy(model = "other-test-model"))

    private fun assertConfigChangeDropsHistory(changed: RootPilotConfig) = runTest {
        val client = RecordingClient()
        val controller = ChatController(this, client)
        controller.updateDraft("old account question")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(2, controller.state.value.messages.size)
        controller.updateDraft("new account question")
        controller.send(changed, configured = true)
        assertEquals(2, controller.state.value.messages.size)
        assertEquals("new account question", controller.state.value.messages.first().content)
        assertEquals("", controller.state.value.messages.last().content)
        advanceUntilIdle()
        assertEquals(2, client.requests.size)
        assertEquals(listOf(ChatTurn("user", "new account question")), client.requests.last())
        assertTrue(controller.state.value.messages.all { it.complete })
    }

    @Test
    fun equivalentApiConfigurationPreservesHistoryAcrossConfigCopiesAndTaskChanges() = runTest {
        val client = RecordingClient()
        val controller = ChatController(this, client)
        controller.updateDraft("first")
        controller.send(config, configured = true)
        advanceUntilIdle()
        controller.updateDraft("second")
        controller.send(config.copy(task = "unrelated device task"), configured = true)
        advanceUntilIdle()
        assertEquals(listOf(
            ChatTurn("user", "first"), ChatTurn("assistant", "a"), ChatTurn("user", "second"),
        ), client.requests.last())
        assertEquals(4, controller.state.value.messages.size)
    }

    @Test
    fun completedTurnsBecomeHistoryButReasoningAndStreamDraftDoNot() = runTest {
        val client = RecordingClient { _, update ->
            update(ModelStreamSnapshot(reasoning = "private reasoning", content = "draft"))
            DeepSeekActionResult.Success("final answer")
        }
        val controller = ChatController(this, client)
        controller.setEffort(ThinkingEffort.LOW)
        controller.updateDraft("  first question  ")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(listOf(ChatTurn("user", "first question")), client.requests.single())
        assertTrue(controller.state.value.messages.all { it.complete })
        assertEquals("private reasoning", controller.state.value.messages.last().reasoning)
        assertEquals("final answer", controller.state.value.messages.last().content)

        controller.updateDraft("second question")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(listOf(
            ChatTurn("user", "first question"), ChatTurn("assistant", "final answer"),
            ChatTurn("user", "second question"),
        ), client.requests.last())
        assertEquals(listOf(ThinkingEffort.LOW, ThinkingEffort.LOW), client.efforts)
        assertFalse(controller.state.value.generating)
        assertNull(controller.state.value.error)
    }

    @Test
    fun failedExchangeStaysVisibleButIsOmittedFromSubsequentHistory() = runTest {
        val client = RecordingClient { messages, update ->
            if (messages.last().content == "failed question") {
                update(ModelStreamSnapshot(reasoning = "unfinished reasoning", content = "unfinished answer"))
                DeepSeekActionResult.Failure("request failed")
            } else DeepSeekActionResult.Success("answer")
        }
        val controller = ChatController(this, client)
        for (prompt in listOf("good question", "failed question", "next question")) {
            controller.updateDraft(prompt)
            controller.send(config, configured = true)
            advanceUntilIdle()
        }
        assertEquals(listOf(
            ChatTurn("user", "good question"), ChatTurn("assistant", "answer"),
            ChatTurn("user", "next question"),
        ), client.requests.last())
        val failed = controller.state.value.messages.subList(2, 4)
        assertTrue(failed.none { it.complete })
        assertEquals("unfinished answer", failed.last().content)
    }

    @Test
    fun stopKeepsConversationAndRequestLockedUntilCleanupThenExcludesCancelledPair() = runTest {
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = RecordingClient { messages, update ->
            if (messages.last().content == "cancel me") {
                update(ModelStreamSnapshot(content = "partial"))
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        release.await()
                    }
                }
            } else DeepSeekActionResult.Success("next answer")
        }
        val controller = ChatController(this, client)
        try {
            controller.updateDraft("cancel me")
            controller.send(config, configured = true)
            runCurrent()
            controller.stop()
            controller.stop()
            runCurrent()
            assertTrue(cleaning.isCompleted)
            assertTrue(controller.state.value.generating)
            val pending = controller.state.value.messages
            controller.newConversation()
            controller.setEffort(ThinkingEffort.NONE)
            controller.updateDraft("next question")
            controller.send(config, configured = true)
            runCurrent()
            assertEquals(pending, controller.state.value.messages)
            assertEquals(ThinkingEffort.HIGH, controller.state.value.effort)
            assertEquals(1, client.requests.size)

            release.complete(Unit)
            advanceUntilIdle()
            assertFalse(controller.state.value.generating)
            assertTrue(controller.state.value.messages.none { it.complete })
            controller.send(config, configured = true)
            advanceUntilIdle()
            assertEquals(listOf(ChatTurn("user", "next question")), client.requests.last())
        } finally {
            release.complete(Unit)
            controller.stop()
        }
    }

    @Test
    fun parentScopeCancellationWaitsForCleanupAndNeverCompletesPartialReply() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = RecordingClient { _, update ->
            update(ModelStreamSnapshot(content = "partial"))
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    release.await()
                }
            }
        }
        val controller = ChatController(scope, client)
        try {
            controller.updateDraft("question")
            controller.send(config, configured = true)
            runCurrent()
            scope.cancel()
            runCurrent()
            assertTrue(cleaning.isCompleted)
            assertTrue(controller.state.value.generating)
            release.complete(Unit)
            advanceUntilIdle()
            assertFalse(controller.state.value.generating)
            assertTrue(controller.state.value.messages.none { it.complete })
            assertNull(controller.state.value.error)
            controller.updateDraft("after cancellation")
            controller.send(config, configured = true)
            advanceUntilIdle()
            assertEquals(1, client.requests.size)
            assertFalse(controller.state.value.generating)
        } finally {
            release.complete(Unit)
            scope.cancel()
            advanceUntilIdle()
        }
    }

    @Test
    fun missingConfigurationAndBlankInputNeverCallClient() = runTest {
        val client = RecordingClient()
        val controller = ChatController(this, client)
        controller.updateDraft(" \n ")
        controller.send(config, configured = true)
        controller.updateDraft("keep this draft")
        controller.send(config, configured = false)
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertTrue(controller.state.value.messages.isEmpty())
        assertEquals("keep this draft", controller.state.value.draft)
        assertEquals("请先在设置中保存 API 配置", controller.state.value.error)
        assertFalse(controller.state.value.generating)
    }

    @Test
    fun draftTruncatesAtInputLimitAndSendsOnlyTruncatedContent() = runTest {
        val client = RecordingClient()
        val controller = ChatController(this, client)
        controller.updateDraft("a".repeat(16_000) + "overflow")
        assertEquals("a".repeat(16_000), controller.state.value.draft)
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(16_000, client.requests.single().single().content.length)
    }

    @Test
    fun historyAllowsExactLimitButRejectsNextCharacterWithoutAddingMessages() = runTest {
        val client = RecordingClient { messages, _ ->
            DeepSeekActionResult.Success(if (messages.size == 1) "a".repeat(32_000) else "")
        }
        val controller = ChatController(this, client)
        repeat(2) {
            controller.updateDraft("q".repeat(16_000))
            controller.send(config, configured = true)
            advanceUntilIdle()
        }
        assertEquals(64_000, client.requests.last().sumOf { it.content.length })
        val before = controller.state.value.messages
        controller.updateDraft("x")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(2, client.requests.size)
        assertEquals(before, controller.state.value.messages)
        assertEquals("x", controller.state.value.draft)
        assertEquals("当前会话已达长度上限，请新建对话", controller.state.value.error)
    }

    @Test
    fun messageLimitRequiresNewConversationAndResetDropsAllHistory() = runTest {
        val client = RecordingClient()
        val controller = ChatController(this, client)
        repeat(50) {
            controller.updateDraft("q")
            controller.send(config, configured = true)
            advanceUntilIdle()
        }
        assertEquals(100, controller.state.value.messages.size)
        controller.updateDraft("blocked")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(50, client.requests.size)
        assertEquals(100, controller.state.value.messages.size)
        controller.setEffort(ThinkingEffort.LOW)
        controller.newConversation()
        assertEquals(ThinkingEffort.LOW, controller.state.value.effort)
        assertTrue(controller.state.value.messages.isEmpty())
        assertNull(controller.state.value.error)
        controller.updateDraft("fresh")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(listOf(ChatTurn("user", "fresh")), client.requests.last())
    }

    @Test
    fun unexpectedExceptionIsSanitizedAndIncompleteTurnIsNotReused() = runTest {
        val sentinel = "PRIVATE_EXCEPTION_SENTINEL"
        val client = RecordingClient { messages, _ ->
            if (messages.last().content == "fail") throw IllegalStateException(sentinel)
            DeepSeekActionResult.Success("ok")
        }
        val controller = ChatController(this, client)
        controller.updateDraft("fail")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals("聊天请求失败，请稍后重试", controller.state.value.error)
        assertFalse(controller.state.value.toString().contains(sentinel))
        assertTrue(controller.state.value.messages.none { it.complete })
        assertFalse(controller.state.value.generating)
        controller.updateDraft("recover")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(listOf(ChatTurn("user", "recover")), client.requests.last())
    }

    private class RecordingClient(
        private val respond: suspend (List<ChatTurn>, suspend (ModelStreamSnapshot) -> Unit) -> DeepSeekActionResult =
            { _, _ -> DeepSeekActionResult.Success("a") },
    ) : DeepSeekChatClient {
        val requests = mutableListOf<List<ChatTurn>>()
        val efforts = mutableListOf<ThinkingEffort>()
        override suspend fun streamChat(
            config: RootPilotConfig,
            messages: List<ChatTurn>,
            effort: ThinkingEffort,
            onUpdate: suspend (ModelStreamSnapshot) -> Unit,
        ): DeepSeekActionResult {
            requests += messages.toList()
            efforts += effort
            return respond(messages, onUpdate)
        }
    }
}
