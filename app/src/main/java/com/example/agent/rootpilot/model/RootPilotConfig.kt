package com.example.agent.rootpilot.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionDisplay { MAIN, VIRTUAL }

data class RootPilotConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val task: String = "",
    val manualConfirmation: Boolean = true,
    val allowScreenUpload: Boolean = false,
    val executionDisplay: ExecutionDisplay = ExecutionDisplay.MAIN,
    val virtualDisplayStartPackage: String = "",
) {
    override fun toString(): String = "RootPilotConfig(credentials=redacted)"
}
