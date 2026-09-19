package com.example.agent.rootpilot

import android.content.ContextWrapper
import android.security.NetworkSecurityPolicy
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.model.RootPilotConfig
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootPilotApiConfigFlowInstrumentedTest {
    @Test
    fun staleTaskUpdate_cannotReplaceNewOrClearedApiConfiguration() {
        val prior = RootPilotService.uiState.value
        try {
            for (replacement in listOf(
                RootPilotApiConfig("test-token-new", "https://new.example.invalid", "new-model"),
                null,
            )) {
                RootPilotService.updateApiConfig(RootPilotApiConfig(apiKey = "test-token-old"))
                // Reproduce a task edit read before IO publishes a credential replacement.
                val staleTaskEdit = RootPilotService.uiState.value.config.copy(
                    task = "updated-task", manualConfirmation = false, allowScreenUpload = true,
                )
                RootPilotService.updateApiConfig(replacement)
                RootPilotService.updateConfig(staleTaskEdit)

                val state = RootPilotService.uiState.value
                val expected = replacement ?: RootPilotApiConfig()
                assertEquals(expected.apiKey, state.config.apiKey)
                assertEquals(expected.baseUrl, state.config.baseUrl)
                assertEquals(expected.model, state.config.model)
                assertEquals(replacement != null, state.apiConfigured)
                assertEquals("updated-task", state.config.task)
                assertFalse(state.config.manualConfirmation)
                assertTrue(state.config.allowScreenUpload)
            }
        } finally {
            RootPilotService.updateApiConfig(
                if (prior.apiConfigured) RootPilotApiConfig(prior.config.apiKey, prior.config.baseUrl, prior.config.model) else null,
            )
            RootPilotService.updateConfig(prior.config)
        }
    }

    @Test
    fun closingEditorAfterDiskWrite_doesNotLeaveOldCredentialsActive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "api-cancel-").toFile()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        val alias = "rootpilot-cancel-test-${UUID.randomUUID()}"
        val store = RootPilotApiConfigStore(directory.resolve("config.enc"), AesGcmApiConfigCipher(AndroidKeystoreApiConfigKeyProvider(alias)))
        val prior = RootPilotService.uiState.value
        val owners = mutableListOf<ViewModelStore>()
        try {
            for (clear in listOf(false, true)) {
                store.save(RootPilotApiConfig(apiKey = "test-token-before-close"))
                val trackCompletion = AtomicBoolean(false)
                val ioFinished = CountDownLatch(1)
                val dispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        Dispatchers.IO.dispatch(context) {
                            block.run()
                            if (trackCompletion.get()) ioFinished.countDown()
                        }
                    }
                }
                lateinit var victim: RootPilotViewModel
                val owner = ViewModelStore().also { owners += it }
                instrumentation.runOnMainSync {
                    victim = RootPilotViewModel(context, store, dispatcher)
                    owner.put("model", victim)
                }
                awaitIdle(victim)
                instrumentation.runOnMainSync {
                    trackCompletion.set(true)
                    if (clear) victim.clearApiConfig() else {
                        victim.editApiConfig()
                        victim.updateApiKey("test-token-after-close")
                        victim.saveApiConfig()
                    }
                    // Keep the main continuation queued until its owner has been destroyed.
                    assertTrue(ioFinished.await(5, TimeUnit.SECONDS))
                    owner.clear()
                }
                val expectedToken = if (clear) "" else "test-token-after-close"
                assertEquals(expectedToken, store.read()?.apiKey.orEmpty())
                assertEquals(expectedToken, RootPilotService.uiState.value.config.apiKey)
                assertEquals(!clear, RootPilotService.uiState.value.apiConfigured)
            }
        } finally {
            instrumentation.runOnMainSync {
                owners.forEach { it.clear() }
                RootPilotService.updateApiConfig(
                    if (prior.apiConfigured) RootPilotApiConfig(prior.config.apiKey, prior.config.baseUrl, prior.config.model) else null,
                )
                RootPilotService.updateConfig(prior.config)
            }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleartextPolicy_allowsOnlyLoopbackRelays() {
        val policy = NetworkSecurityPolicy.getInstance()
        for (host in listOf("localhost", "127.0.0.1", "[::1]")) {
            assertTrue("Local relay must be allowed: $host", policy.isCleartextTrafficPermitted(host))
        }
        assertFalse(policy.isCleartextTrafficPermitted("example.invalid"))
        assertFalse(policy.isCleartextTrafficPermitted("api.deepseek.com"))
    }

    @Test
    fun saveReplaceReloadAndClear_updateMemoryWithoutChangingTaskConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "api-flow-").toFile()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = directory
        }
        val alias = "rootpilot-flow-test-${UUID.randomUUID()}"
        val store = RootPilotApiConfigStore(
            directory.resolve("config.enc"),
            AesGcmApiConfigCipher(AndroidKeystoreApiConfigKeyProvider(alias)),
        )
        val prior = RootPilotService.uiState.value.config
        val priorConfigured = RootPilotService.uiState.value.apiConfigured
        val owners = mutableListOf<ViewModelStore>()
        fun create(): RootPilotViewModel {
            lateinit var model: RootPilotViewModel
            instrumentation.runOnMainSync {
                model = RootPilotViewModel(context, store)
                owners += ViewModelStore().also { it.put("model", model) }
            }
            awaitIdle(model)
            return model
        }
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        try {
            onMain {
                RootPilotService.updateConfig(RootPilotConfig(task = "保留任务", allowScreenUpload = false, manualConfirmation = true))
            }
            val first = create()
            assertFalse(first.apiState.value.configured)
            assertEquals("https://api.deepseek.com", first.apiState.value.draft.baseUrl)
            onMain {
                first.updateApiKey("test-token-first")
                first.saveApiConfig()
            }
            awaitIdle(first)
            assertTrue(first.apiState.value.configured)
            assertEquals("", first.apiState.value.draft.apiKey)
            assertEquals("test-token-first", first.uiState.value.config.apiKey)
            assertEquals("保留任务", first.uiState.value.config.task)
            assertFalse(first.uiState.value.config.allowScreenUpload)
            assertTrue(first.uiState.value.config.manualConfirmation)

            val olderEditor = create()
            onMain { olderEditor.editApiConfig() }

            onMain {
                first.editApiConfig()
                first.updateApiKey("test-token-second")
                first.updateModel("replacement-model")
                first.saveApiConfig()
            }
            awaitIdle(first)
            onMain { olderEditor.cancelApiConfigEdit() }
            assertEquals("test-token-second", olderEditor.uiState.value.config.apiKey)
            assertEquals("replacement-model", olderEditor.apiState.value.draft.model)
            assertEquals("test-token-second", first.uiState.value.config.apiKey)
            val reloaded = create()
            assertTrue(reloaded.apiState.value.configured)
            assertEquals("test-token-second", reloaded.uiState.value.config.apiKey)
            assertEquals("replacement-model", reloaded.apiState.value.draft.model)

            onMain {
                olderEditor.editApiConfig()
                reloaded.clearApiConfig()
            }
            awaitIdle(reloaded)
            onMain { olderEditor.cancelApiConfigEdit() }
            assertFalse(olderEditor.apiState.value.configured)
            assertEquals("", olderEditor.uiState.value.config.apiKey)
            assertEquals("", reloaded.uiState.value.config.apiKey)
            assertNull(store.read())
            val cleared = create()
            assertFalse(cleared.apiState.value.configured)
            assertTrue(cleared.apiState.value.editing)
            assertEquals("deepseek-flash", cleared.apiState.value.draft.model)
            assertFalse(cleared.uiState.value.config.allowScreenUpload)
            assertTrue(cleared.uiState.value.config.manualConfirmation)
        } finally {
            onMain {
                owners.forEach { it.clear() }
                RootPilotService.updateApiConfig(
                    if (priorConfigured) RootPilotApiConfig(prior.apiKey, prior.baseUrl, prior.model) else null,
                )
                RootPilotService.updateConfig(prior)
            }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private fun awaitIdle(model: RootPilotViewModel) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (model.apiState.value.busy && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(20)
        }
        assertFalse("API configuration operation timed out", model.apiState.value.busy)
    }
}
