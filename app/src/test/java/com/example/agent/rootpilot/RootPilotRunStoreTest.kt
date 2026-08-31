package com.example.agent.rootpilot

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPilotRunStoreTest {
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
