package com.example.agent.rootpilot

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
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
)

class RootPilotRunStore(
    private val storageFile: File,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Synchronized
    fun read(): RootPilotRunSnapshot? = runCatching {
        if (!storageFile.isFile) return null
        json.decodeFromString<RootPilotRunSnapshot>(storageFile.readText())
    }.getOrNull()

    @Synchronized
    fun write(snapshot: RootPilotRunSnapshot) {
        val parent = storageFile.parentFile ?: error("RootPilotRunStore 必须有父目录")
        parent.mkdirs()
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
            temporaryFile.delete()
        }
    }

    @Synchronized
    fun clear() {
        storageFile.delete()
    }

    companion object {
        const val FILE_NAME = "rootpilot_run.json"
    }
}
