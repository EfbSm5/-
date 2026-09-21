package com.example.agent.rootpilot.log

import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotAction
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun interface TraceClock {
    fun elapsedMillis(): Long
}

enum class TraceStage { RUN, SCREENSHOT, MODEL, PARSE, POLICY, APPROVAL, EXECUTION, SETTLE, SERVICE }
enum class TraceEvent { RUN_START, RUN_END, START, RESULT, PARSE_RETRY, WAITING, CONFIRMED, REJECTED, TODO_SAVED, STOP_REQUESTED, CONTROL }
enum class TraceStatus { STARTED, WAITING, SUCCESS, FAILED, CANCELLED, REQUESTED }
enum class TraceReason {
    NONE, INVALID_STEP_LIMIT, UPLOAD_NOT_ALLOWED, SCREENSHOT_FAILED, UNCHANGED_SCREEN,
    MODEL_FAILED, PARSE_FAILED, MODEL_REPORTED_FAILURE, DUPLICATE_TODO, TODO_UNAVAILABLE,
    TODO_SAVE_FAILED, REPEATED_ACTION, POLICY_REJECTED, EXECUTION_FAILED, STEP_LIMIT,
    USER_REJECTED, CANCELLED, UNEXPECTED_ERROR, ROOT_CHECK_STARTED, ROOT_CHECK_OK,
    ROOT_CHECK_FAILED, CAPTURE_STARTED, CAPTURE_OK, CAPTURE_FAILED, SERVICE_ERROR,
    SNAPSHOT_READ_FAILED, SNAPSHOT_WRITE_FAILED, SNAPSHOT_CLEAR_FAILED,
}

/** A single invocation owns this trace. Only allowlisted fields can reach either sink. */
class RunTrace(
    private val clock: TraceClock = TraceClock { System.nanoTime() / 1_000_000 },
    private val sink: (String) -> Unit = {},
) {
    val runId: String = UUID.randomUUID().toString()
    private val startedAt = clock.elapsedMillis()
    @Volatile var step: Int = -1
    @Volatile var stage: TraceStage = TraceStage.RUN
    @Volatile private var actionType = "none"
    var outcome: TraceStatus = TraceStatus.FAILED
    var reason: TraceReason = TraceReason.UNEXPECTED_ERROR

    fun action(action: RootPilotAction?) {
        actionType = when (action) {
            null -> "none"
            is RootPilotAction.Tap -> "tap"
            is RootPilotAction.Swipe -> "swipe"
            is RootPilotAction.Type -> "type"
            is RootPilotAction.Key -> "key"
            is RootPilotAction.Wait -> "wait"
            is RootPilotAction.OpenApp -> "open_app"
            is RootPilotAction.CreateTodo -> "create_todo"
            is RootPilotAction.AskUser -> "ask_user"
            is RootPilotAction.Finish -> "finish"
        }
    }

    fun fail(code: TraceReason) {
        outcome = TraceStatus.FAILED
        reason = code
        record(TraceEvent.RESULT, TraceStatus.FAILED, code)
    }

    fun approval(approved: Boolean) {
        record(if (approved) TraceEvent.CONFIRMED else TraceEvent.REJECTED,
            if (approved) TraceStatus.SUCCESS else TraceStatus.CANCELLED,
            if (approved) TraceReason.NONE else TraceReason.USER_REJECTED)
        if (!approved) {
            outcome = TraceStatus.CANCELLED
            reason = TraceReason.USER_REJECTED
        }
    }

    fun record(
        event: TraceEvent,
        status: TraceStatus,
        reasonCode: TraceReason = TraceReason.NONE,
        action: RootPilotAction? = null,
        executable: ExecutableRootAction? = null,
        stage: TraceStage = this.stage,
    ) {
        val line = buildJsonObject {
            put("runId", runId)
            put("step", step)
            put("event", event.name.lowercase())
            put("status", status.name.lowercase())
            put("stage", stage.name.lowercase())
            put("elapsedMs", (clock.elapsedMillis() - startedAt).coerceAtLeast(0))
            put("actionType", actionType)
            put("result", status.name.lowercase())
            put("reasonCode", reasonCode.name.lowercase())
            if (action is RootPilotAction.Tap && executable is ExecutableRootAction.Tap) {
                put("normalizedX", action.x)
                put("normalizedY", action.y)
                put("physicalX", executable.x)
                put("physicalY", executable.y)
            }
        }.toString()
        // Diagnostic failures must not turn a completed side effect into a retryable action failure.
        try { sink(line) } catch (_: Exception) { }
    }

    companion object {
        const val TAG = "RootPilotTrace"
    }
}
