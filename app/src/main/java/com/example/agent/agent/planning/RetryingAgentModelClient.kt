package com.example.agent.agent.planning

import kotlinx.coroutines.delay

class RetryingAgentModelClient(
    private val delegate: AgentModelClient,
    private val retryPolicy: AgentRetryPolicy = AgentRetryPolicy(),
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : AgentModelClient, AutoCloseable {
    override suspend fun generatePlanJson(userRequest: String): AgentModelResult {
        var completedAttempts = 0

        while (true) {
            completedAttempts += 1
            when (val result = delegate.generatePlanJson(userRequest)) {
                is AgentModelResult.Success -> return result
                is AgentModelResult.Failure -> when (
                    val decision = retryPolicy.decide(result.error, completedAttempts)
                ) {
                    AgentRetryDecision.DoNotRetry -> return result
                    is AgentRetryDecision.RetryAfter -> wait(decision.delayMillis)
                }
            }
        }
    }

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }
}
