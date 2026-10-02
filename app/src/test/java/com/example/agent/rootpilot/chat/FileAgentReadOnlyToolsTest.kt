package com.example.agent.rootpilot.chat

import com.example.agent.rootpilot.deepseek.ChatToolCall
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekToolChatClient
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.deepseek.ThinkingEffort
import com.example.agent.rootpilot.deepseek.ToolChatResult
import com.example.agent.rootpilot.deepseek.ToolChatTurn
import com.example.agent.rootpilot.files.*
import com.example.agent.rootpilot.model.RootPilotConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileAgentReadOnlyToolsTest {
    private class Files : WorkspaceAccess {
        val stats = mutableListOf<String>()
        val searches = mutableListOf<Triple<String, String, FileSearchScope>>()
        var writes = 0
        var oldReads = 0
        var error: FileErrorCode? = null
        var release: CompletableDeferred<Unit>? = null
        var searchResult = FileSearchResult(listOf(FileSearchMatch("notes/a.txt", false, "literal a.*[b]?")),
            listOf(FileSearchOmission("binary", FileSearchOmissionReason.NOT_TEXT)), 3, 4, 14, false)
        override suspend fun stat(path: String): FileStat {
            stats += path; error?.let(::fail)
            release?.let { withContext(NonCancellable) { it.await() } }
            return FileStat(path, path.isEmpty(), null, null, null)
        }
        override suspend fun search(path: String, query: String, scope: FileSearchScope): FileSearchResult {
            searches += Triple(path, query, scope); error?.let(::fail)
            release?.let { withContext(NonCancellable) { it.await() } }
            return searchResult
        }
        override suspend fun list(path: String): List<FileEntry> { oldReads++; error("Unexpected list") }
        override suspend fun read(path: String): String { oldReads++; error("Unexpected read") }
        override suspend fun prepareCreate(path: String, content: String): PreparedFileChange { writes++; error("Unexpected write") }
        override suspend fun prepareEdit(path: String, oldText: String, newText: String): PreparedFileChange { writes++; error("Unexpected write") }
        override suspend fun discard(change: PreparedFileChange) { writes++; error("Unexpected discard") }
        override suspend fun commit(change: PreparedFileChange): FileWriteReceipt { writes++; error("Unexpected commit") }
    }

    private class Model(private val calls: List<ChatToolCall>) : DeepSeekToolChatClient {
        val requests = mutableListOf<List<ToolChatTurn>>()
        var tools: List<JsonObject> = emptyList()
        override suspend fun streamToolChat(config: RootPilotConfig, messages: List<ToolChatTurn>, tools: List<JsonObject>,
                                            effort: ThinkingEffort, onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult {
            this.tools = tools; requests += messages.toList()
            return if (requests.size == 1) ToolChatResult.Success("", "kept reasoning", calls)
            else ToolChatResult.Success("完成只读查询", "", emptyList())
        }
    }

    private fun agent(files: WorkspaceAccess, model: Model) = FileAgentController(files, model).apply {
        updateWorkspace("Synthetic", emptyList()); setEnabled(true)
    }
    private suspend fun run(controller: FileAgentController) =
        controller.run(RootPilotConfig(), listOf(ToolChatTurn("user", "只读测试")), ThinkingEffort.HIGH) {}
    private fun call(name: String, args: String, id: String = "call1") = ChatToolCall(id, name, args)

    @Test fun statAndSearchReturnNullMetadataCoverageAndBoundedLiteralArgumentsWithoutWrites() = runTest {
        val files = Files()
        val model = Model(listOf(call("stat_file", """{"path":""}""", "stat"),
            call("search_files", """{"path":"notes","query":"a.*[b]?","scope":"content"}""", "search")))
        val controller = agent(files, model)
        assertTrue(run(controller) is DeepSeekActionResult.Success)
        assertEquals(listOf(""), files.stats)
        assertEquals(listOf(Triple("notes", "a.*[b]?", FileSearchScope.CONTENT)), files.searches)
        val results = model.requests.last().filter { it.role == "tool" }
        assertEquals(listOf("stat", "search"), results.map { it.toolCallId })
        val stat = Json.parseToJsonElement(results[0].content).jsonObject.getValue("untrusted_file_metadata").jsonObject
        assertEquals("directory", stat.getValue("type").jsonPrimitive.content)
        assertEquals(JsonNull, stat["mime"]); assertEquals(JsonNull, stat["size"]); assertEquals(JsonNull, stat["modified_at_ms"])
        val search = Json.parseToJsonElement(results[1].content).jsonObject
        assertFalse(search.getValue("complete").jsonPrimitive.boolean)
        assertTrue(search.getValue("cannot_prove_no_match").jsonPrimitive.boolean)
        assertFalse(search.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(3, search.getValue("omission_count").jsonPrimitive.int)
        assertEquals(2, search.getValue("omission_details_omitted").jsonPrimitive.int)
        assertEquals("NOT_TEXT", search.getValue("untrusted_omissions").jsonArray.single().jsonObject.getValue("reason").jsonPrimitive.content)
        assertEquals("literal a.*[b]?", search.getValue("untrusted_search_results").jsonArray.single().jsonObject.getValue("snippet").jsonPrimitive.content)
        assertEquals("kept reasoning", model.requests.last().first { it.toolCalls.isNotEmpty() }.reasoningContent)
        assertNull(controller.state.value.pending); assertFalse(controller.state.value.busy)
        assertEquals(0, files.writes); assertEquals(0, files.oldReads)
    }

    @Test fun nameScopeReachesBackendAndTruncationIsExplicit() = runTest {
        val files = Files().apply { searchResult = FileSearchResult(emptyList(),
            listOf(FileSearchOmission("deep", FileSearchOmissionReason.DEPTH_LIMIT)), 1, 5, 0, true) }
        val model = Model(listOf(call("search_files", """{"path":"","query":"[a]*","scope":"name"}""")))
        assertTrue(run(agent(files, model)) is DeepSeekActionResult.Success)
        assertEquals(listOf(Triple("", "[a]*", FileSearchScope.NAME)), files.searches)
        val result = Json.parseToJsonElement(model.requests.last().last().content).jsonObject
        assertTrue(result.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(result.getValue("cannot_prove_no_match").jsonPrimitive.boolean)
        assertEquals(0, files.writes)
    }

    @Test fun schemaAndPromptStateLiteralScopesBoundsUnknownMetadataAndUntrustedIncompleteResults() = runTest {
        val files = Files(); val model = Model(emptyList())
        assertTrue(run(agent(files, model)) is DeepSeekActionResult.Success)
        val schemas = model.tools.associate { schema ->
            val function = schema.getValue("function").jsonObject
            function.getValue("name").jsonPrimitive.content to function.getValue("parameters").jsonObject
        }
        val stat = schemas.getValue("stat_file")
        assertEquals(setOf("path"), stat.getValue("properties").jsonObject.keys)
        assertFalse(stat.getValue("additionalProperties").jsonPrimitive.boolean)
        val search = schemas.getValue("search_files")
        assertEquals(setOf("path", "query", "scope"), search.getValue("properties").jsonObject.keys)
        assertEquals(setOf("path", "query", "scope"), search.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse(search.getValue("additionalProperties").jsonPrimitive.boolean)
        val properties = search.getValue("properties").jsonObject
        assertEquals(listOf("name", "content"), properties.getValue("scope").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, properties.getValue("query").jsonObject.getValue("minLength").jsonPrimitive.int)
        assertEquals(128, properties.getValue("query").jsonObject.getValue("maxLength").jsonPrimitive.int)
        val prompt = model.requests.single().first().content
        listOf("untrusted DATA", "literal", "cannot prove no match", "null", "256 KiB", "non-atomic").forEach {
            assertTrue(prompt.contains(it))
        }
        assertEquals(0, files.writes)
    }

    @Test fun strictParameterWhitelistAndStringTypesRejectBeforeStorage() = runTest {
        listOf(
            call("stat_file", """{"path":"a","extra":"x"}"""),
            call("stat_file", "{}"),
            call("stat_file", """{"path":null}"""),
            call("search_files", """{"path":"","query":"q","scope":"name","limit":"99"}"""),
            call("search_files", """{"path":"","query":"q"}"""),
            call("search_files", """{"path":"","query":1,"scope":"name"}"""),
            call("search_files", """{"path":"","query":"q","scope":["name"]}"""),
            call("search_files", """{"path":"","query":"q","scope":"NAME"}"""),
            call("search_files", """{"path":"","query":"q","scope":"regex"}"""),
            call("search_files", """{"path":"","query":"","scope":"name"}"""),
            call("search_files", buildJsonObject { put("path", ""); put("query", "q".repeat(129)); put("scope", "name") }.toString()),
            call("search_files", "not json"),
        ).forEach { tool ->
            val files = Files(); val model = Model(listOf(tool))
            assertTrue(run(agent(files, model)) is DeepSeekActionResult.Failure)
            assertTrue(files.stats.isEmpty()); assertTrue(files.searches.isEmpty())
            assertEquals(0, files.writes); assertEquals(1, model.requests.size)
        }
    }

    @Test fun invalidRelativePathsNeverReachStatOrSearchStorage() = runTest {
        listOf("../secret", "/absolute", "content://outside/tree", "a//b", "a%2fb", "a\\b").forEach { path ->
            listOf("stat_file", "search_files").forEach { name ->
                val args = buildJsonObject {
                    put("path", path)
                    if (name == "search_files") { put("query", "q"); put("scope", "name") }
                }.toString()
                val files = Files(); val model = Model(listOf(call(name, args)))
                assertTrue(run(agent(files, model)) is DeepSeekActionResult.Failure)
                assertTrue(files.stats.isEmpty()); assertTrue(files.searches.isEmpty()); assertEquals(0, files.writes)
            }
        }
    }

    @Test fun storageSelectionAndConflictErrorsStopWithoutReportingNoMatchOrCallingModelAgain() = runTest {
        listOf(FileErrorCode.NOT_SELECTED, FileErrorCode.STORAGE_FAILED, FileErrorCode.CONFLICT).forEach { code ->
            listOf(call("stat_file", """{"path":"a.txt"}"""),
                call("search_files", """{"path":"","query":"q","scope":"name"}""")).forEach { tool ->
                val files = Files().apply { error = code }; val model = Model(listOf(tool))
                val controller = agent(files, model)
                assertTrue(run(controller) is DeepSeekActionResult.Failure)
                assertEquals(1, model.requests.size); assertEquals(0, files.writes)
                assertNull(controller.state.value.pending); assertFalse(controller.state.value.busy)
            }
        }
    }

    @Test fun cancellationDropsLateReadOnlyResultsAndDoesNotSendAnotherModelRequest() = runTest {
        listOf(call("stat_file", """{"path":"a.txt"}"""),
            call("search_files", """{"path":"","query":"q","scope":"content"}""")).forEach { tool ->
            val release = CompletableDeferred<Unit>()
            val files = Files().apply { this.release = release }; val model = Model(listOf(tool))
            val controller = agent(files, model)
            val job = async { run(controller) }
            runCurrent(); assertTrue(controller.state.value.busy)
            job.cancel(); runCurrent(); assertTrue(controller.state.value.busy)
            release.complete(Unit); job.join()
            assertTrue(job.isCancelled); assertEquals(1, model.requests.size)
            assertFalse(controller.state.value.busy); assertNull(controller.state.value.pending); assertEquals(0, files.writes)
        }
    }

    @Test fun disabledAgentMakesNoReadOnlyRequests() = runTest {
        val files = Files(); val model = Model(listOf(call("stat_file", """{"path":""}""")))
        val controller = FileAgentController(files, model).apply { updateWorkspace("Synthetic", emptyList()) }
        assertTrue(run(controller) is DeepSeekActionResult.Failure)
        assertTrue(model.requests.isEmpty()); assertTrue(files.stats.isEmpty()); assertTrue(files.searches.isEmpty())
    }

    @Test fun legacyFakeDefaultsFailExplicitlyInsteadOfPretendingAnEmptyResult() = runTest {
        val legacy = object : WorkspaceAccess {
            override suspend fun list(path: String) = emptyList<FileEntry>()
            override suspend fun read(path: String) = ""
            override suspend fun prepareCreate(path: String, content: String): PreparedFileChange = error("No writes")
            override suspend fun prepareEdit(path: String, oldText: String, newText: String): PreparedFileChange = error("No writes")
            override suspend fun discard(change: PreparedFileChange) = Unit
            override suspend fun commit(change: PreparedFileChange): FileWriteReceipt = error("No writes")
        }
        try { legacy.stat(""); fail("Expected NOT_SUPPORTED") } catch (error: FileStorageException) {
            assertEquals(FileErrorCode.NOT_SUPPORTED, error.code)
        }
        try { legacy.search("", "q", FileSearchScope.NAME); fail("Expected NOT_SUPPORTED") } catch (error: FileStorageException) {
            assertEquals(FileErrorCode.NOT_SUPPORTED, error.code)
        }
    }
}
