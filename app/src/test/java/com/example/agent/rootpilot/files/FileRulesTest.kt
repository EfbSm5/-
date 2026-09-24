package com.example.agent.rootpilot.files

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class FileRulesTest {
    @Test fun pathsRejectTraversalUrisAndEmptySegments() {
        listOf("", "/a", "a/", "a//b", "..", "a/../b", "a/./b", "content://tree/a", "file:a",
            "a\\b", "a\u0000b", "%2e%2e/a").forEach { path ->
            assertEquals(FileErrorCode.INVALID_PATH, assertThrows(FileStorageException::class.java) {
                FileRules.segments(path)
            }.code)
        }
        assertEquals(emptyList<String>(), FileRules.segments("", true))
        assertEquals(listOf("目录", "test.txt"), FileRules.segments("目录/test.txt"))
    }

    @Test fun utf8HasByteLimitAndRejectsMalformedOrBinaryText() {
        assertEquals(65536, FileRules.encode("a".repeat(65536)).size)
        assertThrows(FileStorageException::class.java) { FileRules.encode("中".repeat(21846)) }
        assertThrows(FileStorageException::class.java) { FileRules.encode("\uD800") }
        assertThrows(FileStorageException::class.java) { FileRules.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(FileStorageException::class.java) { FileRules.decode(byteArrayOf(0)) }
        val text = "\uFEFF中文🙂\r\n\t"
        assertEquals(text, FileRules.decode(FileRules.encode(text)))
        assertThrows(FileStorageException::class.java) {
            FileRules.boundedRead(ByteArrayInputStream(ByteArray(65537)))
        }
    }

    @Test fun editRequiresExactlyOneIncludingOverlappingMatch() {
        assertEquals("a新c", FileRules.edit("abc", "b", "新"))
        assertEquals("ac", FileRules.edit("abc", "b", ""))
        listOf("" to "a", "z" to "a", "a" to "aa").forEach { (old, before) ->
            assertThrows(FileStorageException::class.java) { FileRules.edit(before, old, "x") }
        }
        assertThrows(FileStorageException::class.java) { FileRules.edit("aaa", "aa", "b") }
        assertThrows(FileStorageException::class.java) { FileRules.edit("a".repeat(65535) + "b", "b", "xx") }
    }

    @Test fun conflictIncludesAncestryAndIdentityEvenWithSameBytes() {
        val hash = FileRules.hash("same".toByteArray())
        FileRules.verify(listOf("root", "id"), listOf("root", "id"), hash, hash)
        assertThrows(FileStorageException::class.java) {
            FileRules.verify(listOf("root", "id"), listOf("other", "id"), hash, hash)
        }
        assertThrows(FileStorageException::class.java) {
            FileRules.verify(listOf("root", "id"), listOf("root", "id"), hash, null)
        }
    }

    @Test fun publicTypesDoNotRenderSensitiveFields() {
        val secret = "private-name-content"
        val objects = listOf(FileEntry(secret, false), FileBackupInfo(secret, secret),
            PreparedFileChange(secret, secret, secret, secret), FileWriteReceipt(secret, secret),
            FileStorageException(FileErrorCode.WRITE_FAILED, secret, true), FileWriteCancelledException(secret, true))
        objects.forEach { assertFalse(it.toString().contains(secret)) }
    }
}
