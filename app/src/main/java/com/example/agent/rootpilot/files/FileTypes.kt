package com.example.agent.rootpilot.files

import java.util.concurrent.CancellationException

class FileEntry(val name: String, val isDirectory: Boolean) {
    override fun toString() = "FileEntry(redacted)"
}

class FileBackupInfo(val id: String, val label: String) {
    override fun toString() = "FileBackupInfo(redacted)"
}

/** Only the originating FileTools instance may consume this in-memory preview, once. */
class PreparedFileChange(
    val id: String,
    val path: String,
    val before: String?,
    val after: String,
) {
    override fun toString() = "PreparedFileChange(redacted)"
}

class FileWriteReceipt(val backupId: String?, val message: String) {
    override fun toString() = "FileWriteReceipt(redacted)"
}

enum class FileErrorCode {
    INVALID_PATH, INVALID_TEXT, TOO_LARGE, UNSUPPORTED_PROVIDER, NOT_SELECTED,
    NOT_FOUND, ALREADY_EXISTS, AMBIGUOUS, NOT_DIRECTORY, NOT_TEXT, NOT_WRITABLE,
    TOO_MANY_ENTRIES, EDIT_MATCH, CONFLICT, INVALID_CHANGE, BACKUP_FULL,
    BACKUP_FAILED, BACKUP_NOT_FOUND, STORAGE_FAILED, WRITE_FAILED,
}

class FileStorageException(
    val code: FileErrorCode,
    val backupId: String? = null,
    val mayHaveWritten: Boolean = false,
) : Exception("File storage: ${code.name}; inspect result before preparing another change")

class FileWriteCancelledException(
    val backupId: String?,
    val mayHaveWritten: Boolean,
) : CancellationException("File operation cancelled; inspect result; do not replay")

internal fun fail(code: FileErrorCode): Nothing = throw FileStorageException(code)

interface WorkspaceAccess {
    suspend fun list(path: String = ""): List<FileEntry>
    suspend fun read(path: String): String
    suspend fun prepareCreate(path: String, content: String): PreparedFileChange
    suspend fun prepareEdit(path: String, oldText: String, newText: String): PreparedFileChange
    suspend fun discard(change: PreparedFileChange)
    suspend fun commit(change: PreparedFileChange): FileWriteReceipt
}

internal inline fun <T> sanitized(block: () -> T): T = try {
    block()
} catch (e: FileStorageException) {
    throw e
} catch (e: FileWriteCancelledException) {
    throw e
} catch (_: CancellationException) {
    throw FileWriteCancelledException(null, false)
} catch (_: Exception) {
    fail(FileErrorCode.STORAGE_FAILED)
}
