package com.example.agent.rootpilot

import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.ExecutionDisplay
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootPilotRunStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun read_missingSnapshotReturnsNull() {
        assertNull(RootPilotRunStore(temporaryFolder.root.resolve("missing/run.json")).read())
    }

    @Test
    fun read_corruptSnapshotThrowsSanitizedException() {
        val file = temporaryFolder.newFile("run.json")
        file.writeText("{\"task\":\"private-task-content\"")

        assertSanitizedFailure("read") { RootPilotRunStore(file).read() }
    }

    @Test
    fun read_directoryThrowsSanitizedException() {
        assertSanitizedFailure("read") {
            RootPilotRunStore(temporaryFolder.newFolder("private-path")).read()
        }
    }

    @Test
    fun read_invalidParentThrowsSanitizedException() {
        val parent = temporaryFolder.newFile("private-parent")

        assertSanitizedFailure("read") { RootPilotRunStore(parent.resolve("run.json")).read() }
    }

    @Test
    fun write_invalidParentThrowsSanitizedException() {
        val parent = temporaryFolder.newFile("private-parent")

        assertSanitizedFailure("write") {
            RootPilotRunStore(parent.resolve("run.json")).write(snapshot())
        }
        assertTrue(parent.isFile)
    }

    @Test
    fun write_replacementFailurePreservesTargetAndRemovesTemporaryFile() {
        val directory = temporaryFolder.newFolder("storage")
        val target = directory.resolve("run.json").apply { mkdir() }
        val existing = target.resolve("existing").apply { writeText("fixture") }

        assertSanitizedFailure("write") { RootPilotRunStore(target).write(snapshot()) }

        assertTrue(existing.isFile)
        assertEquals(listOf("run.json"), directory.list()!!.toList())
    }

    @Test
    fun write_replacesExistingSnapshotAndRemovesTemporaryFile() {
        val directory = temporaryFolder.newFolder("storage")
        val store = RootPilotRunStore(directory.resolve("run.json"))
        store.write(snapshot())

        store.write(snapshot().copy(step = 3))

        assertEquals(3, store.read()?.step)
        assertEquals(listOf("run.json"), directory.list()!!.toList())
    }

    @Test
    fun clear_missingSnapshotSucceeds() {
        val store = RootPilotRunStore(temporaryFolder.root.resolve("missing/run.json"))

        store.clear()
        store.clear()

        assertNull(store.read())
    }

    @Test
    fun clear_nonEmptyDirectoryThrowsSanitizedException() {
        val directory = temporaryFolder.newFolder("private-path")
        val existing = directory.resolve("existing").apply { writeText("fixture") }

        assertSanitizedFailure("clear") { RootPilotRunStore(directory).clear() }

        assertTrue(existing.isFile)
    }

    @Test
    fun clear_invalidParentThrowsSanitizedException() {
        val parent = temporaryFolder.newFile("private-parent")

        assertSanitizedFailure("clear") { RootPilotRunStore(parent.resolve("run.json")).clear() }
    }

    private fun assertSanitizedFailure(operation: String, block: () -> Unit) {
        val failure = assertThrows(RootPilotRunStoreException::class.java) { block() }
        assertEquals(operation, failure.operation)
        assertEquals("Run snapshot $operation failed", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun snapshot() = RootPilotRunSnapshot(
        baseUrl = "http://localhost:18765",
        model = "test-model",
        task = "private-task-content",
        manualConfirmation = true,
        allowScreenUpload = true,
        status = "RUNNING",
        step = 0,
    )

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
        assertFalse(restored.allowScreenUpload)
        assertEquals("", snapshot.restoreTask(current.copy(apiKey = "")).apiKey)
        assertFalse(restored.toString().contains(current.apiKey))
    }

    @Test
    fun recovery_legacySnapshotDefaultsToMainAndClearsVirtualSelection() {
        val file = temporaryFolder.newFile("legacy-run.json")
        file.writeText(
            """{
                "baseUrl":"http://localhost:18765",
                "model":"old-model",
                "task":"旧任务",
                "manualConfirmation":true,
                "allowScreenUpload":true,
                "status":"WAITING_CONFIRMATION",
                "step":2
            }""".trimIndent(),
        )
        val loaded = RootPilotRunStore(file).read()!!
        val current = RootPilotConfig(
            executionDisplay = ExecutionDisplay.VIRTUAL,
            virtualDisplayStartPackage = "com.example.current",
            allowScreenUpload = true,
        )

        val restored = loaded.restoreTask(current)

        assertEquals(ExecutionDisplay.MAIN, loaded.executionDisplay)
        assertEquals("", loaded.virtualDisplayStartPackage)
        assertEquals(ExecutionDisplay.MAIN, restored.executionDisplay)
        assertEquals("", restored.virtualDisplayStartPackage)
        assertEquals("旧任务", restored.task)
        assertFalse(restored.allowScreenUpload)
    }

    @Test
    fun recovery_virtualSnapshotRestoresOnlySelectionAndRequiresRenewedUploadConsent() {
        val file = temporaryFolder.newFile("virtual-run.json")
        val saved = snapshot().copy(
            executionDisplay = ExecutionDisplay.VIRTUAL,
            virtualDisplayStartPackage = "com.example.selected",
        )
        RootPilotRunStore(file).write(saved)
        val current = RootPilotConfig(
            apiKey = "synthetic-current-key", baseUrl = "https://example.invalid", model = "current-model",
            allowScreenUpload = true, virtualDisplayStartPackage = "com.example.other",
        )

        val loaded = RootPilotRunStore(file).read()!!
        val restored = loaded.restoreTask(current)

        assertEquals(saved, loaded)
        assertEquals(ExecutionDisplay.VIRTUAL, restored.executionDisplay)
        assertEquals("com.example.selected", restored.virtualDisplayStartPackage)
        assertEquals(current.apiKey, restored.apiKey)
        assertEquals(current.baseUrl, restored.baseUrl)
        assertEquals(current.model, restored.model)
        assertFalse(restored.allowScreenUpload)
        assertFalse(file.readText().contains(current.apiKey))
        assertEquals(
            setOf("baseUrl", "model", "task", "manualConfirmation", "allowScreenUpload", "status", "step",
                "actionSummary", "executionDisplay", "virtualDisplayStartPackage"),
            Json.parseToJsonElement(file.readText()).jsonObject.keys,
        )
    }

    @Test
    fun recovery_keepsUnavailableStartPackageWithoutChoosingAReplacement() {
        val unavailablePackage = "com.example.no.longer.allowed"
        val saved = snapshot().copy(
            executionDisplay = ExecutionDisplay.VIRTUAL,
            virtualDisplayStartPackage = unavailablePackage,
        )
        val current = RootPilotConfig(virtualDisplayStartPackage = "com.example.other")

        assertEquals(unavailablePackage, saved.restoreTask(current).virtualDisplayStartPackage)
        assertEquals("", saved.copy(virtualDisplayStartPackage = "").restoreTask(current).virtualDisplayStartPackage)
    }

    @Test
    fun recovery_mainSnapshotKeepsMainDisplayAndRequiresRenewedConsent() {
        val saved = snapshot()
        val current = RootPilotConfig(allowScreenUpload = true)

        val restored = saved.restoreTask(current)

        assertEquals(ExecutionDisplay.MAIN, restored.executionDisplay)
        assertEquals("", restored.virtualDisplayStartPackage)
        assertFalse(restored.allowScreenUpload)
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
