package com.example.agent.agent.planning

import com.example.agent.agent.model.CreateTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
