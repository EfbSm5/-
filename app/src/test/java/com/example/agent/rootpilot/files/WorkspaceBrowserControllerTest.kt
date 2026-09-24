package com.example.agent.rootpilot.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceBrowserControllerTest {
    private class Files : WorkspaceAccess {
        val folder = FileEntry("notes", true)
        val file = FileEntry("a.txt", false)
        val lists = mutableListOf<String>()
        val reads = mutableListOf<String>()
        var failure: Exception? = null
        var blockedRead: CompletableDeferred<String>? = null
        override suspend fun list(path: String): List<FileEntry> {
            lists += path
            failure?.let { throw it }
            return if (path.isEmpty()) listOf(file, folder) else listOf(file)
        }
        override suspend fun read(path: String): String {
            reads += path
            failure?.let { throw it }
            return blockedRead?.let { withContext(NonCancellable) { it.await() } } ?: "中文正文\n"
        }
        override suspend fun prepareCreate(path: String, content: String): PreparedFileChange = error("No writes")
        override suspend fun prepareEdit(path: String, oldText: String, newText: String): PreparedFileChange = error("No writes")
        override suspend fun discard(change: PreparedFileChange): Unit = error("No writes")
        override suspend fun commit(change: PreparedFileChange): FileWriteReceipt = error("No writes")
    }

    @Test fun explicitOpenListsDirectoriesFirstAndReadsOnlySelectedFile() = runTest {
        val files = Files()
        val browser = WorkspaceBrowserController(this, files)
        assertTrue(files.lists.isEmpty())
        browser.open(); runCurrent()
        assertTrue(files.reads.isEmpty())
        assertSame(files.folder, browser.state.value.entries.first())
        browser.select(files.folder); runCurrent()
        assertEquals("notes", browser.state.value.path)
        browser.select(files.file); runCurrent()
        assertEquals(listOf("notes/a.txt"), files.reads)
        assertEquals("中文正文\n", browser.state.value.text)
        browser.back()
        assertNull(browser.state.value.text)
        browser.back(); runCurrent()
        assertEquals("", browser.state.value.path)
    }

    @Test fun staleOrInventedEntryCannotTriggerRead() = runTest {
        val files = Files(); val browser = WorkspaceBrowserController(this, files)
        browser.open(); runCurrent()
        browser.select(FileEntry("../outside", false))
        browser.select(FileEntry("a.txt", false)); runCurrent()
        assertTrue(files.reads.isEmpty())
        browser.close(); browser.select(files.file); runCurrent()
        assertFalse(browser.state.value.visible)
        assertTrue(files.reads.isEmpty())
    }

    @Test fun closeAndReopenDiscardsLateProviderContent() = runTest {
        val files = Files(); val browser = WorkspaceBrowserController(this, files)
        val deferred = CompletableDeferred<String>()
        files.blockedRead = deferred
        browser.open(); runCurrent()
        browser.select(files.file); runCurrent()
        assertTrue(browser.state.value.busy)
        browser.close()
        assertEquals(WorkspaceBrowserState(), browser.state.value)
        browser.open(); runCurrent()
        deferred.complete("late private content"); runCurrent()
        assertTrue(browser.state.value.visible)
        assertNull(browser.state.value.text)
        assertNull(browser.state.value.previewPath)
        assertFalse(browser.state.value.busy)
    }

    @Test fun errorsAreSanitizedAndRefreshCanRecover() = runTest {
        val files = Files(); files.failure = IllegalStateException("secret from provider")
        val browser = WorkspaceBrowserController(this, files)
        browser.open(); runCurrent()
        assertFalse(browser.state.value.error!!.contains("secret"))
        assertTrue(browser.state.value.entries.isEmpty())
        files.failure = null
        browser.refresh(); runCurrent()
        assertNull(browser.state.value.error)
        browser.select(files.file); runCurrent()
        assertFalse(browser.state.value.toString().contains("中文"))
        browser.close()
        assertNull(browser.state.value.text)
        assertTrue(browser.state.value.entries.isEmpty())
    }

    @Test fun largeFileDoesNotRetainOldPreviewAndBackIsAvailable() = runTest {
        val files = Files(); val browser = WorkspaceBrowserController(this, files)
        browser.open(); runCurrent()
        browser.select(files.file); runCurrent()
        browser.back()
        files.failure = FileStorageException(FileErrorCode.TOO_LARGE)
        browser.select(files.file); runCurrent()
        assertNull(browser.state.value.text)
        assertEquals("文件超过 64 KiB，无法预览", browser.state.value.error)
        browser.back()
        assertNull(browser.state.value.previewPath)
        assertNull(browser.state.value.error)
    }
}
