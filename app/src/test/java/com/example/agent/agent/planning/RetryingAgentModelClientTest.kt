package com.example.agent.agent.planning

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryingAgentModelClientTest {
    @Test
    fun transientFailureThenSuccess_retriesAndReturnsSuccess() {
        val delegate = SequencedAgentModelClient(
            AgentModelResult.Failure(AgentModelFailure.Network("超时")),
            AgentModelResult.Success("{\"goal\":\"测试\",\"actions\":[{\"type\":\"ask_user\",\"question\":\"确认？\"}] }"),
        )
        val delays = mutableListOf<Long>()
        val client = RetryingAgentModelClient(delegate, wait = { delays += it })

        val result = runBlocking { client.generatePlanJson("测试") }

        assertEquals(AgentModelResult.Success("{\"goal\":\"测试\",\"actions\":[{\"type\":\"ask_user\",\"question\":\"确认？\"}] }"), result)
        assertEquals(2, delegate.callCount)
        assertEquals(listOf(500L), delays)
    }

    @Test
    fun permanentFailure_returnsWithoutRetry() {
        val failure = AgentModelResult.Failure(AgentModelFailure.Authentication("凭证无效"))
        val delegate = SequencedAgentModelClient(failure)
        val delays = mutableListOf<Long>()
        val client = RetryingAgentModelClient(delegate, wait = { delays += it })

        val result = runBlocking { client.generatePlanJson("测试") }

        assertEquals(failure, result)
        assertEquals(1, delegate.callCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun repeatedTransientFailure_returnsLastFailureAfterMaxAttempts() {
        val failure = AgentModelResult.Failure(AgentModelFailure.Server("服务不可用"))
        val delegate = SequencedAgentModelClient(failure, failure, failure)
        val delays = mutableListOf<Long>()
        val client = RetryingAgentModelClient(delegate, wait = { delays += it })

        val result = runBlocking { client.generatePlanJson("测试") }

        assertEquals(failure, result)
        assertEquals(3, delegate.callCount)
        assertEquals(listOf(500L, 1_000L), delays)
    }

    @Test
    fun cancellation_isPropagatedWithoutRetry() {
        var callCount = 0
        val delays = mutableListOf<Long>()
        val client = RetryingAgentModelClient(
            delegate = object : AgentModelClient {
                override suspend fun generatePlanJson(userRequest: String): AgentModelResult {
                    callCount += 1
                    throw CancellationException("调用已取消")
                }
            },
            wait = { delays += it },
        )

        try {
            runBlocking { client.generatePlanJson("测试") }
            fail("应当向上传播 CancellationException")
        } catch (error: CancellationException) {
            assertEquals("调用已取消", error.message)
        }
        assertEquals(1, callCount)
        assertTrue(delays.isEmpty())
    }

    private class SequencedAgentModelClient(
        private vararg val results: AgentModelResult,
    ) : AgentModelClient {
        var callCount: Int = 0
            private set

        override suspend fun generatePlanJson(userRequest: String): AgentModelResult {
            val index = callCount++
            return results.getOrElse(index) { results.last() }
        }
    }
}
