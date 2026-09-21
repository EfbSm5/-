package com.example.agent.rootpilot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.apps.AndroidAppCatalog
import com.example.agent.rootpilot.apps.AllowlistedAppCatalog
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.input.AndroidImeEnvironment
import com.example.agent.rootpilot.log.AgentLogRepository
import com.example.agent.rootpilot.log.InMemoryAgentLogRepository
import com.example.agent.rootpilot.loop.ActionApproval
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.loop.AgentLoopEvent
import com.example.agent.rootpilot.loop.AgentLoopRequest
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.SuRootExecutor
import com.example.agent.rootpilot.screen.RootScreenshotProvider
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.ui.RootPilotOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log
import com.example.agent.rootpilot.log.RunTrace
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.log.TraceReason

class RootPilotService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var rootExecutor: RootExecutor
    private lateinit var loop: AgentLoop
    private lateinit var logRepository: AgentLogRepository
    private lateinit var runStore: RootPilotRunStore
    private var activeJob: Job? = null
    private var activeTrace: Pair<Job, RunTrace>? = null
    private var pendingApproval: ActionApproval? = null
    private var latestStartId: Int = 0
    private lateinit var overlay: RootPilotOverlay
    private var notificationApprovalToken: String? = null
    private var notificationApprovalIntent: PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        overlay = RootPilotOverlay(this, ::confirmAction) {
            stopAgent(synchronized(stateLock) { latestStartId })
        }
        val appCatalog = AllowlistedAppCatalog(AndroidAppCatalog(this), AppLaunchAllowlistStore.create(this))
        val textInput = AndroidImeEnvironment.createInput(this)
        rootExecutor = SuRootExecutor(appCatalog = appCatalog, typeText = textInput::type)
        logRepository = sharedLogRepository
        runStore = RootPilotRunStore(File(filesDir, RootPilotRunStore.FILE_NAME))
        restoreInterruptedRun()
        loop = AgentLoop(
            screenshotProvider = RootScreenshotProvider(rootExecutor),
            deepSeekClient = HttpDeepSeekClient(),
            rootExecutor = rootExecutor,
            appCatalog = appCatalog,
            todoRepository = com.example.agent.agent.planning.FileTodoRepository(File(filesDir, "agent_todos.json")),
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(stateLock) { latestStartId = startId }
        startForeground(NOTIFICATION_ID, buildNotification())
        intent?.readConfig()?.let(::updateConfig)
        when (intent?.action) {
            ACTION_TEST_ROOT -> testRoot(startId)
            ACTION_CAPTURE_SCREEN -> captureScreen(startId)
            ACTION_SINGLE_STEP -> startRun(singleStep = true, startId = startId)
            ACTION_AUTO_EXECUTE -> startRun(singleStep = false, startId = startId)
            ACTION_CONFIRM -> confirmAction()
            ACTION_CONFIRM_NOTIFICATION -> {
                confirmNotificationAction(intent.getStringExtra(EXTRA_APPROVAL_TOKEN))
                if (synchronized(stateLock) { activeJob?.isActive != true }) stopSelfResult(startId)
            }
            ACTION_STOP -> stopAgent(startId)
            ACTION_RECOVER -> startRun(singleStep = false, startId = startId, recovering = true)
            ACTION_DISCARD_RECOVERY -> discardInterruptedRun(startId)
            ACTION_RESTORE -> restoreInterruptedRun(startId)
            else -> if (intent == null) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlay.hide()
        synchronized(stateLock) {
            notificationApprovalIntent?.cancel()
            notificationApprovalIntent = null
            notificationApprovalToken = null
            pendingApproval?.reject()
            pendingApproval = null
            activeJob?.cancel()
            activeJob = null
        }
        rootExecutor.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun testRoot(startId: Int) {
        startOneShot(startId) { trace ->
            appendLog(trace, TraceReason.ROOT_CHECK_STARTED, TraceStatus.STARTED)
            when (val result = rootExecutor.checkRoot()) {
                is RootExecutionResult.Success -> {
                    appendLog(trace, TraceReason.ROOT_CHECK_OK, TraceStatus.SUCCESS)
                    updateState(status = RootPilotStatus.IDLE, errorMessage = null)
                }

                is RootExecutionResult.Failure -> {
                    appendLog(trace, TraceReason.ROOT_CHECK_FAILED, TraceStatus.FAILED)
                    updateState(
                        status = RootPilotStatus.FAILED,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    private fun captureScreen(startId: Int) {
        startOneShot(startId) { trace ->
            appendLog(trace, TraceReason.CAPTURE_STARTED, TraceStatus.STARTED)
            when (val result = loop.captureScreen()) {
                is ScreenshotCaptureResult.Success -> {
                    updateState(
                        status = RootPilotStatus.IDLE,
                        frame = result.frame,
                        errorMessage = null,
                    )
                    appendLog(trace, TraceReason.CAPTURE_OK, TraceStatus.SUCCESS)
                }

                is ScreenshotCaptureResult.Failure -> {
                    appendLog(trace, TraceReason.CAPTURE_FAILED, TraceStatus.FAILED)
                    updateState(
                        status = RootPilotStatus.FAILED,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    private fun startRun(singleStep: Boolean, startId: Int, recovering: Boolean = false) {
        val config = synchronized(stateLock) { uiState.value.config }
        val shouldStart = synchronized(stateLock) {
            if (activeJob?.isActive == true) {
                null
            } else {
                when {
                config.task.isBlank() -> {
                    updateStateLocked(
                        status = RootPilotStatus.FAILED,
                        errorMessage = "请先输入自然语言任务",
                    )
                    false
                }

                !recovering && uiState.value.status == RootPilotStatus.RECOVERY_REQUIRED -> {
                    updateStateLocked(
                        status = RootPilotStatus.RECOVERY_REQUIRED,
                        errorMessage = "请先处理上次中断的任务",
                    )
                    false
                }

                !config.allowScreenUpload -> {
                    updateStateLocked(
                        status = RootPilotStatus.FAILED,
                        errorMessage = "发送截图前请先打开上传确认",
                    )
                    false
                }

                    else -> {
                        updateStateLocked(
                            status = RootPilotStatus.CAPTURING,
                            step = 0,
                            pendingAction = null,
                            errorMessage = null,
                        )
                        true
                    }
                }
            }
        }
        if (shouldStart != true) {
            if (shouldStart == false) stopSelfResult(startId)
            return
        }

        if (recovering) clearRunSnapshot()
        persistRunSnapshot(RootPilotStatus.CAPTURING, step = 0)

        val trace = RunTrace(sink = ::appendTraceLine)
        lateinit var job: Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                loop.run(
                    request = AgentLoopRequest(
                        config = config,
                        maxSteps = MAX_STEPS,
                        singleStep = singleStep,
                    ),
                    trace = trace,
                    onEvent = ::handleEvent,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clearRunSnapshot()
                updateState(
                    status = RootPilotStatus.FAILED,
                    errorMessage = "AgentLoop 执行异常",
                    clearPendingAction = true,
                )
            } finally {
                synchronized(stateLock) {
                    if (activeTrace?.first === job) activeTrace = null
                }
                finishJob(job)
            }
        }
        synchronized(stateLock) {
            activeJob = job
            activeTrace = job to trace
        }
        job.start()
    }

    private fun startOneShot(startId: Int, work: suspend (RunTrace) -> Unit) {
        synchronized(stateLock) {
            if (activeJob?.isActive == true) return
            updateStateLocked(
                status = RootPilotStatus.CAPTURING,
                errorMessage = null,
            )
            val trace = RunTrace(sink = ::appendTraceLine)
            lateinit var job: Job
            job = serviceScope.launch {
                try {
                    work(trace)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    appendLog(trace, TraceReason.SERVICE_ERROR, TraceStatus.FAILED)
                    updateState(
                        status = RootPilotStatus.FAILED,
                        errorMessage = "RootPilot 操作异常",
                    )
                } finally {
                    finishJob(job)
                }
            }
            activeJob = job
        }
    }

    private fun confirmAction() {
        synchronized(stateLock) {
            pendingApproval?.approve()
            pendingApproval = null
            notifyState()
        }
    }

    private fun confirmNotificationAction(token: String?) {
        synchronized(stateLock) {
            if (token != null && pendingApproval?.approve(token) == true) {
                pendingApproval = null
                notifyState()
            }
        }
    }

    private fun finishJob(job: Job) {
        val startId = synchronized(stateLock) {
            if (activeJob === job) {
                activeJob = null
                latestStartId
            } else {
                null
            }
        }
        startId?.let(::stopSelfResult)
    }

    private fun stopAgent(startId: Int) {
        synchronized(stateLock) {
            activeTrace?.takeIf { it.first === activeJob }?.second?.record(
                TraceEvent.STOP_REQUESTED, TraceStatus.REQUESTED, stage = TraceStage.SERVICE,
            )
            pendingApproval?.reject()
            pendingApproval = null
            activeJob?.cancel()
            activeJob = null
            updateStateLocked(
                status = RootPilotStatus.STOPPED,
                pendingAction = null,
                errorMessage = "用户已停止",
            )
        }
        rootExecutor.cancel()
        clearRunSnapshot()
        stopSelfResult(startId)
    }

    private fun restoreInterruptedRun(startId: Int? = null) {
        val snapshot = runStore.read()
        if (snapshot == null) {
            startId?.let(::stopSelfResult)
            return
        }
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(
                config = snapshot.restoreTask(_uiState.value.config),
                status = RootPilotStatus.RECOVERY_REQUIRED,
                step = snapshot.step,
                errorMessage = buildString {
                    append("上次任务在 ${snapshot.status} 阶段中断，不会自动重放")
                    snapshot.actionSummary?.let { append("：$it") }
                },
                pendingAction = null,
            )
        }
        startId?.let {
            notifyState()
            stopSelfResult(it)
        }
    }

    private fun discardInterruptedRun(startId: Int) {
        clearRunSnapshot()
        updateState(
            status = RootPilotStatus.IDLE,
            clearPendingAction = true,
            errorMessage = null,
        )
        stopSelfResult(startId)
    }

    private fun persistRunSnapshot(
        status: RootPilotStatus,
        step: Int,
        actionSummary: String? = null,
    ) {
        val config = synchronized(stateLock) { uiState.value.config }
        runCatching {
            runStore.write(
                RootPilotRunSnapshot(
                    baseUrl = config.baseUrl,
                    model = config.model,
                    task = config.task,
                    manualConfirmation = config.manualConfirmation,
                    allowScreenUpload = config.allowScreenUpload,
                    status = status.name,
                    step = step,
                    actionSummary = actionSummary,
                ),
            )
        }
    }

    private fun clearRunSnapshot() {
        if (::runStore.isInitialized) {
            runCatching { runStore.clear() }
        }
    }

    private suspend fun handleEvent(event: AgentLoopEvent) {
        when (event) {
            is AgentLoopEvent.Capturing -> {
                updateState(status = RootPilotStatus.CAPTURING, step = event.step)
                persistRunSnapshot(RootPilotStatus.CAPTURING, event.step)
            }

            is AgentLoopEvent.ScreenshotCaptured -> {
                updateState(
                    status = RootPilotStatus.CAPTURING,
                    frame = event.frame,
                    step = event.step,
                )
                persistRunSnapshot(RootPilotStatus.CAPTURING, event.step)
            }

            is AgentLoopEvent.RequestingModel -> {
                updateState(status = RootPilotStatus.REQUESTING_MODEL, step = event.step)
                persistRunSnapshot(RootPilotStatus.REQUESTING_MODEL, event.step)
            }

            is AgentLoopEvent.AwaitingConfirmation -> {
                synchronized(stateLock) {
                    pendingApproval = event.approval
                    updateStateLocked(
                        status = RootPilotStatus.WAITING_CONFIRMATION,
                        step = event.step,
                        lastAction = event.action,
                        pendingAction = event.action,
                    )
                }
                persistRunSnapshot(
                    RootPilotStatus.WAITING_CONFIRMATION,
                    event.step,
                    event.action.describeForSnapshot(),
                )
            }

            is AgentLoopEvent.Executing -> {
                synchronized(stateLock) {
                    pendingApproval = null
                    updateStateLocked(
                        status = RootPilotStatus.EXECUTING,
                        step = event.step,
                        lastAction = event.action,
                        pendingAction = null,
                    )
                }
                persistRunSnapshot(
                    RootPilotStatus.EXECUTING,
                    event.step,
                    event.action.describeForSnapshot(),
                )
            }

            is AgentLoopEvent.WaitingScreen -> {
                updateState(status = RootPilotStatus.WAITING_SCREEN, step = event.step)
                persistRunSnapshot(RootPilotStatus.WAITING_SCREEN, event.step)
            }

            is AgentLoopEvent.Completed -> {
                updateState(
                    status = RootPilotStatus.COMPLETED,
                    clearPendingAction = true,
                    errorMessage = event.message,
                )
                clearRunSnapshot()
            }

            is AgentLoopEvent.Failed -> {
                updateState(
                    status = RootPilotStatus.FAILED,
                    clearPendingAction = true,
                    errorMessage = event.message,
                )
                clearRunSnapshot()
            }

            AgentLoopEvent.Stopped -> {
                clearRunSnapshot()
                updateState(
                    status = RootPilotStatus.STOPPED,
                    clearPendingAction = true,
                    errorMessage = "用户已停止",
                )
            }
        }
        // Complete window removal before the loop captures or injects input; an async
        // state collector could leave the panel in the screenshot or intercept a tap.
        withContext(Dispatchers.Main.immediate) {
            overlay.render(uiState.value)
        }
    }

    private fun updateState(
        status: RootPilotStatus,
        frame: com.example.agent.rootpilot.screen.ScreenshotFrame? = null,
        step: Int? = null,
        lastAction: com.example.agent.rootpilot.model.RootPilotAction? = null,
        pendingAction: com.example.agent.rootpilot.model.RootPilotAction? = null,
        errorMessage: String? = null,
        clearPendingAction: Boolean = false,
    ) {
        synchronized(stateLock) {
            updateStateLocked(status, frame, step, lastAction, pendingAction, errorMessage, clearPendingAction)
        }
    }

    private fun updateStateLocked(
        status: RootPilotStatus,
        frame: com.example.agent.rootpilot.screen.ScreenshotFrame? = null,
        step: Int? = null,
        lastAction: com.example.agent.rootpilot.model.RootPilotAction? = null,
        pendingAction: com.example.agent.rootpilot.model.RootPilotAction? = null,
        errorMessage: String? = null,
        clearPendingAction: Boolean = false,
    ) {
        _uiState.value = _uiState.value.copy(
            status = status,
            frame = frame ?: _uiState.value.frame,
            step = step ?: _uiState.value.step,
            lastAction = lastAction ?: _uiState.value.lastAction,
            pendingAction = if (clearPendingAction) null else pendingAction ?: _uiState.value.pendingAction,
            errorMessage = errorMessage,
        )
        notifyState()
    }

    private fun appendLog(trace: RunTrace, reason: TraceReason, status: TraceStatus) {
        trace.record(
            TraceEvent.CONTROL, status, reason, stage = TraceStage.SERVICE,
        )
    }

    private fun appendTraceLine(line: String) {
        logRepository.append(line)
        Log.i(RunTrace.TAG, line)
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(logs = logRepository.list())
            notifyState()
        }
    }

    private fun notifyState() {
        if (::rootExecutor.isInitialized &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "RootPilot Agent",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification = synchronized(stateLock) {
        val state = uiState.value
        val approval = pendingApproval?.takeIf { state.status == RootPilotStatus.WAITING_CONFIRMATION }
        if (notificationApprovalToken != approval?.token) {
            notificationApprovalIntent?.cancel()
            notificationApprovalToken = approval?.token
            notificationApprovalIntent = approval?.let {
                PendingIntent.getActivity(
                    this,
                    CONFIRM_REQUEST_CODE,
                    Intent(this, RootPilotNotificationActivity::class.java)
                        .setData(Uri.parse("rootpilot://confirm/${it.token}"))
                        .putExtra(EXTRA_APPROVAL_TOKEN, it.token)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }
        val openIntent = Intent(this, RootPilotActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, RootPilotService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = state.pendingAction?.takeIf { approval != null }?.let {
            "${it.describeForSnapshot()}：${it.reason}"
        } ?: state.status.notificationText()
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("RootPilot · 第 ${state.step + 1} 步")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(false)
            .setOngoing(activeJob?.isActive == true)
            .apply {
                notificationApprovalIntent?.let { confirmation ->
                    addAction(
                        NotificationCompat.Action.Builder(
                            android.R.drawable.ic_menu_send,
                            if (state.pendingAction is RootPilotAction.AskUser) "已处理，继续" else "确认当前动作",
                            confirmation,
                        ).setAuthenticationRequired(true).build(),
                    )
                }
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }

    private fun RootPilotStatus.notificationText(): String = when (this) {
        RootPilotStatus.IDLE -> "等待操作"
        RootPilotStatus.CAPTURING -> "正在截取屏幕"
        RootPilotStatus.REQUESTING_MODEL -> "正在请求模型"
        RootPilotStatus.WAITING_CONFIRMATION -> "等待人工确认"
        RootPilotStatus.EXECUTING -> "正在执行动作"
        RootPilotStatus.WAITING_SCREEN -> "等待页面稳定"
        RootPilotStatus.COMPLETED -> "任务已完成"
        RootPilotStatus.FAILED -> "任务失败"
        RootPilotStatus.STOPPED -> "任务已停止"
        RootPilotStatus.RECOVERY_REQUIRED -> "上次任务中断，等待处理"
    }

    private fun Intent.readConfig(): RootPilotConfig? {
        if (!hasExtra(EXTRA_TASK)) return null
        return uiState.value.config.copy(
            task = getStringExtra(EXTRA_TASK).orEmpty(),
            manualConfirmation = getBooleanExtra(EXTRA_MANUAL_CONFIRMATION, true),
            allowScreenUpload = getBooleanExtra(EXTRA_ALLOW_SCREEN_UPLOAD, false),
        )
    }

    private fun RootPilotAction.describeForSnapshot(): String = when (this) {
        is RootPilotAction.CreateTodo -> "create_todo 标题：$title；截止时间：${dueAt ?: "无"}"
        is RootPilotAction.Tap -> "tap($x,$y)"
        is RootPilotAction.Swipe -> "swipe($x1,$y1,$x2,$y2,$durationMillis)"
        is RootPilotAction.OpenApp -> "open_app($packageName)"
        is RootPilotAction.Type -> "type(length=${text.length})"
        is RootPilotAction.Key -> "key($key)"
        is RootPilotAction.Wait -> "wait($durationMillis)"
        is RootPilotAction.AskUser -> "ask_user"
        is RootPilotAction.Finish -> "finish($success)"
    }

    companion object {
        private const val CHANNEL_ID = "rootpilot_agent"
        private const val NOTIFICATION_ID = 2001
        private const val OPEN_REQUEST_CODE = 2003
        private const val STOP_REQUEST_CODE = 2002
        private const val CONFIRM_REQUEST_CODE = 2004
        private const val MAX_STEPS = 20

        private val _uiState = MutableStateFlow(RootPilotUiState())
        val uiState: StateFlow<RootPilotUiState> = _uiState.asStateFlow()
        private val stateLock = Any()
        private val sharedLogRepository: AgentLogRepository = InMemoryAgentLogRepository()

        const val ACTION_TEST_ROOT = "com.example.agent.rootpilot.TEST_ROOT"
        const val ACTION_CAPTURE_SCREEN = "com.example.agent.rootpilot.CAPTURE_SCREEN"
        const val ACTION_SINGLE_STEP = "com.example.agent.rootpilot.SINGLE_STEP"
        const val ACTION_AUTO_EXECUTE = "com.example.agent.rootpilot.AUTO_EXECUTE"
        const val ACTION_CONFIRM = "com.example.agent.rootpilot.CONFIRM"
        const val ACTION_CONFIRM_NOTIFICATION = "com.example.agent.rootpilot.CONFIRM_NOTIFICATION"
        const val EXTRA_APPROVAL_TOKEN = "extra_approval_token"
        const val ACTION_STOP = "com.example.agent.rootpilot.STOP"
        const val ACTION_RECOVER = "com.example.agent.rootpilot.RECOVER"
        const val ACTION_DISCARD_RECOVERY = "com.example.agent.rootpilot.DISCARD_RECOVERY"
        const val ACTION_RESTORE = "com.example.agent.rootpilot.RESTORE"
        const val EXTRA_TASK = "extra_task"
        const val EXTRA_MANUAL_CONFIRMATION = "extra_manual_confirmation"
        const val EXTRA_ALLOW_SCREEN_UPLOAD = "extra_allow_screen_upload"

        fun updateConfig(config: RootPilotConfig) {
            synchronized(stateLock) {
                // Task edits may carry a stale snapshot; only updateApiConfig owns API fields.
                _uiState.value = _uiState.value.copy(
                    config = _uiState.value.config.copy(
                        task = config.task,
                        manualConfirmation = config.manualConfirmation,
                        allowScreenUpload = config.allowScreenUpload,
                    ),
                )
            }
        }

        fun updateApiConfig(api: RootPilotApiConfig?) {
            synchronized(stateLock) {
                _uiState.value = _uiState.value.copy(
                    config = (api ?: RootPilotApiConfig()).applyTo(_uiState.value.config),
                    apiConfigured = api != null,
                )
            }
        }

        fun restoreIfNeeded(context: Context) {
            if (File(context.filesDir, RootPilotRunStore.FILE_NAME).isFile) {
                send(context, ACTION_RESTORE)
            }
        }

        fun send(context: Context, action: String, config: RootPilotConfig? = null) {
            val intent = Intent(context, RootPilotService::class.java).setAction(action)
            config?.let {
                intent.putExtra(EXTRA_TASK, it.task)
                intent.putExtra(EXTRA_MANUAL_CONFIRMATION, it.manualConfirmation)
                intent.putExtra(EXTRA_ALLOW_SCREEN_UPLOAD, it.allowScreenUpload)
            }
            context.startForegroundService(intent)
        }
    }
}
