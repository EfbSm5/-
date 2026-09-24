package com.example.agent.rootpilot.files

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-process metadata-only provider; no registered authority, user grant, disk document or device UI. */
@RunWith(AndroidJUnit4::class)
class SafTreeAccessInstrumentedTest {
    private class FixtureProvider : ContentProvider() {
        val queries = mutableListOf<String>()
        var duplicate = false
        var loading = false
        var virtual = false
        var count = 1
        var childSupported = true
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val id = DocumentsContract.getDocumentId(uri)
            val children = uri.lastPathSegment == "children"
            queries += if (children) "children:$id" else "document:$id"
            val columns = projection ?: error("projection required")
            return MatrixCursor(columns).apply {
                fun row(docId: String, name: String, directory: Boolean) {
                    val flags = if (virtual && !directory) Document.FLAG_VIRTUAL_DOCUMENT else
                        if (directory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
                    val values = mapOf<String, Any>(Document.COLUMN_DOCUMENT_ID to docId,
                        Document.COLUMN_DISPLAY_NAME to name,
                        Document.COLUMN_MIME_TYPE to if (directory) Document.MIME_TYPE_DIR else "text/plain",
                        Document.COLUMN_FLAGS to flags, Document.COLUMN_SIZE to 0L)
                    addRow(columns.map { values[it] }.toTypedArray())
                }
                when {
                    !children -> row("root", "fixture", true)
                    id == "root" -> row("folder", "folder", true)
                    id == "folder" -> {
                        repeat(this@FixtureProvider.count) { index -> row("file$index", if (index == 0) "note.txt" else "n$index", false) }
                        if (duplicate) row("duplicate", "note.txt", false)
                    }
                }
                if (loading) extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            }
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            if (!childSupported) throw UnsupportedOperationException()
            return Bundle().apply { putBoolean("result", true) }
        }
        override fun getType(uri: Uri) = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No writes in fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No deletes")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No writes")
    }

    private fun access(provider: FixtureProvider): SafTreeAccess {
        provider.attachInfo(InstrumentationRegistry.getInstrumentation().targetContext,
            ProviderInfo().apply { authority = "rootpilot.files.fixture" })
        return SafTreeAccess(ContentResolver.wrap(provider),
            DocumentsContract.buildTreeDocumentUri("rootpilot.files.fixture", "root"))
    }

    @Test fun resolvesEveryLevelAgainRatherThanReusingDocumentUri() {
        val provider = FixtureProvider()
        val tree = access(provider)
        assertEquals(listOf("root", "folder", "file0"), tree.resolve("folder/note.txt").chain)
        tree.resolve("folder/note.txt")
        assertEquals(List(2) { listOf("document:root", "children:root", "children:folder") }.flatten(), provider.queries)
    }

    @Test fun duplicateNamesOversizedListingsAndIncompleteProvidersReject() {
        val duplicate = FixtureProvider().apply { this.duplicate = true }
        assertEquals(FileErrorCode.AMBIGUOUS, assertThrows(FileStorageException::class.java) {
            access(duplicate).resolve("folder/note.txt")
        }.code)
        val large = FixtureProvider().apply { count = 101 }
        assertEquals(FileErrorCode.TOO_MANY_ENTRIES, assertThrows(FileStorageException::class.java) {
            access(large).resolve("folder/note.txt")
        }.code)
        listOf(FixtureProvider().apply { loading = true }, FixtureProvider().apply { virtual = true },
            FixtureProvider().apply { childSupported = false }).forEach { provider ->
            assertEquals(FileErrorCode.UNSUPPORTED_PROVIDER, assertThrows(FileStorageException::class.java) {
                access(provider).resolve("folder/note.txt")
            }.code)
        }
    }
}
