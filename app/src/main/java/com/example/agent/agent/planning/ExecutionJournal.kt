package com.example.agent.agent.planning

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ExecutionRunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

@Serializable
data class ExecutionRecord(
    val runId: String,
    val status: ExecutionRunStatus,
    val report: ToolExecutionReport,
    val failureMessage: String? = null,
)

interface ExecutionJournal {
    suspend fun read(runId: String): ExecutionRecord?

    suspend fun write(record: ExecutionRecord)
}

class InMemoryExecutionJournal : ExecutionJournal {
    private val records = ConcurrentHashMap<String, ExecutionRecord>()

    override suspend fun read(runId: String): ExecutionRecord? = records[runId]

    override suspend fun write(record: ExecutionRecord) {
        records[record.runId] = record
    }
}

class FileExecutionJournal(
    private val storageFile: File,
) : ExecutionJournal {
    private val mutex = locks.getOrPut(storageFile.absolutePath) { Mutex() }
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    override suspend fun read(runId: String): ExecutionRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readStoredRecords().firstOrNull { it.runId == runId }
        }
    }

    override suspend fun write(record: ExecutionRecord) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val records = readStoredRecords()
                    .filterNot { it.runId == record.runId } + record
                writeStoredRecords(records)
            }
        }
    }

    private fun readStoredRecords(): List<ExecutionRecord> {
        if (!storageFile.isFile) return emptyList()
        return json.decodeFromString(storageFile.readText())
    }

    private fun writeStoredRecords(records: List<ExecutionRecord>) {
        val parent = storageFile.parentFile
            ?: error("Execution journal 必须有父目录")
        parent.mkdirs()
        val temporaryFile = File.createTempFile("${storageFile.name}.", ".tmp", parent)
        try {
            temporaryFile.writeText(json.encodeToString(records))
            try {
                Files.move(
                    temporaryFile.toPath(),
                    storageFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    storageFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporaryFile.delete()
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

internal fun newExecutionRunId(): String = UUID.randomUUID().toString()
