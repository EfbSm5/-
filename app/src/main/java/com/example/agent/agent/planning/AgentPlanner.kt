package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.serialization.AgentPlanDecoder
import com.example.agent.agent.serialization.PlanDecodeResult

sealed interface PlanBuildResult {
    data class Success(val plan: AgentPlan) : PlanBuildResult

    data class ModelFailure(val error: AgentModelFailure) : PlanBuildResult

    data class DecodeFailure(val reason: String) : PlanBuildResult
}

class AgentPlanner(
    private val modelClient: AgentModelClient,
    private val decoder: AgentPlanDecoder = AgentPlanDecoder(),
) : AutoCloseable {
    suspend fun buildPlan(userRequest: String): PlanBuildResult = when (
        val modelResult = modelClient.generatePlanJson(userRequest)
    ) {
        is AgentModelResult.Failure -> PlanBuildResult.ModelFailure(modelResult.error)
        is AgentModelResult.Success -> when (val decodeResult = decoder.decode(modelResult.rawJson)) {
            is PlanDecodeResult.Success -> PlanBuildResult.Success(decodeResult.plan)
            is PlanDecodeResult.Failure -> PlanBuildResult.DecodeFailure(decodeResult.reason)
        }
    }

    override fun close() {
        (modelClient as? AutoCloseable)?.close()
    }
}
