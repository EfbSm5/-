package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.serialization.AgentPlanDecoder
import com.example.agent.agent.serialization.PlanDecodeFailureKind
import com.example.agent.agent.serialization.PlanDecodeResult

sealed interface PlanBuildResult {
    data class Success(val plan: AgentPlan) : PlanBuildResult

    data class ModelFailure(val error: AgentModelFailure) : PlanBuildResult

    data class DecodeFailure(
        val reason: String,
        val kind: PlanDecodeFailureKind,
    ) : PlanBuildResult
}

class AgentPlanner(
    private val modelClient: AgentModelClient,
    private val decoder: AgentPlanDecoder = AgentPlanDecoder(),
) : AutoCloseable {
    suspend fun buildPlan(userRequest: String): PlanBuildResult {
        var request = userRequest
        repeat(MAX_PLAN_DECODE_ATTEMPTS) { attempt ->
            when (val modelResult = modelClient.generatePlanJson(request)) {
                is AgentModelResult.Failure -> return PlanBuildResult.ModelFailure(modelResult.error)
                is AgentModelResult.Success -> when (val decodeResult = decoder.decode(modelResult.rawJson)) {
                    is PlanDecodeResult.Success -> return PlanBuildResult.Success(decodeResult.plan)
                    is PlanDecodeResult.Failure -> {
                        if (decodeResult.kind == PlanDecodeFailureKind.FORMAT &&
                            attempt + 1 < MAX_PLAN_DECODE_ATTEMPTS
                        ) {
                            request = userRequest + FORMAT_RETRY_HINT
                        } else {
                            return PlanBuildResult.DecodeFailure(
                                reason = decodeResult.reason,
                                kind = decodeResult.kind,
                            )
                        }
                    }
                }
            }
        }
        error("计划解析重试状态不可能到达")
    }

    override fun close() {
        (modelClient as? AutoCloseable)?.close()
    }

    private companion object {
        const val MAX_PLAN_DECODE_ATTEMPTS = 2
        const val FORMAT_RETRY_HINT = """

[协议修复提示]
上一响应未通过 JSON 计划协议校验。请只返回符合 JSON Schema 的 JSON 对象，不要输出 Markdown、解释或额外字段。
"""
    }
}
