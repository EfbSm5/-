package com.example.agent.rootpilot.chat

import com.example.agent.rootpilot.deepseek.*
import com.example.agent.rootpilot.files.*
import com.example.agent.rootpilot.model.RootPilotConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileAgentControllerTest {
    private class Files : WorkspaceAccess {
        var commits = 0
        var reads = 0
        var discarded = 0
        var conflict = false
        var blockCommit: CompletableDeferred<Unit>? = null
        override suspend fun list(path: String) = listOf(FileEntry("note.txt", false))
        override suspend fun read(path: String): String { reads++; return "original" }
        override suspend fun prepareCreate(path: String, content: String) = PreparedFileChange("preview", path, null, content)
        override suspend fun prepareEdit(path: String, oldText: String, newText: String) = PreparedFileChange("preview", path, "original", newText)
        override suspend fun discard(change: PreparedFileChange) { discarded++ }
        override suspend fun commit(change: PreparedFileChange): FileWriteReceipt {
            if (conflict) throw FileStorageException(FileErrorCode.CONFLICT)
            commits++
            blockCommit?.await()
            return FileWriteReceipt("backup", "写入已回读确认")
        }
    }
    private class Model(private val replies: List<ToolChatResult>) : DeepSeekToolChatClient {
        val requests = mutableListOf<List<ToolChatTurn>>()
        override suspend fun streamToolChat(config: RootPilotConfig, messages: List<ToolChatTurn>, tools: List<JsonObject>,
            effort: ThinkingEffort, onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult {
            requests += messages.toList()
            return replies[requests.lastIndex]
        }
    }
    private fun edit() = ToolChatResult.Success("", "reasoning", listOf(ChatToolCall("call1", "edit_file",
        """{"path":"note.txt","old_text":"original","new_text":"updated"}""")))
    private fun done() = ToolChatResult.Success("完成", "", emptyList())
    private fun controller(files: Files, model: Model) = FileAgentController(files, model).apply {
        updateWorkspace("Test directory", emptyList()); setEnabled(true)
    }

    @Test fun nothingIsReadWhenDisabled() = runTest {
        val files = Files(); val model = Model(emptyList())
        val agent = FileAgentController(files, model)
        assertTrue(agent.run(RootPilotConfig(), listOf(ToolChatTurn("user", "test")), ThinkingEffort.HIGH) {} is DeepSeekActionResult.Failure)
        assertEquals(0, files.reads); assertTrue(model.requests.isEmpty())
    }

    @Test fun previewMustBeConfirmedOnceAndReasoningIsReturnedToModel() = runTest {
        val files = Files(); val model = Model(listOf(edit(), done())); val agent = controller(files, model)
        val job = async { agent.run(RootPilotConfig(), listOf(ToolChatTurn("user", "edit")), ThinkingEffort.HIGH) {} }
        runCurrent()
        assertEquals("original", agent.state.value.pending?.before)
        assertEquals(0, files.commits)
        agent.decide("stale", true); runCurrent(); assertEquals(0, files.commits)
        agent.decide("preview", true); agent.decide("preview", true)
        assertTrue(job.await() is DeepSeekActionResult.Success)
        assertEquals(1, files.commits)
        assertEquals("reasoning", model.requests.last().first { it.toolCalls.isNotEmpty() }.reasoningContent)
        assertEquals("call1", model.requests.last().last().toolCallId)
        assertFalse(agent.state.value.busy); assertNull(agent.state.value.pending)
    }

    @Test fun rejectionEndsRunWithoutAnotherModelRequestOrWrite() = runTest {
        val files = Files(); val model = Model(listOf(edit())); val agent = controller(files, model)
        val job = async { agent.run(RootPilotConfig(), listOf(ToolChatTurn("user", "edit")), ThinkingEffort.HIGH) {} }
        runCurrent(); agent.decide("preview", false)
        assertTrue(job.await() is DeepSeekActionResult.Failure)
        assertEquals(0, files.commits); assertEquals(1, model.requests.size)
        assertEquals(1, files.discarded)
    }

    @Test fun cancellationRemovesPreviewAndLateApprovalCannotWrite() = runTest {
        val files = Files(); val agent = controller(files, Model(listOf(edit())))
        val job = async { agent.run(RootPilotConfig(), listOf(ToolChatTurn("user", "edit")), ThinkingEffort.HIGH) {} }
        runCurrent(); job.cancelAndJoin(); agent.decide("preview", true)
        assertEquals(0, files.commits); assertNull(agent.state.value.pending); assertFalse(agent.state.value.busy)
        assertEquals(1, files.discarded)
    }

    @Test fun conflictStopsWithoutRetry() = runTest {
        val files = Files().apply { conflict = true }; val model = Model(listOf(edit())); val agent = controller(files, model)
        val job = async { agent.run(RootPilotConfig(), listOf(ToolChatTurn("user", "edit")), ThinkingEffort.HIGH) {} }
        runCurrent(); agent.decide("preview", true)
        assertTrue(job.await() is DeepSeekActionResult.Failure)
        assertEquals(0, files.commits); assertEquals(1, model.requests.size)
    }

    @Test fun stoppingAnInFlightWriteWaitsForReceiptAndDoesNotCallModelAgain() = runTest {
        val release = CompletableDeferred<Unit>()
        val files = Files().apply { blockCommit = release }; val model = Model(listOf(edit())); val agent = controller(files, model)
        val job = async { agent.run(RootPilotConfig(), listOf(ToolChatTurn("user", "edit")), ThinkingEffort.HIGH) {} }
        runCurrent(); agent.decide("preview", true); runCurrent(); job.cancel(); runCurrent()
        assertTrue(agent.state.value.busy); assertEquals(1, files.commits)
        release.complete(Unit); job.join()
        assertFalse(agent.state.value.busy); assertEquals(1, model.requests.size)
    }

    @Test fun unknownToolsAndExtraArgumentsNeverReachStorage() = runTest {
        listOf(ChatToolCall("1", "shell", """{"path":"x"}"""),
            ChatToolCall("1", "read_file", """{"path":"x","extra":"y"}""")).forEach { call ->
            val files = Files(); val agent = controller(files, Model(listOf(ToolChatResult.Success("", "", listOf(call)))))
            assertTrue(agent.run(RootPilotConfig(), listOf(ToolChatTurn("user", "test")), ThinkingEffort.HIGH) {} is DeepSeekActionResult.Failure)
            assertEquals(0, files.reads); assertEquals(0, files.commits)
        }
    }
}
