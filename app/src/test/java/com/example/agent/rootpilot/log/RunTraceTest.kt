package com.example.agent.rootpilot.log

import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.deepseek.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RunTraceTest {
    @Test fun usageIsRequestScopedSerializableAndAbsentInOldEvents() {
        val lines = mutableListOf<String>()
        val events = mutableListOf<RunTraceEvent>()
        val trace = RunTrace(observer = { events += it }, sink = { lines += it })
        val usage = ModelUsage(completionTokens = 8192, reasoningTokens = 8192, finishReason = ModelFinishReason.LENGTH)
        trace.stage = TraceStage.MODEL
        trace.fail(TraceReason.MODEL_FAILED,
            ModelFailure(ModelFailureCategory.RESPONSE_PROTOCOL, protocolReason = ModelProtocolReason.OUTPUT_LIMIT), usage)
        assertEquals(usage, events.single().modelUsage)
        assertEquals(events.single(), Json.decodeFromString<RunTraceEvent>(Json.encodeToString(events.single())))
        assertEquals("8192", Json.parseToJsonElement(lines.single()).jsonObject["modelUsage"]!!.jsonObject["completionTokens"]!!.jsonPrimitive.content)
        trace.record(TraceEvent.RUN_END, trace.outcome, trace.reason)
        trace.record(TraceEvent.START, TraceStatus.STARTED)
        trace.record(TraceEvent.RESULT, TraceStatus.SUCCESS)
        events.drop(1).forEach { assertNull(it.modelUsage) }
        val legacy = Json.parseToJsonElement(Json.encodeToString(events.first())).jsonObject
            .filterKeys { it != "modelUsage" }
        assertNull(Json.decodeFromString<RunTraceEvent>(kotlinx.serialization.json.JsonObject(legacy).toString()).modelUsage)
    }

    @Test fun observerFailureCannotPreventDiagnosticSinkOrChangeOutcome() {
        val lines = mutableListOf<String>()
        val trace = RunTrace(observer = { error("SECRET") }, sink = { lines += it })
        trace.outcome = TraceStatus.SUCCESS
        trace.reason = TraceReason.NONE
        trace.record(TraceEvent.RUN_END, TraceStatus.SUCCESS)
        assertEquals(1, lines.size)
        assertEquals(TraceStatus.SUCCESS, trace.outcome)
        assertFalse(lines.single().contains("SECRET"))
    }

    @Test
    fun clockAndBoundedSinkKeepOnlySanitizedJsonLines() {
        var now = 100L
        val repository = InMemoryAgentLogRepository(capacity = 2)
        val lines = mutableListOf<String>()
        val trace = RunTrace(TraceClock { now }) {
            repository.append(it)
            lines += it
        }
        trace.record(TraceEvent.RUN_START, TraceStatus.STARTED)
        trace.action(RootPilotAction.CreateTodo("SECRET_TITLE", "SECRET_DATE", "SECRET_REASON"))
        now = 130L
        trace.record(TraceEvent.TODO_SAVED, TraceStatus.SUCCESS)
        now = 150L
        trace.record(TraceEvent.RUN_END, TraceStatus.SUCCESS)
        assertEquals(lines.takeLast(2), repository.list())
        assertEquals(listOf("0", "30", "50"), lines.map {
            Json.parseToJsonElement(it).jsonObject.getValue("elapsedMs").jsonPrimitive.content
        })
        assertFalse(lines.joinToString().contains("SECRET"))
        assertFalse(lines.any { it.contains('\n') })
    }
}
