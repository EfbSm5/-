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
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.root.SuRootExecutor
import com.example.agent.rootpilot.screen.RootScreenshotProvider
import com.example.agent.rootpilot.ui.RootPilotOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log
import com.example.agent.rootpilot.log.RunTrace
import com.example.agent.rootpilot.history.RunHistoryRepository
import com.example.agent.rootpilot.history.RunHistoryState
import com.example.agent.rootpilot.history.RunHistoryStore

class RootPilotService : Service(), RootPilotRunHost {
    private lateinit var controller: RootPilotRunController
    private lateinit var overlay: RootPilotOverlay
    @Volatile private var destroyed = false
    private var latestStartId = 0
    private var notificationApprovalToken: String? = null
    private var notificationApprovalIntent: PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        overlay = RootPilotOverlay(this, { controller.confirmAction() }) {
            controller.stopAgent(latestStartId)
        }
        val appCatalog = AllowlistedAppCatalog(AndroidAppCatalog(this), AppLaunchAllowlistStore.create(this))
        val textInput = AndroidImeEnvironment.createInput(this)
        val rootExecutor = SuRootExecutor(appCatalog = appCatalog, typeText = textInput::type)
        controller = RootPilotRunController(
            taskState = taskState,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            rootExecutor = rootExecutor,
            loop = AgentLoop(
                screenshotProvider = RootScreenshotProvider(rootExecutor),
                deepSeekClient = HttpDeepSeekClient(),
                rootExecutor = rootExecutor,
                appCatalog = appCatalog,
                todoRepository = com.example.agent.agent.planning.FileTodoRepository(File(filesDir, "agent_todos.json")),
            ),
            runStore = RootPilotRunStore(File(filesDir, RootPilotRunStore.FILE_NAME)),
            logRepository = sharedLogRepository,
            traceSink = { Log.i(RunTrace.TAG, it) },
            host = this,
            history = historyRepository(this),
        )
        createNotificationChannel()
        controller.restoreInterruptedRun()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        controller.commandStarted(startId)
        startForeground(NOTIFICATION_ID, buildNotification())
        intent?.readConfig()?.let(::updateConfig)
        when (intent?.action) {
            ACTION_TEST_ROOT -> controller.testRoot(startId)
            ACTION_CAPTURE_SCREEN -> controller.captureScreen(startId)
            ACTION_SINGLE_STEP -> controller.startRun(singleStep = true, startId = startId)
            ACTION_AUTO_EXECUTE -> controller.startRun(singleStep = false, startId = startId)
            ACTION_CONFIRM -> controller.confirmAction()
            ACTION_CONFIRM_NOTIFICATION -> {
                controller.confirmNotificationAction(intent.getStringExtra(EXTRA_APPROVAL_TOKEN))
                if (!controller.busy) stopSelfResult(startId)
            }
            ACTION_STOP -> controller.stopAgent(startId)
            ACTION_RECOVER -> controller.startRun(singleStep = false, startId = startId, recovering = true)
            ACTION_DISCARD_RECOVERY -> controller.discardInterruptedRun(startId)
            ACTION_RESTORE -> controller.restoreInterruptedRun(startId)
            else -> if (intent == null) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        overlay.hide()
        synchronized(stateLock) {
            notificationApprovalIntent?.cancel()
            notificationApprovalIntent = null
            notificationApprovalToken = null
        }
        controller.destroy()
        super.onDestroy()
    }

    override fun stateChanged() = notifyState()

    override suspend fun renderOverlay(state: RootPilotUiState) {
        // Removal must finish before the loop captures or injects input.
        withContext(Dispatchers.Main.immediate) {
            // A stop command may have arrived while this render was queued.
            if (!destroyed) overlay.render(uiState.value)
        }
    }

    override fun hideOverlay() { if (!destroyed) overlay.hide() }

    override fun idle(startId: Int) { if (!destroyed) stopSelfResult(startId) }

    private fun notifyState() {
        if (!destroyed &&
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
        val approval = controller.approval?.takeIf { state.status == RootPilotStatus.WAITING_CONFIRMATION }
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
            .setOngoing(controller.busy)
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
        RootPilotStatus.STOPPING -> "正在停止，等待执行退出"
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

    companion object {
        private const val CHANNEL_ID = "rootpilot_agent"
        private const val NOTIFICATION_ID = 2001
        private const val OPEN_REQUEST_CODE = 2003
        private const val STOP_REQUEST_CODE = 2002
        private const val CONFIRM_REQUEST_CODE = 2004

        private val taskState = RootPilotTaskState()
        private val _uiState get() = taskState.mutableState
        val uiState: StateFlow<RootPilotUiState> = taskState.uiState
        private val stateLock get() = taskState.lock
        private val sharedLogRepository: AgentLogRepository = InMemoryAgentLogRepository()
        private var sharedHistory: RunHistoryRepository? = null

        @Synchronized
        private fun historyRepository(context: Context): RunHistoryRepository = sharedHistory
            ?: RunHistoryRepository(
                RunHistoryStore(File(context.applicationContext.noBackupFilesDir,
                    "rootpilot_history/${RunHistoryStore.FILE_NAME}")),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { sharedHistory = it }

        fun historyState(context: Context): StateFlow<RunHistoryState> = historyRepository(context).state

        fun clearHistory(context: Context) { historyRepository(context).clear() }

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
                if (taskState.owner != null) return
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
            if (File(context.filesDir, RootPilotRunStore.FILE_NAME).exists()) {
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
