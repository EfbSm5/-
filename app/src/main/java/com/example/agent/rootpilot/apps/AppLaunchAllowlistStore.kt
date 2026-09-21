package com.example.agent.rootpilot.apps

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppLaunchAllowlistStore(private val file: File) {
    fun read(): Set<String> = try {
        Json.decodeFromString<List<String>>(file.readText())
            .also { packages -> require(packages.all { PACKAGE_NAME.matches(it) }) }
            .toSet()
    } catch (_: Exception) {
        // Missing, unreadable or invalid selection never grants launch access.
        emptySet()
    }

    fun save(packages: Set<String>) {
        require(packages.all { PACKAGE_NAME.matches(it) }) { "应用包名不合法" }
        val parent = file.absoluteFile.parentFile ?: error("应用选择文件必须有父目录")
        parent.mkdirs()
        val temporaryFile = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(Json.encodeToString(packages.sorted()).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Each reader sees a complete selection; failed replacement leaves the previous file intact.
            Files.move(
                temporaryFile.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporaryFile.delete()
        }
    }

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun create(context: Context): AppLaunchAllowlistStore =
            AppLaunchAllowlistStore(File(context.noBackupFilesDir, "rootpilot_app_launch_allowlist.json"))
    }
}
