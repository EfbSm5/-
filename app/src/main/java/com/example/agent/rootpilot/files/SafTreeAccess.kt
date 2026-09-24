package com.example.agent.rootpilot.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Every lookup starts at the authorized tree and queries each directory, without cached URIs. */
internal class SafTreeAccess(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val checkpoint: () -> Unit = {},
) {
    internal class Node(val id: String, val name: String, val mime: String, val flags: Long, val size: Long?) {
        val directory get() = mime == Document.MIME_TYPE_DIR
        override fun toString() = "SafNode(redacted)"
    }

    internal class Resolved(val chain: List<String>, val parent: Node, val node: Node?) {
        override fun toString() = "SafResolved(redacted)"
    }

    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE)

    fun root(): Node {
        val nodes = query(uri(rootId), false)
        if (nodes.size != 1 || nodes.single().id != rootId || !nodes.single().directory) {
            fail(FileErrorCode.UNSUPPORTED_PROVIDER)
        }
        return nodes.single()
    }

    fun resolve(path: String, allowMissing: Boolean = false, allowRoot: Boolean = false): Resolved {
        val parts = FileRules.segments(path, allowRoot)
        var current = root()
        val chain = mutableListOf(rootId)
        if (parts.isEmpty()) return Resolved(chain, current, current)
        parts.forEachIndexed { index, name ->
            if (!current.directory) fail(FileErrorCode.NOT_DIRECTORY)
            val parent = current
            val found = children(parent).singleOrNull { it.name == name }
            if (found == null) {
                if (allowMissing && index == parts.lastIndex) return Resolved(chain.toList(), parent, null)
                fail(FileErrorCode.NOT_FOUND)
            }
            if (chain.contains(found.id)) fail(FileErrorCode.UNSUPPORTED_PROVIDER)
            chain += found.id
            if (index == parts.lastIndex) return Resolved(chain.toList(), parent, found)
            current = found
        }
        fail(FileErrorCode.NOT_FOUND)
    }

    fun children(parent: Node): List<Node> {
        if (!parent.directory) fail(FileErrorCode.NOT_DIRECTORY)
        val result = query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.id), true)
        if (result.map { it.name }.distinct().size != result.size || result.map { it.id }.distinct().size != result.size) {
            fail(FileErrorCode.AMBIGUOUS)
        }
        result.forEach { child ->
            checkpoint()
            val isChild = try {
                DocumentsContract.isChildDocument(resolver, uri(parent.id), uri(child.id))
            } catch (_: UnsupportedOperationException) {
                fail(FileErrorCode.UNSUPPORTED_PROVIDER)
            }
            if (!isChild) fail(FileErrorCode.UNSUPPORTED_PROVIDER)
        }
        return result
    }

    fun read(node: Node): String {
        requireText(node)
        checkpoint()
        return resolver.openInputStream(uri(node.id))?.use {
            FileRules.decode(FileRules.boundedRead(it))
        } ?: fail(FileErrorCode.UNSUPPORTED_PROVIDER)
    }

    fun requireWritable(resolved: Resolved) {
        if (resolved.node == null) {
            if (resolved.parent.flags and Document.FLAG_DIR_SUPPORTS_CREATE.toLong() == 0L) fail(FileErrorCode.NOT_WRITABLE)
        } else {
            requireText(resolved.node)
            if (resolved.node.flags and Document.FLAG_SUPPORTS_WRITE.toLong() == 0L) fail(FileErrorCode.NOT_WRITABLE)
        }
    }

    fun write(path: String, expected: FileSnapshot, content: ByteArray) {
        val resolved = resolve(path, allowMissing = expected.text == null)
        requireWritable(resolved)
        val currentHash = resolved.node?.let { FileRules.hash(FileRules.encode(read(it))) }
        FileRules.verify(expected.identity, resolved.chain, expected.hash, currentHash)
        checkpoint()
        val node = resolved.node ?: run {
            val created = DocumentsContract.createDocument(resolver, uri(resolved.parent.id), "text/plain", path.substringAfterLast('/'))
                ?: fail(FileErrorCode.WRITE_FAILED)
            if (created.scheme != "content" || created.authority != tree.authority) {
                fail(FileErrorCode.WRITE_FAILED)
            }
            val checked = resolve(path)
            val result = checked.node ?: fail(FileErrorCode.WRITE_FAILED)
            if (checked.chain.dropLast(1) != resolved.chain || result.id != DocumentsContract.getDocumentId(created)) {
                fail(FileErrorCode.WRITE_FAILED)
            }
            // Some providers rename or return an existing document; do not overwrite nonempty content.
            if (read(result).isNotEmpty()) fail(FileErrorCode.WRITE_FAILED)
            requireWritable(checked)
            result
        }
        checkpoint()
        resolver.openOutputStream(uri(node.id), "wt")?.use { output ->
            output.write(content)
            output.flush()
        } ?: fail(FileErrorCode.UNSUPPORTED_PROVIDER)
        checkpoint()
        val checked = resolve(path)
        val checkedNode = checked.node ?: fail(FileErrorCode.WRITE_FAILED)
        if (checkedNode.id != node.id || checked.chain.dropLast(1) !=
            (if (expected.text == null) expected.identity else expected.identity.dropLast(1))) {
            fail(FileErrorCode.WRITE_FAILED)
        }
        if (FileRules.hash(FileRules.encode(read(checkedNode))) != FileRules.hash(content)) fail(FileErrorCode.WRITE_FAILED)
    }

    private fun requireText(node: Node) {
        if (node.directory || !(node.mime.startsWith("text/") || node.mime in setOf("application/json", "application/xml"))) {
            fail(FileErrorCode.NOT_TEXT)
        }
        if (node.size != null && node.size > FileRules.MAX_BYTES) fail(FileErrorCode.TOO_LARGE)
    }

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun query(uri: Uri, children: Boolean): List<Node> {
        checkpoint()
        val cursor = resolver.query(uri, columns, null, null, null, CancellationSignal())
            ?: fail(FileErrorCode.UNSUPPORTED_PROVIDER)
        return cursor.use {
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                it.extras.containsKey(DocumentsContract.EXTRA_ERROR)) fail(FileErrorCode.UNSUPPORTED_PROVIDER)
            val result = mutableListOf<Node>()
            while (it.moveToNext()) {
                checkpoint()
                if (result.size >= if (children) FileRules.MAX_ENTRIES else 1) {
                    fail(if (children) FileErrorCode.TOO_MANY_ENTRIES else FileErrorCode.UNSUPPORTED_PROVIDER)
                }
                val id = it.requiredString(Document.COLUMN_DOCUMENT_ID)
                val name = it.requiredString(Document.COLUMN_DISPLAY_NAME)
                if (id.isEmpty() || FileRules.segments(name).size != 1) fail(FileErrorCode.UNSUPPORTED_PROVIDER)
                val mime = it.requiredString(Document.COLUMN_MIME_TYPE)
                val flagIndex = it.getColumnIndex(Document.COLUMN_FLAGS)
                if (flagIndex < 0 || it.isNull(flagIndex)) fail(FileErrorCode.UNSUPPORTED_PROVIDER)
                val flags = it.getLong(flagIndex)
                if (flags and (Document.FLAG_VIRTUAL_DOCUMENT or Document.FLAG_PARTIAL).toLong() != 0L) {
                    fail(FileErrorCode.UNSUPPORTED_PROVIDER)
                }
                val sizeIndex = it.getColumnIndex(Document.COLUMN_SIZE)
                val size = if (sizeIndex >= 0 && !it.isNull(sizeIndex)) it.getLong(sizeIndex) else null
                if (size != null && size < 0) fail(FileErrorCode.UNSUPPORTED_PROVIDER)
                result += Node(id, name, mime, flags, size)
            }
            result
        }
    }

    private fun Cursor.requiredString(column: String): String {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) fail(FileErrorCode.UNSUPPORTED_PROVIDER)
        return getString(index) ?: fail(FileErrorCode.UNSUPPORTED_PROVIDER)
    }
}
