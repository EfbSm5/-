package com.example.agent.rootpilot

import com.example.agent.rootpilot.log.AgentLogRepository
import com.example.agent.rootpilot.log.RunTrace
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.history.RunHistoryRepository
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.loop.ActionApproval
import com.example.agent.rootpilot.loop.AgentLoop
import com.example.agent.rootpilot.loop.AgentLoopEvent
import com.example.agent.rootpilot.loop.AgentLoopRequest
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.model.SavedTodoResult
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process state and execution ownership share a lifetime, including cancellation cleanup. */
internal class RootPilotTaskState {
    val lock = Any()
    val mutableState = MutableStateFlow(RootPilotUiState())
    val uiState: StateFlow<RootPilotUiState> = mutableState.asStateFlow()
    var owner: RootPilotRunController? = null
        set(value) {
            field = value
            mutableState.value = mutableState.value.copy(running = value != null)
        }
    var recoveryBlocked = false
}

internal interface RootPilotRunHost {
    fun stateChanged()
    suspend fun renderOverlay(state: RootPilotUiState)
    fun hideOverlay()
    fun idle(startId: Int)
}

/** Owns a service session's run; Android windows and notifications remain in the host. */
internal class RootPilotRunController(
    private val taskState: RootPilotTaskState,
    private val scope: CoroutineScope,
    private val rootExecutor: RootExecutor,
    private val loop: AgentLoop,
    private val runStore: RootPilotRunStore,
    private val logRepository: AgentLogRepository,
    private val traceSink: (String) -> Unit = {},
    host: RootPilotRunHost,
    private val history: RunHistoryRepository? = null,
) {
    private val stateLock get() = taskState.lock
    private val _uiState get() = taskState.mutableState
    private val uiState get() = taskState.uiState
    @Volatile private var host: RootPilotRunHost? = host
    private var destroyed = false
    private var activeJob: Job? = null
    private var activeTrace: Pair<Job, RunTrace>? = null
    private var pendingApproval: ActionApproval? = null
    private var latestStartId = 0
    private var isDeviceRun = false
    private var storageFailure = false
    private var interrupted = false
    private var snapshotFailureReason = TraceReason.NONE

    val busy: Boolean get() = synchronized(stateLock) { taskState.owner != null }
    val approval: ActionApproval? get() = synchronized(stateLock) { pendingApproval }

    fun commandStarted(startId: Int) = synchronized(stateLock) { latestStartId = startId }
    private fun finishHost(startId: Int) { host?.idle(startId) }
    private fun notifyState() { host?.stateChanged() }

    fun destroy() {
        synchronized(stateLock) {
            destroyed = true
            host = null
            if (activeJob != null) {
                interrupted = isDeviceRun && uiState.value.status !in setOf(
                    RootPilotStatus.COMPLETED, RootPilotStatus.FAILED, RootPilotStatus.STOPPED,
                )
                updateStateLocked(
                    RootPilotStatus.STOPPING, clearPendingAction = true,
                    errorMessage = "服务已中断，正在等待执行退出",
                )
                pendingApproval?.reject()
                pendingApproval = null
                rootExecutor.cancel()
                activeJob?.cancel()
            }
        }
        scope.cancel()
    }

    fun testRoot(startId: Int) {
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

    fun captureScreen(startId: Int) {
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

    fun startRun(singleStep: Boolean, startId: Int, recovering: Boolean = false) = synchronized(stateLock) {
        if (destroyed) return@synchronized
        val config = uiState.value.config
        if (taskState.owner != null) {
            if (taskState.owner !== this) finishHost(startId)
            return@synchronized
        }
        val problem = when {
            taskState.recoveryBlocked -> "恢复记录不可用，请先核对已执行结果，再放弃记录"
            !recovering && uiState.value.status == RootPilotStatus.RECOVERY_REQUIRED -> "请先处理上次中断的任务"
            config.task.isBlank() -> "请先输入自然语言任务"
            !config.allowScreenUpload -> "发送截图前请先打开上传确认"
            else -> null
        }
        if (problem != null) {
            updateStateLocked(
                status = if (taskState.recoveryBlocked || uiState.value.status == RootPilotStatus.RECOVERY_REQUIRED)
                    RootPilotStatus.RECOVERY_REQUIRED else RootPilotStatus.FAILED,
                errorMessage = problem,
            )
            finishHost(startId)
            return@synchronized
        }
        _uiState.value = _uiState.value.copy(savedTodos = emptyList(), modelReportedResult = false)
        updateStateLocked(
            status = RootPilotStatus.CAPTURING, step = 0, clearPendingAction = true,
        )

        isDeviceRun = true
        storageFailure = false
        snapshotFailureReason = TraceReason.NONE
        val trace = RunTrace(observer = { history?.record(it) }, sink = ::appendTraceLine)
        history?.begin(trace.runId)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                persistRunSnapshot(RootPilotStatus.CAPTURING, step = 0)
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
            } catch (error: RootPilotRunStoreException) {
                // The persistence boundary latched the failure before any executor cleanup.
                if (!storageFailure) markStorageFailure(error.operation)
            } catch (_: Exception) {
                synchronized(stateLock) {
                    if (!destroyed && uiState.value.status != RootPilotStatus.STOPPING && clearRunSnapshot()) {
                        updateStateLocked(
                            status = RootPilotStatus.FAILED,
                            errorMessage = "AgentLoop 执行异常",
                            clearPendingAction = true,
                        )
                    }
                }
            }
        }
        synchronized(stateLock) {
            taskState.owner = this
            activeJob = job
            activeTrace = job to trace
        }
        job.invokeOnCompletion { finishJob(job) }
        job.start()
    }

    private fun startOneShot(startId: Int, work: suspend (RunTrace) -> Unit) {
        synchronized(stateLock) {
            if (destroyed) return
            if (taskState.owner != null) {
                if (taskState.owner !== this) finishHost(startId)
                return
            }
            if (uiState.value.status == RootPilotStatus.RECOVERY_REQUIRED || taskState.recoveryBlocked) {
                finishHost(startId)
                return
            }
            isDeviceRun = false
            storageFailure = false
            _uiState.value = _uiState.value.copy(savedTodos = emptyList(), modelReportedResult = false)
            updateStateLocked(
                status = RootPilotStatus.CAPTURING,
                errorMessage = null,
            )
            val trace = RunTrace(sink = ::appendTraceLine)
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
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
                }
            }
            taskState.owner = this
            activeJob = job
            job.invokeOnCompletion { finishJob(job) }
            job.start()
        }
    }

    fun confirmAction() {
        synchronized(stateLock) {
            if (destroyed || taskState.owner !== this) return
            pendingApproval?.approve()
            pendingApproval = null
            notifyState()
        }
    }

    fun confirmNotificationAction(token: String?) {
        synchronized(stateLock) {
            if (destroyed || taskState.owner !== this) return
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
                val trace = activeTrace?.takeIf { it.first === job }?.second
                if (storageFailure) {
                    recoveryError("恢复记录读写失败，任务已停止；请核对已执行结果后放弃记录")
                } else if (interrupted) {
                    recoveryError("服务已中断，执行已退出；请核对已执行结果，不会自动重放", blocked = false)
                } else if (_uiState.value.status == RootPilotStatus.STOPPING && clearRunSnapshot()) {
                    updateStateLocked(
                        status = RootPilotStatus.STOPPED,
                        clearPendingAction = true,
                        errorMessage = "用户已停止；已执行的操作不会撤销",
                    )
                }
                trace?.let {
                    val finalStatus = when {
                        storageFailure -> RunHistoryStatus.FAILED
                        interrupted -> RunHistoryStatus.INTERRUPTED
                        job.isCancelled -> RunHistoryStatus.STOPPED
                        it.outcome == TraceStatus.SUCCESS -> RunHistoryStatus.COMPLETED
                        it.outcome == TraceStatus.CANCELLED -> RunHistoryStatus.STOPPED
                        else -> RunHistoryStatus.FAILED
                    }
                    history?.finish(it.runId, finalStatus, it.elapsedMs,
                        when {
                            storageFailure -> snapshotFailureReason
                            job.isCancelled -> TraceReason.CANCELLED
                            else -> it.reason
                        })
                }
                if (trace != null) activeTrace = null
                taskState.owner = null
                latestStartId
            } else {
                null
            }
        }
        startId?.let(::finishHost)
    }

    fun stopAgent(startId: Int) {
        val running = synchronized(stateLock) {
            if (destroyed) return
            if (taskState.owner != null && taskState.owner !== this) {
                finishHost(startId)
                return
            }
            if (_uiState.value.status == RootPilotStatus.STOPPING) return
            if (activeJob == null && uiState.value.status == RootPilotStatus.RECOVERY_REQUIRED) {
                finishHost(startId)
                return
            }
            activeTrace?.takeIf { it.first === activeJob }?.second?.record(
                TraceEvent.STOP_REQUESTED, TraceStatus.REQUESTED, stage = TraceStage.SERVICE,
            )
            val job = activeJob
            updateStateLocked(
                status = if (job != null) RootPilotStatus.STOPPING else RootPilotStatus.STOPPED,
                clearPendingAction = true,
                errorMessage = if (job != null) "正在取消请求并等待执行退出" else "用户已停止",
            )
            rootExecutor.cancel()
            job?.cancel()
            pendingApproval?.reject()
            pendingApproval = null
            job != null
        }
        host?.hideOverlay()
        if (!running) {
            clearRunSnapshot()
            finishHost(startId)
        }
    }

    fun restoreInterruptedRun(startId: Int? = null) = synchronized(stateLock) {
        if (destroyed || taskState.owner != null) {
            if (taskState.owner !== this) startId?.let(::finishHost)
            return@synchronized
        }
        val snapshot = try {
            runStore.read()
        } catch (_: RootPilotRunStoreException) {
            recordSnapshotFailure("read")
            recoveryError("恢复记录无法读取，请核对已执行结果后放弃记录")
            startId?.let(::finishHost)
            return@synchronized
        }
        if (taskState.recoveryBlocked) {
            recoveryError("恢复记录不可用，请核对已执行结果后放弃记录")
        } else if (snapshot != null) {
            _uiState.value = _uiState.value.copy(
                config = snapshot.restoreTask(_uiState.value.config),
                status = RootPilotStatus.RECOVERY_REQUIRED,
                step = snapshot.step,
                errorMessage = buildString {
                    append("上次任务在 ${snapshot.status} 阶段中断，不会自动重放")
                    snapshot.actionSummary?.let { append("：$it") }
                },
                pendingAction = null,
                savedTodos = emptyList(),
                modelReportedResult = false,
            )
            notifyState()
        }
        startId?.let(::finishHost)
    }

    fun discardInterruptedRun(startId: Int) = synchronized(stateLock) {
        if (destroyed || taskState.owner != null) {
            if (taskState.owner !== this) finishHost(startId)
            return@synchronized
        }
        if (clearRunSnapshot()) {
            taskState.recoveryBlocked = false
            updateStateLocked(
                status = RootPilotStatus.IDLE, clearPendingAction = true, errorMessage = null,
            )
        }
        finishHost(startId)
    }

    private fun persistRunSnapshot(
        status: RootPilotStatus,
        step: Int,
        actionSummary: String? = null,
    ) {
        val config = synchronized(stateLock) { uiState.value.config }
        try {
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
        } catch (error: RootPilotRunStoreException) {
            // IME restoration runs while this exception unwinds and may itself fail.
            // Keep the storage barrier even if that cleanup replaces the exception.
            markStorageFailure(error.operation)
            throw error
        }
    }

    private fun clearRunSnapshot(): Boolean = try {
        runStore.clear()
        true
    } catch (_: RootPilotRunStoreException) {
        markStorageFailure("clear")
        false
    }

    private fun markStorageFailure(operation: String) = synchronized(stateLock) {
        recordSnapshotFailure(operation)
        storageFailure = true
        taskState.recoveryBlocked = true
        pendingApproval?.reject()
        pendingApproval = null
        if (activeJob != null) {
            updateStateLocked(RootPilotStatus.STOPPING, clearPendingAction = true,
                errorMessage = "恢复记录读写失败，正在停止；请勿直接重试任务")
        } else {
            recoveryError("恢复记录读写失败，请核对已执行结果后放弃记录")
        }
    }

    private fun recordSnapshotFailure(operation: String) {
        val reason = when (operation) {
            "read" -> TraceReason.SNAPSHOT_READ_FAILED
            "clear" -> TraceReason.SNAPSHOT_CLEAR_FAILED
            else -> TraceReason.SNAPSHOT_WRITE_FAILED
        }
        snapshotFailureReason = reason
        (activeTrace?.second ?: RunTrace(sink = ::appendTraceLine)).record(
            TraceEvent.CONTROL, TraceStatus.FAILED, reason, stage = TraceStage.SERVICE,
        )
    }

    private fun recoveryError(message: String, blocked: Boolean = true) {
        taskState.recoveryBlocked = blocked
        _uiState.value = _uiState.value.copy(
            status = RootPilotStatus.RECOVERY_REQUIRED, pendingAction = null,
            modelStream = com.example.agent.rootpilot.deepseek.ModelStreamSnapshot(), errorMessage = message,
        )
        notifyState()
    }

    private suspend fun handleEvent(event: AgentLoopEvent) {
        synchronized(stateLock) {
            if (taskState.owner !== this) return
            // Cancellation may race with a blocking operation returning. Keep STOPPING
            // until job completion, but retain writes that have actually succeeded.
            if (_uiState.value.status == RootPilotStatus.STOPPING && event !is AgentLoopEvent.TodoSaved) return
            when (event) {
                is AgentLoopEvent.ModelOutput -> {
                    if (_uiState.value.status != RootPilotStatus.REQUESTING_MODEL ||
                        _uiState.value.step != event.step) return
                    // Preview stays in memory: no notification, trace or snapshot write per chunk.
                    _uiState.value = _uiState.value.copy(modelStream = event.snapshot)
                }
                is AgentLoopEvent.TodoSaved -> {
                    _uiState.value = _uiState.value.copy(
                        savedTodos = _uiState.value.savedTodos + SavedTodoResult(event.title, event.dueAt),
                    )
                }
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
                    _uiState.value = _uiState.value.copy(modelReportedResult = event.modelReported)
                    updateState(
                        status = RootPilotStatus.COMPLETED,
                        clearPendingAction = true,
                        errorMessage = event.message,
                    )
                    clearRunSnapshot()
                }

                is AgentLoopEvent.Failed -> {
                    _uiState.value = _uiState.value.copy(modelReportedResult = event.modelReported)
                    updateState(
                        status = RootPilotStatus.FAILED,
                        clearPendingAction = true,
                        errorMessage = event.message,
                    )
                    clearRunSnapshot()
                }

                AgentLoopEvent.Stopped -> {
                    updateState(
                        status = RootPilotStatus.STOPPING,
                        clearPendingAction = true,
                        errorMessage = "正在等待执行退出",
                    )
                }
            }
        }
        // Complete window removal before the loop captures or injects input; an async
        // state collector could leave the panel in the screenshot or intercept a tap.
        host?.renderOverlay(uiState.value)
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
        if (_uiState.value.status == RootPilotStatus.STOPPING && status != RootPilotStatus.STOPPED) return
        _uiState.value = _uiState.value.copy(
            status = status,
            frame = frame ?: _uiState.value.frame,
            step = step ?: _uiState.value.step,
            lastAction = lastAction ?: _uiState.value.lastAction,
            pendingAction = if (clearPendingAction) null else pendingAction ?: _uiState.value.pendingAction,
            errorMessage = errorMessage,
            modelStream = com.example.agent.rootpilot.deepseek.ModelStreamSnapshot(),
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
        traceSink(line)
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(logs = logRepository.list())
            notifyState()
        }
    }

    private companion object { const val MAX_STEPS = 20 }
}

internal fun RootPilotAction.describeForSnapshot(): String = when (this) {
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
