package com.example.agent.rootpilot.files

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object FileSearchLimits {
    const val MAX_DEPTH = 4
    const val MAX_VISITED = 500
    const val MAX_READ_BYTES = 256 * 1024
    const val MAX_MATCHES = 20
    const val MAX_SNIPPET = 160
    const val MAX_OMISSION_DETAILS = 20
    const val MAX_QUERY = 128

    fun validate(path: String, query: String) {
        FileRules.segments(path, allowRoot = true)
        if (query.isEmpty() || query.length > MAX_QUERY) fail(FileErrorCode.INVALID_CHANGE)
        FileRules.encode(query)
    }
}

/** Handles are scoped to one protected workspace operation; the backend verifies SAF ancestry. */
internal interface FileSearchBackend<N> {
    fun resolve(path: String): N
    fun describe(node: N, path: String): FileStat
    fun identity(node: N): String
    fun children(node: N): List<Pair<String, N>>
    fun open(node: N, path: String): InputStream
}

/** A fresh traversal, with no index or retained file data and no writing capability. */
internal class FileSearchEngine<N>(
    private val backend: FileSearchBackend<N>,
    private val checkpoint: () -> Unit = {},
) {
    fun search(path: String, query: String, scope: FileSearchScope): FileSearchResult {
        FileSearchLimits.validate(path, query)
        checkpoint()
        val start = backend.resolve(path)
        val traversal = Traversal(query, scope)
        traversal.visit(start, path, 0)
        checkpoint()
        return traversal.result()
    }

    private inner class Traversal(private val query: String, private val scope: FileSearchScope) {
        private val matches = mutableListOf<FileSearchMatch>()
        private val omissions = mutableListOf<FileSearchOmission>()
        private val seen = mutableSetOf<String>()
        private var omissionCount = 0
        private var visited = 0
        private var readBytes = 0
        private var truncated = false
        private var stopped = false

        fun result() = FileSearchResult(matches.toList(), omissions.toList(), omissionCount, visited, readBytes, truncated)

        private fun omit(path: String, reason: FileSearchOmissionReason) {
            omissionCount++
            if (omissions.size < FileSearchLimits.MAX_OMISSION_DETAILS) omissions += FileSearchOmission(path, reason)
            if (reason in setOf(FileSearchOmissionReason.DEPTH_LIMIT, FileSearchOmissionReason.VISIT_LIMIT,
                    FileSearchOmissionReason.BYTE_LIMIT, FileSearchOmissionReason.RESULT_LIMIT,
                    FileSearchOmissionReason.DIRECTORY_LIMIT)) truncated = true
        }

        fun visit(node: N, path: String, depth: Int) {
            checkpoint()
            if (stopped) return
            if (visited >= FileSearchLimits.MAX_VISITED) {
                omit(path, FileSearchOmissionReason.VISIT_LIMIT)
                stopped = true
                return
            }
            FileRules.segments(path, allowRoot = true)
            visited++
            if (!seen.add(backend.identity(node))) {
                omit(path, FileSearchOmissionReason.REPEATED_DOCUMENT)
                return
            }
            val stat = backend.describe(node, path)
            if (scope == FileSearchScope.NAME) {
                if (path.isNotEmpty() && path.substringAfterLast('/').contains(query)) match(stat, null)
            } else if (!stat.isDirectory) {
                val text = read(node, stat)
                if (text != null) {
                    val offset = text.indexOf(query)
                    if (offset >= 0) match(stat, snippet(text, offset))
                }
            }
            checkpoint()
            if (stopped || !stat.isDirectory) return
            if (depth >= FileSearchLimits.MAX_DEPTH) {
                omit(path, FileSearchOmissionReason.DEPTH_LIMIT)
                return
            }
            val children = try {
                backend.children(node)
            } catch (error: FileStorageException) {
                omit(path, skippable(error))
                return
            }
            if (children.size > FileRules.MAX_ENTRIES) {
                omit(path, FileSearchOmissionReason.DIRECTORY_LIMIT)
                return
            }
            for ((name, child) in children.sortedBy { it.first }) {
                checkpoint()
                if (stopped) break
                if (FileRules.segments(name).size != 1) fail(FileErrorCode.INVALID_PATH)
                visit(child, if (path.isEmpty()) name else "$path/$name", depth + 1)
            }
        }

        private fun match(stat: FileStat, snippet: String?) {
            checkpoint()
            matches += FileSearchMatch(stat.path, stat.isDirectory, snippet)
            if (matches.size == FileSearchLimits.MAX_MATCHES) {
                // Remaining siblings/subtrees have not been checked, even if the last match was a leaf.
                omit(stat.path, FileSearchOmissionReason.RESULT_LIMIT)
                stopped = true
            }
        }

        private fun read(node: N, stat: FileStat): String? {
            val mime = stat.mime
            if (mime == null || !(mime.startsWith("text/") || mime in setOf("application/json", "application/xml"))) {
                omit(stat.path, FileSearchOmissionReason.NOT_TEXT)
                return null
            }
            if (stat.size != null && stat.size > FileRules.MAX_BYTES) {
                omit(stat.path, FileSearchOmissionReason.TOO_LARGE)
                return null
            }
            if (readBytes == FileSearchLimits.MAX_READ_BYTES) {
                omit(stat.path, FileSearchOmissionReason.BYTE_LIMIT)
                stopped = true
                return null
            }
            return try {
                backend.open(node, stat.path).use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        checkpoint()
                        val remaining = FileSearchLimits.MAX_READ_BYTES - readBytes
                        if (remaining == 0) {
                            // Do not consume an extra byte to probe EOF beyond the aggregate budget.
                            omit(stat.path, FileSearchOmissionReason.BYTE_LIMIT)
                            stopped = true
                            return@use null
                        }
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining, FileRules.MAX_BYTES + 1 - bytes.size()))
                        checkpoint()
                        if (count < 0) break
                        if (count == 0) fail(FileErrorCode.STORAGE_FAILED)
                        readBytes += count
                        bytes.write(buffer, 0, count)
                        if (bytes.size() > FileRules.MAX_BYTES) fail(FileErrorCode.TOO_LARGE)
                    }
                    FileRules.decode(bytes.toByteArray())
                }
            } catch (error: FileStorageException) {
                omit(stat.path, skippable(error))
                null
            }
        }

        private fun skippable(error: FileStorageException): FileSearchOmissionReason = when (error.code) {
            FileErrorCode.TOO_MANY_ENTRIES -> FileSearchOmissionReason.DIRECTORY_LIMIT
            FileErrorCode.NOT_TEXT -> FileSearchOmissionReason.NOT_TEXT
            FileErrorCode.TOO_LARGE -> FileSearchOmissionReason.TOO_LARGE
            FileErrorCode.INVALID_TEXT -> FileSearchOmissionReason.INVALID_TEXT
            FileErrorCode.NOT_FOUND -> FileSearchOmissionReason.NOT_FOUND
            FileErrorCode.UNSUPPORTED_PROVIDER -> FileSearchOmissionReason.UNSUPPORTED_PROVIDER
            else -> throw error
        }

        private fun snippet(text: String, offset: Int): String {
            var start = maxOf(0, offset - (FileSearchLimits.MAX_SNIPPET - query.length) / 2)
            if (start > 0 && text[start].isLowSurrogate()) start++
            var end = minOf(text.length, start + FileSearchLimits.MAX_SNIPPET)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            return text.substring(start, end)
        }
    }
}
