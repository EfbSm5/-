package com.example.agent.rootpilot.root

import android.content.Context
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.information.AndroidUiTreeProvider
import com.example.agent.rootpilot.information.DeviceInfoResult
import com.example.agent.rootpilot.information.DeviceInfoSource
import com.example.agent.rootpilot.information.DeviceInfoTool
import com.example.agent.rootpilot.information.DeviceInfoUnavailable
import com.example.agent.rootpilot.information.publicMetadata
import com.example.agent.rootpilot.input.VirtualDisplayTextInput
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotKey
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
    private val deviceInfoProvider = RootDeviceInfoProvider()
    private val virtualTextInput = VirtualDisplayTextInput(context.packageName)

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
        else action is ExecutableRootAction.OpenApp || action is ExecutableRootAction.Tap ||
            action is ExecutableRootAction.Swipe || action is ExecutableRootAction.Wait ||
            action is ExecutableRootAction.Type ||
            (action is ExecutableRootAction.Key && action.key != RootPilotKey.HOME)

    override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
        if (!virtualRequested) return main.execute(action)
        if (action is ExecutableRootAction.Type) return RootExecutionResult.Failure("副屏文字输入必须绑定输入框及旧文／选区并确认")
        if (!supports(action)) return RootExecutionResult.Failure("副屏不支持 HOME 按键")
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

    override suspend fun executeConfirmed(action: ExecutableRootAction, confirm: suspend (String?) -> Boolean): RootExecutionResult {
        if (!virtualRequested) return main.executeConfirmed(action, confirm)
        if (action !is ExecutableRootAction.Type) return super.executeConfirmed(action, confirm)
        val owned = session ?: return RootExecutionResult.Failure("副屏会话不存在，未输入文本")
        val ownedObserver = observer ?: return RootExecutionResult.Failure("副屏观察器不可用，未输入文本")
        if (owned.displayId <= 0) return RootExecutionResult.Failure("副屏会话尚未就绪，未输入文本")
        val epoch = synchronized(lock) { generation }
        fun isCurrent() = virtualRequested && session === owned && observer === ownedObserver &&
            synchronized(lock) { generation == epoch }
        return virtualTextInput.type(action.text, DisplaySession(owned.displayId, owned.sessionId),
            isCurrent = ::isCurrent,
            validateSession = { isCurrent() && owned.validate() && isCurrent() },
            observe = { ownedObserver.observe() },
            confirm = { confirm(it) })
    }

    override suspend fun queryDeviceInfo(tool: DeviceInfoTool, expected: ScreenObservation): DeviceInfoResult {
        if (!virtualRequested) return main.queryDeviceInfo(tool, expected)
        val started = System.nanoTime() / 1_000_000
        val source = when (tool) {
            DeviceInfoTool.SCREEN_CONTEXT -> DeviceInfoSource.SCREEN_OBSERVER
            DeviceInfoTool.ACTIVITY_STACK -> DeviceInfoSource.ACTIVITY_DUMP
            DeviceInfoTool.UI_TREE -> DeviceInfoSource.UI_SEMANTICS
        }
        if (tool == DeviceInfoTool.ACTIVITY_STACK) {
            fun notReady() = DeviceInfoResult(source, started, System.nanoTime() / 1_000_000,
                unavailable = DeviceInfoUnavailable.TARGET_NOT_READY)
            val owned = session ?: return notReady()
            val ownedObserver = observer ?: return notReady()
            if (expected.displayId <= 0 || expected.displayId != owned.displayId ||
                expected.sessionId != owned.sessionId || !owned.validate() || session !== owned) return notReady()
            val binding = DisplaySession(owned.displayId, owned.sessionId)
            if (!ActivityStackParser.acceptsTarget(expected, binding)) return notReady()
            val before = ownedObserver.observe()
            currentCoroutineContext().ensureActive()
            if (session !== owned || !expected.sameTarget(before) ||
                expected.keyboardVisible != before.keyboardVisible) return notReady()
            val result = deviceInfoProvider.activityStack(expected, binding)
            currentCoroutineContext().ensureActive()
            val after = ownedObserver.observe()
            currentCoroutineContext().ensureActive()
            // A dump from a released session cannot identify its replacement, even with the same display ID.
            if (session !== owned || !owned.validate() || session !== owned ||
                !expected.sameTarget(after) || expected.keyboardVisible != after.keyboardVisible) return notReady()
            return result
        }
        if (tool == DeviceInfoTool.UI_TREE) {
            fun notReady() = DeviceInfoResult(source, started, System.nanoTime() / 1_000_000,
                unavailable = DeviceInfoUnavailable.TARGET_NOT_READY)
            val owned = session ?: return notReady()
            if (expected.displayId <= 0 || expected.displayId != owned.displayId ||
                expected.sessionId != owned.sessionId || !owned.validate() || session !== owned) return notReady()
            val binding = DisplaySession(expected.displayId, owned.sessionId)
            val result = AndroidUiTreeProvider(context.packageName, binding).query(expected)
            currentCoroutineContext().ensureActive()
            // A tree collected before release must not become evidence for a replacement session.
            if (session !== owned || !owned.validate() || session !== owned) return notReady()
            return result
        }
        val current = observeScreen()
        return if (expected.sameTarget(current) && expected.keyboardVisible == current.keyboardVisible)
            DeviceInfoResult(source, started, System.nanoTime() / 1_000_000, current.publicMetadata())
        else DeviceInfoResult(source, started, System.nanoTime() / 1_000_000,
            unavailable = DeviceInfoUnavailable.TARGET_NOT_READY)
    }

    override fun cancel() {
        synchronized(lock) { generation++ }
        virtualTextInput.cancel()
        deviceInfoProvider.cancel()
        observer?.cancel()
        session?.cancel()
        main.cancel()
    }
}
