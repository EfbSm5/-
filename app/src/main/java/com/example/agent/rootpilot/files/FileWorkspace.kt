package com.example.agent.rootpilot.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A single user-selected SAF tree. Call synchronous selection methods on IO.
 * The caller owns user confirmation before commit; this class never requests root or shell access.
 * All instances serialize in this process. Other apps/providers are outside that lock: SAF has no
 * compare-and-swap transaction, so even a successful readback cannot promise cross-app atomicity.
 */
class FileWorkspace(context: Context) : WorkspaceAccess {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val directory = File(appContext.noBackupFilesDir, "rootpilot_files")
    private val pointer = AtomicFile(File(directory, "selection"))
    private val backups = FileBackupStore(File(directory, "backups"))
    private var checkpoint: () -> Unit = {}
    private val engine = FileChangeEngine(object : FileChangeBackend {
        override fun snapshot(path: String, allowMissing: Boolean): FileSnapshot {
            val selected = requireSelection()
            val access = access(selected)
            val resolved = access.resolve(path, allowMissing)
            access.requireWritable(resolved)
            return FileSnapshot(selected.token, resolved.chain, resolved.node?.let { access.read(it) })
        }

        override fun write(path: String, expected: FileSnapshot, content: ByteArray) {
            val selected = requireSelection()
            if (selected.token != expected.selection) fail(FileErrorCode.CONFLICT)
            access(selected).write(path, expected, content)
        }

        override fun backup(id: String, label: String, bytes: ByteArray) = backups.save(id, label, bytes)
    })

    val selected: Boolean get() = synchronized(lock) {
        sanitized { selection()?.let { hasGrant(it.uri) } ?: false }
    }

    fun selectedLabel(): String? = synchronized(lock) {
        sanitized {
            val selected = selection() ?: return@sanitized null
            if (!hasGrant(selected.uri)) return@sanitized null
            access(selected).root().name
        }
    }

    fun select(uri: Uri, grantFlags: Int) = synchronized(lock) {
        sanitized {
            validateTree(uri)
            if (grantFlags and READ_WRITE != READ_WRITE ||
                grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
                fail(FileErrorCode.NOT_WRITABLE)
            }
            // Check the provider using the picker grant before replacing the saved selection.
            SafTreeAccess(resolver, uri).root()
            val old = selection()
            engine.invalidate()
            saveSelection(null)
            if (old != null && old.uri != uri && hasAnyGrant(old.uri)) {
                resolver.releasePersistableUriPermission(old.uri, READ_WRITE)
            }
            resolver.takePersistableUriPermission(uri, READ_WRITE)
            if (!hasGrant(uri)) fail(FileErrorCode.NOT_WRITABLE)
            saveSelection(Selection(UUID.randomUUID().toString(), uri))
        }
    }

    /** Removes only the workspace pointer/grant, never documents or retained backups. */
    fun clear() = synchronized(lock) {
        sanitized {
            val old = selection()
            engine.invalidate()
            saveSelection(null)
            if (old != null && hasAnyGrant(old.uri)) resolver.releasePersistableUriPermission(old.uri, READ_WRITE)
        }
    }

    override suspend fun list(path: String): List<FileEntry> = operation {
        val access = access(requireSelection())
        val node = access.resolve(path, allowRoot = true).node ?: fail(FileErrorCode.NOT_FOUND)
        access.children(node).map { FileEntry(it.name, it.directory) }
    }

    override suspend fun read(path: String): String = operation {
        val access = access(requireSelection())
        val node = access.resolve(path).node ?: fail(FileErrorCode.NOT_FOUND)
        access.read(node)
    }

    override suspend fun prepareCreate(path: String, content: String): PreparedFileChange = prepare {
        engine.prepareCreate(path, content)
    }

    override suspend fun prepareEdit(path: String, oldText: String, newText: String): PreparedFileChange = prepare {
        engine.prepareEdit(path, oldText, newText)
    }

    override suspend fun discard(change: PreparedFileChange) = operation { engine.discard(change) }

