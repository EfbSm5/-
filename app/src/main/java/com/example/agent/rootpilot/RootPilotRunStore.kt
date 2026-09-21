package com.example.agent.rootpilot

import com.example.agent.rootpilot.model.RootPilotConfig
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RootPilotRunSnapshot(
    val baseUrl: String,
    val model: String,
    val task: String,
    val manualConfirmation: Boolean,
    val allowScreenUpload: Boolean,
    val status: String,
    val step: Int,
    val actionSummary: String? = null,
) {
    // API credentials and endpoint are owned by the saved configuration, never by a run.
    fun restoreTask(config: RootPilotConfig): RootPilotConfig = config.copy(
        task = task,
        manualConfirmation = manualConfirmation,
        allowScreenUpload = allowScreenUpload,
    )
}

// Do not attach the original cause: filesystem and serialization errors can contain private data.
class RootPilotRunStoreException(val operation: String) :
    IOException("Run snapshot $operation failed")

class RootPilotRunStore(
    private val storageFile: File,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Synchronized
    fun read(): RootPilotRunSnapshot? = storageOperation("read") {
        val content = try {
            Files.readAllBytes(storageFile.toPath()).toString(Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return@storageOperation null
        }
        json.decodeFromString<RootPilotRunSnapshot>(content)
    }

    @Synchronized
    fun write(snapshot: RootPilotRunSnapshot): Unit = storageOperation("write") {
        val parent = storageFile.parentFile ?: error("RootPilotRunStore 必须有父目录")
        Files.createDirectories(parent.toPath())
        val temporaryFile = File.createTempFile("${storageFile.name}.", ".tmp", parent)
        try {
            temporaryFile.writeText(json.encodeToString(snapshot))
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
            Files.deleteIfExists(temporaryFile.toPath())
        }
        Unit
    }

    @Synchronized
    fun clear(): Unit = storageOperation("clear") {
        Files.deleteIfExists(storageFile.toPath())
        Unit
    }

    private inline fun <T> storageOperation(operation: String, block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw RootPilotRunStoreException(operation)
    }

    companion object {
        const val FILE_NAME = "rootpilot_run.json"
    }
}
