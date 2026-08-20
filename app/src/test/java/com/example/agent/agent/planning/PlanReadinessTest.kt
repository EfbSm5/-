package com.example.agent.agent.planning

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanReadinessTest {
    @Test
    fun planWithoutQuestions_isReadyToExecute() {
        val plan = AgentPlan(
            goal = "创建待办",
            actions = listOf(CreateTodo(title = "投递简历", dueAt = null)),
        )

        assertEquals(PlanReadiness.ReadyToExecute, plan.assessReadiness())
    }

    @Test
    fun planWithQuestion_requiresClarification() {
        val plan = AgentPlan(
            goal = "准备投递",
            actions = listOf(AskUser("你准备投递哪个岗位？")),
        )

        assertEquals(
            PlanReadiness.NeedsClarification("你准备投递哪个岗位？"),
            plan.assessReadiness(),
        )
    }

    @Test
    fun mixedPlan_requiresClarificationBeforeAnyActionRuns() {
        val plan = AgentPlan(
            goal = "准备投递",
            actions = listOf(
                CreateTodo(title = "投递简历", dueAt = null),
                AskUser("你准备投递哪个岗位？"),
            ),
        )

        assertEquals(
            PlanReadiness.NeedsClarification("你准备投递哪个岗位？"),
            plan.assessReadiness(),
        )
    }
}
