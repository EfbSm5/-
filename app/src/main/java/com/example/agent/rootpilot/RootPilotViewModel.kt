package com.example.agent.rootpilot

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotUiState
import kotlinx.coroutines.flow.StateFlow

class RootPilotViewModel(
    private val appContext: Context,
) : ViewModel() {
    val uiState: StateFlow<RootPilotUiState> = RootPilotService.uiState

    init {
        RootPilotService.restoreIfNeeded(appContext)
    }

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

    fun testRoot() = send(RootPilotService.ACTION_TEST_ROOT)

    fun captureScreen() = send(RootPilotService.ACTION_CAPTURE_SCREEN)

    fun singleStep() = send(RootPilotService.ACTION_SINGLE_STEP)

    fun autoExecute() = send(RootPilotService.ACTION_AUTO_EXECUTE)

    fun confirmAction() = send(RootPilotService.ACTION_CONFIRM)

    fun stop() = send(RootPilotService.ACTION_STOP)

    fun recoverInterruptedRun() = send(RootPilotService.ACTION_RECOVER)

    fun discardInterruptedRun() = send(RootPilotService.ACTION_DISCARD_RECOVERY)

    private fun updateConfig(transform: RootPilotConfig.() -> RootPilotConfig) {
        RootPilotService.updateConfig(transform(uiState.value.config))
    }

    private fun send(action: String) {
        RootPilotService.send(appContext, action, uiState.value.config)
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
