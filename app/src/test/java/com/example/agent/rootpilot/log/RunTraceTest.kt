package com.example.agent.rootpilot.log

import com.example.agent.rootpilot.model.RootPilotAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RunTraceTest {
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
