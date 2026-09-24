package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.ScreenshotFrame
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DeepSeekStreamTest {
    private val toolSchema = Json.parseToJsonElement("""{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}""").jsonObject
    private val toolChunk = "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}}]}\n\n"

    @Test(timeout = 10_000)
    fun toolHttpEntry_sendsToolHistoryAndReturnsStructuredCalls() = runBlocking {
        Server { it.getOutputStream().write(response(toolChunk + chunk(finish = "tool_calls") + DONE)) }.use { server ->
            val result = HttpDeepSeekClient().streamToolChat(server.config, listOf(
                ToolChatTurn("user", "hello"),
                ToolChatTurn("assistant", "", "earlier reasoning", listOf(ChatToolCall("old", "lookup", "{}"))),
                ToolChatTurn("tool", "old result", toolCallId = "old"),
            ), listOf(toolSchema), ThinkingEffort.HIGH) {}
            assertEquals(ToolChatResult.Success("", "", listOf(ChatToolCall("call1", "lookup", "{}"))), result)
            val body = server.body.get()
            assertTrue(body.contains("\"tools\""))
            assertTrue(body.contains("\"reasoning_content\":\"earlier reasoning\""))
            assertTrue(body.contains("\"tool_call_id\":\"old\""))
            server.assertReleased()
        }
    }

    @Test(timeout = 10_000)
    fun ordinaryChatAndVisionHttpEntries_rejectToolCalls() = runBlocking {
        for (vision in listOf(false, true)) {
            Server { it.getOutputStream().write(response(toolChunk + chunk(finish = "tool_calls") + DONE)) }.use { server ->
                val client = HttpDeepSeekClient()
                val result = if (vision) client.requestAction(DeepSeekVisionRequest(
                    server.config, ScreenshotFrame(byteArrayOf(1), 1, 1, "data:image/jpeg;base64,test"), emptyList(), 1,
                )) {} else client.streamChat(server.config, listOf(ChatTurn("user", "hello")), ThinkingEffort.HIGH) {}
                assertEquals(DeepSeekActionResult.Failure("DeepSeek 返回格式无法理解"), result)
                server.assertReleased()
            }
        }
    }

    @Test(timeout = 10_000)
    fun toolMalformedAndTruncatedResponses_areSanitized() = runBlocking {
        for (stream in listOf("data: private synthetic-token\n\n", toolChunk + chunk(finish = "tool_calls") + "data: [DONE]\n")) {
            Server { socket ->
                val bytes = stream.toByteArray()
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes)
            }.use { server ->
                val result = HttpDeepSeekClient().streamToolChat(server.config, listOf(ToolChatTurn("user", "hello")), listOf(toolSchema), ThinkingEffort.HIGH) {}
                assertEquals(ToolChatResult.Failure("DeepSeek 返回格式无法理解"), result)
                server.assertReleased()
            }
        }
    }

    @Test(timeout = 10_000)
    fun toolCallback_isCoveredByDeadlineAndCallerCancellation() = runBlocking {
        for (cancelCaller in listOf(false, true)) {
            Server { it.getOutputStream().write(response(chunk(reasoning = "draft"))) }.use { server ->
                val update = kotlinx.coroutines.CompletableDeferred<Unit>()
                val result = kotlinx.coroutines.CompletableDeferred<ToolChatResult>()
                val job = launch {
                    result.complete(HttpDeepSeekClient(requestTimeoutMillis = if (cancelCaller) 5000 else 500).streamToolChat(
                        server.config, listOf(ToolChatTurn("user", "hello")), listOf(toolSchema), ThinkingEffort.HIGH,
                    ) { update.complete(Unit); awaitCancellation() })
                }
                kotlinx.coroutines.withTimeout(2000) { update.await() }
                if (cancelCaller) {
                    kotlinx.coroutines.withTimeout(1000) { job.cancelAndJoin() }
                    assertTrue(job.isCancelled)
                    assertFalse(result.isCompleted)
                } else {
                    assertEquals(ToolChatResult.Failure("DeepSeek 请求超时"), kotlinx.coroutines.withTimeout(2000) { result.await() })
                }
                server.assertReleased()
            }
        }
    }

    @Test
    fun snapshotAndTurnToString_areRedacted() {
        assertFalse(ModelStreamSnapshot("private-reason", "private-content").toString().contains("private"))
        assertFalse(ChatTurn("private-role", "private-content").toString().contains("private"))
        assertEquals(listOf("none", "low", "high", "max"), ThinkingEffort.entries.map { it.wireValue })
    }

    @Test(timeout = 10_000)
    fun reasoningAndContent_areAccumulatedThrottledAndFlushed() = runBlocking {
        val updates = mutableListOf<ModelStreamSnapshot>()
        val stream = ": keepalive\r\n\r\n" + chunk(reasoning = "思考") +
            (1..100).joinToString("") { chunk(content = "字") } + chunk(finish = "stop") + DONE
        val result = readDeepSeekStream(Buffer().writeUtf8(stream)) { updates += it }
        assertEquals("字".repeat(100), result)
        assertEquals(ModelStreamSnapshot("思考", result), updates.last())
        assertTrue("rapid chunks must be throttled", updates.size < 10)
    }

    @Test(timeout = 10_000)
    fun incompleteMalformedAndAbnormalFinishes_areRejected() = runBlocking {
        val invalid = listOf(
            chunk(content = "partial"),
            chunk(content = "partial", finish = "stop"),
            chunk(content = "partial") + DONE,
            chunk(content = "partial", finish = "length") + DONE,
            chunk(content = "partial", finish = "content_filter") + DONE,
            chunk(content = "partial", finish = "tool_calls") + DONE,
            chunk(content = "partial", finish = "stop") + "data: [DONE]\n",
            "data: private-token-not-json\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":123}}]}\n\n",
            "data: {\"error\":{\"message\":\"private-token\"}}\n\n",
            chunk(content = "ok", finish = "stop") + chunk(content = "after finish") + DONE,
            chunk(finish = "stop") + DONE,
        )
        invalid.forEach { stream ->
            try {
                readDeepSeekStream(Buffer().writeUtf8(stream)) {}
                fail("invalid stream accepted")
            } catch (_: kotlinx.serialization.SerializationException) {
                // The HTTP client maps these parser errors to a fixed public message.
            }
        }
    }

    @Test(timeout = 10_000)
    fun textAndUnterminatedLine_areBounded() = runBlocking {
        for (stream in listOf(
            (1..5).joinToString("") { chunk(content = "x".repeat(60_000)) } + DONE,
            "data: " + "x".repeat(300_000),
        )) {
            try {
                readDeepSeekStream(Buffer().writeUtf8(stream)) {}
                fail("oversized stream accepted")
            } catch (_: kotlinx.serialization.SerializationException) { }
        }
    }

    @Test(timeout = 10_000)
    fun multilineDataAndUsageChunk_areSupported() = runBlocking {
        val multiline = "data: {\"choices\":\n" +
            "data: [{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":\"stop\"}]}\n\n"
        assertEquals("你好", readDeepSeekStream(Buffer().writeUtf8(
            multiline + "data: {\"choices\":[],\"usage\":{}}\n\n" + DONE,
        )) {})
    }

    @Test(timeout = 10_000)
    fun chatPayloadAndCompleteContent_workForEveryEffort() = runBlocking {
        for (effort in ThinkingEffort.entries) {
            Server { it.getOutputStream().write(response(chunk(content = "完整回答", finish = "stop") + DONE)) }.use { server ->
                val updates = mutableListOf<ModelStreamSnapshot>()
                assertEquals(DeepSeekActionResult.Success("完整回答"), HttpDeepSeekClient(requestTimeoutMillis = 1500).streamChat(
                    server.config, listOf(ChatTurn("user", "你好")), effort,
                ) { updates += it })
                val payload = Json.parseToJsonElement(server.body.get()).jsonObject
                assertEquals("true", payload["stream"].toString())
                assertEquals("16384", payload["max_tokens"].toString())
                assertEquals(if (effort == ThinkingEffort.NONE) "\"disabled\"" else "\"enabled\"", payload["thinking"]!!.jsonObject["type"].toString())
                if (effort == ThinkingEffort.NONE) assertNull(payload["reasoning_effort"])
                else assertEquals("\"${effort.wireValue}\"", payload["reasoning_effort"].toString())
                assertEquals("完整回答", updates.last().content)
                server.assertReleased()
            }
        }
    }

    @Test(timeout = 10_000)
    fun taskStream_keepsPromptAndJsonFormatAndTokenBudget() = runBlocking {
        Server { it.getOutputStream().write(response(chunk(content = "{}", finish = "stop") + DONE)) }.use { server ->
            val request = DeepSeekVisionRequest(server.config, ScreenshotFrame(byteArrayOf(1), 1, 1, "data:image/jpeg;base64,test"), emptyList(), 1)
            assertEquals(DeepSeekActionResult.Success("{}"), HttpDeepSeekClient(requestTimeoutMillis = 1500).requestAction(request) {})
            val payload = Json.parseToJsonElement(server.body.get()).jsonObject
            assertEquals("4096", payload["max_tokens"].toString())
            assertEquals("\"json_object\"", payload["response_format"]!!.jsonObject["type"].toString())
            assertTrue(server.body.get().contains("normalized to the FULL screenshot"))
            server.assertReleased()
        }
    }

    @Test(timeout = 10_000)
    fun oldClientOverload_remainsCompatible() = runBlocking {
        val expected = DeepSeekActionResult.Success("old")
        val client = object : DeepSeekClient {
            override suspend fun requestAction(request: DeepSeekVisionRequest) = expected
        }
        val request = DeepSeekVisionRequest(RootPilotConfig(), ScreenshotFrame(byteArrayOf(1), 1, 1, "x"), emptyList(), 1)
        assertEquals(expected, client.requestAction(request) { fail("old client cannot publish") })
    }

    @Test(timeout = 10_000)
    fun malformedNetworkResponse_isSanitized() = runBlocking {
        Server { it.getOutputStream().write(response("data: private synthetic-token\n\n")) }.use { server ->
            assertEquals(DeepSeekActionResult.Failure("DeepSeek 返回格式无法理解"), HttpDeepSeekClient(requestTimeoutMillis = 1500).streamChat(
                server.config, listOf(ChatTurn("user", "hello")), ThinkingEffort.HIGH,
            ) {})
            server.assertReleased()
        }
    }

    @Test(timeout = 10_000)
    fun errorAndRedirectResponses_doNotReadBodyOrPublish() = runBlocking {
        for (status in listOf(401, 307, 503)) {
            Server { socket ->
                socket.getOutputStream().write(("HTTP/1.1 $status Failure\r\nLocation: http://127.0.0.1:1/other\r\n" +
                    "Retry-After: 0\r\nContent-Length: 100000\r\n\r\nprivate synthetic-token").toByteArray())
            }.use { server ->
                val result = HttpDeepSeekClient(requestTimeoutMillis = 1000).streamChat(
                    server.config, listOf(ChatTurn("user", "hello")), ThinkingEffort.LOW,
                ) { fail("error response published") } as DeepSeekActionResult.Failure
                assertTrue(result.message.contains(status.toString()))
                assertFalse(result.message.contains("synthetic-token"))
                server.assertReleased()
            }
        }
    }

    @Test(timeout = 10_000)
    fun suspendedCallback_isIncludedInTotalDeadline() = runBlocking {
        Server { it.getOutputStream().write(response(chunk(reasoning = "partial"))) }.use { server ->
            assertEquals(DeepSeekActionResult.Failure("DeepSeek 请求超时"), HttpDeepSeekClient(requestTimeoutMillis = 500).streamChat(
                server.config, listOf(ChatTurn("user", "hello")), ThinkingEffort.HIGH,
            ) { awaitCancellation() })
            server.assertReleased()
        }
    }

    @Test(timeout = 10_000)
    fun cancelBlockedStream_keepsCancellationAndReleasesPeer() {
        Server { it.getOutputStream().write(response(chunk(reasoning = "partial"))) }.use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val update = CountDownLatch(1)
            val cancelled = AtomicReference<Throwable>()
            val job = scope.launch {
                try {
                    HttpDeepSeekClient().streamChat(server.config, listOf(ChatTurn("user", "hello")), ThinkingEffort.MAX) { update.countDown() }
                    fail("cancel returned a result")
                } catch (error: CancellationException) { cancelled.set(error); throw error }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(update.await(2, TimeUnit.SECONDS))
                executor.submit { runBlocking { job.cancelAndJoin() } }.get(1, TimeUnit.SECONDS)
                assertNotNull(cancelled.get())
                server.assertReleased()
            } finally {
                server.close()
                scope.cancel()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 10_000)
    fun streamingDrip_hitsTotalDeadline() = runBlocking {
        Server { socket ->
            socket.getOutputStream().write(response(""))
            try {
                repeat(60) {
                    socket.getOutputStream().write(chunk(reasoning = ".").toByteArray())
                    socket.getOutputStream().flush()
                    Thread.sleep(50)
                }
            } catch (_: SocketException) { }
        }.use { server ->
            assertEquals(DeepSeekActionResult.Failure("DeepSeek 请求超时"), HttpDeepSeekClient(requestTimeoutMillis = 500).streamChat(
                server.config, listOf(ChatTurn("user", "hello")), ThinkingEffort.MAX,
            ) {})
            server.assertReleased()
        }
    }

    private class Server(respond: (Socket) -> Unit) : Closeable {
        private val listener = ServerSocket(0).apply { soTimeout = 3000 }
        private val socket = AtomicReference<Socket>()
        private val released = CountDownLatch(1)
        private val failure = AtomicReference<Throwable>()
        val body = AtomicReference<String>()
        val config = RootPilotConfig(apiKey = "synthetic-token", baseUrl = "http://127.0.0.1:${listener.localPort}")
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "sse-fixture").apply { isDaemon = true } }
        private val worker = executor.submit {
            try {
                listener.accept().use { accepted ->
                    socket.set(accepted)
                    accepted.soTimeout = 3000
                    val input = accepted.getInputStream()
                    val headers = ByteArrayOutputStream()
                    var tail = 0
                    do {
                        val byte = input.read()
                        check(byte != -1)
                        headers.write(byte)
                        tail = (tail shl 8) or byte
                    } while (tail != 0x0d0a0d0a)
                    val length = headers.toString("ISO-8859-1").lineSequence().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    val bytes = ByteArray(length)
                    for (i in bytes.indices) { val byte = input.read(); check(byte != -1); bytes[i] = byte.toByte() }
                    body.set(bytes.toString(Charsets.UTF_8))
                    respond(accepted)
                    try { check(input.read() == -1) } catch (_: SocketException) { }
                    released.countDown()
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        fun assertReleased() {
            assertTrue("peer released", released.await(1, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("server failure", it) }
        }
        override fun close() {
            listener.close()
            socket.get()?.close()
            worker.get(4, TimeUnit.SECONDS)
            executor.shutdownNow()
            check(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    private fun response(stream: String) = ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: 10000000\r\n\r\n" + stream).toByteArray()

    private fun chunk(reasoning: String? = null, content: String? = null, finish: String? = null): String {
        val delta = buildJsonObject { reasoning?.let { put("reasoning_content", it) }; content?.let { put("content", it) } }
        return "data: {\"choices\":[{\"index\":0,\"delta\":$delta,\"finish_reason\":${finish?.let { "\"$it\"" } ?: "null"}}]}\n\n"
    }
    private companion object { const val DONE = "data: [DONE]\n\n" }
}
