package com.example.agent.rootpilot.files

import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Only synthetic files in JUnit's isolated temporary directory. */
class FileBackupStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newInstanceListsAndExportsRetainedUtf8Backup() {
        val directory = temporary.newFolder()
        val id = UUID.randomUUID().toString()
        val content = "fixture中文\n".toByteArray()
        FileBackupStore(directory).save(id, "fixture.txt", content)
        val reopened = FileBackupStore(directory)
        assertEquals(id, reopened.list().single().id)
        assertEquals("fixture.txt", reopened.list().single().label)
        assertArrayEquals(content, reopened.bytes(id))
        assertThrows(FileStorageException::class.java) { reopened.save(id, "other", byteArrayOf()) }
        assertArrayEquals(content, reopened.bytes(id))
    }

    @Test fun listIncludesAll101BackupsAndEveryListedBackupIsReadable() {
        val directory = temporary.newFolder()
        val store = FileBackupStore(directory)
        val ids = List(101) { UUID.randomUUID().toString() }
        val contents = ids.associateWith { "fixture-$it".toByteArray(Charsets.UTF_8) }
        contents.forEach { (id, bytes) -> store.save(id, "fixture.txt", bytes) }
        val reopened = FileBackupStore(directory)
        val listed = reopened.list()
        assertEquals(101, listed.size)
        assertEquals(ids.toSet(), listed.map { it.id }.toSet())
        assertEquals(101, directory.listFiles()!!.size)
        listed.forEach { assertArrayEquals(contents.getValue(it.id), reopened.bytes(it.id)) }
    }

    @Test fun quotaRejectsAndKeepsExistingBackup() {
        val directory = temporary.newFolder()
        val store = FileBackupStore(directory)
        val first = UUID.randomUUID().toString()
        store.save(first, "fixture.txt", "original".toByteArray())
        // A retained interrupted record still occupies quota.
        java.io.RandomAccessFile(directory.resolve("interrupted.backup"), "rw").use {
            it.setLength(FileRules.BACKUP_QUOTA)
        }
        val error = assertThrows(FileStorageException::class.java) {
            store.save(UUID.randomUUID().toString(), "new.txt", "next".toByteArray())
        }
        assertEquals(FileErrorCode.BACKUP_FULL, error.code)
        assertArrayEquals("original".toByteArray(), store.bytes(first))
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test fun truncatedBackupIsRetainedButNotOfferedForExport() {
        val directory = temporary.newFolder()
        val id = UUID.randomUUID().toString()
        val file = directory.resolve("$id.backup")
        file.writeBytes(byteArrayOf(0, 0, 0, 1))
        val store = FileBackupStore(directory)
        assertTrue(store.list().isEmpty())
        assertThrows(FileStorageException::class.java) { store.bytes(id) }
        assertTrue(file.exists())
        assertThrows(FileStorageException::class.java) { store.bytes("../outside") }
    }
}
