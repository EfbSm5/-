package com.example.agent.agent.planning

interface AgentModelClient {
    suspend fun generatePlanJson(userRequest: String): AgentModelResult
}

sealed interface AgentModelFailure {
    val reason: String

    data class Network(override val reason: String) : AgentModelFailure

    data class RateLimited(
        override val reason: String,
        val retryAfterMillis: Long? = null,
    ) : AgentModelFailure {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0) {
                "retryAfterMillis 不能为负数"
            }
        }
    }

    data class Server(override val reason: String) : AgentModelFailure

    data class Authentication(override val reason: String) : AgentModelFailure

    data class InvalidRequest(override val reason: String) : AgentModelFailure

    data class Unknown(override val reason: String) : AgentModelFailure
}

sealed interface AgentModelResult {
    data class Success(val rawJson: String) : AgentModelResult

    data class Failure(val error: AgentModelFailure) : AgentModelResult
}
