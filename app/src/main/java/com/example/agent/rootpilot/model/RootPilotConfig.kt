package com.example.agent.rootpilot.model

data class RootPilotConfig(
    val apiKey: String = "",
    val baseUrl: String = "http://localhost:18765",
    val model: String = "deepseek-v4-flash-vision-exp",
    val task: String = "",
    val manualConfirmation: Boolean = true,
    val allowScreenUpload: Boolean = false,
)
