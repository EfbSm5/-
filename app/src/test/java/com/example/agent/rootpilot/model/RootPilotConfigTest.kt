package com.example.agent.rootpilot.model

import com.example.agent.rootpilot.RootPilotApiConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPilotConfigTest {
    @Test
    fun defaultsKeepMainDisplayAndRequireExplicitVirtualStartApp() {
        val config = RootPilotConfig()

        assertEquals(ExecutionDisplay.MAIN, config.executionDisplay)
        assertEquals("", config.virtualDisplayStartPackage)
        assertEquals("", config.copy(executionDisplay = ExecutionDisplay.VIRTUAL).virtualDisplayStartPackage)
        assertTrue(config.manualConfirmation)
        assertFalse(config.allowScreenUpload)
    }

    @Test
    fun executionDisplayUsesStableSerializableNames() {
        assertEquals("\"MAIN\"", Json.encodeToString(ExecutionDisplay.MAIN))
        assertEquals("\"VIRTUAL\"", Json.encodeToString(ExecutionDisplay.VIRTUAL))
        assertEquals(ExecutionDisplay.MAIN, Json.decodeFromString<ExecutionDisplay>("\"MAIN\""))
        assertEquals(ExecutionDisplay.VIRTUAL, Json.decodeFromString<ExecutionDisplay>("\"VIRTUAL\""))
    }

    @Test
    fun taskEditsAndApiConfigChangesPreserveVirtualDisplaySelection() {
        val original = RootPilotConfig(
            executionDisplay = ExecutionDisplay.VIRTUAL,
            virtualDisplayStartPackage = "com.example.fixture",
        )
        val edited = original.copy(task = "测试任务", manualConfirmation = false, allowScreenUpload = true)
        val api = RootPilotApiConfig(apiKey = "synthetic-key", baseUrl = "https://example.invalid", model = "fixture-model")

        for (config in listOf(edited, api.applyTo(edited), RootPilotApiConfig().applyTo(edited))) {
            assertEquals(ExecutionDisplay.VIRTUAL, config.executionDisplay)
            assertEquals("com.example.fixture", config.virtualDisplayStartPackage)
            assertEquals(edited.task, config.task)
            assertFalse(config.manualConfirmation)
            assertTrue(config.allowScreenUpload)
        }
    }
}
