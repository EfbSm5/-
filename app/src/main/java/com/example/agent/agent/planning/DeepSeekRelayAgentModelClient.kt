package com.example.agent.agent.planning

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class DeepSeekRelayAgentModelClient(
    context: Context,
    private val toolRegistry: ToolRegistry,
    private val baseUrl: String = "http://localhost:8765",
    private val model: String = "deepseek-v4-flash",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AgentModelClient {
    private val appContext = context.applicationContext
    private val schemaText by lazy {
        appContext.assets.open(SCHEMA_ASSET_NAME).bufferedReader().use { it.readText() }
    }
    private val promptBuilder by lazy { AgentPlannerPrompt(schemaText, toolRegistry) }

    override suspend fun generatePlanJson(userRequest: String): AgentModelResult =
        withContext(dispatcher) {
            val connection = try {
                (URL("${baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpURLConnection)
            } catch (_: IOException) {
                return@withContext AgentModelResult.Failure(
                    AgentModelFailure.Network("DeepSeek Relay 地址不可用"),
                )
            }

            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(buildRequest(userRequest))
                }

                val responseCode = connection.responseCode
                if (responseCode !in HTTP_SUCCESS_RANGE) {
                    return@withContext AgentModelResult.Failure(
                        responseCode.toModelFailure(),
                    )
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                AgentModelResult.Success(DeepSeekResponseParser.parseContent(responseBody))
            } catch (error: CancellationException) {
                throw error
            } catch (error: SocketTimeoutException) {
                AgentModelResult.Failure(AgentModelFailure.Network("DeepSeek Relay 请求超时"))
            } catch (error: kotlinx.serialization.SerializationException) {
                AgentModelResult.Failure(AgentModelFailure.Unknown("DeepSeek 返回格式无法理解"))
            } catch (error: IOException) {
                AgentModelResult.Failure(AgentModelFailure.Network("DeepSeek Relay 网络请求失败"))
            } finally {
                connection.disconnect()
            }
        }

    private fun buildRequest(userRequest: String): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put(
                        "role",
                        "system",
                    )
                    put(
                        "content",
                        "Return only valid JSON. Do not include reasoning or Markdown outside the JSON object.",
                    )
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", promptBuilder.build(userRequest))
                },
            )
        }
        putJsonObject("response_format") {
            put("type", "json_object")
        }
        put("stream", false)
        put("max_tokens", MAX_OUTPUT_TOKENS)
    }.toString()

    private fun Int.toModelFailure(): AgentModelFailure = when {
        this == HTTP_UNAUTHORIZED || this == HTTP_FORBIDDEN ->
            AgentModelFailure.Authentication("DeepSeek Relay 认证失败")
        this == HTTP_TOO_MANY_REQUESTS ->
            AgentModelFailure.RateLimited("DeepSeek Relay 请求过于频繁")
        this in 400..499 -> AgentModelFailure.InvalidRequest("DeepSeek Relay 请求不合法")
        this >= 500 -> AgentModelFailure.Server("DeepSeek 服务暂时不可用")
        else -> AgentModelFailure.Unknown("DeepSeek Relay 返回异常状态")
    }

    private companion object {
        const val SCHEMA_ASSET_NAME = "agent_plan.schema.json"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 120_000
        const val MAX_OUTPUT_TOKENS = 512
        val HTTP_SUCCESS_RANGE = 200..299
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
