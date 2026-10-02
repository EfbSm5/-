package com.example.agent.rootpilot.files

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileDescriptor
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, local-only acceptance in one previously authorized synthetic external-storage tree. */
@RunWith(AndroidJUnit4::class)
class FileSearchSafAcceptanceInstrumentedTest {
    @Test fun preflightKnownWorkspace() {
        assumeTrue(arguments().getString("fileSearchSafPreflight") == "true")
        val context = targetContext()
        val workspace = FileWorkspace(context)
        val selected = booleanProbe { workspace.selected }
        val labelMatches = booleanProbe { workspace.selectedLabel() == WORKSPACE_LABEL }
        val pointerMatches = booleanProbe { knownPointer(selectionBytes(context)) }
        val grantAvailable = booleanProbe { knownGrant(context.contentResolver) }
        report(Bundle().apply {
            putBoolean("selected_valid", selected)
            putBoolean("selected_label_matches", labelMatches)
            putBoolean("known_selection_matches", pointerMatches)
            putBoolean("known_grant_available", grantAvailable)
            putBoolean("known_workspace_available", selected && labelMatches && pointerMatches && grantAvailable)
            putBoolean("workspace_traversed", false)
            putBoolean("synthetic_documents_created", false)
        })
    }

    @Test fun realSafSearchStatAndTruncation() = runBlocking {
        assumeTrue(arguments().getString("fileSearchSafAcceptance") == WORKSPACE_LABEL)
        withTimeout(90_000) {
            val fixture = SyntheticTree(requireKnownState())
            try {
                val files = fixture.workspace
                val notes = fixture.directory("中文资料")
                val first = fixture.directory("$notes/第一层")
                val second = fixture.directory("$first/第二层")
                val literal = "a.*[b]?"
                val text = "中文开始\r\n" + "😀".repeat(90) + literal + "😀".repeat(90) + "\n中文结束"
                val note = fixture.text("$second/中文笔记[样例].txt", text)
                val decoyText = "axxb ROOTPILOT_CASE_ALPHA"
                fixture.text("$second/中文笔记样例.txt", decoyText)

                val stat = files.stat(note)
                assertEquals(note, stat.path)
                assertFalse(stat.isDirectory)
                assertEquals("text/plain", stat.mime)
                assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), stat.size)
                assertTrue("invalid_modified_time", stat.modifiedAtMs?.let { it >= 0 } != false)
                val directoryStat = files.stat(second)
                assertEquals(second, directoryStat.path)
                assertTrue(directoryStat.isDirectory)
                assertEquals(Document.MIME_TYPE_DIR, directoryStat.mime)
                assertEquals(setOf("中文笔记[样例].txt", "中文笔记样例.txt"), files.list(second).map { it.name }.toSet())
                assertEquals(text, files.read(note))

                val names = files.search(notes, "[样例]", FileSearchScope.NAME)
                assertComplete(names)
                assertEquals(listOf(note), names.matches.map { it.path })
                assertFalse(names.matches.single().isDirectory)
                assertTrue(names.matches.all { it.snippet == null })
                assertEquals(0, names.readBytes)
                val nameMiss = files.search(notes, ".*", FileSearchScope.NAME)
                assertComplete(nameMiss)
                assertTrue("name_query_must_be_literal", nameMiss.matches.isEmpty())
                assertEquals(0, nameMiss.readBytes)

                val contents = files.search(notes, literal, FileSearchScope.CONTENT)
                assertComplete(contents)
                assertEquals(listOf(note), contents.matches.map { it.path })
                val snippet = contents.matches.single().snippet
                assertNotNull("content_snippet_required", snippet)
                assertTrue(snippet!!.contains(literal))
                assertTrue(snippet.length <= FileSearchLimits.MAX_SNIPPET)
                FileRules.encode(snippet)
                assertEquals((text + decoyText).toByteArray(Charsets.UTF_8).size, contents.readBytes)
                val caseMiss = files.search(notes, "A.*[b]?", FileSearchScope.CONTENT)
                assertComplete(caseMiss)
                assertTrue("content_query_must_be_case_sensitive", caseMiss.matches.isEmpty())
                val singleFile = files.search(note, literal, FileSearchScope.CONTENT)
                assertComplete(singleFile)
                assertEquals(1, singleFile.visitedEntries)
                assertEquals(listOf(note), singleFile.matches.map { it.path })

                val depthRoot = fixture.directory("深度验证")
                var deepest = depthRoot
                repeat(FileSearchLimits.MAX_DEPTH) { index ->
                    deepest = fixture.directory("$deepest/第${index + 1}级")
                }
                val hidden = fixture.text("$deepest/隐藏目标.txt", "ROOTPILOT_DEPTH_SYNTHETIC")
                val depth = files.search(depthRoot, "隐藏目标", FileSearchScope.NAME)
                assertTruncated(depth, FileSearchOmissionReason.DEPTH_LIMIT)
                assertTrue(depth.matches.isEmpty())
                assertEquals(deepest, depth.omissions.single().path)
                assertEquals(FileSearchLimits.MAX_DEPTH + 1, depth.visitedEntries)
                assertEquals(0, depth.readBytes)
                val reachable = files.search(deepest, "隐藏目标", FileSearchScope.NAME)
                assertComplete(reachable)
                assertEquals(listOf(hidden), reachable.matches.map { it.path })

                val byteRoot = fixture.directory("字节验证")
                repeat(5) { index -> fixture.text("$byteRoot/数据${index.toString().padStart(2, '0')}.txt", "中".repeat(21_000)) }
                val bytes = files.search(byteRoot, "ROOTPILOT_ABSENT_BYTE_SENTINEL", FileSearchScope.CONTENT)
                assertTruncated(bytes, FileSearchOmissionReason.BYTE_LIMIT)
                assertTrue(bytes.matches.isEmpty())
                assertEquals(FileSearchLimits.MAX_READ_BYTES, bytes.readBytes)
                assertEquals(6, bytes.visitedEntries)

                val resultRoot = fixture.directory("结果验证")
                val resultPaths = (0..FileSearchLimits.MAX_MATCHES).map { index ->
                    fixture.text("$resultRoot/命中${index.toString().padStart(2, '0')}.txt", "ROOTPILOT_RESULT_SYNTHETIC")
                }
                val results = files.search(resultRoot, "命中", FileSearchScope.NAME)
                assertTruncated(results, FileSearchOmissionReason.RESULT_LIMIT)
                assertEquals(resultPaths.take(FileSearchLimits.MAX_MATCHES), results.matches.map { it.path })
                assertEquals(FileSearchLimits.MAX_MATCHES + 1, results.visitedEntries)
                assertEquals(0, results.readBytes)

                fixture.assertOriginalsUnchanged()
                fixture.state.assertUnchanged()
                report(Bundle().apply {
                    putBoolean("real_saf_search_stat_verified", true)
                    putBoolean("depth_truncation_verified", true)
                    putBoolean("byte_truncation_verified", true)
                    putBoolean("result_truncation_verified", true)
                    putBoolean("originals_unchanged", true)
                    putBoolean("selection_and_grants_unchanged", true)
                    putBoolean("real_selection_switch_verified", false)
                    putInt("synthetic_file_count", fixture.fileCount)
                })
            } finally {
                fixture.state.assertUnchanged()
            }
        }
    }

    @Test fun cancelsInFlightSafWorkspaceSearch() = runBlocking {
        assumeTrue(arguments().getString("fileSearchSafCancellation") == WORKSPACE_LABEL)
        withTimeout(45_000) {
            val fixture = SyntheticTree(requireKnownState())
            try {
                val path = fixture.text("取消验证.txt", "ROOTPILOT_CANCEL_SYNTHETIC 中文".repeat(100))
                val gate = ReadGate(fixture.state.resolver, fixture.document(path))
                val provider = ReadOnlyForwardingProvider(fixture.state.resolver, fixture.metadataIds(), gate)
                provider.attachInfo(fixture.state.context, ProviderInfo().apply {
                    authority = AUTHORITY
                    exported = true
                    grantUriPermissions = true
                    readPermission = "android.permission.MANAGE_DOCUMENTS"
                    writePermission = "android.permission.MANAGE_DOCUMENTS"
                })
                val wrappedResolver = ContentResolver.wrap(provider)
                val context = object : ContextWrapper(fixture.state.context) {
                    override fun getApplicationContext(): Context = this
                    override fun getContentResolver(): ContentResolver = wrappedResolver
                }
                // Only the resolver is substituted. FileWorkspace reads the real pointer and grant.
                val files = FileWorkspace(context)
                assertTrue("forwarded_workspace_grant_required", files.selected)
                val returnedResult = AtomicBoolean(false)
                supervisorScope {
                    val operation = async(Dispatchers.IO) {
                        files.search(path, "ROOTPILOT_CANCEL_SYNTHETIC", FileSearchScope.CONTENT).also {
                            returnedResult.set(true)
                        }
                    }
                    try {
                        withTimeout(10_000) { gate.entered.await() }
                        assertTrue("search_must_be_active_before_cancel", operation.isActive)
                        assertEquals(1, gate.opens.get())
                        assertTrue("real_document_must_be_open_before_cancel", gate.descriptor?.valid() == true)
                        operation.cancel()
                        gate.release.countDown()
                        withTimeout(10_000) { operation.join() }
                        assertTrue("search_must_finish_cancelled", operation.isCancelled)
                        assertFalse("cancelled_search_must_not_return_results", returnedResult.get())
                        assertFalse("cancelled_search_must_close_stream", gate.descriptor?.valid() == true)
                        try {
                            operation.await()
                            throw AssertionError("cancelled_search_returned")
                        } catch (_: CancellationException) {
                            // A real file descriptor was opened before requesting cancellation.
                        }
                    } finally {
                        operation.cancel()
                        gate.release.countDown()
                        withTimeout(10_000) { operation.join() }
                    }
                }
                fixture.assertOriginalsUnchanged()
                fixture.state.assertUnchanged()
                report(Bundle().apply {
                    putBoolean("in_flight_workspace_cancellation_verified", true)
                    putBoolean("real_document_opened_before_cancel", true)
                    putBoolean("cancelled_search_stream_closed", true)
                    putBoolean("cancellation_uses_readonly_forwarder", true)
                    putBoolean("originals_unchanged", true)
                    putBoolean("selection_and_grants_unchanged", true)
                    putBoolean("real_selection_switch_verified", false)
                })
            } finally {
                fixture.state.assertUnchanged()
            }
        }
    }

    /** Simulates a selection version change in private storage; the real tree and grant stay fixed. */
    @Test fun rejectsStaleSearchAfterPrivatePointerVersionChange() = runBlocking {
        assumeTrue(arguments().getString("fileSearchSafPrivatePointerSimulation") == WORKSPACE_LABEL)
        withTimeout(45_000) {
            val fixture = SyntheticTree(requireKnownState())
            try {
                val content = "ROOTPILOT_PRIVATE_POINTER_SYNTHETIC 中文"
                val path = fixture.text("版本验证.txt", content)
                val privateDirectory = File(fixture.state.context.cacheDir, "RootPilot-Search-Pointer-${UUID.randomUUID()}")
                assertTrue("unique_private_pointer_directory_required", privateDirectory.mkdir())
                val pointerDirectory = File(privateDirectory, "rootpilot_files")
                assertTrue("private_pointer_parent_required", pointerDirectory.mkdir())
                val pointer = AtomicFile(File(pointerDirectory, "selection"))
                val before = writePrivatePointer(pointer)
                val context = object : ContextWrapper(fixture.state.context) {
                    override fun getApplicationContext(): Context = this
                    override fun getNoBackupFilesDir(): File = privateDirectory
                }
                val files = FileWorkspace(context)
                val baseline = files.search(path, content, FileSearchScope.CONTENT)
                assertComplete(baseline)
                assertEquals(listOf(path), baseline.matches.map { it.path })
                assertEquals(content, baseline.matches.single().snippet)
                assertEquals(content.toByteArray(Charsets.UTF_8).size, baseline.readBytes)
                val gate = ReturnDispatchGate()
                val returnedResult = AtomicBoolean(false)
                supervisorScope {
                    val operation = async(gate) {
                        try {
                            files.search(path, content, FileSearchScope.CONTENT)
                            returnedResult.set(true)
                            null
                        } catch (error: FileStorageException) { error.code }
                    }
                    try {
                        withTimeout(10_000) { gate.ioReturned.await() }
                        assertEquals("only_start_and_io_return_dispatch_expected", 2, gate.dispatches.get())
                        assertTrue("search_must_remain_active_at_return_gate", operation.isActive)
                        assertFalse("caller_result_must_still_be_pending", returnedResult.get())
                        assertTrue("private_pointer_changed_before_io_return", before.contentEquals(selectionBytes(context)))
                        val after = writePrivatePointer(pointer)
                        assertFalse("private_selection_version_must_change", before.contentEquals(after))
                        assertTrue("same_known_tree_required", knownPointer(after))
                        fixture.state.assertUnchanged()
                        gate.release()
                        assertEquals(FileErrorCode.CONFLICT, withTimeout(10_000) { operation.await() })
                        assertFalse("stale_result_must_not_escape", returnedResult.get())
                    } finally {
                        gate.release()
                        operation.cancel()
                        withContext(NonCancellable) { withTimeout(10_000) { operation.join() } }
                    }
                }
                val fresh = files.search(path, content, FileSearchScope.CONTENT)
                assertComplete(fresh)
                assertEquals(baseline.readBytes, fresh.readBytes)
                assertEquals(listOf(path), fresh.matches.map { it.path })
                fixture.assertOriginalsUnchanged()
                fixture.state.assertUnchanged()
                report(Bundle().apply {
                    putBoolean("simulation_private_pointer", true)
                    putBoolean("io_return_dispatch_gated", true)
                    putBoolean("user_selection_and_grants_unchanged", true)
                    putBoolean("stale_result_rejected", true)
                    putBoolean("real_selection_switch_verified", false)
                    putString("private_pointer_fixture", privateDirectory.name)
                })
            } finally {
                fixture.state.assertUnchanged()
            }
        }
    }

    /** The user changes the actual picker selection while a completed local read awaits dispatch. */
    @Test fun rejectsStaleSearchAfterManualDirectorySelection() = runBlocking {
        assumeTrue(arguments().getString("fileSearchSafManualSwitch") == WORKSPACE_LABEL)
        val child = arguments().getString("fileSearchSafManualSwitchChild") ?: throw AssertionError("synthetic_child_required")
        assertTrue("synthetic_child_name_required", child.startsWith("RootPilot-Search-"))
        val suffix = child.removePrefix("RootPilot-Search-")
        assertEquals("synthetic_child_uuid_required", suffix, UUID.fromString(suffix).toString())
        val state = requireKnownState()
        val beforePointer = selectionBytes(state.context)
        val beforeGrants = grantSnapshot(state.resolver)
        val workspace = FileWorkspace(state.context)
        val relativePath = "中文资料/第一层/第二层/中文笔记[样例].txt"
        val oldPath = "$child/$relativePath"
        val expected = "中文开始\r\n" + "😀".repeat(90) + "a.*[b]?" + "😀".repeat(90) + "\n中文结束"
        assertTrue("existing_synthetic_fixture_required", workspace.read(oldPath) == expected)
        val childTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "$ROOT_ID/$child")
        fun childSelected(): Boolean = booleanProbe {
            val fields = FileRules.decode(selectionBytes(state.context)).split('\n')
            fields.size == 2 && UUID.fromString(fields[0]).toString() == fields[0] && Uri.parse(fields[1]) == childTree
        }
        val gate = ReturnDispatchGate()
        val returned = AtomicBoolean(false)
        supervisorScope {
            val operation = async(gate) {
                try {
                    workspace.search(oldPath, "a.*[b]?", FileSearchScope.CONTENT)
                    returned.set(true)
                    null
                } catch (error: FileStorageException) { error.code }
            }
            try {
                withTimeout(10_000) { gate.ioReturned.await() }
                assertEquals("real_io_return_gate_required", 2, gate.dispatches.get())
                state.assertUnchanged()
                report(Bundle().apply { putBoolean("ready_for_manual_directory_selection", true) })
                withTimeout(180_000) { while (!childSelected()) delay(100) }
                assertFalse("selection_version_must_change", beforePointer.contentEquals(selectionBytes(state.context)))
                assertTrue("exact_synthetic_child_required", workspace.selectedLabel() == child)
                val expectedGrants = beforeGrants.filterNot { it.first == knownTree() }.toSet() + Triple(childTree, true, true)
                assertTrue("only_requested_grants_may_change", expectedGrants == grantSnapshot(state.resolver))
                gate.release()
                assertEquals("old_result_must_conflict", FileErrorCode.CONFLICT, withTimeout(10_000) { operation.await() })
                assertFalse("old_result_must_not_escape", returned.get())
                val fresh = workspace.search(relativePath, "a.*[b]?", FileSearchScope.CONTENT)
                assertComplete(fresh)
                assertEquals(listOf(relativePath), fresh.matches.map { it.path })
                assertTrue("synthetic_content_unchanged", workspace.read(relativePath) == expected)
                assertEquals(relativePath, workspace.stat(relativePath).path)
                try {
                    workspace.stat(oldPath)
                    throw AssertionError("old_root_relative_path_must_not_resolve")
                } catch (error: FileStorageException) { assertEquals(FileErrorCode.NOT_FOUND, error.code) }
                report(Bundle().apply {
                    putBoolean("real_user_directory_selection_verified", true)
                    putBoolean("stale_result_rejected", true)
                    putBoolean("fresh_search_stat_verified", true)
                    putBoolean("synthetic_content_unchanged", true)
                    putBoolean("no_test_selection_or_grant_mutation", true)
                })
            } finally {
                gate.release()
                operation.cancel()
                withContext(NonCancellable) { withTimeout(10_000) { operation.join() } }
            }
        }
    }

    /** The search coroutine has exactly one suspension: FileWorkspace's withContext(IO). */
    private class ReturnDispatchGate : CoroutineDispatcher() {
        val dispatches = AtomicInteger()
        val ioReturned = CompletableDeferred<Unit>()
        private val lock = Any()
        private var released = false
        private var pending: Pair<CoroutineContext, Runnable>? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val hold = synchronized(lock) {
                // Start runs on Default. The second dispatch follows completion of the real IO block.
                (dispatches.incrementAndGet() == 2 && !released).also {
                    if (it) pending = context to block
                }
            }
            if (hold) ioReturned.complete(Unit) else Dispatchers.Default.dispatch(context, block)
        }

        fun release() {
            val ready = synchronized(lock) {
                released = true
                pending.also { pending = null }
            }
            ready?.let { Dispatchers.Default.dispatch(it.first, it.second) }
        }
    }

    private fun writePrivatePointer(pointer: AtomicFile): ByteArray {
        val bytes = "${UUID.randomUUID()}\n${knownTree()}".toByteArray(Charsets.UTF_8)
        val output = pointer.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            pointer.finishWrite(output)
        } catch (_: Exception) {
            pointer.failWrite(output)
            throw AssertionError("private_pointer_write_failed")
        }
        return bytes
    }

    private class KnownState(val context: Context, private val pointer: ByteArray) {
        val resolver: ContentResolver = context.contentResolver
        private val grants = grantSnapshot(resolver)
        val workspace = FileWorkspace(context)

        fun assertUnchanged() {
            assertTrue("workspace_selection_changed", pointer.contentEquals(selectionBytes(context)))
            assertTrue("persisted_grants_changed", grants == grantSnapshot(resolver))
            assertTrue("known_workspace_grant_required", knownGrant(resolver))
        }
    }

    /** All writes are new synthetic documents; no cleanup, selection write or grant operation. */
    private class SyntheticTree(val state: KnownState) {
        val workspace = state.workspace
        private val relativeName = "RootPilot-Search-${UUID.randomUUID()}"
        private val documents = linkedMapOf<String, Uri>()
        private val originals = linkedMapOf<String, ByteArray>()
        val fileCount get() = originals.size

        init {
            state.assertUnchanged()
            val access = SafTreeAccess(state.resolver, knownTree())
            assertTrue("unique_test_directory_required", access.resolve(relativeName, allowMissing = true).node == null)
            documents[relativeName] = create(rootDocument(), relativeName, Document.MIME_TYPE_DIR)
            report(Bundle().apply { putString("test_subdirectory", relativeName) })
        }

        fun directory(relativePath: String): String {
            val path = scopedPath(relativePath)
            createAt(path, Document.MIME_TYPE_DIR)
            return path
        }

        fun text(relativePath: String, content: String): String {
            val bytes = FileRules.encode(content)
            val path = scopedPath(relativePath)
            val uri = createAt(path, "text/plain")
            val empty = state.resolver.openInputStream(uri)?.use { it.read() == -1 } == true
            assertTrue("new_synthetic_document_must_be_empty", empty)
            state.resolver.openOutputStream(uri, "wt")?.use {
                it.write(bytes)
                it.flush()
            } ?: throw AssertionError("synthetic_output_unavailable")
            originals[path] = bytes
            return path
        }

        fun document(path: String): Uri = documents[path] ?: throw AssertionError("synthetic_document_unknown")

        fun metadataIds(): Set<String> = documents.values.map { DocumentsContract.getDocumentId(it) }.toSet() + ROOT_ID

        fun assertOriginalsUnchanged() {
            state.assertUnchanged()
            originals.forEach { (path, expected) ->
                val actual = state.resolver.openInputStream(document(path))?.use { it.readNBytes(FileRules.MAX_BYTES + 1) }
                    ?: throw AssertionError("synthetic_readback_unavailable")
                assertArrayEquals("synthetic_original_changed", expected, actual)
            }
        }

        private fun createAt(path: String, mime: String): Uri {
            state.assertUnchanged()
            assertTrue("unique_synthetic_subtree_required", path.startsWith("$relativeName/"))
            FileRules.segments(path)
            assertFalse("synthetic_document_already_created", documents.containsKey(path))
            val parent = document(path.substringBeforeLast('/'))
            return create(parent, path.substringAfterLast('/'), mime).also { documents[path] = it }
        }

        private fun scopedPath(path: String): String = if (path.startsWith("$relativeName/")) path else "$relativeName/$path"

        private fun create(parent: Uri, name: String, mime: String): Uri {
            assertEquals(1, FileRules.segments(name).size)
            val created = DocumentsContract.createDocument(state.resolver, parent, mime, name)
                ?: throw AssertionError("synthetic_document_create_failed")
            assertTrue("external_provider_required", created.scheme == "content" && created.authority == AUTHORITY)
            assertTrue("synthetic_document_identity_changed",
                DocumentsContract.getDocumentId(created) == "${DocumentsContract.getDocumentId(parent)}/$name")
            assertTrue("synthetic_document_ancestry_required", DocumentsContract.isChildDocument(state.resolver, parent, created))
            return created
        }
    }

    /** Opens the actual external-provider document, then holds it until cancellation is requested. */
    private class ReadGate(private val resolver: ContentResolver, val document: Uri) {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        @Volatile var descriptor: FileDescriptor? = null

        fun open(signal: CancellationSignal?): ParcelFileDescriptor {
            val file = resolver.openFileDescriptor(document, "r", signal)
                ?: throw AssertionError("real_document_open_failed")
            var handedOff = false
            try {
                descriptor = file.fileDescriptor
                opens.incrementAndGet()
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "cancellation_gate_timeout" }
                handedOff = true
                return file
            } finally {
                if (!handedOff) file.close()
            }
        }
    }

    /** Unregistered forwarder; metadata and the single allowed read come from the real provider. */
    private class ReadOnlyForwardingProvider(
        private val resolver: ContentResolver,
        private val metadataIds: Set<String>,
        private val gate: ReadGate,
    ) : DocumentsProvider() {
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            require(documentId in metadataIds) { "synthetic_metadata_scope_required" }
            return resolver.query(documentUri(documentId), projection, null, null, null)
                ?: throw AssertionError("real_metadata_unavailable")
        }

        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            require(parentDocumentId in metadataIds) { "synthetic_parent_scope_required" }
            return resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(knownTree(), parentDocumentId),
                projection, null, null, sortOrder) ?: throw AssertionError("real_children_unavailable")
        }

        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
            require(parentDocumentId in metadataIds) { "synthetic_ancestry_scope_required" }
            require(documentId == ROOT_ID || documentId.startsWith("$ROOT_ID/")) { "known_tree_scope_required" }
            return DocumentsContract.isChildDocument(resolver, documentUri(parentDocumentId), documentUri(documentId))
        }

        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            require(documentId == DocumentsContract.getDocumentId(gate.document) && mode == "r") { "synthetic_read_scope_required" }
            return gate.open(signal)
        }
    }

    private fun requireKnownState(): KnownState {
        val context = targetContext()
        val pointer = selectionBytes(context)
        assertTrue("historically_authorized_tree_selection_required", knownPointer(pointer))
        assertTrue("historically_authorized_read_write_grant_required", knownGrant(context.contentResolver))
        val workspace = FileWorkspace(context)
        assertTrue("known_workspace_must_be_selected", workspace.selected)
        assertTrue("exact_known_workspace_label_required", workspace.selectedLabel() == WORKSPACE_LABEL)
        return KnownState(context, pointer).also { it.assertUnchanged() }
    }

    private fun assertComplete(result: FileSearchResult) {
        assertTrue("complete_search_required", result.complete)
        assertFalse("unexpected_truncation", result.truncated)
        assertTrue(result.omissions.isEmpty())
        assertEquals(0, result.omissionCount)
        assertBounded(result)
    }

    private fun assertTruncated(result: FileSearchResult, reason: FileSearchOmissionReason) {
        assertFalse("incomplete_search_must_not_prove_absence", result.complete)
        assertTrue("explicit_truncation_required", result.truncated)
        assertEquals(1, result.omissionCount)
        assertEquals(reason, result.omissions.single().reason)
        assertBounded(result)
    }

    private fun assertBounded(result: FileSearchResult) {
        assertTrue(result.visitedEntries in 1..FileSearchLimits.MAX_VISITED)
        assertTrue(result.readBytes in 0..FileSearchLimits.MAX_READ_BYTES)
        assertTrue(result.matches.size <= FileSearchLimits.MAX_MATCHES)
        assertTrue(result.omissions.size <= FileSearchLimits.MAX_OMISSION_DETAILS)
    }

    private companion object {
        const val WORKSPACE_LABEL = "RootPilot-Acceptance-20260924-1700"
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val ROOT_ID = "primary:Documents/$WORKSPACE_LABEL"

        fun knownTree(): Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
        fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(knownTree(), id)
        fun rootDocument(): Uri = documentUri(ROOT_ID)
        fun arguments(): Bundle = InstrumentationRegistry.getArguments()
        fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext
        fun report(values: Bundle) = InstrumentationRegistry.getInstrumentation().sendStatus(0, values)
        fun booleanProbe(block: () -> Boolean): Boolean = try { block() } catch (_: Exception) { false }

        fun knownGrant(resolver: ContentResolver): Boolean = resolver.persistedUriPermissions.any {
            it.uri == knownTree() && it.isReadPermission && it.isWritePermission
        }

        fun grantSnapshot(resolver: ContentResolver): Set<Triple<Uri, Boolean, Boolean>> =
            resolver.persistedUriPermissions.map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet()

        fun selectionBytes(context: Context): ByteArray = try {
            AtomicFile(File(context.noBackupFilesDir, "rootpilot_files/selection")).openRead().use {
                it.readNBytes(16 * 1024 + 1)
            }
        } catch (_: Exception) {
            throw FileStorageException(FileErrorCode.STORAGE_FAILED)
        }

        fun knownPointer(bytes: ByteArray): Boolean = booleanProbe {
            if (bytes.isEmpty() || bytes.size > 16 * 1024) return@booleanProbe false
            val fields = FileRules.decode(bytes).split('\n')
            fields.size == 2 && UUID.fromString(fields[0]).toString() == fields[0] && Uri.parse(fields[1]) == knownTree()
        }
    }
}
