package com.example.agent.rootpilot.history

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface RunHistoryStorage {
    fun read(): List<RunHistoryRecord>
    fun write(records: List<RunHistoryRecord>)
    fun clear()
}

/** Only the history repository's IO worker accesses this file, independently of RunStore. */
internal class RunHistoryStore(private val file: File) : RunHistoryStorage {
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Document(val version: Int = 1, val records: List<RunHistoryRecord>)

    override fun read(): List<RunHistoryRecord> {
        cleanTemporaryFiles()
        val content = try {
            Files.newInputStream(file.toPath()).use { input ->
                val bytes = input.readNBytes(MAX_BYTES + 1)
                require(bytes.size <= MAX_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
        } catch (_: NoSuchFileException) {
            return emptyList()
        }
        val document = json.decodeFromString<Document>(content)
        require(document.version == 1)
        validate(document.records)
        return document.records
    }

    override fun write(records: List<RunHistoryRecord>) {
        validate(records)
        val parent = requireNotNull(file.parentFile)
        Files.createDirectories(parent.toPath())
        cleanTemporaryFiles()
        val temporary = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, parent)
        try {
            temporary.writeText(json.encodeToString(Document(records = records)))
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    override fun clear() {
        cleanTemporaryFiles()
        Files.deleteIfExists(file.toPath())
    }

    /** A killed writer can leave a full copy. Never traverse children or delete unrelated names. */
    private fun cleanTemporaryFiles() {
        val parent = requireNotNull(file.parentFile).toPath()
        val entries = try {
            Files.newDirectoryStream(parent) { entry ->
                val name = entry.fileName.toString()
                name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)
            }
        } catch (_: NoSuchFileException) {
            return
        }
        entries.use { paths ->
            paths.forEach { path ->
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("History temporary cleanup failed")
                }
                Files.deleteIfExists(path)
            }
        }
    }

    private fun validate(records: List<RunHistoryRecord>) {
        require(records.size <= RunHistoryRepository.MAX_RECORDS)
        require(records.map { it.id }.distinct().size == records.size)
        records.forEach { record ->
            require(UUID.fromString(record.id).toString() == record.id)
            require(record.startedAtEpochMs >= 0 && record.durationMs >= 0 && record.stepCount in 0..MAX_STEPS)
            require(record.events.size <= RunHistoryRepository.MAX_EVENTS)
            record.events.forEach { event ->
                require(event.runId == record.id && event.step in -1 until MAX_STEPS && event.elapsedMs >= 0)
            }
        }
    }

    companion object {
        const val FILE_NAME = "runs.json"
        const val MAX_STEPS = 20
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val TEMP_PREFIX = "history-"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
