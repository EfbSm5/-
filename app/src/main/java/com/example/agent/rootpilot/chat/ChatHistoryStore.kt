package com.example.agent.rootpilot.chat

import com.example.agent.rootpilot.ApiConfigCipher
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedChatSource(val title: String, val url: String, val siteName: String? = null) {
    override fun toString() = "SavedChatSource(<redacted>)"
}

/** Only completed plain-chat exchanges are saved; reasoning is never persisted. */
@Serializable
data class SavedChatMessage(
    val role: String,
    val content: String,
    val searched: Boolean = false,
    val sources: List<SavedChatSource> = emptyList(),
) {
    override fun toString() = "SavedChatMessage(role=$role)"
}

@Serializable
data class SavedChatSession(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val messages: List<SavedChatMessage>,
    val version: Int = 1,
) {
    override fun toString() = "SavedChatSession(id=$id, messages=${messages.size})"
}

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val messageCount: Int,
) {
    override fun toString() = "ChatSessionSummary(id=$id, messages=$messageCount)"
}

internal data class ChatHistoryListing(val sessions: List<ChatSessionSummary>, val unreadable: Int)

internal interface ChatHistoryStorage {
    fun list(): ChatHistoryListing
    fun read(id: String): SavedChatSession?
    fun write(session: SavedChatSession)
    fun delete(id: String)
    fun clear()
}

internal fun SavedChatSession.summary() = ChatSessionSummary(id, title, updatedAtEpochMs, messages.size)

/** A single-line prefix of the first question; never splits a surrogate pair. */
internal fun chatSessionTitle(firstQuestion: String): String {
    val line = firstQuestion.trim().replace(Regex("\\s+"), " ")
    if (line.length <= EncryptedChatHistoryStore.MAX_TITLE_CHARS) return line
    val end = EncryptedChatHistoryStore.MAX_TITLE_CHARS - 1
    return line.substring(0, if (line[end - 1].isHighSurrogate()) end - 1 else end) + "…"
}

/** One encrypted file per session; blocking, and only the history repository's worker calls it. */
internal class EncryptedChatHistoryStore(
    private val directory: File,
    private val cipher: ApiConfigCipher,
) : ChatHistoryStorage {
    private val json = Json { encodeDefaults = true }

    override fun list(): ChatHistoryListing {
        cleanTemporaryFiles()
        var unreadable = 0
        val sessions = sessionFiles().mapNotNull { (id, path) ->
            try {
                decode(id, path).summary()
            } catch (_: Exception) {
                // Unreadable files stay on disk until the user clears all history.
                unreadable++
                null
            }
        }.sortedByDescending { it.updatedAtEpochMs }
        return ChatHistoryListing(sessions, unreadable)
    }

    override fun read(id: String): SavedChatSession? = try {
        decode(id, path(id))
    } catch (_: NoSuchFileException) {
        null
    }

    override fun write(session: SavedChatSession) {
        validate(session)
        val plaintext = json.encodeToString(session).encodeToByteArray()
        val ciphertext = try {
            cipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        if (ciphertext.size > MAX_FILE_BYTES) throw IOException("Chat session is too large to save")
        val parent = directory.absoluteFile.toPath()
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX)
        try {
            temporary.toFile().outputStream().use { output ->
                output.write(ciphertext)
                output.fd.sync()
            }
            Files.move(temporary, path(session.id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun delete(id: String) {
        Files.deleteIfExists(path(id))
    }

    /** Removes only session and temporary files written by this store; other names are left untouched. */
    override fun clear() {
        cleanTemporaryFiles()
        sessionFiles().forEach { (_, path) -> Files.deleteIfExists(path) }
    }

    private fun decode(id: String, path: Path): SavedChatSession {
        val ciphertext = Files.newInputStream(path).use { input ->
            input.readNBytes(MAX_FILE_BYTES + 1).also { if (it.size > MAX_FILE_BYTES) throw IOException("Chat session is too large") }
        }
        val plaintext = cipher.decrypt(ciphertext)
        val session = try {
            json.decodeFromString<SavedChatSession>(plaintext.decodeToString(throwOnInvalidSequence = true))
        } catch (_: SerializationException) {
            // Serialization exceptions can include decrypted conversation text.
            throw IOException("Invalid chat history format")
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid chat history format")
        } finally {
            plaintext.fill(0)
        }
        // A file renamed to another session's name is rejected instead of being merged.
        if (session.id != id) throw IOException("Chat session identity mismatch")
        validate(session)
        return session
    }

    private fun path(id: String): Path {
        require(isSessionId(id)) { "Invalid chat session id" }
        return directory.absoluteFile.toPath().resolve("$id$FILE_SUFFIX")
    }

    private fun sessionFiles(): List<Pair<String, Path>> = entries { name ->
        name.endsWith(FILE_SUFFIX) && isSessionId(name.removeSuffix(FILE_SUFFIX))
    }.map { it.fileName.toString().removeSuffix(FILE_SUFFIX) to it }

    /** A killed writer can leave a temporary copy. Never traverse children or delete unrelated names. */
    private fun cleanTemporaryFiles() {
        entries { it.startsWith(TEMP_PREFIX) && it.endsWith(TEMP_SUFFIX) }.forEach { Files.deleteIfExists(it) }
    }

    private fun entries(accept: (String) -> Boolean): List<Path> {
        val stream = try {
            Files.newDirectoryStream(directory.absoluteFile.toPath()) { accept(it.fileName.toString()) }
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        return stream.use { paths ->
            paths.map { path ->
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Unexpected chat history entry")
                path
            }
        }
    }

    private fun validate(session: SavedChatSession) {
        require(session.version == 1 && isSessionId(session.id))
        require(session.updatedAtEpochMs >= 0 && session.title.length <= MAX_TITLE_CHARS)
        require(session.messages.size in 2..MAX_SAVED_MESSAGES && session.messages.size % 2 == 0)
        session.messages.forEachIndexed { index, message ->
            require(message.role == if (index % 2 == 0) "user" else "assistant")
            require(message.role == "assistant" || (!message.searched && message.sources.isEmpty()))
            require(message.sources.size <= MAX_SOURCES)
        }
    }

    companion object {
        const val DIRECTORY = "rootpilot_chat_history"
        const val KEY_ALIAS = "rootpilot_chat_history_aes_v1"
        const val MAX_SAVED_MESSAGES = 100
        const val MAX_TITLE_CHARS = 40
        private const val MAX_SOURCES = 5
        private const val MAX_FILE_BYTES = 8 * 1024 * 1024
        private const val FILE_SUFFIX = ".enc"
        private const val TEMP_PREFIX = "chat-"
        private const val TEMP_SUFFIX = ".tmp"

        private fun isSessionId(value: String) = try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
