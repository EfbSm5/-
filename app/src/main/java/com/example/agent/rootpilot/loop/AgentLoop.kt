package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.action.ActionParser
import com.example.agent.rootpilot.action.ActionParseResult
import com.example.agent.rootpilot.action.ActionPolicy
import com.example.agent.rootpilot.action.ActionPolicyResult
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.ScreenSize
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class AgentLoopRequest(
    val config: RootPilotConfig,
    val maxSteps: Int,
    val singleStep: Boolean = false,
)

class ActionApproval internal constructor(
    private val decision: CompletableDeferred<Boolean>,
) {
    fun approve() {
        decision.complete(true)
    }

    fun reject() {
        decision.complete(false)
    }

    suspend fun await(): Boolean = decision.await()
}

sealed interface AgentLoopEvent {
    data class Capturing(val step: Int) : AgentLoopEvent

    data class ScreenshotCaptured(val step: Int, val frame: ScreenshotFrame) : AgentLoopEvent

    data class RequestingModel(val step: Int) : AgentLoopEvent

    data class AwaitingConfirmation(
        val step: Int,
        val action: RootPilotAction,
        val approval: ActionApproval,
    ) : AgentLoopEvent

    data class Executing(val step: Int, val action: RootPilotAction) : AgentLoopEvent

    data class WaitingScreen(val step: Int) : AgentLoopEvent

    data class Completed(val message: String) : AgentLoopEvent

    data class Failed(val message: String) : AgentLoopEvent

    data object Stopped : AgentLoopEvent
}

class AgentLoop(
    private val screenshotProvider: ScreenshotProvider,
    private val deepSeekClient: DeepSeekClient,
    private val rootExecutor: RootExecutor,
    private val actionParser: ActionParser = ActionParser(),
    private val actionPolicy: ActionPolicy = ActionPolicy(),
) {
    suspend fun captureScreen(): ScreenshotCaptureResult = screenshotProvider.capture()

    suspend fun run(
        request: AgentLoopRequest,
        onEvent: suspend (AgentLoopEvent) -> Unit,
    ) {
        if (request.maxSteps !in 1..MAX_STEPS) {
            onEvent(AgentLoopEvent.Failed("步骤数必须在 1 到 $MAX_STEPS 之间"))
            return
        }
        if (!request.config.allowScreenUpload) {
            onEvent(AgentLoopEvent.Failed("发送截图前请先打开上传确认"))
            return
        }

        val history = mutableListOf<String>()
        var previousSignature: String? = null
        var sameFrameCount = 0
        var previousAction: RootPilotAction? = null
        var sameActionCount = 0

        for (step in 0 until request.maxSteps) {
            currentCoroutineContext().ensureActive()
            onEvent(AgentLoopEvent.Capturing(step))
            val captureResult = screenshotProvider.capture()
            val frame = when (captureResult) {
                is ScreenshotCaptureResult.Failure -> {
                    onEvent(AgentLoopEvent.Failed(captureResult.message))
                    return
                }

                is ScreenshotCaptureResult.Success -> captureResult.frame
            }
            onEvent(AgentLoopEvent.ScreenshotCaptured(step, frame))

            val signature = frame.bytes.sha256()
            if (signature == previousSignature) {
                sameFrameCount++
            } else {
                previousSignature = signature
                sameFrameCount = 0
            }
            if (sameFrameCount >= MAX_SAME_FRAME_REPEATS) {
                onEvent(AgentLoopEvent.Failed("连续截图没有变化，已停止避免死循环"))
                return
            }

            var action: RootPilotAction? = null
            var parseRetryUsed = false
            var requestHistory: List<String> = history
            while (action == null) {
                onEvent(AgentLoopEvent.RequestingModel(step))
                val modelResult = deepSeekClient.requestAction(
                    DeepSeekVisionRequest(
                        config = request.config,
                        frame = frame,
                        history = requestHistory,
                        remainingSteps = request.maxSteps - step,
                    ),
                )
                val rawActionJson = when (modelResult) {
                    is DeepSeekActionResult.Failure -> {
                        onEvent(AgentLoopEvent.Failed(modelResult.message))
                        return
                    }

                    is DeepSeekActionResult.Success -> modelResult.rawActionJson
                }
                when (val parseResult = actionParser.parse(rawActionJson)) {
                    is ActionParseResult.Success -> action = parseResult.action
                    is ActionParseResult.Failure -> {
                        if (parseRetryUsed) {
                            onEvent(AgentLoopEvent.Failed(parseResult.message))
                            return
                        }
                        parseRetryUsed = true
                        requestHistory = history + "上一响应未通过本地动作协议校验，请只返回合法的单个动作 JSON。"
                    }
                }
            }

            if (action is RootPilotAction.AskUser) {
                val approval = ActionApproval(CompletableDeferred())
                onEvent(AgentLoopEvent.AwaitingConfirmation(step, action, approval))
                if (!approval.await()) {
                    onEvent(AgentLoopEvent.Stopped)
                    return
                }
                currentCoroutineContext().ensureActive()
                history += "step=$step action=ask_user result=user_confirmed"
                continue
            }
            if (action is RootPilotAction.Finish) {
                if (action.success) {
                    onEvent(AgentLoopEvent.Completed(action.message))
                } else {
                    onEvent(AgentLoopEvent.Failed(action.message))
                }
                return
            }

            if (action == previousAction) {
                sameActionCount++
            } else {
                previousAction = action
                sameActionCount = 0
            }
            if (sameActionCount >= MAX_SAME_ACTION_REPEATS) {
                onEvent(AgentLoopEvent.Failed("连续重复相同动作，已停止避免死循环"))
                return
            }

            val executableAction = when (val policyResult = actionPolicy.toExecutable(
                    action = action,
                    screenSize = ScreenSize(frame.physicalWidth, frame.physicalHeight),
                )) {
                is ActionPolicyResult.Rejected -> {
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
            if (approval != null) {
                onEvent(AgentLoopEvent.AwaitingConfirmation(step, action, approval))
                if (!approval.await()) {
                    onEvent(AgentLoopEvent.Stopped)
                    return
                }
            }

            currentCoroutineContext().ensureActive()
            onEvent(AgentLoopEvent.Executing(step, action))
            currentCoroutineContext().ensureActive()
            when (val executionResult = rootExecutor.execute(executableAction)) {
                is RootExecutionResult.Failure -> {
                    onEvent(AgentLoopEvent.Failed(executionResult.message))
                    return
                }

                is RootExecutionResult.Success -> {
                    history += "step=$step action=${action.describeForHistory()} result=success"
                }
            }
            onEvent(AgentLoopEvent.WaitingScreen(step))
            delay(SCREEN_SETTLE_MILLIS)
            if (request.singleStep) {
                onEvent(AgentLoopEvent.Completed("单步执行完成"))
                return
            }
        }
        onEvent(AgentLoopEvent.Failed("达到最大步骤数 ${request.maxSteps}，已停止"))
    }

    private fun RootPilotAction.describeForHistory(): String = when (this) {
        is RootPilotAction.Tap -> "tap($x,$y)"
        is RootPilotAction.Swipe -> "swipe($x1,$y1,$x2,$y2,$durationMillis)"
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
    }
}
