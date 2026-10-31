package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekSearchTest {
    @Test
    fun queryIsTrimmedToApiCodePointLimitWithoutSplittingSurrogates() {
        assertEquals("天气", webSearchQuery("  天气 \n"))
        val emoji = "😀".repeat(130)
        val query = webSearchQuery(emoji)
        assertEquals(128, query.codePointCount(0, query.length))
        assertEquals("😀".repeat(128), query)
        assertTrue(isValidWebSearchQuery(query))
        assertFalse(isValidWebSearchQuery(" \n "))
        assertFalse(isValidWebSearchQuery("a".repeat(129)))
    }

    @Test
    fun parserKeepsOnlyWebSourcesAndBoundsUntrustedFields() {
        val hits = parseWebSearchResponse(
            """{"results":[
              {"title":"  First\n title ","url":"https://example.com/a","summary":"long","snippet":"short","site_name":" Site ","date":"2026-06-01T00:00:00+00:00","extra":1},
              {"title":"Script","url":"javascript:alert(1)","summary":"x"},
              {"title":"Creds","url":"https://user:pass@example.com/","summary":"x"},
              {"title":"","url":"http://example.org/b","summary":" ","snippet":"fallback","site_name":null,"date":null},
              {"title":"c","url":"https://e.com/3","summary":"s"},
              {"title":"d","url":"https://e.com/4","summary":"s"},
              {"title":"e","url":"https://e.com/5","summary":"s"},
              {"title":"f","url":"https://e.com/6","summary":"s"}
            ]}""",
        )
        assertEquals(5, hits.size)
        assertEquals(WebSearchSource("First title", "https://example.com/a", "Site"), hits[0].source)
        assertEquals("long", hits[0].summary)
        assertEquals("2026-06-01T00:00:00+00:00", hits[0].date)
        assertEquals(WebSearchSource("http://example.org/b", "http://example.org/b", null), hits[1].source)
        assertEquals("fallback", hits[1].summary)
        assertEquals(listOf("https://e.com/3", "https://e.com/4", "https://e.com/5"), hits.drop(2).map { it.source.url })
        assertEquals(emptyList<WebSearchHit>(), parseWebSearchResponse("""{"results":[]}"""))
        assertThrows(SerializationException::class.java) { parseWebSearchResponse("""{"data":[]}""") }
        assertFalse(hits[0].toString().contains("example.com"))
    }

    @Test(timeout = 10_000)
    fun searchPostsOfficialContractOnceWithBearer() {
        val body = """{"results":[{"title":"T","url":"https://example.com/","summary":"S"}]}""".toByteArray()
        serve("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            .toByteArray() + body) { base, captured ->
            val result = runBlocking {
                HttpDeepSeekClient(requestTimeoutMillis = 2_000, officialBaseUrl = base)
                    .search(RootPilotConfig(apiKey = "synthetic-token", baseUrl = "$base/"), "query 天气")
            }
            assertEquals(WebSearchResult.Success(listOf(WebSearchHit(WebSearchSource("T", "https://example.com/"), "S"))), result)
            val (headers, request) = captured()
            assertTrue(headers.startsWith("POST /v1/tools/search HTTP/1.1"))
            assertTrue(headers.contains("Authorization: Bearer synthetic-token", ignoreCase = true))
            val json = Json.parseToJsonElement(request).jsonObject
            assertEquals(setOf("query", "count", "need_content"), json.keys)
            assertEquals("query 天气", json.getValue("query").jsonPrimitive.content)
            assertEquals(5, json.getValue("count").jsonPrimitive.int)
            assertFalse(json.getValue("need_content").jsonPrimitive.boolean)
        }
    }

    @Test(timeout = 10_000)
    fun errorStatusAndOversizedOrMalformedBodiesFailWithoutExposingBody() {
        for (response in listOf(
            "HTTP/1.1 402 Payment Required\r\nContent-Length: 30\r\n\r\nprivate synthetic-token body".toByteArray(),
            ("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + "x".repeat(MAX_WEB_SEARCH_RESPONSE_BYTES.toInt() + 10)).toByteArray(),
            "HTTP/1.1 200 OK\r\nContent-Length: 23\r\nConnection: close\r\n\r\nprivate synthetic-token".toByteArray(),
        )) {
            serve(response) { base, _ ->
                val result = runBlocking {
                    HttpDeepSeekClient(requestTimeoutMillis = 2_000, officialBaseUrl = base)
                        .search(RootPilotConfig(apiKey = "synthetic-token", baseUrl = base), "q")
                } as WebSearchResult.Failure
                assertFalse(result.message.contains("synthetic-token"))
                assertFalse(result.message.contains("private"))
            }
        }
    }

    @Test
    fun relayOrInvalidQueryNeverOpensConnection() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val base = "http://127.0.0.1:${listener.localPort}"
            val client = HttpDeepSeekClient(requestTimeoutMillis = 1_000, officialBaseUrl = "https://api.deepseek.com")
            val relay = runBlocking { client.search(RootPilotConfig(apiKey = "synthetic-token", baseUrl = base), "q") }
            assertEquals(ModelFailureCategory.CONFIGURATION, (relay as WebSearchResult.Failure).diagnostic.category)
            val official = HttpDeepSeekClient(requestTimeoutMillis = 1_000, officialBaseUrl = base)
            val blank = runBlocking { official.search(RootPilotConfig(apiKey = "synthetic-token", baseUrl = base), " ") }
            assertEquals(ModelFailureCategory.REQUEST_CONTRACT, (blank as WebSearchResult.Failure).diagnostic.category)
            listener.soTimeout = 200
            assertThrows(SocketTimeoutException::class.java) { listener.accept().use { error("unexpected request") } }
        }
    }

    private fun serve(response: ByteArray, block: (String, () -> Pair<String, String>) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3_000 }.use { server ->
            val captured = AtomicReference<Pair<String, String>>()
            val failure = AtomicReference<Throwable>()
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().apply { soTimeout = 3_000 }.use { socket ->
                        captured.set(readHttpRequest(socket.getInputStream()))
                        try {
                            socket.getOutputStream().write(response)
                            socket.getOutputStream().flush()
                        } catch (_: java.net.SocketException) {
                            // The client may abort an oversized body before it is fully written.
                        }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            block("http://127.0.0.1:${server.localPort}") { captured.get() }
            worker.join(3_000)
            failure.get()?.let { throw AssertionError("local server failed", it) }
            server.soTimeout = 200
            assertThrows(SocketTimeoutException::class.java) { server.accept().use { error("request replayed") } }
        }
    }

    private fun readHttpRequest(input: InputStream): Pair<String, String> {
        val headerBytes = ByteArrayOutputStream()
        var tail = 0
        do {
            val byte = input.read()
            check(byte != -1) { "incomplete headers" }
            headerBytes.write(byte)
            tail = (tail shl 8) or byte
        } while (tail != 0x0d0a0d0a)
        val headers = headerBytes.toString(Charsets.ISO_8859_1.name())
        val length = headers.lineSequence().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            check(count != -1) { "incomplete body" }
            offset += count
        }
        return headers to body.toString(Charsets.UTF_8)
    }
}
