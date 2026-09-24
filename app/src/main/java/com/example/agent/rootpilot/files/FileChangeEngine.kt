package com.example.agent.rootpilot.files

import java.util.UUID
import java.util.concurrent.CancellationException

internal class FileSnapshot(
    val selection: String,
    val identity: List<String>,
    val text: String?,
) {
    val hash: String? = text?.let { FileRules.hash(FileRules.encode(it)) }
    override fun toString() = "FileSnapshot(redacted)"
}

internal interface FileChangeBackend {
    fun snapshot(path: String, allowMissing: Boolean): FileSnapshot
    fun write(path: String, expected: FileSnapshot, content: ByteArray)
    fun backup(id: String, label: String, bytes: ByteArray)
}

/** Serialized by FileWorkspace. No pending change or retry instruction survives process death. */
internal class FileChangeEngine(private val backend: FileChangeBackend) {
    private class Pending(val change: PreparedFileChange, val snapshot: FileSnapshot)
    private val pending = linkedMapOf<String, Pending>()

    fun invalidate() = pending.clear()

    fun discard(change: PreparedFileChange) {
        if (pending[change.id]?.change === change) pending.remove(change.id)
    }

    fun prepareCreate(path: String, content: String): PreparedFileChange {
        FileRules.segments(path)
        FileRules.encode(content)
        val snapshot = backend.snapshot(path, true)
        if (snapshot.text != null) fail(FileErrorCode.ALREADY_EXISTS)
        return remember(path, snapshot, content)
    }

    fun prepareEdit(path: String, oldText: String, newText: String): PreparedFileChange {
        FileRules.segments(path)
        val snapshot = backend.snapshot(path, false)
        val before = snapshot.text ?: fail(FileErrorCode.NOT_FOUND)
        return remember(path, snapshot, FileRules.edit(before, oldText, newText))
    }

    private fun remember(path: String, snapshot: FileSnapshot, after: String): PreparedFileChange {
        if (pending.size >= 16) fail(FileErrorCode.INVALID_CHANGE)
        val change = PreparedFileChange(UUID.randomUUID().toString(), path, snapshot.text, after)
        pending[change.id] = Pending(change, snapshot)
        return change
    }

    fun commit(change: PreparedFileChange, checkpoint: () -> Unit): FileWriteReceipt {
        val item = pending[change.id] ?: fail(FileErrorCode.INVALID_CHANGE)
        if (item.change !== change) fail(FileErrorCode.INVALID_CHANGE)
        // Consume before cancellation checks or provider calls: uncertain outcomes must never replay.
        pending.remove(change.id)
        var backupId: String? = null
        var mayHaveWritten = false
        try {
            checkpoint()
            verify(item.snapshot, backend.snapshot(change.path, change.before == null))
            if (change.before != null) {
                backend.backup(change.id, change.path.substringAfterLast('/'), FileRules.encode(change.before))
                backupId = change.id
            }
            checkpoint()
            // Backup IO can take time. Re-resolve the entire ancestry and re-read content afterwards.
            verify(item.snapshot, backend.snapshot(change.path, change.before == null))
            checkpoint()
            mayHaveWritten = true
            backend.write(change.path, item.snapshot, FileRules.encode(change.after))
            checkpoint()
            val actual = backend.snapshot(change.path, false)
            if (actual.selection != item.snapshot.selection || actual.hash != FileRules.hash(FileRules.encode(change.after))) {
                fail(FileErrorCode.WRITE_FAILED)
            }
            if (change.before != null && actual.identity != item.snapshot.identity) fail(FileErrorCode.WRITE_FAILED)
            return FileWriteReceipt(backupId, "写入后已回读核对；SAF 不保证跨应用原子性，备份未自动删除。")
        } catch (_: CancellationException) {
            throw FileWriteCancelledException(backupId, mayHaveWritten)
        } catch (e: FileStorageException) {
            throw FileStorageException(e.code, backupId, mayHaveWritten)
        } catch (_: Exception) {
            throw FileStorageException(FileErrorCode.WRITE_FAILED, backupId, mayHaveWritten)
        }
    }

    private fun verify(expected: FileSnapshot, actual: FileSnapshot) {
        if (expected.selection != actual.selection) fail(FileErrorCode.CONFLICT)
        FileRules.verify(expected.identity, actual.identity, expected.hash, actual.hash)
    }
}
