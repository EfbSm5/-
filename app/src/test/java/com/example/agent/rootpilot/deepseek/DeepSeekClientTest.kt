package com.example.agent.rootpilot.deepseek

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
}
