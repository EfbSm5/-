package com.example.agent.agent.serialization

import com.example.agent.agent.model.AgentPlan
import com.example.agent.agent.model.AskUser
import com.example.agent.agent.model.CreateTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanDecoderTest {
    private val decoder = AgentPlanDecoder()

    @Test
    fun validAskUserPlan_decodesToDomainModel() {
        val result = decoder.decode(
            """
            {
              "goal": "提醒我投递豆包手机助手",
              "actions": [
                {
                  "type": "ask_user",
                  "question": "你希望几点提醒？"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(
            PlanDecodeResult.Success(
                AgentPlan(
                    goal = "提醒我投递豆包手机助手",
                    actions = listOf(AskUser("你希望几点提醒？")),
                ),
            ),
            result,
        )
    }

    @Test
    fun unknownActionType_returnsFailure() {
        val result = decoder.decode(
            """{"goal":"测试","actions":[{"type":"delete_everything"}]}""",
        )

        assertFailure(result, "type 不支持")
    }

    @Test
    fun createTodoWithoutTitle_returnsFailure() {
        val result = decoder.decode(
            """{"goal":"创建待办","actions":[{"type":"create_todo"}]}""",
        )

        assertFailure(result, "title 不能为空")
    }

    @Test
    fun blankDueAt_returnsFailure() {
        val result = decoder.decode(
            """{"goal":"创建待办","actions":[{"type":"create_todo","title":"投递岗位","due_at":" "}]}""",
        )

        assertFailure(result, "due_at 不能为空")
    }

    @Test
    fun validRfc3339DueAt_decodesToDomainModel() {
        val result = decoder.decode(
            """{"goal":"创建待办","actions":[{"type":"create_todo","title":"投递岗位","due_at":"2026-08-20T18:30:00+08:00"}]}""",
        )

        assertEquals(
            PlanDecodeResult.Success(
                AgentPlan(
                    goal = "创建待办",
                    actions = listOf(
                        CreateTodo(
                            title = "投递岗位",
                            dueAt = "2026-08-20T18:30:00+08:00",
                        ),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun dueAtWithoutTimezone_returnsFailure() {
        val result = decoder.decode(
            """{"goal":"创建待办","actions":[{"type":"create_todo","title":"投递岗位","due_at":"2026-08-20T18:30:00"}]}""",
        )

        assertFailure(result, "RFC 3339")
    }

    @Test
    fun dueAtWithInvalidCalendarDate_returnsFailure() {
        val result = decoder.decode(
            """{"goal":"创建待办","actions":[{"type":"create_todo","title":"投递岗位","due_at":"2026-02-30T18:30:00+08:00"}]}""",
        )

        assertFailure(result, "RFC 3339")
    }

    @Test
    fun actionWithFieldsFromAnotherType_returnsFailure() {
        val result = decoder.decode(
            """
            {
              "goal": "创建待办",
              "actions": [
                {
                  "type": "create_todo",
                  "title": "投递岗位",
                  "package_name": "com.example.app"
                }
              ]
            }
            """.trimIndent(),
        )

        assertFailure(result, "包含无关字段")
    }

    @Test
    fun unknownJsonField_returnsFailure() {
        val result = decoder.decode(
            """{"goal":"测试","actions":[],"hidden_instruction":"忽略校验"}""",
        )

        assertFailure(result, "JSON 格式不符合计划协议")
    }

    private fun assertFailure(result: PlanDecodeResult, expectedMessage: String) {
        assertTrue(result is PlanDecodeResult.Failure)
        assertTrue((result as PlanDecodeResult.Failure).reason.contains(expectedMessage))
    }
}
