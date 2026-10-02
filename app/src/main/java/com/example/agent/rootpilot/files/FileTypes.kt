package com.example.agent.rootpilot.files

import java.util.concurrent.CancellationException

class FileEntry(val name: String, val isDirectory: Boolean) {
    override fun toString() = "FileEntry(redacted)"
}

class FileStat(
    val path: String,
    val isDirectory: Boolean,
    val mime: String?,
    val size: Long?,
    val modifiedAtMs: Long?,
) {
    override fun toString() = "FileStat(redacted)"
}

enum class FileSearchScope { NAME, CONTENT }

class FileSearchMatch(val path: String, val isDirectory: Boolean, val snippet: String?) {
    override fun toString() = "FileSearchMatch(redacted)"
}

enum class FileSearchOmissionReason {
    DEPTH_LIMIT, VISIT_LIMIT, BYTE_LIMIT, RESULT_LIMIT, DIRECTORY_LIMIT,
    NOT_TEXT, TOO_LARGE, INVALID_TEXT, NOT_FOUND, UNSUPPORTED_PROVIDER, REPEATED_DOCUMENT,
}

class FileSearchOmission(val path: String, val reason: FileSearchOmissionReason) {
    override fun toString() = "FileSearchOmission(redacted)"
}

class FileSearchResult(
    val matches: List<FileSearchMatch>,
    val omissions: List<FileSearchOmission>,
    val omissionCount: Int,
    val visitedEntries: Int,
    val readBytes: Int,
    val truncated: Boolean,
) {
    val complete get() = omissionCount == 0
    override fun toString() = "FileSearchResult(redacted)"
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
    BACKUP_FAILED, BACKUP_NOT_FOUND, STORAGE_FAILED, WRITE_FAILED, NOT_SUPPORTED,
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
    suspend fun stat(path: String = ""): FileStat = fail(FileErrorCode.NOT_SUPPORTED)
    suspend fun search(path: String, query: String, scope: FileSearchScope): FileSearchResult = fail(FileErrorCode.NOT_SUPPORTED)
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
