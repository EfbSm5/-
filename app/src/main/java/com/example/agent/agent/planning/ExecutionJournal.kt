package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentAction
import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.model.OpenApp
import com.example.agent.agent.serialization.AgentPlanDecoder
import com.example.agent.agent.serialization.PlanDecodeResult
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ExecutionRunStatus {
    RUNNING,
    RECOVERING,
    SUCCEEDED,
    FAILED,
}

@Serializable
data class ExecutionRecord(
    val runId: String,
    val status: ExecutionRunStatus,
    val report: ToolExecutionReport,
    val failureMessage: String? = null,
    val plan: PersistedAgentPlan? = null,
    val ownerToken: String? = null,
    val version: Long = 0L,
)

@Serializable
data class PersistedAgentPlan(
    val goal: String,
    val actions: List<PersistedAgentAction>,
) {
    fun toDomainOrNull(): AgentPlan? = runCatching {
        when (val result = AgentPlanDecoder().decode(SNAPSHOT_JSON.encodeToString(this))) {
            is PlanDecodeResult.Success -> result.plan
            is PlanDecodeResult.Failure -> null
        }
    }.getOrNull()

    companion object {
        fun fromDomain(plan: AgentPlan): PersistedAgentPlan = PersistedAgentPlan(
            goal = plan.goal,
            actions = plan.actions.map(PersistedAgentAction::fromDomain),
        )

        private val SNAPSHOT_JSON = Json {
            encodeDefaults = false
        }
    }
}

@Serializable
data class PersistedAgentAction(
    val type: String,
    val title: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    val question: String? = null,
) {
    companion object {
        fun fromDomain(action: AgentAction): PersistedAgentAction = when (action) {
            is CreateTodo -> PersistedAgentAction(
                type = "create_todo",
                title = action.title,
                dueAt = action.dueAt,
            )

            is OpenApp -> PersistedAgentAction(
                type = "open_app",
                packageName = action.packageName,
            )

            is AskUser -> PersistedAgentAction(
                type = "ask_user",
                question = action.question,
            )
        }
    }
}

data class RecoverableExecution(
    val record: ExecutionRecord,
    val plan: AgentPlan?,
)

interface ExecutionJournal {
    suspend fun read(runId: String): ExecutionRecord?

    suspend fun write(record: ExecutionRecord)

    suspend fun claim(runId: String, ownerToken: String): ExecutionRecord?

    suspend fun listUnfinished(): List<ExecutionRecord>

    suspend fun delete(runId: String): Boolean

    fun release(runId: String, ownerToken: String) {}
}

class InMemoryExecutionJournal : ExecutionJournal {
    private val records = ConcurrentHashMap<String, ExecutionRecord>()
    private val liveOwners = ConcurrentHashMap<String, String>()

    override suspend fun read(runId: String): ExecutionRecord? = records[runId]

    override suspend fun write(record: ExecutionRecord) {
        synchronized(records) {
            val current = records[record.runId]
            if (current?.ownerToken != null && current.ownerToken != record.ownerToken) {
                error("执行记录已被其他执行者占用")
            }
            records[record.runId] = record
            if ((record.status == ExecutionRunStatus.RUNNING ||
                record.status == ExecutionRunStatus.RECOVERING) &&
                record.ownerToken != null
            ) {
                liveOwners[record.runId] = record.ownerToken
            } else {
                liveOwners.remove(record.runId)
            }
        }
    }

    override suspend fun claim(runId: String, ownerToken: String): ExecutionRecord? =
        synchronized(records) {
            val current = records[runId] ?: return@synchronized null
            if (current.status == ExecutionRunStatus.SUCCEEDED || liveOwners.containsKey(runId)) {
                return@synchronized null
            }
            current.copy(
                status = ExecutionRunStatus.RECOVERING,
                ownerToken = ownerToken,
                version = current.version + 1,
            ).also {
                records[runId] = it
                liveOwners[runId] = ownerToken
            }
    }

    override suspend fun listUnfinished(): List<ExecutionRecord> = records.values
        .filter { it.status != ExecutionRunStatus.SUCCEEDED }

    override suspend fun delete(runId: String): Boolean = synchronized(records) {
        val current = records[runId] ?: return@synchronized false
        if (liveOwners.containsKey(runId)) {
            return@synchronized false
        }
        records.remove(runId) != null
    }

    override fun release(runId: String, ownerToken: String) {
        if (liveOwners[runId] == ownerToken) {
            liveOwners.remove(runId)
        }
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
                val current = readStoredRecords().firstOrNull { it.runId == record.runId }
                if (current?.ownerToken != null && current.ownerToken != record.ownerToken) {
                    error("执行记录已被其他执行者占用")
                }
                val records = readStoredRecords()
                    .filterNot { it.runId == record.runId } + record
                writeStoredRecords(records)
                val key = ownerKey(record.runId)
                if ((record.status == ExecutionRunStatus.RUNNING ||
                    record.status == ExecutionRunStatus.RECOVERING) &&
                    record.ownerToken != null
                ) {
                    liveOwners[key] = record.ownerToken
                } else {
                    liveOwners.remove(key)
                }
            }
        }
    }

    override suspend fun claim(runId: String, ownerToken: String): ExecutionRecord? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val records = readStoredRecords()
                val current = records.firstOrNull { it.runId == runId }
                    ?: return@withLock null
                val key = ownerKey(runId)
                if (current.status == ExecutionRunStatus.SUCCEEDED || liveOwners.containsKey(key)) {
                    return@withLock null
                }
                val claimed = current.copy(
                    status = ExecutionRunStatus.RECOVERING,
                    ownerToken = ownerToken,
                    version = current.version + 1,
                )
                writeStoredRecords(records.filterNot { it.runId == runId } + claimed)
                liveOwners[key] = ownerToken
                claimed
            }
        }

    override suspend fun listUnfinished(): List<ExecutionRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readStoredRecords().filter { it.status != ExecutionRunStatus.SUCCEEDED }
        }
    }

    override suspend fun delete(runId: String): Boolean = withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readStoredRecords().firstOrNull { it.runId == runId }
                    ?: return@withLock false
                if (liveOwners.containsKey(ownerKey(runId))) {
                    return@withLock false
                }
                val records = readStoredRecords().filterNot { it.runId == runId }
                writeStoredRecords(records)
                liveOwners.remove(ownerKey(runId))
                true
            }
        }

    override fun release(runId: String, ownerToken: String) {
        val key = ownerKey(runId)
        if (liveOwners[key] == ownerToken) {
            liveOwners.remove(key)
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

    private fun ownerKey(runId: String): String = storageFile.absolutePath + "::" + runId

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
        val liveOwners = ConcurrentHashMap<String, String>()
    }
}

internal fun newExecutionRunId(): String = UUID.randomUUID().toString()
