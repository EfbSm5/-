package com.example.agent.rootpilot.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceBrowserState(
    val visible: Boolean = false,
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val previewPath: String? = null,
    val text: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    override fun toString() = "WorkspaceBrowserState(redacted)"
}

/** Main-thread owner. Browsing never calls a model or adds content to chat history. */
class WorkspaceBrowserController(private val scope: CoroutineScope, private val files: WorkspaceAccess) {
    private val mutable = MutableStateFlow(WorkspaceBrowserState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun open() {
        close()
        mutable.value = WorkspaceBrowserState(visible = true)
        loadDirectory("")
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        mutable.value = WorkspaceBrowserState()
    }

    fun refresh() {
        if (!mutable.value.visible || mutable.value.busy) return
        loadDirectory(mutable.value.path)
    }

    fun back() {
        val current = mutable.value
        if (!current.visible || current.busy) return
        if (current.previewPath != null) {
            mutable.value = current.copy(previewPath = null, text = null, error = null)
        } else if (current.path.isNotEmpty()) {
            loadDirectory(current.path.substringBeforeLast('/', ""))
        }
    }

    fun select(entry: FileEntry) {
        val current = mutable.value
        if (!current.visible || current.busy || current.previewPath != null ||
            current.entries.none { it === entry }) return
        val path = if (current.path.isEmpty()) entry.name else "${current.path}/${entry.name}"
        if (entry.isDirectory) loadDirectory(path) else request(
            current.copy(previewPath = path, text = null),
        ) { it.copy(text = files.read(path)) }
    }

    private fun loadDirectory(path: String) = request(
        WorkspaceBrowserState(visible = true, path = path),
    ) { it.copy(entries = files.list(path).sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name })) }

    private fun request(initial: WorkspaceBrowserState, load: suspend (WorkspaceBrowserState) -> WorkspaceBrowserState) {
        val version = ++generation
        job?.cancel()
        mutable.value = initial.copy(busy = true, error = null)
        job = scope.launch {
            try {
                val result = load(initial)
                // A provider may finish after the dialog closed or a different workspace was selected.
                if (version == generation) mutable.value = result.copy(busy = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when ((error as? FileStorageException)?.code) {
                    FileErrorCode.NOT_TEXT, FileErrorCode.INVALID_TEXT -> "仅支持 UTF-8 文本、JSON 和 XML"
                    FileErrorCode.TOO_LARGE -> "文件超过 64 KiB，无法预览"
                    FileErrorCode.TOO_MANY_ENTRIES -> "目录超过 100 项，无法列出"
                    FileErrorCode.NOT_FOUND -> "文件或目录已不存在，请返回后刷新"
                    FileErrorCode.NOT_SELECTED -> "目录授权不可用，请重新选择目录"
                    else -> "读取失败，请检查目录授权或文件提供方"
                }
                if (version == generation) mutable.value = initial.copy(busy = false, error = message)
            }
        }
    }
}
