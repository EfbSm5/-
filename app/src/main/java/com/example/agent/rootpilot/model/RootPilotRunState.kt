package com.example.agent.rootpilot.model

import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot

enum class RootPilotStatus {
    IDLE,
    CAPTURING,
    REQUESTING_MODEL,
    WAITING_CONFIRMATION,
    EXECUTING,
    WAITING_SCREEN,
    COMPLETED,
    FAILED,
    STOPPING,
    STOPPED,
    RECOVERY_REQUIRED,
}

data class RootPilotUiState(
    val config: RootPilotConfig = RootPilotConfig(),
    val apiConfigured: Boolean = false,
    val status: RootPilotStatus = RootPilotStatus.IDLE,
    val frame: ScreenshotFrame? = null,
    val step: Int = 0,
    val lastAction: RootPilotAction? = null,
    val pendingAction: RootPilotAction? = null,
    val errorMessage: String? = null,
    val logs: List<String> = emptyList(),
    val savedTodos: List<SavedTodoResult> = emptyList(),
    val modelReportedResult: Boolean = false,
    val modelStream: ModelStreamSnapshot = ModelStreamSnapshot(),
)

data class SavedTodoResult(val title: String, val dueAt: String?)
