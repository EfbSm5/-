package com.example.agent.rootpilot.files

import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.RootPilotApiConfigStore
import com.example.agent.rootpilot.chat.FileAgentController
import com.example.agent.rootpilot.deepseek.*
import com.example.agent.rootpilot.model.RootPilotConfig
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No UI automation, selected-workspace changes or grants: only a private synthetic provider. */
@RunWith(AndroidJUnit4::class)
class FileAgentUsabilityInstrumentedTest {
    @Test fun syntheticProviderSearchAndStatWithoutAdditionalWrites() = runBlocking {
        assumeTrue(arguments().getString("fileSearchAcceptance") == "true")
        val files = seededWorkspace()
        val stat = files.stat(NAME)
        assertEquals(NAME, stat.path)
        assertFalse(stat.isDirectory)
        assertEquals("text/plain", stat.mime)
        assertEquals(ALPHA.toByteArray(Charsets.UTF_8).size.toLong(), stat.size)
        assertNull("missing_timestamp_must_stay_unknown", stat.modifiedAtMs)
        val names = files.search("", "usability", FileSearchScope.NAME)
        assertTrue(names.complete)
        assertFalse(names.truncated)
        assertEquals(listOf(NAME), names.matches.map { it.path })
        assertEquals(0, names.readBytes)
        val content = files.search("", "USABILITY_ALPHA", FileSearchScope.CONTENT)
        assertTrue(content.complete)
        assertEquals(listOf(NAME), content.matches.map { it.path })
        assertTrue(content.matches.single().snippet!!.contains("USABILITY_ALPHA"))
        assertEquals(ALPHA.toByteArray(Charsets.UTF_8).size, content.readBytes)
        assertTrue("query_must_be_literal", files.search("", ".*", FileSearchScope.CONTENT).matches.isEmpty())
        try {
            files.search("../", "usability", FileSearchScope.NAME)
            fail("parent_escape_accepted")
        } catch (error: FileStorageException) { assertEquals(FileErrorCode.INVALID_PATH, error.code) }
        assertEquals(ALPHA, files.read(NAME))
        assertEquals("readonly_search_must_not_write", 1, files.writes)
        report("synthetic_search_stat_readonly", files.writes)
        files.reportLocation()
    }

