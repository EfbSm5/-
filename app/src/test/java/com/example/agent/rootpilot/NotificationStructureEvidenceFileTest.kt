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
class NotificationStructureEvidenceFileTest {
    @get:Rule val temporary = TemporaryFolder()
    private val receipt = "321b21cd-4137-4e24-a14a-56d6a94b94f1"
    private val payload = NotificationStructureEvidence.capture(0, { null as Int? }) {
        NotificationStructureEvidence.Node("android:id/title", "android.widget.TextView", "android", true, false, false)
    }.json(receipt, 123)

    @Test fun legalWriteReturnsOnlyWithCompleteFinalFileAndNoTemporaryEntry() {
        val base = base()
        NotificationStructureEvidenceFile.write(base, receipt, payload)
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
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_EXISTS,
            rejected { NotificationStructureEvidenceFile.write(base, receipt, payload) })
        assertArrayEquals(original, Files.readAllBytes(file))
    }

    @Test fun existingDirectoryAtFinalPathIsNeverChanged() {
        val base = base()
        val file = prepareDestination(base)
        Files.createDirectory(file)
        val marker = file.resolve("marker")
        Files.write(marker, byteArrayOf(1, 2, 3))
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_EXISTS,
            rejected { NotificationStructureEvidenceFile.write(base, receipt, payload) })
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(marker))
    }

    @Test fun finalSymlinkIsRejectedAndTargetRemainsUnchanged() {
        val base = base()
        val file = prepareDestination(base)
        val target = temporary.newFile("target").toPath()
        Files.write(target, byteArrayOf(4, 5, 6))
        Files.createSymbolicLink(file, target)
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_EXISTS,
            rejected { NotificationStructureEvidenceFile.write(base, receipt, payload) })
        assertTrue(Files.isSymbolicLink(file))
        assertArrayEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(target))
    }

    @Test fun danglingFinalSymlinkIsRejectedWithoutCreatingItsTarget() {
        val base = base()
        val file = prepareDestination(base)
        val target = base.resolve("absent-target")
        Files.createSymbolicLink(file, target)
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_EXISTS,
            rejected { NotificationStructureEvidenceFile.write(base, receipt, payload) })
        assertTrue(Files.isSymbolicLink(file))
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun symlinkEvidenceDirectoryIsRejectedWithoutWritingIntoItsTarget() {
        val base = base()
        val target = temporary.newFolder("directory-target").toPath()
        Files.createSymbolicLink(file(base).parent, target)
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_INVALID,
            rejected { NotificationStructureEvidenceFile.write(base, receipt, payload) })
        assertFalse(Files.exists(target.resolve("$receipt.json"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun symlinkBaseIsRejectedWithoutCreatingEvidenceDirectory() {
        val target = base()
        val alias = temporary.root.toPath().resolve("base-link")
        Files.createSymbolicLink(alias, target)
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_INVALID,
            rejected { NotificationStructureEvidenceFile.write(alias, receipt, payload) })
        assertFalse(Files.exists(file(target).parent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun oversizedPayloadCreatesNeitherDirectoryNorFile() {
        val base = base()
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_INVALID,
            rejected { NotificationStructureEvidenceFile.write(base, receipt,
                ByteArray(NotificationStructureEvidence.MAX_BYTES + 1)) })
        assertFalse(Files.exists(file(base).parent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun maximumPayloadIsWrittenCompletely() {
        val base = base()
        val bytes = ByteArray(NotificationStructureEvidence.MAX_BYTES) { (it % 127).toByte() }
        NotificationStructureEvidenceFile.write(base, receipt, bytes)
        assertArrayEquals(bytes, Files.readAllBytes(file(base)))
    }

    @Test fun emptyPayloadAndInvalidReceiptDoNotCreateEvidenceDirectory() {
        val base = base()
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_INVALID,
            rejected { NotificationStructureEvidenceFile.write(base, receipt, byteArrayOf()) })
        for (invalid in listOf("../other-receipt", "x".repeat(36), receipt.uppercase())) {
            assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_INVALID,
                rejected { NotificationStructureEvidenceFile.write(base, invalid, payload) })
        }
        assertFalse(Files.exists(file(base).parent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun errorTypesHaveIndependentFixedEvidenceCodesWithoutMessages() {
        val cases = listOf(
            FileAlreadyExistsException("private path") to NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_EXISTS,
            IOException("private message") to NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_IO,
            UnsupportedOperationException("private message") to NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_UNSUPPORTED,
            SecurityException("private message") to NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_SECURITY,
            IllegalStateException("private message") to NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_ILLEGAL_STATE,
            NullPointerException("private message") to NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_NULL_POINTER,
            TimeoutException("private message") to NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_TIMEOUT,
            IllegalArgumentException("private message") to NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_OTHER,
        )
        for ((error, code) in cases) assertEquals(code, NotificationStructureEvidenceFile.classify(error))
        val unreadable = object : IOException() {
            override val message: String get() = error("must not read exception message")
            override val cause: Throwable get() = error("must not read exception cause")
        }
        assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_IO,
            NotificationStructureEvidenceFile.classify(unreadable))
    }

    @Test fun longPressFailureUsesSeparateFixedDirectoryWithSameReceiptWithoutChangingOrdinaryEvidence() {
        val base = base()
        val bytes = "fixed failure-only payload".toByteArray()
        NotificationStructureEvidenceFile.write(base, receipt, payload)
        NotificationStructureEvidenceFile.writeExpansionFailure(base, receipt, bytes)
        assertArrayEquals(payload, Files.readAllBytes(file(base)))
        val failure = failureFile(base)
        assertTrue(Files.isRegularFile(failure, LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals(bytes, Files.readAllBytes(failure))
        Files.newDirectoryStream(failure.parent).use { entries ->
            assertEquals(listOf("$receipt.json"), entries.map { it.fileName.toString() })
        }
    }

    @Test fun longPressFailureNeverOverwritesExistingRegularDirectorySymlinkOrDanglingSymlink() {
        for (kind in 0..3) {
            val base = base()
            val file = failureFile(base)
            Files.createDirectory(file.parent)
            val target = temporary.newFolder().toPath().resolve("target")
            when (kind) {
                0 -> Files.write(file, byteArrayOf(7))
                1 -> Files.createDirectory(file)
                2 -> { Files.write(target, byteArrayOf(9)); Files.createSymbolicLink(file, target) }
                3 -> Files.createSymbolicLink(file, target)
            }
            assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_EXISTS,
                rejected { NotificationStructureEvidenceFile.writeExpansionFailure(base, receipt, payload) })
            when (kind) {
                0 -> assertArrayEquals(byteArrayOf(7), Files.readAllBytes(file))
                1 -> assertTrue(Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS))
                2 -> { assertTrue(Files.isSymbolicLink(file)); assertArrayEquals(byteArrayOf(9), Files.readAllBytes(target)) }
                3 -> { assertTrue(Files.isSymbolicLink(file)); assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS)) }
            }
        }
    }

    @Test fun longPressFailureRejectsSymlinkDirectoryOrBaseWithoutWritingOutside() {
        for (symlinkBase in listOf(false, true)) {
            val base = base()
            val target = temporary.newFolder().toPath()
            val alias = base.resolve("alias")
            val entry = if (symlinkBase) alias else failureFile(base).parent
            Files.createSymbolicLink(entry, target)
            assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_INVALID,
                rejected { NotificationStructureEvidenceFile.writeExpansionFailure(if (symlinkBase) alias else base, receipt, payload) })
            Files.newDirectoryStream(target).use { assertFalse(it.iterator().hasNext()) }
        }
    }

    @Test fun longPressFailureRejectsTraversalEmptyAndOversizedBeforeCreatingDirectory() {
        val base = base()
        for (invalid in listOf("../other", "x".repeat(36), receipt.uppercase())) {
            assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_INVALID,
                rejected { NotificationStructureEvidenceFile.writeExpansionFailure(base, invalid, payload) })
        }
        for (invalid in listOf(byteArrayOf(), ByteArray(NotificationStructureEvidence.MAX_BYTES + 1))) {
            assertEquals(NotificationStructureEvidenceFile.Code.STRUCTURE_EVIDENCE_INVALID,
                rejected { NotificationStructureEvidenceFile.writeExpansionFailure(base, receipt, invalid) })
        }
        assertFalse(Files.exists(failureFile(base).parent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun longPressFailureAcceptsExactSixteenKiBBoundWithoutChangingIdentityLimit() {
        val base = base()
        val bytes = ByteArray(NotificationStructureEvidence.MAX_BYTES) { 23 }
        NotificationStructureEvidenceFile.writeExpansionFailure(base, receipt, bytes)
        assertArrayEquals(bytes, Files.readAllBytes(failureFile(base)))
        assertEquals(32768, NotificationIdentityEvidence.MAX_BYTES)
    }

    private fun failureFile(base: Path): Path = base.resolve("rootpilot-notification-expansion-failure").resolve("$receipt.json")
    private fun base(): Path = temporary.newFolder().toPath()
    private fun file(base: Path): Path = base.resolve("rootpilot-notification-structure").resolve("$receipt.json")
    private fun prepareDestination(base: Path): Path = file(base).also { Files.createDirectory(it.parent) }
    private fun rejected(block: () -> Unit): NotificationStructureEvidenceFile.Code {
        val error = runCatching(block).exceptionOrNull() ?: throw AssertionError("WRITE_REJECTION_EXPECTED")
        return NotificationStructureEvidenceFile.classify(error)
    }
}
