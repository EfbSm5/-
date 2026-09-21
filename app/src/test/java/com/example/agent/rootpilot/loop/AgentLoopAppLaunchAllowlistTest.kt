package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.apps.AllowlistedAppCatalog
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentLoopAppLaunchAllowlistTest {
    @get:Rule val temporary = TemporaryFolder()
    private val notes = RootPilotApp("com.example.notes", "记事本", "com.example.notes.Main")
    private val settings = RootPilotApp("com.android.settings", "设置", "com.android.settings.Settings")
    private val request = AgentLoopRequest(
        config = RootPilotConfig(task = "测试应用选择", allowScreenUpload = true),
        maxSteps = 2,
    )

    @Test
    fun modelReceivesOnlySelectedAppsAndNextRequestSeesRevocationFromAnotherStore() = runTest {
        val file = File(temporary.root, "selection.json")
        AppLaunchAllowlistStore(file).save(setOf(notes.packageName, "com.example.notinstalled"))
        val client = RecordingClient(
            """{"action":"ask_user","message":"测试暂停点"}""",
            """{"action":"finish","success":true,"message":"完成"}""",
        )
        val events = mutableListOf<AgentLoopEvent>()
        loop(file, client).run(request) {
            events += it
            if (it is AgentLoopEvent.AwaitingConfirmation) {
                assertEquals(listOf(notes), client.requests.single().availableApps)
                AppLaunchAllowlistStore(file).save(emptySet())
                it.approval.approve()
            }
        }

        assertEquals(listOf(0, 1), client.requests.map { it.step })
        assertEquals(listOf(listOf(notes), emptyList<RootPilotApp>()), client.requests.map { it.availableApps })
        assertTrue(events.last() is AgentLoopEvent.Completed)
    }

    @Test
    fun firstRunWithoutSelectionSendsEmptyAvailableAppsDespiteInstalledApps() = runTest {
        val client = RecordingClient("""{"action":"finish","success":true,"message":"完成"}""")
        val events = mutableListOf<AgentLoopEvent>()
        loop(File(temporary.root, "missing.json"), client).run(request) { events += it }

        assertEquals(emptyList<RootPilotApp>(), client.requests.single().availableApps)
        assertTrue(events.last() is AgentLoopEvent.Completed)
    }

    private fun loop(file: File, client: DeepSeekClient): AgentLoop = AgentLoop(
        screenshotProvider = object : ScreenshotProvider {
            override suspend fun capture() = ScreenshotCaptureResult.Success(
                ScreenshotFrame(byteArrayOf(1), 100, 100, "data:image/jpeg;base64,fixture"),
            )
        },
        deepSeekClient = client,
        rootExecutor = object : RootExecutor {
            override suspend fun checkRoot(): Nothing = error("测试不允许 Root 调用")
            override suspend fun captureScreen(): Nothing = error("测试仅允许 fixture 截图")
            override suspend fun execute(action: ExecutableRootAction): Nothing = error("测试不允许动作或 Shell 执行")
            override fun cancel(): Unit = error("测试不允许 Root 调用")
        },
        appCatalog = AllowlistedAppCatalog(
            AppCatalog { listOf(notes, settings) },
            AppLaunchAllowlistStore(file),
        ),
    )

    private class RecordingClient(vararg responses: String) : DeepSeekClient {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<DeepSeekVisionRequest>()

        override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
            requests += request
            return DeepSeekActionResult.Success(responses.removeFirst())
        }
    }
}
