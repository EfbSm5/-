package com.example.agent.rootpilot.model

data class RootPilotConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val task: String = "",
    val manualConfirmation: Boolean = true,
    val allowScreenUpload: Boolean = false,
) {
    override fun toString(): String = "RootPilotConfig(credentials=redacted)"
}
