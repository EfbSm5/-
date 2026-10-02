package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.action.ActionParser
import com.example.agent.rootpilot.action.ActionParseResult
import com.example.agent.rootpilot.action.ActionPolicy
import com.example.agent.rootpilot.action.ActionPolicyResult
import com.example.agent.rootpilot.apps.AppCatalog
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.ToolChatResult
import com.example.agent.rootpilot.deepseek.ToolChatTurn
import com.example.agent.rootpilot.information.DeviceInfoTool
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.ScreenSize
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import com.example.agent.rootpilot.screen.ScreenObservation
import java.security.MessageDigest
import java.util.UUID
import java.time.OffsetDateTime
import com.example.agent.agent.model.CreateTodo
import com.example.agent.agent.planning.TodoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import com.example.agent.rootpilot.log.RunTrace
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceReason

data class AgentLoopRequest(
    val config: RootPilotConfig,
    val maxSteps: Int,
    val singleStep: Boolean = false,
)

class ActionApproval internal constructor(
    private val decision: CompletableDeferred<Boolean>,
) {
    val token: String = UUID.randomUUID().toString()

    fun approve() {
        decision.complete(true)
    }

    fun approve(expectedToken: String): Boolean =
        expectedToken == token && decision.complete(true)

    fun reject() {
        decision.complete(false)
    }

    suspend fun await(): Boolean = decision.await()
}

sealed interface AgentLoopEvent {
    data class Capturing(val step: Int) : AgentLoopEvent

    data class ScreenshotCaptured(val step: Int, val frame: ScreenshotFrame) : AgentLoopEvent

    data class RequestingModel(val step: Int) : AgentLoopEvent

    data class QueryingInformation(val step: Int, val tool: DeviceInfoTool) : AgentLoopEvent

    data class ModelOutput(val step: Int, val snapshot: ModelStreamSnapshot) : AgentLoopEvent

    data class AwaitingConfirmation(
        val step: Int,
        val action: RootPilotAction,
        val approval: ActionApproval,
    ) : AgentLoopEvent

    data class Executing(val step: Int, val action: RootPilotAction) : AgentLoopEvent

    data class WaitingScreen(val step: Int) : AgentLoopEvent

    data class TodoSaved(val title: String, val dueAt: String?) : AgentLoopEvent

    data class Completed(val message: String, val modelReported: Boolean = false) : AgentLoopEvent

    data class Failed(val message: String, val modelReported: Boolean = false) : AgentLoopEvent

    data object Stopped : AgentLoopEvent
}

