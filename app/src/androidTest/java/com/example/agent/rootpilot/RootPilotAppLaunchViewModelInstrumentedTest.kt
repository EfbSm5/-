package com.example.agent.rootpilot

import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.model.RootPilotApp
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootPilotAppLaunchViewModelInstrumentedTest {
    @Test
    fun selectionSurvivesEditorRecreationAndClearIsImmediate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "launch-editor-").toFile()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        val prior = RootPilotService.uiState.value
        val owners = mutableListOf<ViewModelStore>()
        val apps = listOf(
            RootPilotApp("com.example.notes", "测试笔记", "com.example.notes.Main"),
            RootPilotApp("com.example.reader", "测试阅读", "com.example.reader.Main"),
        )
        val store = AppLaunchAllowlistStore(directory.resolve("apps.json"))
        val cipher = object : ApiConfigCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.copyOf()
            override fun decrypt(ciphertext: ByteArray) = ciphertext.copyOf()
        }
        fun model(selection: AppLaunchAllowlistStore = store): RootPilotViewModel {
            lateinit var result: RootPilotViewModel
            instrumentation.runOnMainSync {
                result = RootPilotViewModel(
                    context, RootPilotApiConfigStore(directory.resolve("unused-api.enc"), cipher),
                    appCatalog = AppCatalog { apps }, appLaunchStore = selection,
                )
                ViewModelStore().also { it.put("model", result); owners += it }
            }
            awaitLoaded(result)
            return result
        }
        try {
            val first = model()
            val staleEditor = model()
            assertTrue(first.appLaunchState.value.allowedPackages.isEmpty())
            instrumentation.runOnMainSync { first.setAppLaunchAllowed("com.example.notes", true) }
            awaitLoaded(first)
            assertEquals(setOf("com.example.notes"), store.read())
            instrumentation.runOnMainSync { staleEditor.setAppLaunchAllowed("com.example.reader", true) }
            awaitLoaded(staleEditor)
            assertEquals(setOf("com.example.notes", "com.example.reader"), store.read())
            instrumentation.runOnMainSync { owners.first().clear() }
            val recreated = model()
            assertEquals(setOf("com.example.notes", "com.example.reader"), recreated.appLaunchState.value.allowedPackages)
            instrumentation.runOnMainSync { recreated.clearAppLaunchSelection() }
            awaitLoaded(recreated)
            assertTrue(store.read().isEmpty())
            assertTrue(model().appLaunchState.value.allowedPackages.isEmpty())

            val blocker = directory.resolve("not-a-directory").apply { writeText("fixture") }
            val failing = model(AppLaunchAllowlistStore(File(blocker, "apps.json")))
            instrumentation.runOnMainSync { failing.setAppLaunchAllowed("com.example.notes", true) }
            awaitLoaded(failing)
            assertTrue(failing.appLaunchState.value.allowedPackages.isEmpty())
            assertNotNull(failing.appLaunchState.value.message)
            assertTrue(failing.appLaunchState.value.message!!.contains("保存失败"))
            assertEquals(prior.config.task, RootPilotService.uiState.value.config.task)
            assertEquals(prior.config.allowScreenUpload, RootPilotService.uiState.value.config.allowScreenUpload)
        } finally {
            instrumentation.runOnMainSync {
                owners.forEach { it.clear() }
                RootPilotService.updateApiConfig(if (prior.apiConfigured) RootPilotApiConfig(
                    prior.config.apiKey, prior.config.baseUrl, prior.config.model,
                ) else null)
                RootPilotService.updateConfig(prior.config)
            }
            directory.deleteRecursively()
        }
    }

    private fun awaitLoaded(model: RootPilotViewModel) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while ((model.appLaunchState.value.busy || model.apiState.value.busy) &&
            android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertFalse(model.appLaunchState.value.busy)
        assertFalse(model.apiState.value.busy)
    }
}
