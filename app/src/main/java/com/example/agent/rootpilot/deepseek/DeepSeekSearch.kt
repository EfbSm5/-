package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class WebSearchSource(val title: String, val url: String, val siteName: String? = null) {
    override fun toString() = "WebSearchSource(<redacted>)"
}

data class WebSearchHit(val source: WebSearchSource, val summary: String, val date: String? = null) {
    override fun toString() = "WebSearchHit(summaryLength=${summary.length})"
}

sealed interface WebSearchResult {
    data class Success(val hits: List<WebSearchHit>) : WebSearchResult

    data class Failure(val message: String, val diagnostic: ModelFailure = ModelFailure()) : WebSearchResult {
        override fun toString() = "WebSearchResult.Failure(diagnostic=$diagnostic)"
    }
}

/** DeepSeek official Search API; one call is one billed request and is never retried. */
interface DeepSeekSearchClient {
    suspend fun search(config: RootPilotConfig, query: String): WebSearchResult
}

internal const val WEB_SEARCH_RESULT_COUNT = 5
internal const val MAX_WEB_SEARCH_QUERY_CODE_POINTS = 128
internal const val MAX_WEB_SEARCH_RESPONSE_BYTES = 262_144L
private const val MAX_TITLE_CHARS = 300
private const val MAX_URL_CHARS = 2_048
private const val MAX_SITE_CHARS = 100
private const val MAX_SUMMARY_CHARS = 1_200
private const val MAX_DATE_CHARS = 40
private val WHITESPACE = Regex("\\s+")

/** The API counts query length in Unicode code points; never split a surrogate pair. */
internal fun webSearchQuery(prompt: String): String {
    val trimmed = prompt.trim()
    if (trimmed.codePointCount(0, trimmed.length) <= MAX_WEB_SEARCH_QUERY_CODE_POINTS) return trimmed
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_WEB_SEARCH_QUERY_CODE_POINTS)).trimEnd()
}

internal fun isValidWebSearchQuery(query: String): Boolean = query.isNotBlank() &&
    query.codePointCount(0, query.length) <= MAX_WEB_SEARCH_QUERY_CODE_POINTS

/** Results are untrusted remote data; only http(s) sources without credentials are kept. */
internal fun parseWebSearchResponse(raw: String): List<WebSearchHit> =
    SEARCH_JSON.decodeFromString<SearchResponse>(raw).results.asSequence().mapNotNull { item ->
        val url = item.url.trim().takeIf { it.length <= MAX_URL_CHARS && isWebUrl(it) } ?: return@mapNotNull null
        WebSearchHit(
            WebSearchSource(
                title = item.title.singleLine().ifEmpty { url }.take(MAX_TITLE_CHARS),
                url = url,
                siteName = item.siteName?.singleLine()?.takeIf { it.isNotEmpty() }?.take(MAX_SITE_CHARS),
            ),
            summary = item.summary.ifBlank { item.snippet }.trim().take(MAX_SUMMARY_CHARS),
            date = item.date?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DATE_CHARS),
        )
    }.take(WEB_SEARCH_RESULT_COUNT).toList()

private fun String.singleLine() = trim().replace(WHITESPACE, " ")

private fun isWebUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
} catch (_: Exception) {
    false
}

private val SEARCH_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

@Serializable
private class SearchResponse(val results: List<SearchItem>)

@Serializable
private class SearchItem(
    val url: String,
    val title: String = "",
    val summary: String = "",
    val snippet: String = "",
    @SerialName("site_name") val siteName: String? = null,
    val date: String? = null,
)
