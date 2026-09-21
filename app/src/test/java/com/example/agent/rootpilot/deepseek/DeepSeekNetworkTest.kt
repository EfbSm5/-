package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DeepSeekNetworkTest {
    @Test(timeout = 10_000)
    fun cancelWhileWaitingForHeaders_joinsAndClosesSocket() = assertCancellation(false)

    @Test(timeout = 10_000)
    fun cancelWhileWaitingForBody_joinsAndClosesSocket() = assertCancellation(true)

    @Test(timeout = 10_000)
    fun drippingBody_respectsCallerDeadline() {
        LocalServer { drip(it) }.use { server ->
            val caller = Executors.newSingleThreadExecutor { Thread(it, "network-test-deadline").apply { isDaemon = true } }
            try {
                val result = caller.submit<Boolean> {
                    runBlocking {
                        try {
                            withTimeout(500) { HttpDeepSeekClient().testConnection(server.config) }
                            false
                        } catch (_: CancellationException) {
                            true
                        }
                    }
                }
                assertTrue(result.get(1500, TimeUnit.MILLISECONDS))
                assertTrue("drip socket released", server.closed.await(1, TimeUnit.SECONDS))
                server.checkFailure()
            } finally {
                server.close()
                caller.shutdownNow()
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 10_000)
    fun drippingBody_hitsTotalRequestDeadlineAndClosesSocket() {
        val sent = AtomicInteger()
        LocalServer { drip(it, sent) }.use { server ->
            val start = System.nanoTime()
            val result = request(server, HttpDeepSeekClient(requestTimeoutMillis = 500))
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(DeepSeekActionResult.Failure("DeepSeek 请求超时"), result)
            assertTrue("deadline elapsed=$elapsed", elapsed in 350..1500)
            assertTrue("continuous body progress before deadline", sent.get() >= 5)
            assertTrue("deadline releases socket", server.closed.await(1, TimeUnit.SECONDS))
            server.checkFailure()
        }
    }

    @Test(timeout = 10_000)
    fun blockedHeaders_hitTotalRequestDeadline() = assertDeadline(false)

    @Test(timeout = 10_000)
    fun blockedBody_hitsTotalRequestDeadline() = assertDeadline(true)

    private fun assertDeadline(sendHeaders: Boolean) {
        LocalServer { socket -> if (sendHeaders) writeHeaders(socket) }.use { server ->
            assertEquals(
                DeepSeekActionResult.Failure("DeepSeek 请求超时"),
                request(server, HttpDeepSeekClient(requestTimeoutMillis = 500)),
            )
            assertTrue(server.closed.await(1, TimeUnit.SECONDS))
            server.checkFailure()
        }
    }

    @Test(timeout = 10_000)
    fun redirect_isNotFollowedAndBearerStaysAtOriginalServer() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
            LocalServer { socket ->
                socket.getOutputStream().write(
                    ("HTTP/1.1 307 Redirect\r\nLocation: http://127.0.0.1:${target.localPort}/other\r\n" +
                        "Content-Length: 10000\r\n\r\nprivate synthetic-token").toByteArray(),
                )
            }.use { server ->
                val result = request(server) as DeepSeekActionResult.Failure
                assertTrue(result.message.contains("307"))
                assertFalse(result.message.contains("synthetic-token"))
                assertTrue(server.headers.get().contains("Authorization: Bearer synthetic-token", true))
                target.soTimeout = 200
                assertThrows(SocketTimeoutException::class.java) { target.accept().use { error("redirect followed") } }
                assertTrue(server.closed.await(1, TimeUnit.SECONDS))
                server.checkFailure()
            }
        }
    }

    @Test(timeout = 10_000)
    fun retryableStatuses_areNotReplayedAndErrorBodiesAreNotRead() {
        for (status in listOf(401, 408, 503)) {
            LocalServer { socket ->
                socket.getOutputStream().write(
                    ("HTTP/1.1 $status Failure\r\nRetry-After: 0\r\nContent-Length: 10000\r\n\r\n" +
                        "private synthetic-token").toByteArray(),
                )
            }.use { server ->
                val result = request(server) as DeepSeekActionResult.Failure
                assertTrue("original status returned", result.message.contains(status.toString()))
                assertFalse(result.message.contains("synthetic-token"))
                assertFalse(result.message.contains("private"))
                assertTrue(server.closed.await(1, TimeUnit.SECONDS))
                server.assertNoAdditionalConnections()
                server.checkFailure()
            }
        }
    }

    @Test(timeout = 10_000)
    fun malformedSuccessBody_doesNotExposeResponse() {
        LocalServer { socket ->
            val body = "private synthetic-token".toByteArray()
            socket.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body,
            )
        }.use { server ->
            assertEquals(DeepSeekActionResult.Failure("DeepSeek 返回格式无法理解"), request(server))
            assertTrue(server.closed.await(1, TimeUnit.SECONDS))
            server.checkFailure()
        }
    }

    @Test(timeout = 10_000)
    fun disconnectedServer_isNotRetriedAndReturnsSanitizedFailure() {
        LocalServer { it.close() }.use { server ->
            assertEquals(DeepSeekActionResult.Failure("DeepSeek 网络请求失败"), request(server))
            server.assertNoAdditionalConnections()
        }
    }

    private fun request(server: LocalServer, client: HttpDeepSeekClient = HttpDeepSeekClient(requestTimeoutMillis = 1000)): DeepSeekActionResult {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "network-test-request").apply { isDaemon = true } }
        val result = executor.submit<DeepSeekActionResult> { runBlocking { client.testConnection(server.config) } }
        try {
            return result.get(2, TimeUnit.SECONDS)
        } finally {
            if (!result.isDone) server.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun writeHeaders(socket: Socket) {
        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n".toByteArray())
        socket.getOutputStream().flush()
    }

    private fun drip(socket: Socket, sent: AtomicInteger = AtomicInteger()) {
        writeHeaders(socket)
        try {
            repeat(60) {
                socket.getOutputStream().write(' '.code)
                socket.getOutputStream().flush()
                sent.incrementAndGet()
                Thread.sleep(50)
            }
        } catch (_: SocketException) {
            // The cancellation must close the socket while the response is still dripping.
        }
    }

    private fun assertCancellation(sendHeaders: Boolean) {
        LocalServer { socket ->
            if (sendHeaders) {
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n{".toByteArray(),
                )
                socket.getOutputStream().flush()
            }
        }.use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val canceller = Executors.newSingleThreadExecutor { Thread(it, "network-test-cancel").apply { isDaemon = true } }
            val outcome = AtomicReference<Any>()
            val job = scope.launch {
                try {
                    outcome.set(HttpDeepSeekClient().testConnection(server.config))
                } catch (cancelled: CancellationException) {
                    outcome.set(cancelled)
                    throw cancelled
                }
            }
            try {
                assertTrue("request reached server", server.ready.await(2, TimeUnit.SECONDS))
                // Give the client time to enter the header/body read; cancellation itself is
                // measured on another thread so a blocking cancellation handler cannot hide.
                Thread.sleep(100)
                canceller.submit { runBlocking { job.cancelAndJoin() } }.get(1, TimeUnit.SECONDS)
                assertTrue(job.isCancelled && job.isCompleted)
                assertTrue("cancellation must not return Failure", outcome.get() is CancellationException)
                assertTrue("peer socket released", server.closed.await(1, TimeUnit.SECONDS))
                server.checkFailure()
            } finally {
                server.close()
                scope.cancel()
                canceller.shutdownNow()
                assertTrue(canceller.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    private class LocalServer(private val respond: (Socket) -> Unit) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3000 }
        private val socket = AtomicReference<Socket>()
        private val failure = AtomicReference<Throwable>()
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val headers = AtomicReference<String>()
        val config = RootPilotConfig(apiKey = "synthetic-token", baseUrl = "http://127.0.0.1:${listener.localPort}")
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "network-test-server").apply { isDaemon = true } }
        private val worker = executor.submit {
            try {
                listener.accept().use { accepted ->
                    socket.set(accepted)
                    accepted.soTimeout = 3000
                    val input = accepted.getInputStream()
                    val bytes = ByteArrayOutputStream()
                    var tail = 0
                    do {
                        val byte = input.read()
                        check(byte != -1) { "incomplete headers" }
                        bytes.write(byte)
                        tail = (tail shl 8) or byte
                    } while (tail != 0x0d0a0d0a)
                    val requestHeaders = bytes.toString(Charsets.ISO_8859_1.name())
                    headers.set(requestHeaders)
                    val length = requestHeaders.lineSequence().first { it.startsWith("Content-Length:", true) }
                        .substringAfter(':').trim().toInt()
                    repeat(length) { check(input.read() != -1) }
                    respond(accepted)
                    ready.countDown()
                    try {
                        check(input.read() == -1) { "unexpected second request" }
                    } catch (_: SocketException) {
                        // A reset also proves that the peer closed the transport.
                    }
                    closed.countDown()
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        fun checkFailure() { failure.get()?.let { throw AssertionError("local server failed", it) } }

        fun assertNoAdditionalConnections() {
            listener.soTimeout = 200
            assertThrows(SocketTimeoutException::class.java) { listener.accept().use { error("request replayed") } }
        }

        override fun close() {
            listener.close()
            socket.get()?.close()
            worker.get(4, TimeUnit.SECONDS)
            executor.shutdownNow()
            check(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }
}
