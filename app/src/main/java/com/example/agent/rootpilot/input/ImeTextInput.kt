package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.root.RootExecutionResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ImeEnvironment {
    val ownId: String
    fun currentId(): String?
    fun isEnabled(id: String): Boolean
    suspend fun select(id: String): RootExecutionResult
    suspend fun commit(text: String, confirm: suspend (String) -> Boolean): RootExecutionResult
}

interface ImeRestoreStore {
    fun read(): String?
    fun save(id: String): Boolean
    fun clear(): Boolean
}

class ImeTextInput(
    private val environment: ImeEnvironment,
    private val store: ImeRestoreStore,
    private val mutex: Mutex = Mutex(),
) {
    suspend fun recover(): RootExecutionResult = mutex.withLock { restoreLocked() }

    suspend fun type(text: String, confirm: suspend (String) -> Boolean = { true }): RootExecutionResult = mutex.withLock {
        if (!InputText.isValid(text)) return@withLock RootExecutionResult.Failure("输入文本不合法")
        val recovery = restoreLocked()
        if (recovery is RootExecutionResult.Failure) return@withLock recovery
        if (!environment.isEnabled(environment.ownId)) {
            return@withLock RootExecutionResult.Failure("请在 RootPilot 的输入法设置中手动启用 RootPilot 输入法")
        }
        val original = environment.currentId()
        if (original.isNullOrBlank() || original == environment.ownId || !environment.isEnabled(original)) {
            return@withLock RootExecutionResult.Failure("请先切换到常用输入法，再执行文本输入")
        }
        // Persist before switching so process death cannot erase the restoration target.
        if (!store.save(original)) return@withLock RootExecutionResult.Failure("无法保存原输入法，未执行切换")
        var result: RootExecutionResult
        var restoration: RootExecutionResult
        try {
            val switched = environment.select(environment.ownId)
            result = if (switched is RootExecutionResult.Failure) switched else environment.commit(text, confirm)
        } finally {
            // Cancellation must not leave the user's default IME changed.
            restoration = withContext(NonCancellable) { restoreLocked() }
        }
        if (restoration is RootExecutionResult.Failure) {
            RootExecutionResult.Failure("输入可能已生效，但原输入法未恢复；请手动恢复，勿直接重放输入")
        } else {
            result
        }
    }

    private suspend fun restoreLocked(): RootExecutionResult {
        val original = store.read() ?: return RootExecutionResult.Success()
        val current = environment.currentId()
        if (current.isNullOrBlank()) return RootExecutionResult.Failure("无法读取当前输入法，保留恢复记录")
        if (current == environment.ownId) {
            if (original == environment.ownId || !environment.isEnabled(original)) {
                return RootExecutionResult.Failure("原输入法已不可用，请手动选择常用输入法")
            }
            val result = environment.select(original)
            if (result is RootExecutionResult.Failure) return result
            if (environment.currentId() != original) {
                return RootExecutionResult.Failure("原输入法尚未恢复，保留恢复记录")
            }
        }
        // A different current IME means the user/system already switched; do not override it.
        return if (store.clear()) RootExecutionResult.Success() else {
            RootExecutionResult.Failure("无法清除输入法恢复记录")
        }
    }
}
