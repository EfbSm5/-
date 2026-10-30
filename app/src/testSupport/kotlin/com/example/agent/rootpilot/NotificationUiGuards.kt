package com.example.agent.rootpilot

/** Test-only assertions: preserve the caller's failure type and never include candidate contents. */
internal class NotificationUiGuards<R>(private val fail: (R) -> Nothing) {
    fun require(value: Boolean, reason: R) {
        if (!value) fail(reason)
    }

    fun <T> single(candidates: List<T>, missing: R, ambiguous: R): T = when (candidates.size) {
        0 -> fail(missing)
        1 -> candidates[0]
        else -> fail(ambiguous)
    }
}
