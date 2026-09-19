package com.example.agent.rootpilot

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.deepseek.apiValidationError
import com.example.agent.rootpilot.input.AndroidImeEnvironment
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ApiConfigUiState(
    val draft: RootPilotApiConfig = RootPilotApiConfig(),
    val configured: Boolean = false,
    val editing: Boolean = true,
    val busy: Boolean = true,
    val message: String? = null,
)

class RootPilotViewModel(
    private val appContext: Context,
    private val configStore: RootPilotApiConfigStore = RootPilotApiConfigStore.create(appContext),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val uiState: StateFlow<RootPilotUiState> = RootPilotService.uiState
    private val _apiState = MutableStateFlow(ApiConfigUiState())
    val apiState: StateFlow<ApiConfigUiState> = _apiState.asStateFlow()
    private val _inputMessage = MutableStateFlow<String?>(null)
    val inputMessage: StateFlow<String?> = _inputMessage.asStateFlow()

    fun recoverInputMethod() {
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { AndroidImeEnvironment.createInput(appContext).recover() }
                _inputMessage.value = (result as? RootExecutionResult.Failure)?.message
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _inputMessage.value = "输入法恢复失败，请在系统输入法设置中选择常用输入法"
            }
        }
    }

    init {
        viewModelScope.launch {
            try {
                apiConfigMutex.withLock {
                    withContext(ioDispatcher) {
                        RootPilotService.updateApiConfig(configStore.read())
                    }
                    refreshApiUi()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _apiState.value = ApiConfigUiState(
                    busy = false,
                    message = "已保存配置无法解密或读取，请重新配置或清除",
                )
            }
            RootPilotService.restoreIfNeeded(appContext)
        }
        viewModelScope.launch {
            uiState.map { Triple(it.apiConfigured, it.config.baseUrl, it.config.model) }
                .distinctUntilChanged().collect {
                    if (!_apiState.value.busy) {
                        if (_apiState.value.editing) {
                            _apiState.value = _apiState.value.copy(configured = uiState.value.apiConfigured)
                        } else {
                            refreshApiUi()
                        }
                    }
                }
        }
    }

    fun updateApiKey(value: String) = updateDraft { copy(apiKey = value) }

    fun updateBaseUrl(value: String) = updateDraft { copy(baseUrl = value) }

    fun updateModel(value: String) = updateDraft { copy(model = value) }

    fun editApiConfig() {
        if (!canChangeApiConfig()) return
        refreshApiUi()
        _apiState.value = _apiState.value.copy(editing = true, message = null)
    }

    fun cancelApiConfigEdit() {
        if (!canChangeApiConfig()) return
        // Cancellation changes only the editor, never the active credentials.
        refreshApiUi()
    }

    fun saveApiConfig() {
        if (!canChangeApiConfig()) return
        val draft = _apiState.value.draft.let {
            it.copy(apiKey = it.apiKey.trim(), baseUrl = it.baseUrl.trim().trimEnd('/'), model = it.model.trim())
        }
        draft.applyTo(RootPilotConfig()).apiValidationError()?.let {
            _apiState.value = _apiState.value.copy(message = it)
            return
        }
        _apiState.value = _apiState.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                apiConfigMutex.withLock {
                    withContext(ioDispatcher) {
                        configStore.save(draft)
                        RootPilotService.updateApiConfig(draft)
                    }
                    refreshApiUi("配置已安全保存")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _apiState.value = _apiState.value.copy(busy = false, message = "配置保存失败，原配置未更改")
            }
        }
    }

    fun clearApiConfig() {
        if (!canChangeApiConfig()) return
        _apiState.value = _apiState.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                apiConfigMutex.withLock {
                    withContext(ioDispatcher) {
                        configStore.clear()
                        RootPilotService.updateApiConfig(null)
                    }
                    refreshApiUi("配置已清除")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _apiState.value = _apiState.value.copy(busy = false, message = "配置清除失败，请重试")
            }
        }
    }

    fun testConnection() {
        if (!canChangeApiConfig()) return
        val config = if (_apiState.value.editing) {
            _apiState.value.draft.applyTo(RootPilotConfig())
        } else {
            uiState.value.config
        }
        _apiState.value = _apiState.value.copy(busy = true, message = "正在测试连接（不上传截图）")
        viewModelScope.launch {
            val message = try {
                when (val result = HttpDeepSeekClient().testConnection(config)) {
                    is DeepSeekActionResult.Success -> "连接成功，模型可用（未上传截图）"
                    is DeepSeekActionResult.Failure -> result.message
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                "连接测试失败，请检查配置或网络"
            }
            _apiState.value = _apiState.value.copy(busy = false, message = message)
        }
    }

    private fun refreshApiUi(message: String? = null) {
        val state = uiState.value
        _apiState.value = ApiConfigUiState(
            draft = RootPilotApiConfig(baseUrl = state.config.baseUrl, model = state.config.model),
            configured = state.apiConfigured,
            editing = !state.apiConfigured,
            busy = false,
            message = message,
        )
    }

    private fun updateDraft(transform: RootPilotApiConfig.() -> RootPilotApiConfig) {
        if (!canChangeApiConfig() || !_apiState.value.editing) return
        _apiState.value = _apiState.value.copy(draft = transform(_apiState.value.draft), message = null)
    }

    private fun canChangeApiConfig(): Boolean = !_apiState.value.busy && uiState.value.status !in setOf(
        RootPilotStatus.CAPTURING, RootPilotStatus.REQUESTING_MODEL, RootPilotStatus.EXECUTING,
        RootPilotStatus.WAITING_SCREEN, RootPilotStatus.WAITING_CONFIRMATION,
    )

    fun updateTask(value: String) = updateConfig { copy(task = value) }

    fun setManualConfirmation(enabled: Boolean) = updateConfig {
        copy(manualConfirmation = enabled)
    }

    fun setAllowScreenUpload(enabled: Boolean) = updateConfig {
        copy(allowScreenUpload = enabled)
    }

    fun testRoot() = send(RootPilotService.ACTION_TEST_ROOT)

    fun captureScreen() = send(RootPilotService.ACTION_CAPTURE_SCREEN)

    fun singleStep() = startTask(RootPilotService.ACTION_SINGLE_STEP)

    fun autoExecute() = startTask(RootPilotService.ACTION_AUTO_EXECUTE)

    fun confirmAction() = send(RootPilotService.ACTION_CONFIRM)

    fun stop() = send(RootPilotService.ACTION_STOP)

    fun recoverInterruptedRun() = startTask(RootPilotService.ACTION_RECOVER)

    fun discardInterruptedRun() = send(RootPilotService.ACTION_DISCARD_RECOVERY)

    private fun updateConfig(transform: RootPilotConfig.() -> RootPilotConfig) {
        RootPilotService.updateConfig(transform(uiState.value.config))
    }

    private fun send(action: String) {
        RootPilotService.send(appContext, action, uiState.value.config)
    }

    private fun startTask(action: String) {
        if (_apiState.value.busy || _apiState.value.editing || !uiState.value.apiConfigured) return
        viewModelScope.launch {
            apiConfigMutex.withLock {
                if (!_apiState.value.busy && !_apiState.value.editing && uiState.value.apiConfigured) send(action)
            }
        }
    }

    private companion object {
        // Keep disk replacement and active-state publication in the same order across editors.
        val apiConfigMutex = Mutex()
    }

    class Factory(
        private val context: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RootPilotViewModel::class.java)) {
                "不支持的 ViewModel 类型：${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return RootPilotViewModel(context.applicationContext) as T
        }
    }
}
