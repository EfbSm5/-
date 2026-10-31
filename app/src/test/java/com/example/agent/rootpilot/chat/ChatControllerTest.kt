package com.example.agent.rootpilot.chat

import com.example.agent.rootpilot.deepseek.ChatTurn
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekChatClient
import com.example.agent.rootpilot.deepseek.DeepSeekSearchClient
import com.example.agent.rootpilot.deepseek.DeepSeekToolChatClient
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import com.example.agent.rootpilot.deepseek.ToolChatResult
import com.example.agent.rootpilot.deepseek.ToolChatTurn
import com.example.agent.rootpilot.deepseek.WebSearchHit
import com.example.agent.rootpilot.deepseek.WebSearchResult
import com.example.agent.rootpilot.deepseek.WebSearchSource
import com.example.agent.rootpilot.files.FileEntry
import com.example.agent.rootpilot.files.FileWriteReceipt
import com.example.agent.rootpilot.files.PreparedFileChange
import com.example.agent.rootpilot.files.WorkspaceAccess
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
import kotlinx.serialization.json.JsonObject
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

    @Test
    fun webSearchIsOffByDefaultAndNeverCalledUntilEnabled() = runTest {
        val client = RecordingClient()
        val search = RecordingSearch()
        val controller = ChatController(this, client, search = search)
        assertFalse(controller.state.value.webSearch)
        controller.updateDraft("question")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertTrue(search.queries.isEmpty())
        assertEquals(listOf(ChatTurn("user", "question")), client.requests.single())
        assertFalse(controller.state.value.messages.last().searched)
    }

    @Test
    fun enabledSearchAddsPerTurnContextAndSourcesButLaterHistoryOnlyKeepsAnswers() = runTest {
        val client = RecordingClient()
        val search = RecordingSearch()
        val controller = ChatController(this, client, search = search)
        controller.setWebSearch(true)
        controller.updateDraft("  latest news  ")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(listOf("latest news"), search.queries)
        val first = client.requests.single()
        assertEquals(2, first.size)
        assertEquals("system", first[0].role)
        assertTrue(first[0].content.contains("不可信的外部网页数据"))
        assertTrue(first[0].content.contains("https://example.com/a"))
        assertTrue(first[0].content.contains("summary A"))
        assertEquals(ChatTurn("user", "latest news"), first[1])
        val answer = controller.state.value.messages.last()
        assertTrue(answer.complete && answer.searched)
        assertEquals(listOf(WebSearchSource("Title A", "https://example.com/a", "Site")), answer.sources)

        controller.updateDraft("follow up")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(2, search.queries.size)
        val second = client.requests.last()
        assertEquals(ChatTurn("system", second[0].content), second[0])
        assertEquals(listOf(ChatTurn("user", "latest news"), ChatTurn("assistant", "a"), ChatTurn("user", "follow up")),
            second.drop(1))
        assertTrue(controller.state.value.webSearch)

        controller.newConversation()
        assertFalse(controller.state.value.webSearch)
    }

    @Test
    fun searchFailureOrEmptyResultsFailClosedOrReportNoSources() = runTest {
        val client = RecordingClient()
        val search = RecordingSearch { query ->
            if (query == "fail") WebSearchResult.Failure("请求频率或额度受限（HTTP 429），请稍后重试")
            else WebSearchResult.Success(emptyList())
        }
        val controller = ChatController(this, client, search = search)
        controller.setWebSearch(true)
        controller.updateDraft("fail")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertTrue(client.requests.isEmpty())
        assertEquals("联网搜索失败，未发送给模型：请求频率或额度受限（HTTP 429），请稍后重试", controller.state.value.error)
        assertTrue(controller.state.value.messages.none { it.complete })
        assertFalse(controller.state.value.generating)

        controller.updateDraft("empty")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertEquals(listOf("fail", "empty"), search.queries)
        assertEquals(listOf(ChatTurn("user", "empty")), client.requests.single().drop(1))
        assertTrue(client.requests.single()[0].content.endsWith("[]"))
        val answer = controller.state.value.messages.last()
        assertTrue(answer.complete && answer.searched && answer.sources.isEmpty())
    }

    @Test
    fun stopDuringSearchNeverSendsModelRequestAndToggleIsLockedWhileGenerating() = runTest {
        val client = RecordingClient()
        val search = RecordingSearch { awaitCancellation() }
        val controller = ChatController(this, client, search = search)
        controller.setWebSearch(true)
        controller.updateDraft("question")
        controller.send(config, configured = true)
        runCurrent()
        controller.setWebSearch(false)
        assertTrue(controller.state.value.webSearch)
        controller.stop()
        advanceUntilIdle()
        assertEquals(listOf("question"), search.queries)
        assertTrue(client.requests.isEmpty())
        assertFalse(controller.state.value.generating)
        assertTrue(controller.state.value.messages.none { it.complete })
    }

    @Test
    fun searchIsUnavailableWithoutClientAndNeverUsedByFileAgent() = runTest {
        val plain = ChatController(this, RecordingClient())
        plain.setWebSearch(true)
        assertFalse(plain.state.value.webSearch)

        val client = RecordingClient()
        val search = RecordingSearch()
        val tools = RecordingToolClient()
        val fileAgent = FileAgentController(NoFiles(), tools)
        val controller = ChatController(this, client, fileAgent, search)
        controller.setWebSearch(true)
        assertTrue(controller.state.value.webSearch)
        fileAgent.updateWorkspace("Test directory", emptyList())
        fileAgent.setEnabled(true)
        controller.setWebSearch(true)
        controller.updateDraft("edit notes")
        controller.send(config, configured = true)
        advanceUntilIdle()
        assertTrue(search.queries.isEmpty())
        assertTrue(client.requests.isEmpty())
        val toolRequest = tools.requests.single()
        assertEquals(ToolChatTurn("user", "edit notes"), toolRequest.last())
        assertTrue(toolRequest.none { "联网搜索" in it.content || "example.com" in it.content })
        assertFalse(controller.state.value.messages.last().searched)

        controller.newConversation()
        controller.setWebSearch(true)
        assertFalse(controller.state.value.webSearch)
    }

    private class RecordingSearch(
        private val respond: suspend (String) -> WebSearchResult = {
            WebSearchResult.Success(listOf(WebSearchHit(WebSearchSource("Title A", "https://example.com/a", "Site"), "summary A")))
        },
    ) : DeepSeekSearchClient {
        val queries = mutableListOf<String>()
        override suspend fun search(config: RootPilotConfig, query: String): WebSearchResult {
            queries += query
            return respond(query)
        }
    }

    private class RecordingToolClient : DeepSeekToolChatClient {
        val requests = mutableListOf<List<ToolChatTurn>>()
        override suspend fun streamToolChat(
            config: RootPilotConfig, messages: List<ToolChatTurn>, tools: List<JsonObject>,
            effort: ThinkingEffort, onUpdate: suspend (ModelStreamSnapshot) -> Unit,
        ): ToolChatResult {
            requests += messages.toList()
            return ToolChatResult.Success("done", "", emptyList())
        }
    }

    private class NoFiles : WorkspaceAccess {
        override suspend fun list(path: String) = emptyList<FileEntry>()
        override suspend fun read(path: String): String = error("unexpected read")
        override suspend fun prepareCreate(path: String, content: String): PreparedFileChange = error("unexpected write")
        override suspend fun prepareEdit(path: String, oldText: String, newText: String): PreparedFileChange = error("unexpected write")
        override suspend fun discard(change: PreparedFileChange) = error("unexpected discard")
        override suspend fun commit(change: PreparedFileChange): FileWriteReceipt = error("unexpected commit")
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
