package com.example.agent.rootpilot.deepseek

import java.io.ByteArrayOutputStream
import java.io.InputStream
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.ScreenshotFrame
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    @Test(timeout = 10_000)
    fun testConnection_usesSelectedModelAndBearerWithoutScreenshotOrTask() = runTest {
        ServerSocket(0).apply { soTimeout = 3000 }.use { server ->
            val captured = AtomicReference<Pair<String, String>>()
            val worker = thread(isDaemon = true) {
                server.accept().apply { soTimeout = 3000 }.use { socket ->
                    captured.set(readHttpRequest(socket.getInputStream()))
                    val body = """{"choices":[{"message":{"content":"OK"}}]}""".toByteArray()
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body,
                    )
                }
            }
            val result = HttpDeepSeekClient().testConnection(
                RootPilotConfig(
                    apiKey = "test-token-not-a-real-secret",
                    baseUrl = "http://127.0.0.1:${server.localPort}",
                    model = "chosen-model",
                    task = "must-not-upload-this-task",
                    allowScreenUpload = false,
                ),
            )
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(result is DeepSeekActionResult.Success)
            val (headers, body) = captured.get()
            assertTrue(headers.contains("Authorization: Bearer test-token-not-a-real-secret", ignoreCase = true))
            assertTrue(body.contains("chosen-model"))
            assertFalse(body.contains("image_url"))
            assertFalse(body.contains("must-not-upload-this-task"))
            assertFalse(body.contains("test-token-not-a-real-secret"))
        }
    }

    @Test(timeout = 10_000)
    fun failures_doNotExposeServerBodyOrFollowRedirects() = runTest {
        for (status in listOf(401, 302)) {
            ServerSocket(0).apply { soTimeout = 3000 }.use { server ->
                val worker = thread(isDaemon = true) {
                    server.accept().apply { soTimeout = 3000 }.use { socket ->
                        readHttpBody(socket.getInputStream())
                        val body = "private server diagnostic test-token".toByteArray()
                        socket.getOutputStream().write(
                            ("HTTP/1.1 $status Failure\r\nLocation: http://127.0.0.1:1/other\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body,
                        )
                    }
                }
                val result = HttpDeepSeekClient().testConnection(
                    RootPilotConfig(apiKey = "test-token", baseUrl = "http://127.0.0.1:${server.localPort}"),
                ) as DeepSeekActionResult.Failure
                worker.join(5_000)
                assertFalse(worker.isAlive)
                assertTrue(result.message.contains(status.toString()))
                assertFalse(result.message.contains("test-token"))
                assertFalse(result.message.contains("private server"))
            }
        }
    }

    @Test(timeout = 10_000)
    fun invalidConfig_failsLocallyWithoutExposingCredential() = runTest {
        val configs = listOf(
            RootPilotConfig(),
            RootPilotConfig(apiKey = "test-token", baseUrl = "https://test-token@example.invalid"),
            RootPilotConfig(apiKey = "test-token", baseUrl = "https://example.invalid?token=test-token"),
            RootPilotConfig(apiKey = "test-token", baseUrl = "file:///test-token"),
            RootPilotConfig(apiKey = "test-token", baseUrl = "http://example.invalid"),
            RootPilotConfig(apiKey = "test-token\n"),
        )
        for (config in configs) {
            val result = HttpDeepSeekClient().testConnection(config) as DeepSeekActionResult.Failure
            assertFalse(result.message.contains("test-token"))
        }
    }

    @Test(timeout = 10_000)
    fun relayMode_allowsBlankAppKeyAndOmitsAuthorizationHeader() = runTest {
        ServerSocket(0).apply { soTimeout = 3000 }.use { server ->
            val authorization = AtomicReference<String?>()
            val responseBody =
                """{"choices":[{"message":{"content":"{\"action\":\"finish\",\"success\":true,\"message\":\"完成\"}"}}]}"""
            val serverThread = thread(start = true, isDaemon = true) {
                server.accept().apply { soTimeout = 3000 }.use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authorization.set(line.substringAfter(':').trim())
                        }
                    }
                    val bytes = responseBody.toByteArray()
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Type: application/json\r\n".toByteArray())
                        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                        output.write("Connection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                    }
                }
            }

            val result = HttpDeepSeekClient().requestAction(
                DeepSeekVisionRequest(
                    config = RootPilotConfig(
                        baseUrl = "http://127.0.0.1:${server.localPort}",
                        model = "test-model",
                        task = "测试任务",
                        allowScreenUpload = true,
                    ),
                    frame = ScreenshotFrame(
                        bytes = byteArrayOf(1),
                        width = 1,
                        height = 1,
                        dataUrl = "data:image/jpeg;base64,test",
                    ),
                    history = emptyList(),
                    remainingSteps = 1,
                ),
            )
            serverThread.join(5_000)

            assertTrue(serverThread.isAlive.not())
            assertNull(authorization.get())
            assertEquals(
                "{\"action\":\"finish\",\"success\":true,\"message\":\"完成\"}",
                (result as DeepSeekActionResult.Success).rawActionJson,
            )
        }
    }

    @Test(timeout = 10_000)
    fun promptIncludesCapabilitiesAndStepWithoutTaskSpecificConstraints() = runTest {
        ServerSocket(0).apply { soTimeout = 3000 }.use { server ->
            val requestBodies = mutableListOf<String>()
            val responseBody =
                """{"choices":[{"message":{"content":"{\"action\":\"finish\",\"success\":true,\"message\":\"完成\"}"}}]}"""
            val serverThread = thread(start = true, isDaemon = true) {
                repeat(2) {
                    server.accept().apply { soTimeout = 3000 }.use { socket ->
                        requestBodies += readHttpBody(socket.getInputStream())
                        val bytes = responseBody.toByteArray()
                        socket.getOutputStream().use { output ->
                            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                            output.write("Content-Type: application/json\r\n".toByteArray())
                            output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                            output.write("Connection: close\r\n\r\n".toByteArray())
                            output.write(bytes)
                        }
                    }
                }
            }

            suspend fun request(step: Int): DeepSeekActionResult =
                HttpDeepSeekClient().requestAction(
                    DeepSeekVisionRequest(
                        config = RootPilotConfig(
                            baseUrl = "http://127.0.0.1:${server.localPort}",
                            model = "test-model",
                            task = "打开系统设置，进入显示设置",
                            allowScreenUpload = true,
                        ),
                        frame = ScreenshotFrame(
                            bytes = byteArrayOf(1),
                            width = 1,
                            height = 1,
                            dataUrl = "data:image/jpeg;base64,test",
                        ),
                        history = emptyList(),
                        remainingSteps = 2,
                        step = step,
                        availableApps = listOf(com.example.agent.rootpilot.model.RootPilotApp(
                            "com.example.notes", "记事本", "com.example.notes.Main",
                        )),
                    ),
                )

            assertTrue(request(0) is DeepSeekActionResult.Success)
            assertTrue(request(1) is DeepSeekActionResult.Success)
            serverThread.join(5_000)

            assertEquals(2, requestBodies.size)
            assertTrue(requestBodies[0].contains("当前步骤：1"))
            assertTrue(requestBodies[0].contains("可通过 open_app 打开的应用"))
            assertTrue(requestBodies[1].contains("当前步骤：2"))
            requestBodies.forEach { body ->
                assertTrue(body.contains("com.example.notes"))
                assertTrue(body.contains("记事本"))
                assertTrue(!body.contains("入口约束"))
                assertTrue(!body.contains("第一步必须返回"))
                assertTrue(body.contains("image_url"))
                val payload = Json.parseToJsonElement(body).jsonObject
                assertEquals(
                    "enabled",
                    payload.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content,
                )
                assertEquals(4_096, payload.getValue("max_tokens").jsonPrimitive.int)
                val messages = payload.getValue("messages").jsonArray
                val prompt = messages[0].jsonObject.getValue("content").jsonPrimitive.content
                assertTrue(prompt.contains("normalized to the FULL screenshot"))
                assertTrue(prompt.contains("including status and navigation bars"))
                assertTrue(prompt.contains("y=round(pixel_y/image_height*1000)"))
                assertTrue(prompt.contains("create_todo"))
                assertTrue(prompt.contains("null means no deadline"))
                assertTrue(prompt.contains("ask_user when the intended date or timezone is unclear"))
                val userPrompt = messages[1].jsonObject.getValue("content").jsonArray
                    .first { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                    .jsonObject.getValue("text").jsonPrimitive.content
                val currentTime = userPrompt.lineSequence()
                    .first { it.startsWith("当前本地时间（含时区偏移）：") }.substringAfter("：")
                java.time.OffsetDateTime.parse(currentTime)
                val imagePart = messages[1].jsonObject.getValue("content").jsonArray
                    .first { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
                assertEquals(
                    "high",
                    imagePart.jsonObject.getValue("image_url").jsonObject.getValue("detail").jsonPrimitive.content,
                )
            }
        }
    }

    private fun readHttpBody(input: InputStream): String = readHttpRequest(input).second

    private fun readHttpRequest(input: InputStream): Pair<String, String> {
        val headerBytes = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current == -1) error("HTTP 请求提前结束")
            headerBytes.write(current)
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = headerBytes.toByteArray()
                if (bytes.takeLast(4).toByteArray().contentEquals(byteArrayOf(13, 10, 13, 10))) break
            }
            previous = current
        }
        val headers = headerBytes.toString(Charsets.ISO_8859_1.name())
        val contentLength = headers.lineSequence()
            .first { it.startsWith("Content-Length:", ignoreCase = true) }
            .substringAfter(':')
            .trim()
            .toInt()
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count == -1) error("HTTP 请求体提前结束")
            offset += count
        }
        return headers to body.toString(Charsets.UTF_8)
    }
}
