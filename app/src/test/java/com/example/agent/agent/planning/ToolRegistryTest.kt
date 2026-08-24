package com.example.agent.agent.planning

import com.example.agent.agent.model.CreateTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    @Test
    fun registry_resolvesToolByActionCapability() {
        val registry = ToolRegistry.default()

        assertEquals(
            AgentToolNames.CREATE_TODO,
            registry.resolve(CreateTodo("测试", dueAt = null))?.name,
        )
    }

    @Test
    fun registry_rejectsDuplicateToolNames() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry(
                tools = listOf(CreateTodoTool(), CreateTodoTool()),
            )
        }
    }

    @Test
    fun registry_describesParametersAndRuntimeAllowlist() {
        val registry = ToolRegistry(
            tools = listOf(
                CreateTodoTool(),
                OpenAppTool(AllowlistedAppLauncher()),
            ),
        )

        val description = registry.describeForModel()

        assertTrue(description.contains("create_todo"))
        assertTrue(description.contains("due_at"))
        assertTrue(description.contains("com.android.settings"))
    }

    private class AllowlistedAppLauncher : AppLauncher {
        override val allowedPackageNames: Set<String> = setOf("com.android.settings")

        override fun preflight(packageName: String): AppLaunchPreflight =
            AppLaunchPreflight.Ready

        override suspend fun launch(packageName: String): AppLaunchResult =
            AppLaunchResult.Launched
    }
}
