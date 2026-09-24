package com.example.agent.rootpilot.files

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FileChangeEngineTest {
    private class Fake : FileChangeBackend {
        var text: String? = "before"
        var selection = "selection"
        var identity = listOf("root", "doc")
        val backups = mutableMapOf<String, ByteArray>()
        var writes = 0
        var backupError = false
        var writeError = false
        var cancelInWrite = false
        var mutateAfterBackup = false
        override fun snapshot(path: String, allowMissing: Boolean) = FileSnapshot(selection, identity, text)
        override fun backup(id: String, label: String, bytes: ByteArray) {
            if (backupError) fail(FileErrorCode.BACKUP_FULL)
            backups[id] = bytes.copyOf()
            if (mutateAfterBackup) text = "external"
        }
        override fun write(path: String, expected: FileSnapshot, content: ByteArray) {
            if (expected.text != null) assertArrayEquals(expected.text.toByteArray(), backups.values.single())
            writes++
            text = if (writeError || cancelInWrite) "partial" else FileRules.decode(content)
            if (cancelInWrite) throw CancellationException("sensitive provider text")
            if (writeError) throw IllegalStateException("sensitive provider text")
            if (expected.text == null) identity = identity + "created"
        }
    }

    @Test fun successfulEditBacksUpFirstAndConsumesPreview() {
        val fake = Fake()
        val engine = FileChangeEngine(fake)
        val change = engine.prepareEdit("file.txt", "before", "after")
        val receipt = engine.commit(change) {}
        assertEquals(change.id, receipt.backupId)
        assertEquals("after", fake.text)
        assertEquals(1, fake.writes)
        assertThrows(FileStorageException::class.java) { engine.commit(change) {} }
    }

    @Test fun createRequiresAbsenceAndHasNoBackup() {
        val fake = Fake()
        val engine = FileChangeEngine(fake)
        assertThrows(FileStorageException::class.java) { engine.prepareCreate("a", "new") }
        fake.text = null
        fake.identity = listOf("root")
        val change = engine.prepareCreate("a", "new")
        assertNull(engine.commit(change) {}.backupId)
        assertTrue(fake.backups.isEmpty())
    }

    @Test fun changedContentIdentitySelectionOrCreateCollisionRejectsBeforeWrite() {
        listOf<(Fake) -> Unit>({ it.text = "external" }, { it.identity = listOf("root", "replacement") },
            { it.selection = "reselected" }).forEach { mutate ->
            val fake = Fake()
            val engine = FileChangeEngine(fake)
            val change = engine.prepareEdit("a", "before", "after")
            mutate(fake)
            assertEquals(FileErrorCode.CONFLICT, assertThrows(FileStorageException::class.java) {
                engine.commit(change) {}
            }.code)
            assertEquals(0, fake.writes)
            assertTrue(fake.backups.isEmpty())
        }
        val fake = Fake().apply { text = null }
        val engine = FileChangeEngine(fake)
        val change = engine.prepareCreate("a", "new")
        fake.text = "created elsewhere"
        assertThrows(FileStorageException::class.java) { engine.commit(change) {} }
        assertEquals(0, fake.writes)
    }

    @Test fun backupQuotaFailureNeverTouchesOriginalAndCannotReplay() {
        val fake = Fake().apply { backupError = true }
        val engine = FileChangeEngine(fake)
        val change = engine.prepareEdit("a", "before", "after")
        val error = assertThrows(FileStorageException::class.java) { engine.commit(change) {} }
        assertEquals(FileErrorCode.BACKUP_FULL, error.code)
        assertNull(error.backupId)
        assertFalse(error.mayHaveWritten)
        assertEquals("before", fake.text)
        assertThrows(FileStorageException::class.java) { engine.commit(change) {} }
    }

    @Test fun conflictDuringBackupRetainsBackupWithoutWriting() {
        val fake = Fake().apply { mutateAfterBackup = true }
        val engine = FileChangeEngine(fake)
        val change = engine.prepareEdit("a", "before", "after")
        val error = assertThrows(FileStorageException::class.java) { engine.commit(change) {} }
        assertEquals(change.id, error.backupId)
        assertFalse(error.mayHaveWritten)
        assertEquals(0, fake.writes)
        assertEquals(1, fake.backups.size)
    }

    @Test fun partialWriteAndCancellationKeepBackupAndNeverReplay() {
        listOf(false, true).forEach { cancellation ->
            val fake = Fake().apply { writeError = !cancellation; cancelInWrite = cancellation }
            val engine = FileChangeEngine(fake)
            val change = engine.prepareEdit("a", "before", "after")
            if (cancellation) {
                val error = assertThrows(FileWriteCancelledException::class.java) { engine.commit(change) {} }
                assertEquals(change.id, error.backupId)
                assertTrue(error.mayHaveWritten)
                assertNull(error.cause)
            } else {
                val error = assertThrows(FileStorageException::class.java) { engine.commit(change) {} }
                assertEquals(change.id, error.backupId)
                assertTrue(error.mayHaveWritten)
                assertNull(error.cause)
            }
            assertEquals("partial", fake.text)
            assertEquals(1, fake.backups.size)
            assertThrows(FileStorageException::class.java) { engine.commit(change) {} }
            assertEquals(1, fake.writes)
        }
    }

    @Test fun cancelledBeforeWriteConsumesAndForgedPreviewCannotCommit() {
        val fake = Fake()
        val engine = FileChangeEngine(fake)
        val change = engine.prepareEdit("a", "before", "after")
        assertThrows(FileStorageException::class.java) {
            engine.commit(PreparedFileChange(change.id, change.path, change.before, change.after)) {}
        }
        assertThrows(FileWriteCancelledException::class.java) {
            engine.commit(change) { throw CancellationException() }
        }
        assertThrows(FileStorageException::class.java) { engine.commit(change) {} }
        assertEquals(0, fake.writes)
    }

    @Test fun discardingRejectedPreviewsReleasesCapacityAndIsIdempotent() {
        val engine = FileChangeEngine(Fake())
        repeat(32) {
            val change = engine.prepareEdit("a", "before", "after")
            engine.discard(change)
            engine.discard(change)
            assertEquals(FileErrorCode.INVALID_CHANGE, assertThrows(FileStorageException::class.java) {
                engine.commit(change) {}
            }.code)
        }
        val change = engine.prepareEdit("a", "before", "after")
        engine.commit(change) {}
    }

    @Test fun forgedDiscardDoesNotRemoveRealPreview() {
        val engine = FileChangeEngine(Fake())
        val change = engine.prepareEdit("a", "before", "after")
        engine.discard(PreparedFileChange(change.id, change.path, change.before, change.after))
        engine.commit(change) {}
    }
}
