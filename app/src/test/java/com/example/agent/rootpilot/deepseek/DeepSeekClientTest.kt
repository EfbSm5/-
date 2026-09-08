package com.example.agent.rootpilot.deepseek

import java.io.ByteArrayOutputStream
import java.io.InputStream
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.ScreenshotFrame
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    @Test
    fun relayMode_allowsBlankAppKeyAndOmitsAuthorizationHeader() = runTest {
        ServerSocket(0).use { server ->
            val authorization = AtomicReference<String?>()
            val responseBody =
                """{"choices":[{"message":{"content":"{\"action\":\"finish\",\"success\":true,\"message\":\"完成\"}"}}]}"""
            val serverThread = thread(start = true) {
                server.accept().use { socket ->
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

    @Test
    fun systemSettingsPromptOnlyRequiresOpenAppOnFirstStep() = runTest {
        ServerSocket(0).use { server ->
            val requestBodies = mutableListOf<String>()
            val responseBody =
                """{"choices":[{"message":{"content":"{\"action\":\"finish\",\"success\":true,\"message\":\"完成\"}"}}]}"""
            val serverThread = thread(start = true) {
                repeat(2) {
                    server.accept().use { socket ->
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
                    ),
                )

            request(0)
            request(1)
            serverThread.join(5_000)

            assertEquals(2, requestBodies.size)
            assertTrue(requestBodies[0].contains("当前步骤：1"))
            assertTrue(requestBodies[0].contains("第一步必须返回"))
            assertTrue(requestBodies[1].contains("当前步骤：2"))
            assertTrue(requestBodies[1].contains("后续步骤约束"))
            assertTrue(requestBodies[1].contains("不要重复返回 open_app"))
        }
    }

    private fun readHttpBody(input: InputStream): String {
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
        return body.toString(Charsets.UTF_8)
    }
}
