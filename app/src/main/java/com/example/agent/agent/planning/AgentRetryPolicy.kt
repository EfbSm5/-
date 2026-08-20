package com.example.agent.agent.planning

sealed interface AgentRetryDecision {
    data object DoNotRetry : AgentRetryDecision

    data class RetryAfter(
        val delayMillis: Long,
        val nextAttempt: Int,
    ) : AgentRetryDecision
}

class AgentRetryPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialBackoffMillis: Long = DEFAULT_INITIAL_BACKOFF_MILLIS,
    private val maxBackoffMillis: Long = DEFAULT_MAX_BACKOFF_MILLIS,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts 必须至少为 1" }
        require(initialBackoffMillis >= 0) { "initialBackoffMillis 不能为负数" }
        require(maxBackoffMillis >= initialBackoffMillis) {
            "maxBackoffMillis 不能小于 initialBackoffMillis"
        }
    }

    fun decide(
        failure: AgentModelFailure,
        completedAttempts: Int,
    ): AgentRetryDecision {
        require(completedAttempts >= 1) { "completedAttempts 必须至少为 1" }
        if (completedAttempts >= maxAttempts || !failure.isRetryable()) {
            return AgentRetryDecision.DoNotRetry
        }

        val nextAttempt = completedAttempts + 1
        val delayMillis = when (failure) {
            is AgentModelFailure.RateLimited -> failure.retryAfterMillis
                ?: exponentialBackoff(completedAttempts)

            else -> exponentialBackoff(completedAttempts)
        }.coerceAtMost(maxBackoffMillis)
        return AgentRetryDecision.RetryAfter(
            delayMillis = delayMillis,
            nextAttempt = nextAttempt,
        )
    }

    private fun exponentialBackoff(completedAttempts: Int): Long {
        var delay = initialBackoffMillis
        repeat(completedAttempts - 1) {
            delay = when {
                delay == 0L -> 0L
                delay >= maxBackoffMillis / 2 -> maxBackoffMillis
                else -> delay * 2
            }
        }
        return delay
    }

    private fun AgentModelFailure.isRetryable(): Boolean = when (this) {
        is AgentModelFailure.Network,
        is AgentModelFailure.RateLimited,
        is AgentModelFailure.Server,
        -> true

        is AgentModelFailure.Authentication,
        is AgentModelFailure.InvalidRequest,
        is AgentModelFailure.Unknown,
        -> false
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_INITIAL_BACKOFF_MILLIS = 500L
        const val DEFAULT_MAX_BACKOFF_MILLIS = 4_000L
    }
}
