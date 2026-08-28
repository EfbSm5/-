package com.example.agent.rootpilot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.loop.AgentLoopEvent
import com.example.agent.rootpilot.loop.AgentLoopRequest
import com.example.agent.rootpilot.loop.ActionApproval
import com.example.agent.rootpilot.log.AgentLogRepository
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RootPilotViewModel(
    private val loop: AgentLoop,
    private val rootExecutor: RootExecutor,
    private val logRepository: AgentLogRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RootPilotUiState())
    val uiState: StateFlow<RootPilotUiState> = _uiState.asStateFlow()

    private val stateLock = Any()
    private var runJob: Job? = null
    private var pendingApproval: ActionApproval? = null

    fun updateApiKey(value: String) = updateConfig { copy(apiKey = value) }

    fun updateBaseUrl(value: String) = updateConfig { copy(baseUrl = value) }

    fun updateModel(value: String) = updateConfig { copy(model = value) }

    fun updateTask(value: String) = updateConfig { copy(task = value) }

    fun setManualConfirmation(enabled: Boolean) = updateConfig {
        copy(manualConfirmation = enabled)
    }

    fun setAllowScreenUpload(enabled: Boolean) = updateConfig {
        copy(allowScreenUpload = enabled)
    }

    fun testRoot() {
        launchExclusive {
            updateState(status = RootPilotStatus.CAPTURING, errorMessage = null)
            appendLog("开始测试 Root 权限")
            when (val result = rootExecutor.checkRoot()) {
                is RootExecutionResult.Success -> {
                    appendLog("Root 检测成功")
                    updateState(status = RootPilotStatus.IDLE)
                }

                is RootExecutionResult.Failure -> {
                    appendLog("Root 检测失败：${result.message}")
                    updateState(
                        status = RootPilotStatus.FAILED,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun captureScreen() {
        launchExclusive {
            updateState(status = RootPilotStatus.CAPTURING, errorMessage = null)
            appendLog("开始截取屏幕")
            when (val result = loop.captureScreen()) {
                is ScreenshotCaptureResult.Success -> {
                    updateState(status = RootPilotStatus.IDLE, frame = result.frame)
                    appendLog("截图成功：${result.frame.width}x${result.frame.height}")
                }

                is ScreenshotCaptureResult.Failure -> {
                    appendLog("截图失败：${result.message}")
                    updateState(
                        status = RootPilotStatus.FAILED,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun singleStep() = startRun(maxSteps = 1, singleStep = true)

    fun autoExecute() = startRun(maxSteps = MAX_STEPS)

    fun confirmAction() {
        synchronized(stateLock) {
            pendingApproval?.approve()
            pendingApproval = null
        }
    }

    fun stop() {
        synchronized(stateLock) {
            pendingApproval?.reject()
            pendingApproval = null
            runJob?.cancel()
            runJob = null
            _uiState.value = _uiState.value.copy(
                status = RootPilotStatus.STOPPED,
                pendingAction = null,
                errorMessage = "用户已停止",
            )
        }
        rootExecutor.cancel()
        appendLog("用户停止执行")
    }

    override fun onCleared() {
        rootExecutor.cancel()
        synchronized(stateLock) {
            pendingApproval?.reject()
            pendingApproval = null
            runJob?.cancel()
        }
        super.onCleared()
    }

    private fun startRun(maxSteps: Int, singleStep: Boolean = false) {
        synchronized(stateLock) {
            if (runJob?.isActive == true) return
            val currentConfig = _uiState.value.config
            when {
                currentConfig.task.isBlank() -> {
                    _uiState.value = _uiState.value.copy(
                        status = RootPilotStatus.FAILED,
                        errorMessage = "请先输入自然语言任务",
                    )
                    return
                }

                !currentConfig.allowScreenUpload -> {
                    _uiState.value = _uiState.value.copy(
                        status = RootPilotStatus.FAILED,
                        errorMessage = "发送截图前请先打开上传确认",
                    )
                    return
                }

                else -> {
                    _uiState.value = _uiState.value.copy(
                        status = RootPilotStatus.CAPTURING,
                        step = 0,
                        errorMessage = null,
                        pendingAction = null,
                    )
                    runJob = viewModelScope.launch(dispatcher) {
                        try {
                            loop.run(
                                request = AgentLoopRequest(
                                    config = currentConfig,
                                    maxSteps = maxSteps,
                                    singleStep = singleStep,
                                ),
                                onEvent = ::handleEvent,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            appendLog("AgentLoop 异常：${error.message ?: "未知错误"}")
                            updateState(
                                status = RootPilotStatus.FAILED,
                                errorMessage = "AgentLoop 执行异常",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        synchronized(stateLock) {
            if (runJob?.isActive == true) return
            runJob = viewModelScope.launch(dispatcher) { block() }
        }
    }

    private suspend fun handleEvent(event: AgentLoopEvent) {
        when (event) {
            is AgentLoopEvent.Capturing -> {
                updateState(status = RootPilotStatus.CAPTURING, step = event.step)
                appendLog("第 ${event.step + 1} 步：截取屏幕")
            }

            is AgentLoopEvent.ScreenshotCaptured -> {
                updateState(status = RootPilotStatus.CAPTURING, frame = event.frame, step = event.step)
            }

            is AgentLoopEvent.RequestingModel -> {
                updateState(status = RootPilotStatus.REQUESTING_MODEL, step = event.step)
                appendLog("第 ${event.step + 1} 步：请求 DeepSeek Vision")
            }

            is AgentLoopEvent.AwaitingConfirmation -> {
                synchronized(stateLock) {
                    pendingApproval = event.approval
                    _uiState.value = _uiState.value.copy(
                        status = RootPilotStatus.WAITING_CONFIRMATION,
                        step = event.step,
                        lastAction = event.action,
                        pendingAction = event.action,
                    )
                }
                appendLog("等待确认：${event.action.reason}")
            }

            is AgentLoopEvent.Executing -> {
                synchronized(stateLock) {
                    pendingApproval = null
                    _uiState.value = _uiState.value.copy(
                        status = RootPilotStatus.EXECUTING,
                        step = event.step,
                        lastAction = event.action,
                        pendingAction = null,
                    )
                }
                appendLog("执行动作：${event.action.reason}")
            }

            is AgentLoopEvent.WaitingScreen -> {
                updateState(status = RootPilotStatus.WAITING_SCREEN, step = event.step)
                appendLog("等待页面稳定")
            }

            is AgentLoopEvent.Completed -> {
                updateState(
                    status = RootPilotStatus.COMPLETED,
                    clearPendingAction = true,
                    errorMessage = event.message,
                )
                appendLog("任务完成：${event.message}")
            }

            is AgentLoopEvent.Failed -> {
                updateState(
                    status = RootPilotStatus.FAILED,
                    clearPendingAction = true,
                    errorMessage = event.message,
                )
                appendLog("任务失败：${event.message}")
            }

            AgentLoopEvent.Stopped -> {
                updateState(
                    status = RootPilotStatus.STOPPED,
                    clearPendingAction = true,
                    errorMessage = "用户已停止",
                )
            }
        }
    }

    private fun updateConfig(transform: RootPilotConfig.() -> RootPilotConfig) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(config = transform(_uiState.value.config))
        }
    }

    private fun updateState(
        status: RootPilotStatus,
        frame: com.example.agent.rootpilot.screen.ScreenshotFrame? = null,
        step: Int? = null,
        errorMessage: String? = null,
        clearPendingAction: Boolean = false,
    ) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(
                status = status,
                frame = frame ?: _uiState.value.frame,
                step = step ?: _uiState.value.step,
                errorMessage = errorMessage,
                pendingAction = if (clearPendingAction) null else _uiState.value.pendingAction,
            )
        }
    }

    private fun appendLog(message: String) {
        logRepository.append(message)
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(logs = logRepository.list())
        }
    }

    private companion object {
        const val MAX_STEPS = 20
    }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RootPilotViewModel::class.java)) {
                "不支持的 ViewModel 类型：${modelClass.name}"
            }
            val rootExecutor = com.example.agent.rootpilot.root.SuRootExecutor()
            val loop = AgentLoop(
                screenshotProvider = com.example.agent.rootpilot.screen.RootScreenshotProvider(rootExecutor),
                deepSeekClient = com.example.agent.rootpilot.deepseek.HttpDeepSeekClient(),
                rootExecutor = rootExecutor,
            )
            @Suppress("UNCHECKED_CAST")
            return RootPilotViewModel(
                loop = loop,
                rootExecutor = rootExecutor,
                logRepository = com.example.agent.rootpilot.log.InMemoryAgentLogRepository(),
            ) as T
        }
    }
}
