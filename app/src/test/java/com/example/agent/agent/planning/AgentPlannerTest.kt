package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val planner = AgentPlanner(
            modelClient = FakeAgentModelClient(
                AgentModelResult.Success(
                    """{"goal":"删除所有内容","actions":[{"type":"delete_everything"}]}""",
                ),
            ),
        )

        val result = runBlocking { planner.buildPlan("帮我删除所有内容") }

        assertDecodeFailure(result, "type 不支持")
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
}
