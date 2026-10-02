package com.example.agent.rootpilot.files

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FileSearchEngineTest {
    private class Node(
        val name: String,
        val directory: Boolean = false,
        val bytes: ByteArray = byteArrayOf(),
        val mime: String? = if (directory) "vnd.android.document/directory" else "text/plain",
        val size: Long? = if (directory) null else bytes.size.toLong(),
        val modifiedAtMs: Long? = null,
        val children: MutableList<Node> = mutableListOf(),
    )

    private class Stream(bytes: ByteArray, private val onRead: () -> Unit) : ByteArrayInputStream(bytes) {
        var consumed = 0
        var closed = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
            if (it > 0) consumed += it
            onRead()
        }
        override fun close() { closed = true; super.close() }
    }

    private class Backend(val root: Node = Node("", directory = true)) : FileSearchBackend<Node> {
        val opens = mutableListOf<String>()
        val listed = mutableListOf<Node>()
        val streams = mutableListOf<Stream>()
        val childrenErrors = mutableMapOf<Node, FileErrorCode>()
        val readErrors = mutableMapOf<Node, FileErrorCode>()
        var resolveError: FileErrorCode? = null
        var onRead: () -> Unit = {}
        var resolves = 0
        override fun resolve(path: String): Node {
            resolves++
            resolveError?.let(::fail)
            var node = root
            for (name in FileRules.segments(path, allowRoot = true)) {
                if (!node.directory) fail(FileErrorCode.NOT_DIRECTORY)
                node = node.children.singleOrNull { it.name == name } ?: fail(FileErrorCode.NOT_FOUND)
            }
            return node
        }
        override fun describe(node: Node, path: String) = FileStat(path, node.directory, node.mime, node.size, node.modifiedAtMs)
        override fun identity(node: Node) = System.identityHashCode(node).toString()
        override fun children(node: Node): List<Pair<String, Node>> {
            listed += node
            childrenErrors[node]?.let(::fail)
            return node.children.map { it.name to it }
        }
        override fun open(node: Node, path: String): InputStream {
            opens += path
            readErrors[node]?.let(::fail)
            return Stream(node.bytes) { onRead() }.also { streams += it }
        }
        fun search(query: String, scope: FileSearchScope = FileSearchScope.CONTENT, path: String = "",
                   checkpoint: () -> Unit = {}) = FileSearchEngine(this, checkpoint).search(path, query, scope)
    }

    private fun file(name: String, text: String) = Node(name, bytes = text.toByteArray(Charsets.UTF_8))
    private fun assertCode(code: FileErrorCode, block: () -> Unit) =
        assertEquals(code, assertThrows(FileStorageException::class.java, block).code)

    @Test fun nameSearchIsLiteralCaseSensitiveAndNeverOpensContent() {
        val backend = Backend()
        val literal = "a.*[b]?"
        backend.root.children += listOf(file("$literal.txt", "hidden"), file("axxb.txt", literal),
            Node("$literal-folder", directory = true))
        val result = backend.search(literal, FileSearchScope.NAME)
        assertEquals(listOf("$literal-folder", "$literal.txt"), result.matches.map { it.path })
        assertTrue(result.matches.first().isDirectory)
        assertTrue(result.matches.all { it.snippet == null })
        assertTrue(result.complete); assertFalse(result.truncated)
        assertTrue(backend.opens.isEmpty()); assertEquals(0, result.readBytes)
        assertTrue(backend.search("A.*[b]?", FileSearchScope.NAME).matches.isEmpty())
    }

    @Test fun contentSearchIsLiteralAndReturnsOneSnippetPerFileWithinRequestedSubtree() {
        val backend = Backend()
        val folder = Node("notes", directory = true)
        folder.children += file("a.txt", "前缀 a.*[b]? 后缀 a.*[b]?")
        folder.children += file("b.txt", "axxb")
        backend.root.children += listOf(folder, file("outside.txt", "a.*[b]?"))
        val result = backend.search("a.*[b]?", path = "notes")
        assertEquals(listOf("notes/a.txt"), result.matches.map { it.path })
        assertTrue(result.matches.single().snippet!!.contains("a.*[b]?"))
        assertFalse(backend.opens.contains("outside.txt")); assertTrue(result.complete)
    }

    @Test fun filePathSearchDoesNotEnumerateOtherFiles() {
        val backend = Backend()
        backend.root.children += listOf(file("one.txt", "needle"), file("two.txt", "needle"))
        val result = backend.search("needle", path = "one.txt")
        assertEquals(listOf("one.txt"), result.matches.map { it.path })
        assertEquals(1, result.visitedEntries); assertTrue(backend.listed.isEmpty())
    }

    @Test fun invalidPathsAndQueriesFailBeforeResolvingOrReading() {
        listOf("../escape", "/absolute", "content://provider/tree/a", "a//b", "a/./b", "a\\b", "a%2fb", "a\n").forEach { path ->
            val backend = Backend()
            assertCode(FileErrorCode.INVALID_PATH) { backend.search("x", path = path) }
            assertEquals(0, backend.resolves); assertTrue(backend.opens.isEmpty())
        }
        listOf("", "x".repeat(129)).forEach { query ->
            val backend = Backend()
            assertCode(FileErrorCode.INVALID_CHANGE) { backend.search(query) }
            assertEquals(0, backend.resolves)
        }
        listOf("\uD800", "\u0000").forEach { query ->
            val backend = Backend()
            assertCode(FileErrorCode.INVALID_TEXT) { backend.search(query) }
            assertEquals(0, backend.resolves)
        }
    }

    @Test fun snippetsContainTheLiteralAndNeverSplitSurrogatePairs() {
        val backend = Backend()
        val query = "q".repeat(128)
        backend.root.children += file("long.txt", "😀".repeat(100) + query + "😀".repeat(100))
        val result = backend.search(query)
        val snippet = result.matches.single().snippet!!
        assertTrue(snippet.contains(query)); assertTrue(snippet.length <= 160)
        FileRules.encode(snippet)
        val second = Backend()
        second.root.children += file("long.txt", "😀".repeat(100) + "needle" + "😀".repeat(100))
        val shortSnippet = second.search("needle").matches.single().snippet!!
        assertTrue(shortSnippet.contains("needle")); assertTrue(shortSnippet.length <= 160)
        FileRules.encode(shortSnippet)
    }

    @Test fun depthLimitReportsUnsearchedDirectoryAndNeverVisitsItsChildren() {
        val backend = Backend()
        var parent = backend.root
        repeat(4) { index -> Node("d$index", directory = true).also { parent.children += it; parent = it } }
        parent.children += file("hidden.txt", "needle")
        val result = backend.search("needle")
        assertTrue(result.matches.isEmpty()); assertFalse(result.complete); assertTrue(result.truncated)
        assertEquals("d0/d1/d2/d3", result.omissions.single().path)
        assertEquals(FileSearchOmissionReason.DEPTH_LIMIT, result.omissions.single().reason)
        assertEquals(5, result.visitedEntries); assertFalse(backend.listed.contains(parent)); assertTrue(backend.opens.isEmpty())
    }

    @Test fun visitedLimitStopsAt500IncludingTheStartingNode() {
        val backend = Backend()
        repeat(6) { folder ->
            backend.root.children += Node("d$folder", directory = true, children =
                (0 until 100).map { file("f${it.toString().padStart(3, '0')}.txt", "") }.toMutableList())
        }
        val result = backend.search("absent", FileSearchScope.NAME)
        assertEquals(500, result.visitedEntries); assertFalse(result.complete); assertTrue(result.truncated)
        assertEquals(FileSearchOmissionReason.VISIT_LIMIT, result.omissions.single().reason)
        assertTrue(backend.opens.isEmpty())
    }

    @Test fun resultLimitStopsAfter20AndDoesNotReadRemainingFiles() {
        val backend = Backend()
        repeat(21) { backend.root.children += file("f${it.toString().padStart(2, '0')}.txt", "needle") }
        val result = backend.search("needle")
        assertEquals(20, result.matches.size); assertEquals(20, backend.opens.size)
        assertFalse(result.complete); assertTrue(result.truncated)
        assertEquals(FileSearchOmissionReason.RESULT_LIMIT, result.omissions.single().reason)
        assertTrue(backend.streams.all { it.closed })
    }

    @Test fun aggregateReadBudgetCountsUtf8BytesAndNeverConsumesAnExtraByte() {
        val backend = Backend()
        repeat(6) { backend.root.children += file("f$it.txt", "中".repeat(21_000)) }
        val result = backend.search("absent")
        assertEquals(256 * 1024, result.readBytes)
        assertEquals(result.readBytes, backend.streams.sumOf { it.consumed })
        assertEquals(5, backend.opens.size)
        assertEquals(FileSearchOmissionReason.BYTE_LIMIT, result.omissions.single().reason)
        assertFalse(result.complete); assertTrue(result.truncated)
        assertTrue(backend.streams.all { it.closed })
    }

    @Test fun nonTextMissingMimeAndKnownOversizeAreSkippedWithoutOpening() {
        val backend = Backend()
        backend.root.children += listOf(Node("binary", mime = "application/octet-stream"), Node("unknown", mime = null),
            Node("large", size = FileRules.MAX_BYTES + 1L))
        val result = backend.search("absent")
        assertTrue(result.matches.isEmpty()); assertFalse(result.complete); assertFalse(result.truncated)
        assertEquals(3, result.omissionCount); assertTrue(backend.opens.isEmpty())
        assertEquals(setOf(FileSearchOmissionReason.NOT_TEXT, FileSearchOmissionReason.TOO_LARGE), result.omissions.map { it.reason }.toSet())
    }

    @Test fun invalidUtf8IsExplicitAndItsConsumedBytesStillCount() {
        val backend = Backend()
        backend.root.children += Node("bad.txt", bytes = byteArrayOf(0xc3.toByte(), 0x28))
        backend.root.children += file("good.txt", "中文 needle")
        val result = backend.search("needle")
        assertEquals(listOf("good.txt"), result.matches.map { it.path })
        assertEquals(FileSearchOmissionReason.INVALID_TEXT, result.omissions.single().reason)
        assertFalse(result.complete); assertEquals(2 + "中文 needle".toByteArray(Charsets.UTF_8).size, result.readBytes)
        assertTrue(backend.streams.all { it.closed })
    }

    @Test fun unknownSizeFileIsStillBoundedAndStreamIsClosed() {
        val backend = Backend()
        backend.root.children += Node("large.txt", bytes = ByteArray(FileRules.MAX_BYTES + 100) { 'a'.code.toByte() }, size = null)
        val result = backend.search("absent")
        assertEquals(FileRules.MAX_BYTES + 1, result.readBytes)
        assertEquals(FileSearchOmissionReason.TOO_LARGE, result.omissions.single().reason)
        assertFalse(result.complete); assertTrue(backend.streams.single().closed)
    }

    @Test fun directoryFailuresAndUnreadableFilesDoNotProveAbsence() {
        listOf(FileErrorCode.TOO_MANY_ENTRIES, FileErrorCode.UNSUPPORTED_PROVIDER, FileErrorCode.NOT_FOUND).forEach { code ->
            val backend = Backend()
            backend.childrenErrors[backend.root] = code
            val result = backend.search("absent")
            assertFalse(result.complete); assertEquals(1, result.omissionCount); assertTrue(result.matches.isEmpty())
        }
        val backend = Backend()
        val node = file("unreadable.txt", "needle")
        backend.root.children += node; backend.readErrors[node] = FileErrorCode.UNSUPPORTED_PROVIDER
        val result = backend.search("needle")
        assertFalse(result.complete); assertTrue(result.matches.isEmpty())
        assertEquals(FileSearchOmissionReason.UNSUPPORTED_PROVIDER, result.omissions.single().reason)
    }

    @Test fun oversizedDirectoryIsNotPartiallyEnumeratedAsComplete() {
        val backend = Backend()
        repeat(101) { backend.root.children += file("f$it.txt", "needle") }
        val result = backend.search("needle")
        assertTrue(result.matches.isEmpty()); assertFalse(result.complete); assertTrue(result.truncated)
        assertEquals(FileSearchOmissionReason.DIRECTORY_LIMIT, result.omissions.single().reason)
        assertTrue(backend.opens.isEmpty())
    }

    @Test fun omissionDetailsAreBoundedButTotalOmissionCountIsPreserved() {
        val backend = Backend()
        repeat(30) { backend.root.children += Node("binary$it", mime = null) }
        val result = backend.search("absent")
        assertEquals(20, result.omissions.size); assertEquals(30, result.omissionCount); assertFalse(result.complete)
    }

    @Test fun securityStorageAndConflictErrorsAreNeverConvertedToEmptyOrPartialResults() {
        listOf(FileErrorCode.NOT_SELECTED, FileErrorCode.STORAGE_FAILED, FileErrorCode.CONFLICT).forEach { code ->
            val backend = Backend()
            backend.resolveError = code
            assertCode(code) { backend.search("needle") }
            backend.resolveError = null; backend.childrenErrors[backend.root] = code
            assertCode(code) { backend.search("needle") }
            backend.childrenErrors.clear()
            val node = file("read.txt", "needle")
            backend.root.children += node; backend.readErrors[node] = code
            assertCode(code) { backend.search("needle") }
        }
    }

    @Test fun missingStartingPathFailsInsteadOfReturningNoMatches() {
        assertCode(FileErrorCode.NOT_FOUND) { Backend().search("needle", path = "missing") }
    }

    @Test fun repeatedDocumentIsNotReadTwiceOrTraversedInACycle() {
        val backend = Backend()
        val folder = Node("folder", directory = true)
        folder.children += folder
        backend.root.children += folder
        val result = backend.search("absent")
        assertFalse(result.complete)
        assertEquals(FileSearchOmissionReason.REPEATED_DOCUMENT, result.omissions.single().reason)
        assertEquals(3, result.visitedEntries); assertTrue(backend.opens.isEmpty())
    }

    @Test fun cancellationDuringReadingClosesStreamAndDoesNotReturnAccumulatedMatches() {
        val backend = Backend()
        backend.root.children += file("a.txt", "needle")
        backend.root.children += file("b.txt", "needle".repeat(1000))
        var cancelled = false
        backend.onRead = { if (backend.opens.size == 2) cancelled = true }
        assertThrows(CancellationException::class.java) {
            backend.search("needle", checkpoint = { if (cancelled) throw CancellationException() })
        }
        assertEquals(2, backend.opens.size); assertTrue(backend.streams.all { it.closed })
    }

    @Test fun finalCheckpointRejectsLateCancellationEvenAfterTraversalFinishes() {
        val backend = Backend()
        backend.root.children += file("a.txt", "needle")
        assertThrows(CancellationException::class.java) {
            backend.search("needle", checkpoint = {
                if (backend.streams.isNotEmpty() && backend.streams.all { it.closed }) throw CancellationException()
            })
        }
    }

    @Test fun aNewSearchAlwaysReadsCurrentDataWithoutAnIndex() {
        val backend = Backend()
        backend.root.children += file("a.txt", "old")
        assertTrue(backend.search("needle").matches.isEmpty())
        backend.root.children.clear(); backend.root.children += file("new.txt", "needle")
        assertEquals(listOf("new.txt"), backend.search("needle").matches.map { it.path })
        assertEquals(2, backend.resolves)
    }

    @Test fun metadataPreservesUnknownAndProvidedModificationTimesAndRedactsDiagnostics() {
        val backend = Backend()
        val missing = Node("private.txt", mime = null, size = null)
        val stat = backend.describe(missing, "private.txt")
        assertNull(stat.mime); assertNull(stat.size); assertNull(stat.modifiedAtMs)
        val provided = backend.describe(Node("known.txt", modifiedAtMs = 1234567L), "known.txt")
        assertEquals(1234567L, provided.modifiedAtMs)
        val historical = backend.describe(Node("historical.txt", modifiedAtMs = -1234567L), "historical.txt")
        assertEquals(-1234567L, historical.modifiedAtMs)
        assertFalse(stat.toString().contains("private"))
        backend.root.children += file("private.txt", "private needle")
        val result = backend.search("needle")
        assertFalse(result.toString().contains("private")); assertFalse(result.matches.single().toString().contains("private"))
    }
}
