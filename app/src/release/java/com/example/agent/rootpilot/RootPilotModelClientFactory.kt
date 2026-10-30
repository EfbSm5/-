package com.example.agent.rootpilot

import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient

internal object RootPilotModelClientFactory {
    fun create(@Suppress("UNUSED_PARAMETER") service: RootPilotService): DeepSeekClient = HttpDeepSeekClient()
}
