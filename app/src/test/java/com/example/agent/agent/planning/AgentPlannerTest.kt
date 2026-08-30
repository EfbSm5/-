package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.serialization.PlanDecodeFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentPlannerTest {
    @Test
    fun validModelJson_returnsTrustedPlan() {
        val planner = AgentPlanner(
            modelClient = FakeAgentModelClient(
                AgentModelResult.Success(
                    """{"goal":"确认提醒时间","actions":[{"type":"ask_user","question":"你希望几点提醒？"}]}""",
                ),
            ),
        )

        assertEquals(
            PlanBuildResult.Success(
                AgentPlan(
                    goal = "确认提醒时间",
                    actions = listOf(AskUser("你希望几点提醒？")),
                ),
            ),
            runBlocking { planner.buildPlan("帮我设置提醒") },
        )
    }

    @Test
    fun modelFailure_mapsToModelFailure() {
        val planner = AgentPlanner(
            modelClient = FakeAgentModelClient(
                AgentModelResult.Failure(AgentModelFailure.Network("网络不可用")),
            ),
        )

        assertEquals(
            PlanBuildResult.ModelFailure(AgentModelFailure.Network("网络不可用")),
            runBlocking { planner.buildPlan("帮我设置提醒") },
        )
    }

    @Test
    fun malformedModelJson_returnsDecodeFailure() {
        val planner = AgentPlanner(
            modelClient = FakeAgentModelClient(AgentModelResult.Success("不是 JSON")),
        )

        val result = runBlocking { planner.buildPlan("帮我设置提醒") }

        assertDecodeFailure(result, "JSON 格式不符合计划协议")
    }

    @Test
    fun semanticallyInvalidModelJson_returnsDecodeFailure() {
        val client = SequencedFakeAgentModelClient(
            AgentModelResult.Success(
                """{"goal":"删除所有内容","actions":[{"type":"delete_everything"}]}""",
            ),
            AgentModelResult.Success(
                """{"goal":"不应请求第二次","actions":[{"type":"ask_user","question":"不应出现"}]}""",
            ),
        )
        val planner = AgentPlanner(
            modelClient = client,
        )

        val result = runBlocking { planner.buildPlan("帮我删除所有内容") }

        assertDecodeFailure(result, "type 不支持")
        assertEquals(1, client.callCount)
        assertEquals(PlanDecodeFailureKind.SEMANTIC, (result as PlanBuildResult.DecodeFailure).kind)
    }

    @Test
    fun formatFailure_retriesOnceAndReturnsTrustedPlan() {
        val client = SequencedFakeAgentModelClient(
            AgentModelResult.Success("不是 JSON"),
            AgentModelResult.Success(
                """{"goal":"确认提醒时间","actions":[{"type":"ask_user","question":"你希望几点提醒？"}]}""",
            ),
        )
        val planner = AgentPlanner(modelClient = client)

        val result = runBlocking { planner.buildPlan("帮我设置提醒") }

        assertEquals(
            PlanBuildResult.Success(
                AgentPlan(
                    goal = "确认提醒时间",
                    actions = listOf(AskUser("你希望几点提醒？")),
                ),
            ),
            result,
        )
        assertEquals(2, client.callCount)
        assertTrue(client.requests[1].contains("协议修复提示"))
    }

    @Test
    fun formatFailure_retriesOnlyOnce() {
        val client = SequencedFakeAgentModelClient(
            AgentModelResult.Success("不是 JSON"),
            AgentModelResult.Success("仍然不是 JSON"),
        )
        val planner = AgentPlanner(modelClient = client)

        val result = runBlocking { planner.buildPlan("测试") }

        assertDecodeFailure(result, "JSON 格式不符合计划协议")
        assertEquals(2, client.callCount)
    }

    @Test
    fun secondResponse_isStillCheckedByLocalDecoder() {
        val client = SequencedFakeAgentModelClient(
            AgentModelResult.Success("不是 JSON"),
            AgentModelResult.Success(
                """{"goal":"不安全","actions":[{"type":"delete_everything"}]}""",
            ),
        )
        val planner = AgentPlanner(modelClient = client)

        val result = runBlocking { planner.buildPlan("测试") }

        assertDecodeFailure(result, "type 不支持")
        assertEquals(PlanDecodeFailureKind.SEMANTIC, (result as PlanBuildResult.DecodeFailure).kind)
        assertEquals(2, client.callCount)
    }

    @Test
    fun networkFailure_isReturnedWithoutPlannerRetry() {
        val client = SequencedFakeAgentModelClient(
            AgentModelResult.Failure(AgentModelFailure.Network("网络不可用")),
            AgentModelResult.Success(
                """{"goal":"不应请求第二次","actions":[{"type":"ask_user","question":"不应出现"}]}""",
            ),
        )
        val planner = AgentPlanner(modelClient = client)

        val result = runBlocking { planner.buildPlan("测试") }

        assertEquals(
            PlanBuildResult.ModelFailure(AgentModelFailure.Network("网络不可用")),
            result,
        )
        assertEquals(1, client.callCount)
    }

    @Test
    fun modelCancellation_isPropagatedWithoutRetry() {
        var callCount = 0
        val planner = AgentPlanner(
            modelClient = object : AgentModelClient {
                override suspend fun generatePlanJson(userRequest: String): AgentModelResult {
                    callCount++
                    throw CancellationException("调用已取消")
                }
            },
        )

        try {
            runBlocking { planner.buildPlan("测试") }
            fail("应当向上传播 CancellationException")
        } catch (error: CancellationException) {
            assertEquals("调用已取消", error.message)
        }
        assertEquals(1, callCount)
    }

    private fun assertDecodeFailure(result: PlanBuildResult, expectedMessage: String) {
        assertTrue(result is PlanBuildResult.DecodeFailure)
        assertTrue((result as PlanBuildResult.DecodeFailure).reason.contains(expectedMessage))
    }

    private class FakeAgentModelClient(
        private val result: AgentModelResult,
    ) : AgentModelClient {
        override suspend fun generatePlanJson(userRequest: String): AgentModelResult = result
    }

    private class SequencedFakeAgentModelClient(
        private vararg val results: AgentModelResult,
    ) : AgentModelClient {
        val requests = mutableListOf<String>()
        var callCount = 0

        override suspend fun generatePlanJson(userRequest: String): AgentModelResult {
            requests += userRequest
            return results.getOrElse(callCount++) { results.last() }
        }
    }
}
