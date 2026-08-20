package com.example.agent.agent.planning

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class DemoAgentModelClient : AgentModelClient {
    override suspend fun generatePlanJson(userRequest: String): AgentModelResult {
        val normalizedRequest = userRequest.trim()
        if (normalizedRequest.isEmpty()) {
            return AgentModelResult.Failure(
                AgentModelFailure.InvalidRequest("用户请求不能为空"),
            )
        }

        return AgentModelResult.Success(
            buildJsonObject {
                put("goal", normalizedRequest)
                putJsonArray("actions") {
                    add(
                        buildJsonObject {
                            put("type", "ask_user")
                            put("question", "请确认这个计划：$normalizedRequest")
                        },
                    )
                }
            }.toString(),
        )
    }
}