    override suspend fun commit(change: PreparedFileChange): FileWriteReceipt {
        // Include dispatch-return cancellation in the sanitized outcome. A write may already exist.
        var receipt: FileWriteReceipt? = null
        var started = false
        try {
            return operation {
                started = true
                engine.commit(change, checkpoint).also { receipt = it }
            }
        } catch (e: FileWriteCancelledException) {
            synchronized(lock) { engine.discard(change) }
            if (receipt != null) throw FileWriteCancelledException(receipt?.backupId, true)
            throw e
        } catch (_: java.util.concurrent.CancellationException) {
            synchronized(lock) { engine.discard(change) }
            throw FileWriteCancelledException(receipt?.backupId, started)
        }
    }

    suspend fun listBackups(): List<FileBackupInfo> = operation { backups.list() }

    suspend fun backupBytes(id: String): ByteArray = operation { backups.bytes(id) }

    override fun toString() = "FileWorkspace(redacted)"

    private suspend fun prepare(block: () -> PreparedFileChange): PreparedFileChange {
        var prepared: PreparedFileChange? = null
        try {
            return operation {
                checkpoint()
                block().also { prepared = it }
            }
        } catch (_: java.util.concurrent.CancellationException) {
            // withContext may cancel while dispatching back after the preview was registered.
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(lock) { prepared?.let(engine::discard) }
            }
            throw FileWriteCancelledException(null, false)
        }
    }

    private suspend fun <T> operation(block: () -> T): T = withContext(Dispatchers.IO) {
        val jobContext = currentCoroutineContext()
        synchronized(lock) {
            checkpoint = { jobContext.ensureActive() }
            try {
                sanitized { block() }
            } finally {
                checkpoint = {}
            }
        }
    }

    private class Selection(val token: String, val uri: Uri) {
        override fun toString() = "FileSelection(redacted)"
    }

    private fun requireSelection(): Selection {
        checkpoint()
        val result = selection() ?: fail(FileErrorCode.NOT_SELECTED)
        if (!hasGrant(result.uri)) fail(FileErrorCode.NOT_SELECTED)
        return result
    }

    private fun access(selected: Selection) = SafTreeAccess(resolver, selected.uri, checkpoint)

    private fun hasGrant(uri: Uri) = resolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }

    private fun hasAnyGrant(uri: Uri) = resolver.persistedUriPermissions.any { it.uri == uri }

    private fun selection(): Selection? {
        val bytes = try {
            pointer.openRead().use { it.readNBytes(16 * 1024 + 1) }
        } catch (_: java.io.FileNotFoundException) {
            if (pointer.baseFile.exists()) fail(FileErrorCode.STORAGE_FAILED)
            return null
        }
        if (bytes.isEmpty()) return null
        if (bytes.size > 16 * 1024) fail(FileErrorCode.STORAGE_FAILED)
        val fields = FileRules.decode(bytes).split('\n')
        if (fields.size != 2 || UUID.fromString(fields[0]).toString() != fields[0]) fail(FileErrorCode.STORAGE_FAILED)
        val uri = Uri.parse(fields[1])
        validateTree(uri)
        return Selection(fields[0], uri)
    }

    private fun saveSelection(selected: Selection?) {
        if (!directory.isDirectory && !directory.mkdirs()) fail(FileErrorCode.STORAGE_FAILED)
        val bytes = selected?.let { "${it.token}\n${it.uri}".toByteArray(Charsets.UTF_8) } ?: byteArrayOf()
        if (bytes.size > 16 * 1024) fail(FileErrorCode.STORAGE_FAILED)
        val stream = pointer.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            pointer.finishWrite(stream)
        } catch (e: Exception) {
            pointer.failWrite(stream)
            throw e
        }
    }

    private fun validateTree(uri: Uri) {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.query != null ||
            uri.fragment != null || !DocumentsContract.isTreeUri(uri) ||
            uri.pathSegments.size != 2 || uri.pathSegments[0] != "tree" ||
            DocumentsContract.getTreeDocumentId(uri).isEmpty()) fail(FileErrorCode.UNSUPPORTED_PROVIDER)
    }

    private companion object {
        val lock = Any()
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
