package com.example.agent.agent.planning

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRetryPolicyTest {
    private val policy = AgentRetryPolicy()

    @Test
    fun networkFailure_beforeMaxAttempts_retriesWithExponentialBackoff() {
        assertEquals(
            AgentRetryDecision.RetryAfter(delayMillis = 500, nextAttempt = 2),
            policy.decide(AgentModelFailure.Network("超时"), completedAttempts = 1),
        )
        assertEquals(
            AgentRetryDecision.RetryAfter(delayMillis = 1_000, nextAttempt = 3),
            policy.decide(AgentModelFailure.Server("暂时不可用"), completedAttempts = 2),
        )
    }

    @Test
    fun rateLimitedFailure_usesServerRetryAfter() {
        assertEquals(
            AgentRetryDecision.RetryAfter(delayMillis = 3_000, nextAttempt = 2),
            policy.decide(
                AgentModelFailure.RateLimited("请求过多", retryAfterMillis = 3_000),
                completedAttempts = 1,
            ),
        )
        assertEquals(
            AgentRetryDecision.RetryAfter(delayMillis = 4_000, nextAttempt = 2),
            policy.decide(
                AgentModelFailure.RateLimited("请求过多", retryAfterMillis = Long.MAX_VALUE),
                completedAttempts = 1,
            ),
        )
    }

    @Test
    fun permanentFailure_doesNotRetry() {
        assertEquals(
            AgentRetryDecision.DoNotRetry,
            policy.decide(AgentModelFailure.Authentication("凭证无效"), completedAttempts = 1),
        )
        assertEquals(
            AgentRetryDecision.DoNotRetry,
            policy.decide(AgentModelFailure.InvalidRequest("请求不合法"), completedAttempts = 1),
        )
    }

    @Test
    fun maxAttemptsReached_doesNotRetry() {
        assertEquals(
            AgentRetryDecision.DoNotRetry,
            policy.decide(AgentModelFailure.Network("超时"), completedAttempts = 3),
        )
    }
}
