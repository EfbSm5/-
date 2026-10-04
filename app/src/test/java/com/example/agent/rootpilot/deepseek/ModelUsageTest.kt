package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.ScreenshotFrame
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ModelUsageTest {
    private val tokens = """{"prompt_tokens":12,"completion_tokens":8192,"total_tokens":8204,"completion_tokens_details":{"reasoning_tokens":8192}}"""
    private val prefix = event("""{"reasoning_content":"SECRET","content":"  "}""")
    private val done = "data: [DONE]\n\n"

    @Test fun lengthRetainsNumericFinalUsageAndStillRejectsCompleteContent() = runBlocking {
        val collector = ModelUsageCollector()
        val wire = prefix + event("{}", "length", tokens)
        try { readDeepSeekToolStream(Buffer().writeUtf8(wire), collector) {}; fail("length accepted") }
        catch (error: ModelProtocolException) { assertEquals(ModelProtocolReason.OUTPUT_LIMIT, error.reason) }
        val usage = collector.snapshot()
        assertEquals(12L, usage.promptTokens)
        assertEquals(8192L, usage.completionTokens)
        assertEquals(8192L, usage.reasoningTokens)
        assertEquals(8204L, usage.totalTokens)
        assertEquals(6L, usage.observedReasoningChars)
        assertEquals(2L, usage.observedContentChars)
        assertEquals(2L, usage.observedWhitespaceChars)
        assertEquals(ModelFinishReason.LENGTH, usage.finishReason)
        assertFalse(Json.encodeToString(usage).contains("SECRET"))
    }

    @Test fun malformedUsageCannotChangeProductionSuccess() = runBlocking {
        for (usage in listOf("[]", """{"completion_tokens":"SECRET"}""", """{"prompt_tokens":-1}""",
            """{"completion_tokens":2000001}""", """{"completion_tokens":1.5}""",
            """{"completion_tokens":1,"completion_tokens_details":{"reasoning_tokens":2}}""",
            """{"prompt_tokens":1,"completion_tokens":2,"total_tokens":4}""")) {
            val collector = ModelUsageCollector()
            val result = readDeepSeekToolStream(Buffer().writeUtf8(event("""{"content":"{}"}""", "stop", usage) + done), collector) {}
            assertEquals("{}", result.content)
            assertTrue(collector.snapshot().usageMalformed)
            assertNull(collector.snapshot().completionTokens)
            assertFalse(Json.encodeToString(collector.snapshot()).contains("SECRET"))
        }
    }

    @Test fun finalUsageOnlyReplacesAllFieldsWithoutMixingEarlierReasoning() = runBlocking {
        for (details in listOf("", ",\"completion_tokens_details\":null", ",\"completion_tokens_details\":{}")) {
            val collector = ModelUsageCollector()
            val stream = event("""{"content":"{}"}""", "stop", tokens) +
                "data: {\"choices\":[],\"usage\":{\"completion_tokens\":2$details}}\n\n" + done
            readDeepSeekToolStream(Buffer().writeUtf8(stream), collector) {}
            assertEquals(2L, collector.snapshot().completionTokens)
            assertNull(collector.snapshot().reasoningTokens)
            assertNull(collector.snapshot().promptTokens)
            assertNull(collector.snapshot().totalTokens)
            assertFalse(collector.snapshot().usageMalformed)
        }
    }

    @Test fun missingUsageAndIncompleteStreamRemainUnknownNotZero() = runBlocking {
        val collector = ModelUsageCollector()
        try { readDeepSeekToolStream(Buffer().writeUtf8(prefix), collector) {}; fail("incomplete accepted") }
        catch (error: ModelProtocolException) { assertEquals(ModelProtocolReason.INCOMPLETE_STREAM, error.reason) }
        val usage = collector.snapshot()
        assertNull(usage.completionTokens)
        assertNull(usage.reasoningTokens)
        assertNull(usage.finishReason)
        assertFalse(usage.usageMalformed)
        assertEquals(6L, usage.observedReasoningChars)
    }

    @Test fun unknownFinishIsAllowlistedAndDoesNotAlterRejection() = runBlocking {
        val collector = ModelUsageCollector()
        try { readDeepSeekToolStream(Buffer().writeUtf8(event("{}", "SECRET_FINISH")), collector) {}; fail("finish accepted") }
        catch (error: ModelProtocolException) { assertEquals(ModelProtocolReason.UNSUPPORTED_FINISH, error.reason) }
        assertEquals(ModelFinishReason.OTHER, collector.snapshot().finishReason)
        assertFalse(Json.encodeToString(collector.snapshot()).contains("SECRET"))
    }

    @Test fun enablingCollectorDoesNotChangeStreamRejections() = runBlocking {
        val streams = listOf("data: SECRET_INVALID\n\n", "data: [DONE]\n\n", event("{\"tool_calls\":null}"),
            event("{\"content\":${Json.encodeToString("x".repeat(60_000))}}").repeat(5),
            "data: " + "x".repeat(270_000))
        for (stream in streams) {
            suspend fun reason(collector: ModelUsageCollector?): ModelProtocolReason {
                try { readDeepSeekToolStream(Buffer().writeUtf8(stream), collector) {}; error("accepted") }
                catch (error: ModelProtocolException) { return error.reason }
            }
            val collector = ModelUsageCollector()
            assertEquals(reason(null), reason(collector))
            collector.snapshot()
        }
    }

    @Test(timeout = 15_000) fun realDecisionTransportCarriesSuccessAndFailureUsageWithoutCrossRequestState() = runBlocking {
        val client = HttpDeepSeekClient(requestTimeoutMillis = 2000)
        val failure = respond(client, prefix + event("{}", "length", tokens)) as ToolChatResult.Failure
        assertEquals(ModelProtocolReason.OUTPUT_LIMIT, failure.diagnostic.protocolReason)
        assertEquals(8192L, failure.usage?.completionTokens)
        val success = respond(client, event("""{"content":"{}"}""", "stop", tokens) + done) as ToolChatResult.Success
        assertEquals(12L, success.usage?.promptTokens)
        val missing = respond(client, event("""{"content":"{}"}""", "stop") + done) as ToolChatResult.Success
        assertNull(missing.usage?.promptTokens)
        assertEquals(0L, missing.usage?.observedReasoningChars)
        val http = respond(client, "SECRET", 401) as ToolChatResult.Failure
        assertNull(http.usage)
        assertEquals(ModelFailureCategory.HTTP, http.diagnostic.category)
    }

    private fun event(delta: String, finish: String? = null, usage: String? = null) =
        "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":${finish?.let { "\"$it\"" } ?: "null"}}]" +
            (usage?.let { ",\"usage\":$it" } ?: "") + "}\n\n"

    private suspend fun respond(client: HttpDeepSeekClient, body: String, status: Int = 200): ToolChatResult {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3000 }.use { server ->
            val failure = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().apply { soTimeout = 3000 }.use { socket ->
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            check(byte >= 0 && header.length < 16_384)
                            header.append(byte.toChar())
                        }
                        val size = header.lines().single { it.startsWith("Content-Length:", true) }
                            .substringAfter(':').trim().toInt()
                        check(input.readNBytes(size).size == size)
                        val bytes = body.toByteArray()
                        socket.getOutputStream().write(("HTTP/1.1 $status Result\r\nContent-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray() + bytes)
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            val result = client.requestDecision(DeepSeekVisionRequest(
                RootPilotConfig(baseUrl = "http://127.0.0.1:${server.localPort}", apiKey = "SECRET"),
                ScreenshotFrame(byteArrayOf(1), 1, 1, "data:image/jpeg;base64,AA=="), emptyList(), 1,
            ), emptyList(), true) {}
            worker.join(4000)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            return result
        }
    }
}
