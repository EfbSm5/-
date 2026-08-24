package com.example.agent.agent.planning

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlannerPromptTest {
    @Test
    fun prompt_containsSchemaToolCapabilitiesAndUserRequest() {
        val registry = ToolRegistry(
            tools = listOf(
                CreateTodoTool(),
                OpenAppTool(AllowlistedAppLauncher()),
            ),
        )

        val prompt = AgentPlannerPrompt(
            schemaText = "schema-marker",
            toolRegistry = registry,
        ).build("打开设置")

        assertTrue(prompt.contains("schema-marker"))
        assertTrue(prompt.contains("create_todo"))
        assertTrue(prompt.contains("com.android.settings"))
        assertTrue(prompt.contains("打开设置"))
    }

    private class AllowlistedAppLauncher : AppLauncher {
        override val allowedPackageNames: Set<String> = setOf("com.android.settings")

        override fun preflight(packageName: String): AppLaunchPreflight =
            AppLaunchPreflight.Ready

        override suspend fun launch(packageName: String): AppLaunchResult =
            AppLaunchResult.Launched
    }
}
