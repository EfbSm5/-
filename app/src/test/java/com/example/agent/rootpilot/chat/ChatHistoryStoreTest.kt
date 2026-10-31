package com.example.agent.rootpilot.chat

import com.example.agent.rootpilot.AesGcmApiConfigCipher
import com.example.agent.rootpilot.ApiConfigCipher
import com.example.agent.rootpilot.ApiConfigKeyProvider
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatHistoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun sessionRoundTripsAndCiphertextHidesConversation() {
        val directory = directory()
        val session = session(content = "私密回答🔐\n\"\\", sources = listOf(SavedChatSource("标题", "https://example.com/a", "站点")))
        store(directory).write(session)

        assertEquals(session, store(directory).read(session.id))
        val bytes = directory.resolve("${session.id}.enc").readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("私密回答", "example.com", "question", "content")) assertFalse(bytes.contains(secret))
        assertEquals(ChatHistoryListing(listOf(session.summary()), 0), store(directory).list())
        assertEquals(listOf("${session.id}.enc"), directory.list()!!.toList())
    }

    @Test
    fun listSortsNewestFirstAndCountsUnreadableFilesWithoutDeletingThem() {
        val directory = directory()
        val older = session(updated = 1)
        val newer = session(updated = 2)
        store(directory).write(older)
        store(directory).write(newer)
        val damaged = session(updated = 3)
        store(directory).write(damaged)
        val damagedFile = directory.resolve("${damaged.id}.enc")
        damagedFile.writeBytes(damagedFile.readBytes().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() })

        assertEquals(ChatHistoryListing(listOf(newer.summary(), older.summary()), 1), store(directory).list())
        assertTrue(damagedFile.exists())
        assertThrows(AEADBadTagException::class.java) { store(directory).read(damaged.id) }
        // Another key cannot read the session at all.
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(3, store(directory, otherKey).list().unreadable)
    }

    @Test
    fun renamedSessionFileIsRejectedInsteadOfBeingMerged() {
        val directory = directory()
        val original = session()
        store(directory).write(original)
        val otherId = UUID.randomUUID().toString()
        directory.resolve("${original.id}.enc").renameTo(directory.resolve("$otherId.enc"))

        assertThrows(IOException::class.java) { store(directory).read(otherId) }
        assertEquals(1, store(directory).list().unreadable)
    }

    @Test
    fun missingSessionReadsNullAndInvalidIdsNeverTouchTheFileSystem() {
        val directory = directory()
        val provider = object : ApiConfigKeyProvider {
            override fun getOrCreateKey(): SecretKey = error("Unexpected key creation")
            override fun getKey(): SecretKey = error("Unexpected key access")
        }
        val store = EncryptedChatHistoryStore(directory, AesGcmApiConfigCipher(provider))
        assertNull(store.read(UUID.randomUUID().toString()))
        assertEquals(ChatHistoryListing(emptyList(), 0), store.list())
        for (id in listOf("../escape", "", UUID.randomUUID().toString().uppercase())) {
            assertThrows(IllegalArgumentException::class.java) { store.read(id) }
            assertThrows(IllegalArgumentException::class.java) { store.delete(id) }
        }
    }

    @Test
    fun invalidSessionsAreNeverWritten() {
        val directory = directory()
        val valid = session()
        val invalid = listOf(
            valid.copy(id = "not-a-uuid"),
            valid.copy(messages = emptyList()),
            valid.copy(messages = valid.messages.take(1)),
            valid.copy(messages = valid.messages.reversed()),
            valid.copy(messages = List(102) { SavedChatMessage(if (it % 2 == 0) "user" else "assistant", "x") }),
            valid.copy(messages = listOf(valid.messages[0].copy(searched = true), valid.messages[1])),
            valid.copy(title = "t".repeat(41)),
            valid.copy(updatedAtEpochMs = -1),
            valid.copy(version = 2),
        )
        invalid.forEach { session ->
            assertThrows(IllegalArgumentException::class.java) { store(directory).write(session) }
        }
        assertFalse(directory.exists() && directory.list()!!.isNotEmpty())
    }

    @Test
    fun deleteAndClearRemoveOnlyStoreFilesIncludingLeftoverTemporaryCopies() {
        val directory = directory()
        val kept = session()
        val removed = session()
        store(directory).write(kept)
        store(directory).write(removed)
        directory.resolve("chat-123.tmp").writeText("partial")
        directory.resolve("unrelated.txt").writeText("keep")

        store(directory).delete(removed.id)
        store(directory).delete(removed.id)
        assertEquals(listOf(kept.summary()), store(directory).list().sessions)
        assertFalse(directory.resolve("chat-123.tmp").exists())

        directory.resolve("chat-456.tmp").writeText("partial")
        store(directory).clear()
        assertEquals(listOf("unrelated.txt"), directory.list()!!.toList())
    }

    @Test
    fun titleIsSingleLineAndNeverSplitsSurrogatePairs() {
        assertEquals("first line second", chatSessionTitle("  first\n line\tsecond "))
        assertEquals("a".repeat(40), chatSessionTitle("a".repeat(40)))
        assertEquals("a".repeat(39) + "…", chatSessionTitle("a".repeat(41)))
        val emoji = "a".repeat(38) + "😀" + "tail"
        assertEquals("a".repeat(38) + "…", chatSessionTitle(emoji))
        assertTrue(chatSessionTitle(emoji).length <= EncryptedChatHistoryStore.MAX_TITLE_CHARS)
    }

    @Test
    fun toStringNeverIncludesConversationText() {
        val session = session(content = "SECRET_ANSWER", sources = listOf(SavedChatSource("SECRET_TITLE", "https://secret.example")))
        val text = listOf(session, session.messages[1], session.messages[1].sources[0], session.summary()).joinToString()
        for (secret in listOf("SECRET_ANSWER", "SECRET_TITLE", "secret.example", "question")) assertFalse(text.contains(secret))
    }

    private fun directory(): File = temporaryFolder.root.resolve(EncryptedChatHistoryStore.DIRECTORY)

    private fun store(directory: File, secretKey: SecretKey = key) = EncryptedChatHistoryStore(directory, cipher(secretKey))

    private fun cipher(secretKey: SecretKey): ApiConfigCipher = AesGcmApiConfigCipher(object : ApiConfigKeyProvider {
        override fun getOrCreateKey(): SecretKey = secretKey
        override fun getKey(): SecretKey = secretKey
    })

    private fun session(
        updated: Long = 1,
        content: String = "answer",
        sources: List<SavedChatSource> = emptyList(),
    ) = SavedChatSession(
        id = UUID.randomUUID().toString(),
        title = "question",
        updatedAtEpochMs = updated,
        messages = listOf(
            SavedChatMessage("user", "question"),
            SavedChatMessage("assistant", content, searched = sources.isNotEmpty(), sources = sources),
        ),
    )
}
