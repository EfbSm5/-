package com.example.agent.rootpilot

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Executes the same file writer as instrumentation against real private temporary files. */
class NotificationIdentityEvidenceFileTest {
    @get:Rule val temporary = TemporaryFolder()
    private val receipt = "321b21cd-4137-4e24-a14a-56d6a94b94f1"
    private val payload = "{\"fixed\":true}".toByteArray()

    @Test fun legalWriteReturnsOnlyWithCompleteFinalFileAndNoTemporaryEntry() {
        val base = base()
        NotificationIdentityEvidenceFile.write(base, receipt, payload)
        val file = file(base)
        assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals(payload, Files.readAllBytes(file))
        Files.newDirectoryStream(file.parent).use { entries ->
            assertEquals(listOf("$receipt.json"), entries.map { it.fileName.toString() })
        }
    }

    @Test fun existingRegularFileIsNeverOverwrittenTruncatedOrDeleted() {
        val base = base()
        val file = prepareDestination(base)
        val original = "original fixed evidence".toByteArray()
        Files.write(file, original)
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_EXISTS,
            rejected { NotificationIdentityEvidenceFile.write(base, receipt, payload) })
        assertArrayEquals(original, Files.readAllBytes(file))
    }

    @Test fun existingDirectoryAtFinalPathIsNeverChanged() {
        val base = base()
        val file = prepareDestination(base)
        Files.createDirectory(file)
        val marker = file.resolve("marker")
        Files.write(marker, byteArrayOf(1, 2, 3))
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_EXISTS,
            rejected { NotificationIdentityEvidenceFile.write(base, receipt, payload) })
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(marker))
    }

    @Test fun finalSymlinkIsRejectedAndTargetRemainsUnchanged() {
        val base = base()
        val file = prepareDestination(base)
        val target = temporary.newFile("target").toPath()
        Files.write(target, byteArrayOf(4, 5, 6))
        Files.createSymbolicLink(file, target)
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_EXISTS,
            rejected { NotificationIdentityEvidenceFile.write(base, receipt, payload) })
        assertTrue(Files.isSymbolicLink(file))
        assertArrayEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(target))
    }

    @Test fun danglingFinalSymlinkIsRejectedWithoutCreatingItsTarget() {
        val base = base()
        val file = prepareDestination(base)
        val target = base.resolve("absent-target")
        Files.createSymbolicLink(file, target)
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_EXISTS,
            rejected { NotificationIdentityEvidenceFile.write(base, receipt, payload) })
        assertTrue(Files.isSymbolicLink(file))
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun symlinkEvidenceDirectoryIsRejectedWithoutWritingIntoItsTarget() {
        val base = base()
        val target = temporary.newFolder("directory-target").toPath()
        Files.createSymbolicLink(file(base).parent, target)
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_INVALID,
            rejected { NotificationIdentityEvidenceFile.write(base, receipt, payload) })
        assertFalse(Files.exists(target.resolve("$receipt.json"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun symlinkBaseIsRejectedWithoutCreatingEvidenceDirectory() {
        val target = base()
        val alias = temporary.root.toPath().resolve("base-link")
        Files.createSymbolicLink(alias, target)
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_INVALID,
            rejected { NotificationIdentityEvidenceFile.write(alias, receipt, payload) })
        assertFalse(Files.exists(file(target).parent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun oversizedPayloadCreatesNeitherDirectoryNorFile() {
        val base = base()
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_INVALID,
            rejected { NotificationIdentityEvidenceFile.write(base, receipt,
                ByteArray(NotificationIdentityEvidence.MAX_BYTES + 1)) })
        assertFalse(Files.exists(file(base).parent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun maximumPayloadIsWrittenCompletely() {
        val base = base()
        val bytes = ByteArray(NotificationIdentityEvidence.MAX_BYTES) { (it % 127).toByte() }
        NotificationIdentityEvidenceFile.write(base, receipt, bytes)
        assertArrayEquals(bytes, Files.readAllBytes(file(base)))
    }

    @Test fun emptyPayloadAndInvalidReceiptDoNotCreateEvidenceDirectory() {
        val base = base()
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_INVALID,
            rejected { NotificationIdentityEvidenceFile.write(base, receipt, byteArrayOf()) })
        for (invalid in listOf("../other-receipt", "x".repeat(36), receipt.uppercase())) {
            assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_INVALID,
                rejected { NotificationIdentityEvidenceFile.write(base, invalid, payload) })
        }
        assertFalse(Files.exists(file(base).parent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun errorTypesHaveIndependentFixedEvidenceCodesWithoutMessages() {
        val cases = listOf(
            FileAlreadyExistsException("private path") to NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_EXISTS,
            IOException("private message") to NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_IO,
            UnsupportedOperationException("private message") to NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_UNSUPPORTED,
            SecurityException("private message") to NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_SECURITY,
            IllegalStateException("private message") to NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_ILLEGAL_STATE,
            NullPointerException("private message") to NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_NULL_POINTER,
            TimeoutException("private message") to NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_TIMEOUT,
            IllegalArgumentException("private message") to NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_OTHER,
        )
        for ((error, code) in cases) assertEquals(code, NotificationIdentityEvidenceFile.classify(error))
        val unreadable = object : IOException() {
            override val message: String get() = error("must not read exception message")
            override val cause: Throwable get() = error("must not read exception cause")
        }
        assertEquals(NotificationIdentityEvidenceFile.Code.IDENTITY_EVIDENCE_IO,
            NotificationIdentityEvidenceFile.classify(unreadable))
    }

    private fun base(): Path = temporary.newFolder().toPath()
    private fun file(base: Path): Path = base.resolve("rootpilot-notification-identity").resolve("$receipt.json")
    private fun prepareDestination(base: Path): Path = file(base).also { Files.createDirectory(it.parent) }
    private fun rejected(block: () -> Unit): NotificationIdentityEvidenceFile.Code {
        val error = runCatching(block).exceptionOrNull() ?: throw AssertionError("WRITE_REJECTION_EXPECTED")
        return NotificationIdentityEvidenceFile.classify(error)
    }
}