    @Test fun realModelConsumesSearchAndStatWithoutWriting() = runBlocking {
        assumeTrue(arguments().getString("liveFileSearchAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = try { RootPilotApiConfigStore.create(context).read() }
            catch (_: Exception) { throw AssertionError("saved_config_unavailable") }
        assertNotNull("saved_config_required", saved)
        assertEquals("deepseek_endpoint_required", "https://api.deepseek.com", saved!!.baseUrl.trimEnd('/'))
        val files = seededWorkspace()
        val consumed = mutableSetOf<String>()
        val scopes = mutableSetOf<String>()
        val transport = HttpDeepSeekClient(requestTimeoutMillis = 60_000)
        val client = object : DeepSeekToolChatClient {
            override suspend fun streamToolChat(config: RootPilotConfig, messages: List<ToolChatTurn>,
                tools: List<JsonObject>, effort: ThinkingEffort, onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult {
                val calls = messages.flatMap { it.toolCalls }.associateBy { it.id }
                messages.filter { it.role == "tool" }.forEach { turn ->
                    calls[turn.toolCallId]?.let { call ->
                        consumed += call.name
                        if (call.name == "search_files") scopes += Json.parseToJsonElement(call.arguments)
                            .jsonObject.getValue("scope").jsonPrimitive.content
                    }
                }
                return transport.streamToolChat(config, messages, tools, effort, onUpdate).also { result ->
                    if (result is ToolChatResult.Success) result.toolCalls.forEach { call ->
                        assertTrue("readonly_tool_scope_required", call.name in setOf("stat_file", "search_files"))
                    }
                }
            }
        }
        val controller = FileAgentController(files, client).apply {
            updateWorkspace("isolated_fixture", emptyList())
            setEnabled(true)
        }
        val result = withTimeout(180_000) {
            controller.run(saved.applyTo(RootPilotConfig()), listOf(ToolChatTurn("user",
                "这是隔离测试目录，仅执行只读操作。先调用 stat_file 查询 usability.txt 的信息，再调用 search_files 在根目录按名称字面量 usability 搜索，然后按内容字面量 USABILITY_ALPHA 搜索。每次调用后核对回执，完成三项后简短报告；时间未知时明确未知。不调用其他工具，不读取整文件、不创建或编辑文件。")), ThinkingEffort.HIGH) {}
        }
        assertTrue("search_model_did_not_complete", result is DeepSeekActionResult.Success)
        assertEquals(setOf("stat_file", "search_files"), consumed)
        assertEquals("both_search_receipts_required", setOf("name", "content"), scopes)
        assertEquals(ALPHA, files.read(NAME))
        assertEquals("readonly_model_must_not_write", 1, files.writes)
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.pending)
        report("live_search_stat_readonly", files.writes)
        files.reportLocation()
    }

    private suspend fun seededWorkspace(): FixtureWorkspace = FixtureWorkspace().also { files ->
        runApproved(files, sequence(call("create_file", """{"path":"usability.txt","content":"$ALPHA"}""")),
            "fixture_seed", null, ALPHA)
    }

    @Test fun syntheticProviderCreateEditBackupAndReject() = runBlocking {
        assumeTrue(arguments().getString("fileSyntheticAcceptance") == "true")
        val files = FixtureWorkspace()
        assertTrue(files.list().isEmpty())
        runApproved(files, sequence(call("create_file", """{"path":"usability.txt","content":"$ALPHA"}""")),
            "fixture_create", null, ALPHA)
        assertEquals(ALPHA, files.read(NAME))
        runApproved(files, sequence(call("read_file", """{"path":"usability.txt"}"""),
            call("edit_file", """{"path":"usability.txt","old_text":"$ALPHA","new_text":"$BETA"}""")),
            "fixture_edit", ALPHA, BETA)
        assertEquals(BETA, files.read(NAME))
        assertEquals(ALPHA, files.originalBackup())
        runApproved(files, sequence(call("edit_file",
            """{"path":"usability.txt","old_text":"$BETA","new_text":"REJECTED"}""")),
            "fixture_reject", BETA, "REJECTED", allowed = false)
        assertEquals(BETA, files.read(NAME))
        assertEquals(2, files.writes)
        report("synthetic_create_edit_backup_reject", files.writes)
        files.reportLocation()
    }

    @Test fun realModelCreatesReadsEditsAndRetainsBackup() = runBlocking {
        assumeTrue(arguments().getString("liveFileUsability") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = try { RootPilotApiConfigStore.create(context).read() }
            catch (_: Exception) { throw AssertionError("saved_config_unavailable") }
        assertNotNull("saved_config_required", saved)
        assertEquals("deepseek_endpoint_required", "https://api.deepseek.com", saved!!.baseUrl.trimEnd('/'))
        val files = FixtureWorkspace()
        assertTrue(files.list().isEmpty())
        val transport = HttpDeepSeekClient(requestTimeoutMillis = 60_000)
        val requestedTools = mutableListOf<String>()
        var readbackConsumed = false
        val client = object : DeepSeekToolChatClient {
            override suspend fun streamToolChat(config: RootPilotConfig, messages: List<ToolChatTurn>,
                tools: List<JsonObject>, effort: ThinkingEffort, onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult {
                messages.filter { it.role == "tool" }.forEach { turn ->
                    val receipt = Json.parseToJsonElement(turn.content) as? JsonObject
                    if (receipt?.get("untrusted_file_content")?.jsonPrimitive?.content == BETA) readbackConsumed = true
                }
                return transport.streamToolChat(config, messages, tools, effort, onUpdate).also { result ->
                    if (result is ToolChatResult.Success) requestedTools += result.toolCalls.map { it.name }
                }
            }
        }
        val config = saved.applyTo(RootPilotConfig())
        runApproved(files, client,
            "这是隔离测试目录。请先列目录，再创建 usability.txt，内容严格为 $ALPHA，不加换行。不操作其他文件，收到写入回执后简短报告完成。",
            null, ALPHA, config = config)
        assertEquals(ALPHA, files.read(NAME))
        report("live_create_readback", files.writes)
        runApproved(files, client,
            "请先读取 usability.txt，再用 edit_file 将唯一的 $ALPHA 替换为 $BETA，不加换行，不操作其他文件。写入后再次读取 usability.txt，收到回执并核对后简短报告完成。",
            ALPHA, BETA, config = config)
        assertEquals(BETA, files.read(NAME))
        assertEquals(ALPHA, files.originalBackup())
        assertEquals(2, files.writes)
        assertEquals(listOf(NAME), files.list().map { it.name })
        assertTrue("list_tool_required", "list_files" in requestedTools)
        assertTrue("read_before_edit_required", requestedTools.indexOf("read_file") in 0 until requestedTools.indexOf("edit_file"))
        assertTrue("post_edit_readback_receipt_required", readbackConsumed)
        report("live_edit_readback_backup", files.writes)
        files.reportLocation()
    }

    private suspend fun runApproved(
        files: FixtureWorkspace,
        client: DeepSeekToolChatClient,
        prompt: String,
        before: String?,
        after: String,
        allowed: Boolean = true,
        config: RootPilotConfig = RootPilotConfig(),
    ) = coroutineScope {
        val controller = FileAgentController(files, client).apply {
            updateWorkspace("isolated_fixture", emptyList())
            setEnabled(true)
        }
        var approvals = 0
        var scopeViolation = false
        val watcher = launch {
            controller.state.collect { state ->
                state.pending?.let { change ->
                    val exact = approvals == 0 && change.path == NAME && change.before == before && change.after == after
                    if (!exact) scopeViolation = true
                    approvals++
                    controller.decide(change.id, allowed && exact)
                }
            }
        }
        try {
            val result = withTimeout(180_000) {
                controller.run(config, listOf(ToolChatTurn("user", prompt)), ThinkingEffort.HIGH) {}
            }
            assertFalse("write_outside_fixture_scope", scopeViolation)
            assertEquals("exactly_one_preview_required", 1, approvals)
            if (allowed) assertTrue("file_model_did_not_complete", result is DeepSeekActionResult.Success)
            else assertTrue("rejected_write_must_stop", result is DeepSeekActionResult.Failure)
            assertFalse("controller_busy_after_return", controller.state.value.busy)
            assertNull("preview_remaining", controller.state.value.pending)
        } finally {
            watcher.cancelAndJoin()
        }
    }

    private fun call(name: String, args: String) = ToolChatResult.Success("", "fixture_reasoning",
        listOf(ChatToolCall(UUID.randomUUID().toString(), name, args)))

    private fun sequence(vararg responses: ToolChatResult) = object : DeepSeekToolChatClient {
        private val pending = ArrayDeque(responses.toList() + ToolChatResult.Success("fixture_complete", "", emptyList()))
        override suspend fun streamToolChat(config: RootPilotConfig, messages: List<ToolChatTurn>,
            tools: List<JsonObject>, effort: ThinkingEffort, onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult =
            pending.removeFirst()
    }

    private class FixtureWorkspace : WorkspaceAccess {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(context.cacheDir, "rootpilot-file-usability-${UUID.randomUUID()}").also {
            check(!it.exists() && it.mkdir()) { "fixture_directory_unavailable" }
        }
        private val provider = FixtureProvider(File(directory, NAME)).also {
            // DocumentsProvider validates this metadata even for an unregistered wrapped instance.
            it.attachInfo(context, ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = "android.permission.MANAGE_DOCUMENTS"
            })
        }
        private val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        private val access = SafTreeAccess(ContentResolver.wrap(provider), tree)
        private val backups = FileBackupStore(File(directory, "backups"))
        private val selection = UUID.randomUUID().toString()
        var writes = 0
            private set
        private val engine = FileChangeEngine(object : FileChangeBackend {
            override fun snapshot(path: String, allowMissing: Boolean): FileSnapshot {
                requireName(path)
                val resolved = access.resolve(path, allowMissing)
                access.requireWritable(resolved)
                return FileSnapshot(selection, resolved.chain, resolved.node?.let(access::read))
            }
            override fun write(path: String, expected: FileSnapshot, content: ByteArray) {
                requireName(path)
                access.write(path, expected, content)
                writes++
            }
            override fun backup(id: String, label: String, bytes: ByteArray) = backups.save(id, label, bytes)
        })

        private fun requireName(path: String) {
            if (path != NAME) fail(FileErrorCode.INVALID_PATH)
        }
        override suspend fun list(path: String) = withContext(Dispatchers.IO) {
            if (path.isNotEmpty()) fail(FileErrorCode.INVALID_PATH)
            access.children(access.root()).map { FileEntry(it.name, it.directory) }
        }
        override suspend fun read(path: String): String = withContext(Dispatchers.IO) {
            requireName(path)
            access.read(access.resolve(path).node ?: fail(FileErrorCode.NOT_FOUND))
        }
        override suspend fun stat(path: String): FileStat = withContext(Dispatchers.IO) {
            access.stat(path)
        }
        override suspend fun search(path: String, query: String, scope: FileSearchScope): FileSearchResult =
            withContext(Dispatchers.IO) {
                access.search(path, query, scope)
            }
        override suspend fun prepareCreate(path: String, content: String) = withContext(Dispatchers.IO) {
            requireName(path); engine.prepareCreate(path, content)
        }
        override suspend fun prepareEdit(path: String, oldText: String, newText: String) = withContext(Dispatchers.IO) {
            requireName(path); engine.prepareEdit(path, oldText, newText)
        }
        override suspend fun discard(change: PreparedFileChange) = withContext(Dispatchers.IO) { engine.discard(change) }
        override suspend fun commit(change: PreparedFileChange) = withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            engine.commit(change) { job.ensureActive() }
        }
        suspend fun originalBackup() = withContext(Dispatchers.IO) {
            backups.bytes(backups.list().single().id).toString(Charsets.UTF_8)
        }
        fun reportLocation() = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("syntheticArtifactDirectory", directory.absolutePath)
        })
    }

    /** Unregistered, process-local DocumentsProvider exposes exactly one generated test file. */
    private class FixtureProvider(private val file: File) : DocumentsProvider() {
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            require(documentId == "root" || documentId == "file" && file.isFile) { "fixture_document_unknown" }
            return cursor(projection).apply { append(documentId) }
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            require(parentDocumentId == "root") { "fixture_parent_unknown" }
            return cursor(projection).apply { if (file.isFile) append("file") }
        }
        override fun isChildDocument(parentDocumentId: String, documentId: String) =
            parentDocumentId == "root" && documentId == "file" && file.isFile
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            require(parentDocumentId == "root" && mimeType == "text/plain" && displayName == NAME) { "fixture_create_scope" }
            check(file.createNewFile()) { "fixture_document_exists" }
            return "file"
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            require(documentId == "file" && file.isFile && mode in setOf("r", "wt")) { "fixture_open_scope" }
            signal?.throwIfCanceled()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
        private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE))
        private fun MatrixCursor.append(id: String) {
            val values = mapOf<String, Any>(
                Document.COLUMN_DOCUMENT_ID to id,
                Document.COLUMN_DISPLAY_NAME to if (id == "root") "isolated_fixture" else NAME,
                Document.COLUMN_MIME_TYPE to if (id == "root") Document.MIME_TYPE_DIR else "text/plain",
                Document.COLUMN_FLAGS to if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE,
                Document.COLUMN_SIZE to if (id == "root") 0L else file.length(),
            )
            addRow(columnNames.map { values[it] }.toTypedArray())
        }
    }

    private fun report(stage: String, writes: Int) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("fileStage", stage); putInt("confirmedWrites", writes) })
    private fun arguments() = InstrumentationRegistry.getArguments()
    private companion object {
        const val AUTHORITY = "rootpilot.usability.synthetic"
        const val NAME = "usability.txt"
        const val ALPHA = "ROOTPILOT_USABILITY_ALPHA"
        const val BETA = "ROOTPILOT_USABILITY_BETA"
    }
}
