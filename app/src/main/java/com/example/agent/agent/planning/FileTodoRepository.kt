package com.example.agent.agent.planning

import com.example.agent.agent.model.CreateTodo
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileTodoRepository(
    private val storageFile: File,
) : TodoRepository {
    private val mutex = locks.getOrPut(storageFile.absolutePath) { Mutex() }
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    override suspend fun addAll(todos: List<CreateTodo>) {
        if (todos.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val currentTodos = readStoredTodos()
                writeStoredTodos(currentTodos + todos.map(StoredTodo::fromDomain))
            }
        }
    }

    override suspend fun list(): List<CreateTodo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readStoredTodos().map(StoredTodo::toDomain)
        }
    }

    private fun readStoredTodos(): List<StoredTodo> {
        if (!storageFile.isFile) return emptyList()
        return json.decodeFromString(storageFile.readText())
    }

    private fun writeStoredTodos(todos: List<StoredTodo>) {
        val parent = storageFile.parentFile
            ?: error("Todo 存储文件必须有父目录")
        parent.mkdirs()
        val temporaryFile = File.createTempFile("${storageFile.name}.", ".tmp", parent)
        try {
            temporaryFile.writeText(json.encodeToString<List<StoredTodo>>(todos))
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

    @Serializable
    private data class StoredTodo(
        val title: String,
        @SerialName("due_at") val dueAt: String? = null,
    ) {
        fun toDomain(): CreateTodo = CreateTodo(title = title, dueAt = dueAt)

        companion object {
            fun fromDomain(todo: CreateTodo): StoredTodo = StoredTodo(
                title = todo.title,
                dueAt = todo.dueAt,
            )
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
