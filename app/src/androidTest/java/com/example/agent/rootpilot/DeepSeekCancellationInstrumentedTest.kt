package com.example.agent.rootpilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.model.RootPilotConfig
import java.net.InetAddress
import java.net.ServerSocket
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepSeekCancellationInstrumentedTest {
    @Test(timeout = 10_000)
    fun cancellationClosesWaitingHeaderAndBodyOnAndroid() {
        for (headers in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val workers = Executors.newFixedThreadPool(2)
                val ready = CountDownLatch(1)
                val cancelled = CountDownLatch(1)
                val accepted = AtomicReference<java.net.Socket>()
                val peer = workers.submit<Boolean> {
                    server.accept().use { socket ->
                        accepted.set(socket)
                        socket.soTimeout = 3000
                        consumeRequest(socket)
                        if (headers) {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{".toByteArray())
                            socket.getOutputStream().flush()
                        }
                        ready.countDown()
                        socket.getInputStream().read() == -1
                    }
                }
                val job = clientScope.launch {
                    try {
                        HttpDeepSeekClient().testConnection(config(server.localPort))
                    } catch (error: CancellationException) {
                        cancelled.countDown()
                        throw error
                    }
                }
                try {
                    assertTrue(ready.await(3, TimeUnit.SECONDS))
                    workers.submit { runBlocking { job.cancelAndJoin() } }.get(2, TimeUnit.SECONDS)
                    assertTrue(job.isCompleted && job.isCancelled)
                    assertEquals(0L, cancelled.count)
                    assertTrue(peer.get(2, TimeUnit.SECONDS))
                } finally {
                    accepted.get()?.close()
                    server.close()
                    clientScope.cancel()
                    workers.shutdownNow()
                    assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun drippingResponseHitsTotalDeadlineOnAndroid() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val workers = Executors.newFixedThreadPool(2)
            val accepted = AtomicReference<java.net.Socket>()
            val peer = workers.submit {
                server.accept().use { socket ->
                    accepted.set(socket)
                    socket.soTimeout = 3000
                    consumeRequest(socket)
                    try {
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n".toByteArray())
                        repeat(100) {
                            output.write(' '.code)
                            output.flush()
                            Thread.sleep(30)
                        }
                    } catch (_: java.io.IOException) {
                        // The request deadline closes the connection before the body finishes.
                    }
                }
            }
            try {
                val result = workers.submit<DeepSeekActionResult> {
                    runBlocking { HttpDeepSeekClient(requestTimeoutMillis = 500).testConnection(config(server.localPort)) }
                }.get(2, TimeUnit.SECONDS)
                assertEquals(DeepSeekActionResult.Failure("DeepSeek 请求超时"), result)
                peer.get(2, TimeUnit.SECONDS)
            } finally {
                accepted.get()?.close()
                server.close()
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    private fun config(port: Int) = RootPilotConfig(baseUrl = "http://127.0.0.1:$port")

    private fun consumeRequest(socket: java.net.Socket) {
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val next = input.read()
            check(next >= 0 && header.length < 16_384)
            header.append(next.toChar())
        }
        val size = header.lines().first { it.startsWith("Content-Length:", ignoreCase = true) }
            .substringAfter(':').trim().toInt()
        repeat(size) { check(input.read() >= 0) }
    }
}
