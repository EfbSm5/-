package com.example.agent.rootpilot

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeoutException

/** Test-only exclusive writing; no hard links, rename, fallback, or existing-file cleanup. */
internal object NotificationStructureEvidenceFile {
    enum class Code {
        STRUCTURE_EVIDENCE_INVALID, STRUCTURE_EVIDENCE_EXISTS, STRUCTURE_EVIDENCE_IO,
        STRUCTURE_EVIDENCE_UNSUPPORTED, STRUCTURE_EVIDENCE_SECURITY, STRUCTURE_EVIDENCE_ILLEGAL_STATE,
        STRUCTURE_EVIDENCE_NULL_POINTER, STRUCTURE_EVIDENCE_TIMEOUT, STRUCTURE_EVIDENCE_OTHER,
    }
    private class Failure(val code: Code) : RuntimeException(code.name)

    /**
     * CREATE_NEW refuses an existing entry of any type, including a dangling symlink.
     * This is NOT atomic publication: failure may leave this receipt's incomplete file.
     * Only return after the full write, force, and close. Callers must require the successful
     * completed diagnostic receipt AND a complete schema before accepting/exporting the file.
     * Never overwrite/delete an existing receipt or remove a failed write to permit retries.
     */
    fun write(base: Path, receipt: String, bytes: ByteArray) = write(base, receipt, bytes, Directory.STRUCTURE)

    fun writeExpansionFailure(base: Path, receipt: String, bytes: ByteArray) =
        write(base, receipt, bytes, Directory.EXPANSION_FAILURE)

    private enum class Directory(val pathName: String) {
        STRUCTURE("rootpilot-notification-structure"), EXPANSION_FAILURE("rootpilot-notification-expansion-failure")
    }

    private fun write(base: Path, receipt: String, bytes: ByteArray, kind: Directory) {
        fixed(bytes.isNotEmpty() && bytes.size <= NotificationStructureEvidence.MAX_BYTES)
        fixed(receipt.length == 36 &&
            runCatching { UUID.fromString(receipt).toString() == receipt }.getOrDefault(false))
        plainDirectory(base)
        val directory = base.resolve(kind.pathName)
        try { Files.createDirectory(directory) } catch (_: FileAlreadyExistsException) { /* Check without following links below. */ }
        plainDirectory(directory)
        val destination = directory.resolve("$receipt.json")
        FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { output ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) output.write(buffer)
            output.force(true)
        }
    }

    /** Classify types only; paths, exception messages, and causes never become evidence/status. */
    fun classify(error: Throwable): Code = when (error) {
        is Failure -> error.code
        is FileAlreadyExistsException -> Code.STRUCTURE_EVIDENCE_EXISTS
        is IOException -> Code.STRUCTURE_EVIDENCE_IO
        is UnsupportedOperationException -> Code.STRUCTURE_EVIDENCE_UNSUPPORTED
        is SecurityException -> Code.STRUCTURE_EVIDENCE_SECURITY
        is IllegalStateException -> Code.STRUCTURE_EVIDENCE_ILLEGAL_STATE
        is NullPointerException -> Code.STRUCTURE_EVIDENCE_NULL_POINTER
        is TimeoutException -> Code.STRUCTURE_EVIDENCE_TIMEOUT
        else -> Code.STRUCTURE_EVIDENCE_OTHER
    }

    private fun plainDirectory(path: Path) {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        fixed(attrs.isDirectory && !attrs.isSymbolicLink)
    }

    private fun fixed(value: Boolean) {
        if (!value) throw Failure(Code.STRUCTURE_EVIDENCE_INVALID)
    }
}
