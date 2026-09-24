package com.example.agent.rootpilot.files

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Immutable private records. Failed/partial records count against quota and are never removed. */
internal class FileBackupStore(private val directory: File) {
    fun save(id: String, label: String, bytes: ByteArray) {
        sanitized {
            validateId(id)
            FileRules.decode(bytes)
            FileRules.segments(label)
            val record = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use {
                    it.writeInt(1)
                    it.writeUTF(label)
                    it.writeInt(bytes.size)
                    it.write(bytes)
                    it.writeUTF(FileRules.hash(bytes))
                }
            }.toByteArray()
            if (!directory.isDirectory && !directory.mkdirs()) fail(FileErrorCode.BACKUP_FAILED)
            val existing = directory.listFiles() ?: fail(FileErrorCode.BACKUP_FAILED)
            if (existing.any { !it.isFile } || existing.size >= 1000 ||
                existing.sumOf { it.length() } + record.size > FileRules.BACKUP_QUOTA
            ) fail(FileErrorCode.BACKUP_FULL)
            val target = file(id)
            if (!target.createNewFile()) fail(FileErrorCode.BACKUP_FAILED)
            FileOutputStream(target).use { stream ->
                stream.write(record)
                stream.flush()
                stream.fd.sync()
            }
            if (!read(id).second.contentEquals(bytes)) fail(FileErrorCode.BACKUP_FAILED)
        }
    }

    fun list(): List<FileBackupInfo> = sanitized {
        if (!directory.exists()) return@sanitized emptyList()
        val files = directory.listFiles() ?: fail(FileErrorCode.BACKUP_FAILED)
        files.asSequence().filter { it.name.endsWith(".backup") }
            .sortedByDescending { it.lastModified() }.mapNotNull { candidate ->
            val id = candidate.name.removeSuffix(".backup")
            try {
                val (label, _) = read(id)
                FileBackupInfo(id, label)
            } catch (_: Exception) {
                // An interrupted backup is not exportable; retain it and its quota usage.
                null
            }
        }.toList()
    }

    fun bytes(id: String): ByteArray = sanitized { read(id).second }

    private fun read(id: String): Pair<String, ByteArray> {
        validateId(id)
        val target = file(id)
        if (!target.isFile) fail(FileErrorCode.BACKUP_NOT_FOUND)
        if (target.length() > FileRules.MAX_BYTES + 16384) fail(FileErrorCode.BACKUP_FAILED)
        return DataInputStream(target.inputStream().buffered()).use { input ->
            if (input.readInt() != 1) fail(FileErrorCode.BACKUP_FAILED)
            val label = input.readUTF()
            if (FileRules.segments(label).size != 1) fail(FileErrorCode.BACKUP_FAILED)
            val size = input.readInt()
            if (size !in 0..FileRules.MAX_BYTES) fail(FileErrorCode.BACKUP_FAILED)
            val bytes = ByteArray(size)
            input.readFully(bytes)
            if (input.readUTF() != FileRules.hash(bytes) || input.read() != -1) fail(FileErrorCode.BACKUP_FAILED)
            FileRules.decode(bytes)
            label to bytes
        }
    }

    private fun file(id: String) = File(directory, "$id.backup")

    private fun validateId(id: String) {
        val valid = try { UUID.fromString(id).toString() == id } catch (_: Exception) { false }
        if (!valid) fail(FileErrorCode.BACKUP_NOT_FOUND)
    }
}
