package com.example.agent.rootpilot.input

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import android.view.inputmethod.InputMethodManager
import com.example.agent.rootpilot.root.RootExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

class AndroidImeEnvironment(
    private val context: Context,
) : ImeEnvironment {
    private val commands = ImeCommandRunner()
    override val ownId: String = ComponentName(context, RootPilotInputMethodService::class.java).flattenToShortString()

    override fun currentId(): String? = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
    )

    override fun isEnabled(id: String): Boolean =
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == id }

    override suspend fun select(id: String): RootExecutionResult {
        if (!isEnabled(id)) return RootExecutionResult.Failure("目标输入法未启用")
        if (id == ownId) withContext(Dispatchers.Main.immediate) { InputConnectionBridge.editor.value = null }
        val result = commands.select(id)
        return if (result is RootExecutionResult.Success && currentId() != id) {
            RootExecutionResult.Failure("系统未确认输入法切换成功")
        } else result
    }

    override suspend fun commit(text: String, confirm: suspend (String) -> Boolean): RootExecutionResult =
        InputConnectionBridge.commit(text, context.packageName, confirm)

    companion object {
        // Service recreation must not race a cancelled run's non-cancellable restoration.
        val sessionMutex = Mutex()

        fun createInput(context: Context): ImeTextInput = ImeTextInput(
            AndroidImeEnvironment(context.applicationContext),
            FileImeRestoreStore(context.applicationContext),
            sessionMutex,
        )
    }
}

class FileImeRestoreStore(context: Context) : ImeRestoreStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "rootpilot_original_ime"))
    override fun read(): String? = if (file.baseFile.exists()) file.readFully().toString(Charsets.UTF_8) else null
    override fun save(id: String): Boolean {
        val output = try { file.startWrite() } catch (_: Exception) { return false }
        return try {
            output.write(id.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            true
        } catch (_: Exception) {
            file.failWrite(output)
            false
        }
    }
    override fun clear(): Boolean {
        file.delete()
        return !file.baseFile.exists()
    }
}
