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

/** Independent 32 KiB contract; the structure writer remains limited to 16 KiB. */
internal object NotificationIdentityEvidenceFile {
    enum class Code {
        IDENTITY_EVIDENCE_INVALID, IDENTITY_EVIDENCE_EXISTS, IDENTITY_EVIDENCE_IO,
        IDENTITY_EVIDENCE_UNSUPPORTED, IDENTITY_EVIDENCE_SECURITY, IDENTITY_EVIDENCE_ILLEGAL_STATE,
        IDENTITY_EVIDENCE_NULL_POINTER, IDENTITY_EVIDENCE_TIMEOUT, IDENTITY_EVIDENCE_OTHER,
    }
    private class Failure(val code: Code) : RuntimeException(code.name)

    /** CREATE_NEW/NOFOLLOW; failed writes remain invalid and are never removed for a retry. */
    fun write(base: Path, receipt: String, bytes: ByteArray) =
        write(base, receipt, bytes, "rootpilot-notification-identity")

    fun writeExpansionTimeout(base: Path, receipt: String, bytes: ByteArray) =
        write(base, receipt, bytes, "rootpilot-notification-expansion-timeout")

    private fun write(base: Path, receipt: String, bytes: ByteArray, directoryName: String) {
        fixed(bytes.isNotEmpty() && bytes.size <= NotificationIdentityEvidence.MAX_BYTES)
        fixed(receipt.length == 36 && runCatching { UUID.fromString(receipt).toString() == receipt }.getOrDefault(false))
        plainDirectory(base)
        val directory = base.resolve(directoryName)
        try { Files.createDirectory(directory) } catch (_: FileAlreadyExistsException) { /* Validate below. */ }
        plainDirectory(directory)
        FileChannel.open(directory.resolve("$receipt.json"), StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) output.write(buffer)
            output.force(true)
        }
    }
    fun classify(error: Throwable): Code = when (error) {
        is Failure -> error.code
        is FileAlreadyExistsException -> Code.IDENTITY_EVIDENCE_EXISTS
        is IOException -> Code.IDENTITY_EVIDENCE_IO
        is UnsupportedOperationException -> Code.IDENTITY_EVIDENCE_UNSUPPORTED
        is SecurityException -> Code.IDENTITY_EVIDENCE_SECURITY
        is IllegalStateException -> Code.IDENTITY_EVIDENCE_ILLEGAL_STATE
        is NullPointerException -> Code.IDENTITY_EVIDENCE_NULL_POINTER
        is TimeoutException -> Code.IDENTITY_EVIDENCE_TIMEOUT
        else -> Code.IDENTITY_EVIDENCE_OTHER
    }
    private fun plainDirectory(path: Path) {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        fixed(attrs.isDirectory && !attrs.isSymbolicLink)
    }
    private fun fixed(value: Boolean) { if (!value) throw Failure(Code.IDENTITY_EVIDENCE_INVALID) }
}
