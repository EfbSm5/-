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
import java.io.IOException
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

    @Test
    fun completedPlainChatIsSavedToOneSessionWithoutReasoningOrIncompleteTurns() = runTest {
        val storage = MemoryHistory()
        val history = ChatHistoryRepository(storage, backgroundScope)
        val client = RecordingClient { messages, update ->
            update(ModelStreamSnapshot(reasoning = "PRIVATE_REASONING", content = "draft"))
            if (messages.last().content == "fail") DeepSeekActionResult.Failure("request failed")
            else DeepSeekActionResult.Success("answer to ${messages.last().content}")
        }
        val controller = ChatController(this, client, history = history, clock = { 42 })
        for (prompt in listOf("  first\nquestion ", "fail", "second")) {
            controller.updateDraft(prompt)
            controller.send(config, configured = true)
            runCurrent()
        }
        val saved = storage.sessions.values.single()
        assertEquals(controller.state.value.sessionId, saved.id)
        assertEquals("first question", saved.title)
        assertEquals(42L, saved.updatedAtEpochMs)
        assertEquals(listOf(
            SavedChatMessage("user", "first\nquestion"), SavedChatMessage("assistant", "answer to first\nquestion"),
            SavedChatMessage("user", "second"), SavedChatMessage("assistant", "answer to second"),
        ), saved.messages)
        assertEquals(listOf(saved.id), history.state.value.sessions.map { it.id })
        assertEquals(2, storage.writes)
    }

    @Test
    fun searchedAnswerKeepsSourcesWhenSaved() = runTest {
        val storage = MemoryHistory()
        val controller = ChatController(this, RecordingClient(), search = RecordingSearch(),
            history = ChatHistoryRepository(storage, backgroundScope))
        controller.setWebSearch(true)
        controller.updateDraft("news")
        controller.send(config, configured = true)
        runCurrent()
        assertEquals(SavedChatMessage("assistant", "a", searched = true,
            sources = listOf(SavedChatSource("Title A", "https://example.com/a", "Site"))),
            storage.sessions.values.single().messages.last())
    }

    @Test
    fun fileAgentConversationIsNeverSavedEvenAfterAgentIsTurnedOff() = runTest {
        val storage = MemoryHistory()
        val fileAgent = FileAgentController(NoFiles(), RecordingToolClient())
        val controller = ChatController(this, RecordingClient(), fileAgent, history = ChatHistoryRepository(storage, backgroundScope))
        fileAgent.updateWorkspace("Test directory", emptyList())
        fileAgent.setEnabled(true)
        controller.updateDraft("edit notes")
        controller.send(config, configured = true)
        runCurrent()
        fileAgent.setEnabled(false)
        controller.updateDraft("plain follow up")
        controller.send(config, configured = true)
        runCurrent()
        assertTrue(storage.sessions.isEmpty())
        assertNull(controller.state.value.sessionId)

        controller.newConversation()
        controller.updateDraft("fresh plain chat")
        controller.send(config, configured = true)
        runCurrent()
        assertEquals(1, storage.sessions.size)
    }

    @Test
    fun apiConfigChangeStartsNewSessionAndKeepsEarlierSavedSession() = runTest {
        val storage = MemoryHistory()
        val client = RecordingClient()
        val controller = ChatController(this, client, history = ChatHistoryRepository(storage, backgroundScope))
        controller.updateDraft("old account question")
        controller.send(config, configured = true)
        runCurrent()
        val first = controller.state.value.sessionId!!
        controller.updateDraft("new model question")
        controller.send(config.copy(model = "other-test-model"), configured = true)
        runCurrent()
        val second = controller.state.value.sessionId!!
        assertTrue(first != second)
        assertEquals(listOf(ChatTurn("user", "new model question")), client.requests.last())
        assertEquals(setOf(first, second), storage.sessions.keys)
        assertEquals("old account question", storage.sessions.getValue(first).messages.first().content)
    }

    @Test
    fun openedSessionContinuesAsHistoryAndSavesBackToTheSameSession() = runTest {
        val existing = SavedChatSession(UUID_A, "earlier", 1, listOf(
            SavedChatMessage("user", "earlier"),
            SavedChatMessage("assistant", "reply", searched = true, sources = listOf(SavedChatSource("T", "https://example.com/t"))),
        ))
        val storage = MemoryHistory(existing)
        val history = ChatHistoryRepository(storage, backgroundScope)
        val client = RecordingClient()
        val controller = ChatController(this, client, history = history, clock = { 7 })
        controller.setWebSearch(true)
        controller.updateDraft("kept draft")
        controller.openSession(UUID_A)
        runCurrent()
        val state = controller.state.value
        assertEquals(UUID_A, state.sessionId)
        assertEquals("kept draft", state.draft)
        assertFalse(state.webSearch)
        assertTrue(state.messages.all { it.complete && it.reasoning.isEmpty() })
        assertEquals(listOf(WebSearchSource("T", "https://example.com/t")), state.messages.last().sources)
        assertTrue(client.requests.isEmpty())

        controller.send(config, configured = true)
        runCurrent()
        assertEquals(listOf(ChatTurn("user", "earlier"), ChatTurn("assistant", "reply"), ChatTurn("user", "kept draft")),
            client.requests.single())
        val saved = storage.sessions.getValue(UUID_A)
        assertEquals(4, saved.messages.size)
        assertEquals(7L, saved.updatedAtEpochMs)
        assertEquals("earlier", saved.title)
    }

    @Test
    fun openIsRefusedForFileAgentAndUnreadableSessionsAndStaleReadsAreDropped() = runTest {
        val existing = SavedChatSession(UUID_A, "earlier", 1, listOf(SavedChatMessage("user", "q"), SavedChatMessage("assistant", "a")))
        val storage = MemoryHistory(existing)
        val history = ChatHistoryRepository(storage, backgroundScope)
        val fileAgent = FileAgentController(NoFiles(), RecordingToolClient())
        val controller = ChatController(this, RecordingClient(), fileAgent, history = history)
        fileAgent.updateWorkspace("Test directory", emptyList())
        fileAgent.setEnabled(true)
        controller.openSession(UUID_A)
        runCurrent()
        assertTrue(controller.state.value.messages.isEmpty())
        fileAgent.setEnabled(false)

        controller.openSession(UUID_A)
        controller.newConversation()
        runCurrent()
        assertTrue(controller.state.value.messages.isEmpty())
        assertNull(controller.state.value.sessionId)

        storage.damaged += UUID_A
        controller.openSession(UUID_A)
        runCurrent()
        assertTrue(controller.state.value.messages.isEmpty())
        assertEquals("会话无法读取，可能已删除或已损坏", controller.state.value.error)
    }

    @Test
    fun deletingOrClearingOpenSessionClosesItSoLaterTurnsCannotRecreateIt() = runTest {
        val storage = MemoryHistory()
        val history = ChatHistoryRepository(storage, backgroundScope)
        val controller = ChatController(this, RecordingClient(), history = history)
        controller.updateDraft("first")
        controller.send(config, configured = true)
        runCurrent()
        val id = controller.state.value.sessionId!!
        controller.deleteSession(id)
        runCurrent()
        assertTrue(storage.sessions.isEmpty())
        assertTrue(controller.state.value.messages.isEmpty())
        assertNull(controller.state.value.sessionId)

        controller.updateDraft("second")
        controller.send(config, configured = true)
        runCurrent()
        val other = controller.state.value.sessionId!!
        assertTrue(other != id)
        controller.clearHistory()
        runCurrent()
        assertTrue(storage.sessions.isEmpty())
        assertTrue(controller.state.value.messages.isEmpty())
        assertTrue(history.state.value.sessions.isEmpty())
    }

    @Test
    fun historyChangesAreLockedWhileGenerating() = runTest {
        val existing = SavedChatSession(UUID_A, "earlier", 1, listOf(SavedChatMessage("user", "q"), SavedChatMessage("assistant", "a")))
        val storage = MemoryHistory(existing)
        val client = RecordingClient { _, _ -> kotlinx.coroutines.awaitCancellation() }
        val controller = ChatController(this, client, history = ChatHistoryRepository(storage, backgroundScope))
        controller.updateDraft("running")
        controller.send(config, configured = true)
        runCurrent()
        controller.openSession(UUID_A)
        controller.deleteSession(UUID_A)
        controller.clearHistory()
        runCurrent()
        assertEquals(setOf(UUID_A), storage.sessions.keys)
        assertEquals("running", controller.state.value.messages.first().content)
        controller.stop()
        runCurrent()
        assertEquals(setOf(UUID_A), storage.sessions.keys)
    }

    private class MemoryHistory(vararg initial: SavedChatSession) : ChatHistoryStorage {
        val sessions = linkedMapOf<String, SavedChatSession>().apply { initial.forEach { put(it.id, it) } }
        val damaged = mutableSetOf<String>()
        var writes = 0
        override fun list() = ChatHistoryListing(sessions.values.map { it.summary() }, 0)
        override fun read(id: String): SavedChatSession? {
            if (id in damaged) throw IOException("damaged")
            return sessions[id]
        }
        override fun write(session: SavedChatSession) { writes++; sessions[session.id] = session }
        override fun delete(id: String) { sessions.remove(id) }
        override fun clear() = sessions.clear()
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

    private companion object {
        const val UUID_A = "00000000-0000-4000-8000-000000000001"
    }
}
