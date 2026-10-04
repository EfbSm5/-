package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.log.*
import com.example.agent.rootpilot.model.RootPilotConfig
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import okio.Buffer

class ModelFailureTest {
    @Test fun nativeStreamRejectionHasFixedReasonWithoutResponseText() = runBlocking {
        val cases = listOf(
            "data: {PRIVATE_INVALID_JSON\n\n" to ModelProtocolReason.INVALID_JSON,
            "data: [DONE]\n\n" to ModelProtocolReason.MISSING_FINISH,
            "data: [DONE]\n" to ModelProtocolReason.INCOMPLETE_STREAM,
            chunk("{\"tool_calls\":null}") to ModelProtocolReason.NULL_TOOL_CALLS,
            chunk("{\"tool_calls\":[]}") to ModelProtocolReason.EMPTY_TOOL_CALLS,
            chunk("{}", "length") to ModelProtocolReason.OUTPUT_LIMIT,
            chunk("{}", "unsupported_private_finish") to ModelProtocolReason.UNSUPPORTED_FINISH,
            chunk("{}", "stop") + "data: [DONE]\n\n" to ModelProtocolReason.EMPTY_OUTPUT,
        )
        for ((stream, expected) in cases) {
            try {
                readDeepSeekToolStream(Buffer().writeUtf8(stream)) {}
                fail("expected_protocol_rejection")
            } catch (error: ModelProtocolException) {
                assertEquals(expected, error.reason)
                assertFalse(error.toString().contains("PRIVATE"))
                assertFalse(error.toString().contains("unsupported_private_finish"))
            }
        }
    }

    @Test fun protocolReasonSurvivesTraceAndCannotAttachToOtherCategory() {
        val diagnostic = ModelFailure(ModelFailureCategory.RESPONSE_PROTOCOL, protocolReason = ModelProtocolReason.OUTPUT_LIMIT)
        val lines = mutableListOf<String>()
        val trace = RunTrace(sink = { lines += it })
        trace.fail(TraceReason.MODEL_FAILED, diagnostic)
        assertTrue(lines.single().contains("\"modelProtocolReason\":\"output_limit\""))
        assertEquals(diagnostic, Json.decodeFromString<ModelFailure>(Json.encodeToString(diagnostic)))
        assertThrows(IllegalArgumentException::class.java) {
            ModelFailure(ModelFailureCategory.HTTP, 500, ModelProtocolReason.OUTPUT_LIMIT)
        }
    }

