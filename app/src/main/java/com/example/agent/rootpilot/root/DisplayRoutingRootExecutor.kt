package com.example.agent.rootpilot.root

import android.content.Context
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.information.DeviceInfoResult
import com.example.agent.rootpilot.information.DeviceInfoSource
import com.example.agent.rootpilot.information.DeviceInfoTool
import com.example.agent.rootpilot.information.DeviceInfoUnavailable
import com.example.agent.rootpilot.information.publicMetadata
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.sameTarget
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

/** Routes an entire run, never individual failed operations, to a single execution display. */
internal class DisplayRoutingRootExecutor(
    private val context: Context,
    private val main: RootExecutor,
    private val apps: AppCatalog,
) : RootExecutor {
    private val lock = Any()
    @Volatile private var virtualRequested = false
    @Volatile private var session: VirtualDisplaySession? = null
    @Volatile private var observer: RootScreenObserver? = null
    @Volatile private var startApp: RootPilotApp? = null
    private var generation = 0L

    override val initialApp: RootPilotApp? get() = startApp
    override val sessionIdentity: String? get() = session?.sessionId

    override suspend fun beginRun(config: RootPilotConfig): RootExecutionResult {
        val epoch = synchronized(lock) {
            if (session != null) return RootExecutionResult.Failure("上一副屏尚未确认关闭")
            virtualRequested = config.executionDisplay == ExecutionDisplay.VIRTUAL
            startApp = null
            generation
        }
        if (!virtualRequested) return main.beginRun(config)
        val app = apps.listApps().singleOrNull { it.packageName == config.virtualDisplayStartPackage }
            ?: return RootExecutionResult.Failure("请从允许启动的应用中选择副屏起始应用")
        currentCoroutineContext().ensureActive()
        val created = VirtualDisplaySession(context)
        synchronized(lock) {
            if (generation != epoch) throw CancellationException("Display session cancelled")
            session = created
        }
        val result = created.start()
        currentCoroutineContext().ensureActive()
        if (result is RootExecutionResult.Success) {
            synchronized(lock) {
                if (generation != epoch) throw CancellationException("Display session cancelled")
                observer = RootScreenObserver(DisplaySession(created.displayId, created.sessionId))
                startApp = app
            }
        }
        return result
    }

    override suspend fun endRun(): RootExecutionResult {
        val owned = session
        if (owned == null) return if (virtualRequested) RootExecutionResult.Success() else main.endRun()
        if (!owned.close()) return RootExecutionResult.Failure("副屏释放尚未确认，禁止继续启动任务")
        synchronized(lock) {
            if (session === owned) {
                session = null
                observer = null
                startApp = null
            }
        }
        return RootExecutionResult.Success()
    }

    override suspend fun checkRoot() = main.checkRoot()
    override suspend fun validateSession(): Boolean =
        if (!virtualRequested) main.validateSession() else session?.validate() == true

    private fun unavailable(): ScreenObservation = ScreenObservation(null, null, null, null, null,
        System.nanoTime() / 1_000_000, session?.displayId ?: -1, sessionIdentity)

    override suspend fun observeScreen(): ScreenObservation {
        if (!virtualRequested) return main.observeScreen()
        if (!validateSession()) return unavailable()
        val result = observer?.observe() ?: return unavailable()
        return if (validateSession()) result else unavailable()
    }

    override suspend fun captureScreen(): RootScreenshotResult =
        if (!virtualRequested) main.captureScreen()
        else session?.capture() ?: RootScreenshotResult.Failure("副屏会话不存在，未截取主屏")

    override fun supports(action: ExecutableRootAction): Boolean = if (!virtualRequested) main.supports(action)
        else action is ExecutableRootAction.OpenApp || action is ExecutableRootAction.Tap || action is ExecutableRootAction.Wait

    override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
        if (!virtualRequested) return main.execute(action)
        if (!supports(action)) return RootExecutionResult.Failure("副屏首版只支持应用启动、点击和等待")
        val owned = session ?: return RootExecutionResult.Failure("副屏会话不存在，未执行动作")
        if (!owned.validate()) return RootExecutionResult.Failure("副屏会话已失效，未执行动作")
        if (action is ExecutableRootAction.OpenApp && apps.listApps().none {
                it.packageName == action.app.packageName && it.activityName == action.app.activityName
            }) return RootExecutionResult.Failure("应用已不在允许启动列表中")
        if (action is ExecutableRootAction.Wait) {
            if (action.durationMillis !in 300..5_000) return RootExecutionResult.Failure("等待时长不合法")
            delay(action.durationMillis.toLong())
            return if (owned.validate()) RootExecutionResult.Success() else RootExecutionResult.Failure("副屏会话已失效")
        }
        return owned.execute(action)
    }

    override suspend fun executeConfirmed(action: ExecutableRootAction, confirm: suspend (String?) -> Boolean): RootExecutionResult =
        if (!virtualRequested) main.executeConfirmed(action, confirm) else super.executeConfirmed(action, confirm)

    override suspend fun queryDeviceInfo(tool: DeviceInfoTool, expected: ScreenObservation): DeviceInfoResult {
        if (!virtualRequested) return main.queryDeviceInfo(tool, expected)
        val started = System.nanoTime() / 1_000_000
        val source = when (tool) {
            DeviceInfoTool.SCREEN_CONTEXT -> DeviceInfoSource.SCREEN_OBSERVER
            DeviceInfoTool.ACTIVITY_STACK -> DeviceInfoSource.ACTIVITY_DUMP
            DeviceInfoTool.UI_TREE -> DeviceInfoSource.UI_SEMANTICS
        }
        if (tool != DeviceInfoTool.SCREEN_CONTEXT) return DeviceInfoResult(source, started, started,
            unavailable = DeviceInfoUnavailable.NOT_SUPPORTED)
        val current = observeScreen()
        return if (expected.sameTarget(current) && expected.keyboardVisible == current.keyboardVisible)
            DeviceInfoResult(source, started, System.nanoTime() / 1_000_000, current.publicMetadata())
        else DeviceInfoResult(source, started, System.nanoTime() / 1_000_000,
            unavailable = DeviceInfoUnavailable.TARGET_NOT_READY)
    }

    override fun cancel() {
        synchronized(lock) { generation++ }
        observer?.cancel()
        session?.cancel()
        main.cancel()
    }
}