class AgentLoop(
    private val screenshotProvider: ScreenshotProvider,
    private val deepSeekClient: DeepSeekClient,
    private val rootExecutor: RootExecutor,
    private val actionParser: ActionParser = ActionParser(),
    private val actionPolicy: ActionPolicy = ActionPolicy(),
    private val appCatalog: AppCatalog = AppCatalog { emptyList() },
    private val todoRepository: TodoRepository? = null,
) {
    suspend fun captureScreen(): ScreenshotCaptureResult = screenshotProvider.capture()

    suspend fun run(
        request: AgentLoopRequest,
        trace: RunTrace = RunTrace(),
        onEvent: suspend (AgentLoopEvent) -> Unit,
    ) {
        try {
            trace.record(TraceEvent.RUN_START, TraceStatus.STARTED)
            runSteps(request, trace) { event ->
                when (event) {
                    is AgentLoopEvent.Completed -> {
                        trace.outcome = TraceStatus.SUCCESS
                        trace.reason = TraceReason.NONE
                    }
                    else -> Unit
                }
                onEvent(event)
            }
        } catch (error: CancellationException) {
            trace.outcome = TraceStatus.CANCELLED
            trace.reason = TraceReason.CANCELLED
            throw error
        } catch (error: Exception) {
            trace.outcome = TraceStatus.FAILED
            trace.reason = TraceReason.UNEXPECTED_ERROR
            throw error
        } finally {
            if (!currentCoroutineContext().isActive) {
                trace.outcome = TraceStatus.CANCELLED
                trace.reason = TraceReason.CANCELLED
            }
            trace.record(TraceEvent.RUN_END, trace.outcome, trace.reason)
        }
    }

    private suspend fun runSteps(
        request: AgentLoopRequest,
        trace: RunTrace,
        onEvent: suspend (AgentLoopEvent) -> Unit,
    ) {
        if (request.maxSteps !in 1..MAX_STEPS) {
            trace.fail(TraceReason.INVALID_STEP_LIMIT)
            onEvent(AgentLoopEvent.Failed("步骤数必须在 1 到 $MAX_STEPS 之间"))
            return
        }
        if (!request.config.allowScreenUpload) {
            trace.fail(TraceReason.UPLOAD_NOT_ALLOWED)
            onEvent(AgentLoopEvent.Failed("发送截图前请先打开上传确认"))
            return
        }

        val history = mutableListOf<String>()
        // Only successful writes in this run participate; prior runs are independent user requests.
        val savedTodos = mutableSetOf<Pair<String, java.time.Instant?>>()
        var previousSignature: String? = null
        var sameFrameCount = 0
        var previousAction: RootPilotAction? = null
        var sameActionCount = 0
        var informationQueries = 0

        for (step in 0 until request.maxSteps) {
            trace.step = step
            trace.action(null)
            currentCoroutineContext().ensureActive()
            trace.stage = TraceStage.SCREENSHOT
            trace.record(TraceEvent.START, TraceStatus.STARTED)
            onEvent(AgentLoopEvent.Capturing(step))
            val beforeCapture = rootExecutor.observeScreen()
            val captureResult = screenshotProvider.capture()
            val frame = when (captureResult) {
                is ScreenshotCaptureResult.Failure -> {
                    trace.fail(TraceReason.SCREENSHOT_FAILED)
                    onEvent(AgentLoopEvent.Failed(captureResult.message))
                    return
                }

                is ScreenshotCaptureResult.Success -> captureResult.frame
            }
            val observation = rootExecutor.observeScreen()
            if (!beforeCapture.sameWindow(observation) || beforeCapture.keyboardVisible != observation.keyboardVisible) {
                trace.fail(TraceReason.SCREEN_CONTEXT_CHANGED)
                onEvent(AgentLoopEvent.Failed("截图期间窗口状态不可确认或已变化，未发送模型请求，请重新观察"))
                return
            }
            onEvent(AgentLoopEvent.ScreenshotCaptured(step, frame))
            trace.record(TraceEvent.RESULT, TraceStatus.SUCCESS)

            val signature = frame.bytes.sha256()
            if (signature == previousSignature) {
                sameFrameCount++
            } else {
                previousSignature = signature
                sameFrameCount = 0
            }
            if (sameFrameCount >= MAX_SAME_FRAME_REPEATS) {
                trace.fail(TraceReason.UNCHANGED_SCREEN)
                onEvent(AgentLoopEvent.Failed("连续截图没有变化，已停止避免死循环"))
                return
            }

            val availableApps = appCatalog.listApps()
            var action: RootPilotAction? = null
            var parseRetryUsed = false
            var requestHistory: List<String> = history
            val toolHistory = mutableListOf<ToolChatTurn>()
            var stepInformationQueries = 0
            while (action == null) {
                trace.stage = TraceStage.MODEL
                trace.record(TraceEvent.START, TraceStatus.STARTED)
                onEvent(AgentLoopEvent.RequestingModel(step))
                val modelResult = deepSeekClient.requestDecision(
                    DeepSeekVisionRequest(
                        config = request.config,
                        frame = frame,
                        history = requestHistory,
                        remainingSteps = request.maxSteps - step,
                        step = step,
                        availableApps = availableApps,
                        observation = observation,
                        observationStartedAtMillis = beforeCapture.observedAtMillis,
                    ),
                    toolHistory = toolHistory,
                    allowTools = stepInformationQueries < MAX_INFORMATION_QUERIES_PER_STEP &&
                        informationQueries < MAX_INFORMATION_QUERIES_PER_RUN,
                    onUpdate = { onEvent(AgentLoopEvent.ModelOutput(step, it)) },
                )
                val decision = when (modelResult) {
                    is ToolChatResult.Failure -> {
                        trace.fail(TraceReason.MODEL_FAILED)
                        onEvent(AgentLoopEvent.Failed(modelResult.message))
                        return
                    }

                    is ToolChatResult.Success -> modelResult
                }
                trace.record(TraceEvent.RESULT, TraceStatus.SUCCESS)
                if (decision.toolCalls.isNotEmpty()) {
                    trace.stage = TraceStage.INFORMATION
                    if (stepInformationQueries >= MAX_INFORMATION_QUERIES_PER_STEP ||
                        informationQueries >= MAX_INFORMATION_QUERIES_PER_RUN) {
                        trace.fail(TraceReason.INFORMATION_LIMIT)
                        onEvent(AgentLoopEvent.Failed("页面信息查询已达到上限，未执行动作"))
                        return
                    }
                    val call = decision.toolCalls.singleOrNull()
                    val tool = call?.let { DeviceInfoTool.fromWireName(it.name) }
                    val args = call?.let {
                        try { Json.parseToJsonElement(it.arguments) as? JsonObject } catch (_: IllegalArgumentException) { null }
                    }
                    if (call == null || tool == null || args == null || args.isNotEmpty()) {
                        trace.fail(TraceReason.INFORMATION_CALL_INVALID)
                        onEvent(AgentLoopEvent.Failed("只接受已提供的单个只读信息工具及空参数，未执行动作"))
                        return
                    }
                    stepInformationQueries++
                    informationQueries++
                    // The host removes its model-preview overlay before collecting page semantics.
                    onEvent(AgentLoopEvent.QueryingInformation(step, tool))
                    currentCoroutineContext().ensureActive()
                    trace.record(when (tool) {
                        DeviceInfoTool.SCREEN_CONTEXT -> TraceEvent.READ_SCREEN_CONTEXT
                        DeviceInfoTool.ACTIVITY_STACK -> TraceEvent.READ_ACTIVITY_STACK
                        DeviceInfoTool.UI_TREE -> TraceEvent.READ_UI_TREE
                    }, TraceStatus.STARTED)
                    val beforeQuery = rootExecutor.observeScreen()
                    if (!observation.sameWindow(beforeQuery) || observation.keyboardVisible != beforeQuery.keyboardVisible) {
                        trace.fail(TraceReason.SCREEN_CONTEXT_CHANGED)
                        onEvent(AgentLoopEvent.Failed("信息查询前窗口状态已变化或无法确认，未采集页面信息"))
                        return
                    }
                    val result = rootExecutor.queryDeviceInfo(tool, beforeQuery)
                    currentCoroutineContext().ensureActive()
                    val afterQuery = rootExecutor.observeScreen()
                    if (!beforeQuery.sameWindow(afterQuery) || beforeQuery.keyboardVisible != afterQuery.keyboardVisible) {
                        trace.fail(TraceReason.SCREEN_CONTEXT_CHANGED)
                        onEvent(AgentLoopEvent.Failed("信息查询期间窗口状态已变化或无法确认，未发送查询结果"))
                        return
                    }
                    val text = result.toModelJson(tool, UUID.randomUUID().toString())
                    if (text.toByteArray(Charsets.UTF_8).size > MAX_INFORMATION_RESULT_BYTES) {
                        trace.fail(TraceReason.INFORMATION_LIMIT)
                        onEvent(AgentLoopEvent.Failed("页面信息超过返回大小限制，未发送查询结果"))
                        return
                    }
                    trace.record(TraceEvent.RESULT,
                        if (result.unavailable == null) TraceStatus.SUCCESS else TraceStatus.FAILED,
                        if (result.unavailable == null) TraceReason.NONE else TraceReason.INFORMATION_UNAVAILABLE)
                    toolHistory += ToolChatTurn("assistant", decision.content, decision.reasoning, decision.toolCalls)
                    toolHistory += ToolChatTurn("tool", text, toolCallId = call.id)
                    continue
                }
                val rawActionJson = decision.content
                trace.stage = TraceStage.PARSE
                when (val parseResult = actionParser.parse(rawActionJson)) {
                    is ActionParseResult.Success -> action = parseResult.action
                    is ActionParseResult.Failure -> {
                        if (parseRetryUsed) {
                            trace.fail(TraceReason.PARSE_FAILED)
                            onEvent(AgentLoopEvent.Failed(parseResult.message))
                            return
                        }
                        parseRetryUsed = true
                        trace.record(TraceEvent.PARSE_RETRY, TraceStatus.FAILED, TraceReason.PARSE_FAILED)
                        requestHistory = history + "上一响应未通过本地动作协议校验，请只返回合法的单个动作 JSON。"
                    }
                }
            }

            trace.action(action)

            if (action is RootPilotAction.AskUser) {
                val approval = ActionApproval(CompletableDeferred())
                trace.stage = TraceStage.APPROVAL
                trace.record(TraceEvent.WAITING, TraceStatus.WAITING)
                onEvent(AgentLoopEvent.AwaitingConfirmation(step, action, approval))
                val approved = approval.await()
                trace.approval(approved)
                if (!approved) {
                    onEvent(AgentLoopEvent.Stopped)
                    return
                }
                currentCoroutineContext().ensureActive()
                history += "step=$step action=ask_user result=user_confirmed"
                continue
            }
            if (action is RootPilotAction.Finish) {
                if (action.success) {
                    onEvent(AgentLoopEvent.Completed(action.message, modelReported = true))
                } else {
                    trace.fail(TraceReason.MODEL_REPORTED_FAILURE)
                    onEvent(AgentLoopEvent.Failed(action.message, modelReported = true))
                }
                return
            }

            if (action is RootPilotAction.CreateTodo) {
                trace.stage = TraceStage.POLICY
                val key = action.title to action.dueAt?.let { OffsetDateTime.parse(it).toInstant() }
                if (key in savedTodos) {
                    trace.fail(TraceReason.DUPLICATE_TODO)
                    onEvent(AgentLoopEvent.Failed("本次任务已保存相同待办，已阻止重复写入"))
                    return
                }
                val repository = todoRepository
                if (repository == null) {
                    trace.fail(TraceReason.TODO_UNAVAILABLE)
                    onEvent(AgentLoopEvent.Failed("本地待办存储不可用"))
                    return
                }
                val approval = ActionApproval(CompletableDeferred())
                trace.stage = TraceStage.APPROVAL
                trace.record(TraceEvent.WAITING, TraceStatus.WAITING)
                onEvent(AgentLoopEvent.AwaitingConfirmation(step, action, approval))
                val approved = approval.await()
                trace.approval(approved)
                if (!approved) {
                    onEvent(AgentLoopEvent.Stopped)
                    return
                }
                currentCoroutineContext().ensureActive()
                onEvent(AgentLoopEvent.Executing(step, action))
                currentCoroutineContext().ensureActive()
                try {
                    trace.stage = TraceStage.EXECUTION
                    trace.record(TraceEvent.START, TraceStatus.STARTED)
                    repository.addAll(listOf(CreateTodo(action.title, action.dueAt)))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    trace.fail(TraceReason.TODO_SAVE_FAILED)
                    onEvent(AgentLoopEvent.Failed("本地待办保存失败，已停止；请人工核对保存结果后再决定是否重试"))
                    return
                }
                trace.record(TraceEvent.TODO_SAVED, TraceStatus.SUCCESS)
                onEvent(AgentLoopEvent.TodoSaved(action.title, action.dueAt))
                trace.record(TraceEvent.RESULT, TraceStatus.SUCCESS)
                savedTodos += key
                history += "step=$step action=${action.describeForHistory()} result=success"
                // Local persistence is progress without a screen change; retain the UI guard for UI actions.
                sameFrameCount = 0
                previousSignature = null
                if (request.singleStep) {
                    onEvent(AgentLoopEvent.Completed("本地待办已保存"))
                    return
                }
                continue
            }

            trace.stage = TraceStage.POLICY
            if (action == previousAction) {
                sameActionCount++
            } else {
                previousAction = action
                sameActionCount = 0
            }
            if (sameActionCount >= MAX_SAME_ACTION_REPEATS) {
                trace.fail(TraceReason.REPEATED_ACTION)
                onEvent(AgentLoopEvent.Failed("连续重复相同动作，已停止避免死循环"))
                return
            }

            val executableAction = when (val policyResult = actionPolicy.toExecutable(
                    action = action,
                    screenSize = ScreenSize(frame.physicalWidth, frame.physicalHeight),
                    availableApps = availableApps,
                )) {
                is ActionPolicyResult.Rejected -> {
                    trace.fail(TraceReason.POLICY_REJECTED)
                    onEvent(AgentLoopEvent.Failed(policyResult.message))
                    return
                }

                is ActionPolicyResult.Allowed -> policyResult.action
            }

            val approval = if (
                actionPolicy.requiresConfirmation(
                    action = action,
                    manualConfirmation = request.config.manualConfirmation,
                )
            ) {
                ActionApproval(CompletableDeferred())
            } else {
                null
            }
            var rejected = false
            var contextChanged = false
            trace.stage = TraceStage.EXECUTION
            val executionResult = rootExecutor.executeConfirmed(executableAction) { targetPackage ->
                if (approval != null) {
                    trace.stage = TraceStage.APPROVAL
                    trace.record(TraceEvent.WAITING, TraceStatus.WAITING)
                    val preview = if (action is RootPilotAction.Type && targetPackage != null) {
                        action.copy(reason = "输入到 $targetPackage：${action.reason}")
                    } else action
                    onEvent(AgentLoopEvent.AwaitingConfirmation(step, preview, approval))
                    rejected = !approval.await()
                    trace.approval(!rejected)
                }
                if (!rejected) {
                    currentCoroutineContext().ensureActive()
                    trace.stage = TraceStage.EXECUTION
                    trace.record(TraceEvent.START, TraceStatus.STARTED, action = action, executable = executableAction)
                    onEvent(AgentLoopEvent.Executing(step, action))
                    currentCoroutineContext().ensureActive()
                    if (action !is RootPilotAction.Wait) {
                        val current = rootExecutor.observeScreen()
                        // IME switching intentionally changes keyboard visibility; editor identity is
                        // additionally checked by InputConnectionBridge immediately before commit.
                        contextChanged = !observation.sameWindow(current) ||
                            if (action is RootPilotAction.Type) {
                                targetPackage != observation.focusedPackage
                            } else {
                                observation.keyboardVisible == null ||
                                    observation.keyboardVisible != current.keyboardVisible
                            }
                        currentCoroutineContext().ensureActive()
                    }
                }
                !rejected && !contextChanged
            }
            if (rejected) {
                onEvent(AgentLoopEvent.Stopped)
                return
            }
            if (contextChanged) {
                trace.fail(TraceReason.SCREEN_CONTEXT_CHANGED)
                onEvent(AgentLoopEvent.Failed("当前窗口或键盘状态已变化／无法确认，未执行动作，请重新观察；若使用了文本输入，请核对原输入法已恢复"))
                return
            }
            when (executionResult) {
                is RootExecutionResult.Failure -> {
                    trace.fail(TraceReason.EXECUTION_FAILED)
                    onEvent(AgentLoopEvent.Failed(executionResult.message))
                    return
                }

                is RootExecutionResult.Success -> {
                    trace.record(TraceEvent.RESULT, TraceStatus.SUCCESS)
                    history += "step=$step action=${action.describeForHistory()} result=success"
                }
            }
            trace.stage = TraceStage.SETTLE
            onEvent(AgentLoopEvent.WaitingScreen(step))
            delay(SCREEN_SETTLE_MILLIS)
            if (request.singleStep) {
                onEvent(AgentLoopEvent.Completed("单步执行完成"))
                return
            }
        }
        trace.fail(TraceReason.STEP_LIMIT)
        onEvent(AgentLoopEvent.Failed("达到最大步骤数 ${request.maxSteps}，已停止"))
    }

    private fun ScreenObservation.sameWindow(other: ScreenObservation): Boolean =
        foregroundPackage != null && foregroundActivity != null && focusedPackage != null && focusedWindowId != null &&
            foregroundPackage == other.foregroundPackage && foregroundActivity == other.foregroundActivity &&
            focusedPackage == other.focusedPackage && focusedWindowId == other.focusedWindowId

    private fun RootPilotAction.describeForHistory(): String = when (this) {
        is RootPilotAction.CreateTodo -> buildJsonObject {
            put("action", "create_todo")
            put("title", title)
            put("due_at", dueAt)
        }.toString()
        is RootPilotAction.Tap -> "tap($x,$y)"
        is RootPilotAction.Swipe -> "swipe($x1,$y1,$x2,$y2,$durationMillis)"
        is RootPilotAction.OpenApp -> "open_app($packageName)"
        is RootPilotAction.Type -> "type(length=${text.length})"
        is RootPilotAction.Key -> "key($key)"
        is RootPilotAction.Wait -> "wait($durationMillis)"
        is RootPilotAction.AskUser -> "ask_user"
        is RootPilotAction.Finish -> "finish($success)"
    }

    private fun ByteArray.sha256(): String = MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_STEPS = 20
        const val MAX_SAME_FRAME_REPEATS = 2
        const val MAX_SAME_ACTION_REPEATS = 2
        const val SCREEN_SETTLE_MILLIS = 500L
        const val MAX_INFORMATION_QUERIES_PER_STEP = 3
        const val MAX_INFORMATION_QUERIES_PER_RUN = 12
        const val MAX_INFORMATION_RESULT_BYTES = 256 * 1024
    }
}
