package com.example.agent.rootpilot.model

import com.example.agent.rootpilot.screen.ScreenshotFrame

enum class RootPilotStatus {
    IDLE,
    CAPTURING,
    REQUESTING_MODEL,
    WAITING_CONFIRMATION,
    EXECUTING,
    WAITING_SCREEN,
    COMPLETED,
    FAILED,
    STOPPED,
}

data class RootPilotUiState(
    val config: RootPilotConfig = RootPilotConfig(),
    val status: RootPilotStatus = RootPilotStatus.IDLE,
    val frame: ScreenshotFrame? = null,
    val step: Int = 0,
    val lastAction: RootPilotAction? = null,
    val pendingAction: RootPilotAction? = null,
    val errorMessage: String? = null,
    val logs: List<String> = emptyList(),
)
