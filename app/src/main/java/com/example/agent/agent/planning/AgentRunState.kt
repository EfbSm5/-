package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser

class ExecutionConfirmation private constructor(
    internal val plan: AgentPlan,
    internal val runId: String,
    internal val ownerToken: String,
    internal val isRecovery: Boolean,
) {

    companion object {
        internal fun issue(plan: AgentPlan): ExecutionConfirmation = ExecutionConfirmation(
            plan = plan,
            runId = newExecutionRunId(),
            ownerToken = newExecutionRunId(),
            isRecovery = false,
        )

        internal fun recover(execution: RecoverableExecution): ExecutionConfirmation {
            val plan = requireNotNull(execution.plan) { "缺少可恢复的计划快照" }
            return ExecutionConfirmation(
                plan = plan,
                runId = execution.record.runId,
                ownerToken = newExecutionRunId(),
                isRecovery = true,
            )
        }
    }
}

sealed interface PlanReadiness {
    data class NeedsClarification(val question: String) : PlanReadiness

    data object ReadyToExecute : PlanReadiness
}

fun AgentPlan.assessReadiness(): PlanReadiness {
    val firstQuestion = actions
        .filterIsInstance<AskUser>()
        .firstOrNull()
        ?.question

    return if (firstQuestion == null) {
        PlanReadiness.ReadyToExecute
    } else {
        PlanReadiness.NeedsClarification(firstQuestion)
    }
}

sealed interface AgentRunState {
    data object Idle : AgentRunState

    data object RecoveryScanning : AgentRunState

    data class RecoveryRequired(
        val executions: List<RecoverableExecution>,
        val message: String? = null,
        val busyRunId: String? = null,
    ) : AgentRunState

    data class Planning(val request: String) : AgentRunState

    data class NeedsClarification(
        val request: String,
        val plan: AgentPlan,
        val question: String,
    ) : AgentRunState

    data class AwaitingConfirmation(
        val plan: AgentPlan,
        internal val confirmation: ExecutionConfirmation,
    ) : AgentRunState

    data class Executing(
        val plan: AgentPlan,
        val actionIndex: Int,
    ) : AgentRunState

    data class Completed(
        val plan: AgentPlan,
        val report: ToolExecutionReport,
    ) : AgentRunState

    data class Failure(
        val message: String,
        val canRetry: Boolean,
        val report: ToolExecutionReport = ToolExecutionReport(),
    ) : AgentRunState
}
