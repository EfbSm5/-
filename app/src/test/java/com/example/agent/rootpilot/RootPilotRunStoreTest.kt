package com.example.agent.rootpilot

import com.example.agent.rootpilot.model.RootPilotConfig
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPilotRunStoreTest {
    @Test
    fun recovery_preservesCurrentCredentialsEndpointAndModel() {
        val current = RootPilotConfig(apiKey = "test-saved-token", model = "current-model")
        val snapshot = RootPilotRunSnapshot(
            baseUrl = "http://localhost:18765",
            model = "old-model",
            task = "上次任务",
            manualConfirmation = true,
            allowScreenUpload = true,
            status = "WAITING_CONFIRMATION",
            step = 2,
        )

        val restored = snapshot.restoreTask(current)

        assertEquals(current.apiKey, restored.apiKey)
        assertEquals(current.baseUrl, restored.baseUrl)
        assertEquals(current.model, restored.model)
        assertEquals("上次任务", restored.task)
        assertTrue(restored.manualConfirmation)
        assertTrue(restored.allowScreenUpload)
        assertEquals("", snapshot.restoreTask(current.copy(apiKey = "")).apiKey)
        assertFalse(restored.toString().contains(current.apiKey))
    }

    @Test
    fun snapshot_survivesStoreRecreationWithoutPersistingApiKey() {
        val directory = Files.createTempDirectory("rootpilot-run-store").toFile()
        val file = directory.resolve(RootPilotRunStore.FILE_NAME)
        val snapshot = RootPilotRunSnapshot(
            baseUrl = "http://localhost:18765",
            model = "test-model",
            task = "打开系统设置",
            manualConfirmation = true,
            allowScreenUpload = true,
            status = "EXECUTING",
            step = 2,
            actionSummary = "key(HOME)",
        )

        RootPilotRunStore(file).write(snapshot)

        assertEquals(snapshot, RootPilotRunStore(file).read())
        assertFalse(file.readText().contains("apiKey"))
    }

    @Test
    fun clear_removesSnapshot() {
        val file = Files.createTempDirectory("rootpilot-run-store")
            .toFile()
            .resolve(RootPilotRunStore.FILE_NAME)
        val store = RootPilotRunStore(file)
        store.write(
            RootPilotRunSnapshot(
                baseUrl = "http://localhost:18765",
                model = "test-model",
                task = "测试",
                manualConfirmation = true,
                allowScreenUpload = true,
                status = "RUNNING",
                step = 0,
            ),
        )

        store.clear()

        assertNull(store.read())
        assertTrue(!file.exists())
    }
}