    private fun chunk(delta: String, finish: String? = null) =
        "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":${finish?.let { "\"$it\"" } ?: "null"}}]}\n\n"

    @Test fun completeActionWithLengthIsRejectedWithOrWithoutUsage() = runBlocking {
        val action = """{"action":"finish","success":true,"message":"fixture"}"""
        val prefix = chunk("{\"content\":${Json.encodeToString(action)}}")
        for (terminal in listOf(chunk("{}", "length"), terminalWithUsage("length"))) {
            assertProtocolReason(prefix + terminal + "data: [DONE]\n\n", ModelProtocolReason.OUTPUT_LIMIT)
        }
    }

    @Test fun localTextEventAndWireLimitsHaveDistinctReason() = runBlocking {
        val accumulatedText = chunk("{\"reasoning_content\":${Json.encodeToString("x".repeat(60_000))}}").repeat(5)
        val oversizedEvent = "data: " + "x".repeat(262_145)
        val oversizedWire = (":" + "你".repeat(10_000) + "\r\n\r\n").repeat(140)
        for (stream in listOf(accumulatedText, oversizedEvent, oversizedWire)) {
            assertProtocolReason(stream, ModelProtocolReason.STREAM_LIMIT)
        }
    }

    @Test fun usageOnFinalStopChunkDoesNotRejectCompleteAction() = runBlocking {
        val action = """{"action":"finish","success":true,"message":"fixture"}"""
        val reasoning = "synthetic reasoning ".repeat(600)
        val stream = chunk("{\"reasoning_content\":${Json.encodeToString(reasoning)}}") +
            chunk("{\"content\":${Json.encodeToString(action)}}") + terminalWithUsage("stop") + "data: [DONE]\n\n"
        val result = readDeepSeekToolStream(Buffer().writeUtf8(stream)) {}
        assertEquals(action, result.content)
        assertEquals(reasoning, result.reasoning)
        assertTrue(result.toolCalls.isEmpty())
    }

    private suspend fun assertProtocolReason(stream: String, expected: ModelProtocolReason) {
        try {
            readDeepSeekToolStream(Buffer().writeUtf8(stream)) {}
            fail("expected_protocol_rejection")
        } catch (error: ModelProtocolException) {
            assertEquals(expected, error.reason)
        }
    }

    private fun terminalWithUsage(finish: String) =
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"$finish\"}]," +
            "\"usage\":{\"prompt_tokens\":128,\"completion_tokens\":4096,\"total_tokens\":4224," +
            "\"completion_tokens_details\":{\"reasoning_tokens\":4000}}}\n\n"

    @Test(timeout = 10_000) fun responseProtocolFailureIsTypedAndRedacted() = runBlocking {
        val result = response("SECRET_INVALID_JSON")
        assertEquals(ModelFailureCategory.RESPONSE_PROTOCOL, result.diagnostic.category)
        assertFalse(result.toString().contains("SECRET"))
    }

    @Test(timeout = 10_000) fun connectionFailureIsNetworkWithoutExceptionText() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val result = HttpDeepSeekClient(requestTimeoutMillis = 1000).testConnection(
            RootPilotConfig(baseUrl = "http://127.0.0.1:$port")) as DeepSeekActionResult.Failure
        assertEquals(ModelFailureCategory.NETWORK, result.diagnostic.category)
        assertNull(result.diagnostic.httpStatus)
    }

    @Test(timeout = 10_000) fun stalledResponseIsTimeout() = runBlocking {
        ServerSocket(0).apply { soTimeout = 2000 }.use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    // No headers are sent. Read until client cancellation closes its socket.
                    while (socket.getInputStream().read() != -1) Unit
                }
            }
            val result = HttpDeepSeekClient(requestTimeoutMillis = 250).testConnection(
                RootPilotConfig(baseUrl = "http://127.0.0.1:${server.localPort}")) as DeepSeekActionResult.Failure
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertEquals(ModelFailureCategory.TIMEOUT, result.diagnostic.category)
        }
    }

    @Test fun invalidToolRequestFailsLocallyWithContractCategory() = runBlocking {
        val result = HttpDeepSeekClient().streamToolChat(RootPilotConfig(), emptyList(), emptyList(), ThinkingEffort.NONE) {}
            as ToolChatResult.Failure
        assertEquals(ModelFailureCategory.REQUEST_CONTRACT, result.diagnostic.category)
    }

    @Test fun diagnosticSurvivesTraceAndHistorySerializationButNotPayloads() {
        val lines = mutableListOf<String>()
        val events = mutableListOf<RunTraceEvent>()
        val trace = RunTrace(observer = { events += it }, sink = { lines += it })
        trace.stage = TraceStage.MODEL
        trace.fail(TraceReason.MODEL_FAILED, ModelFailure(ModelFailureCategory.HTTP, 429))
        trace.record(TraceEvent.RUN_END, trace.outcome, trace.reason)
        assertEquals(2, events.size)
        events.forEach {
            assertEquals(ModelFailure(ModelFailureCategory.HTTP, 429), it.modelFailure)
            assertEquals(it, Json.decodeFromString<RunTraceEvent>(Json.encodeToString(it)))
        }
        assertTrue(lines.all { it.contains("\"modelHttpStatus\":429") && it.contains("\"modelFailureCategory\":\"http\"") })
        trace.fail(TraceReason.PARSE_FAILED)
        assertNull(events.last().modelFailure)
    }

    @Test fun legacyHistoryWithoutDiagnosticStillDecodes() {
        val event = RunTraceEvent("fixture", 0, 0, TraceActionType.NONE, TraceStage.MODEL,
            TraceEvent.RESULT, TraceStatus.FAILED, TraceReason.MODEL_FAILED)
        assertFalse(Json.encodeToString(event).contains("modelFailure"))
        assertNull(Json.decodeFromString<RunTraceEvent>(Json.encodeToString(event)).modelFailure)
    }

    @Test fun legacyFailureIsUnknownAndDoesNotPrintArbitraryMessage() {
        assertEquals(ModelFailureCategory.UNKNOWN, ToolChatResult.Failure("SECRET").diagnostic.category)
        assertFalse(ToolChatResult.Failure("SECRET").toString().contains("SECRET"))
        assertFalse(DeepSeekActionResult.Failure("SECRET").toString().contains("SECRET"))
    }

    @Test fun httpDiagnosticRejectsNonStatusAndUnexpectedFields() {
        for (status in listOf(null, 0, 99, 600)) {
            assertThrows(IllegalArgumentException::class.java) { ModelFailure(ModelFailureCategory.HTTP, status) }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelFailure(ModelFailureCategory.NETWORK, 429) }
    }

    private suspend fun response(body: String): DeepSeekActionResult.Failure {
        ServerSocket(0).apply { soTimeout = 3000 }.use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().apply { soTimeout = 3000 }.use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val bytes = body.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                }
            }
            val result = HttpDeepSeekClient().testConnection(RootPilotConfig(baseUrl = "http://127.0.0.1:${server.localPort}"))
            worker.join(5000)
            assertFalse(worker.isAlive)
            return result as DeepSeekActionResult.Failure
        }
    }
}
