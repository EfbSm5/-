package com.example.agent.rootpilot.deepseek

import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.ScreenshotFrame
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class DeepSeekVisionRequest(
    val config: RootPilotConfig,
    val frame: ScreenshotFrame,
    val history: List<String>,
    val remainingSteps: Int,
)

sealed interface DeepSeekActionResult {
    data class Success(val rawActionJson: String) : DeepSeekActionResult

    data class Failure(val message: String) : DeepSeekActionResult
}

interface DeepSeekClient {
    suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult
}

class HttpDeepSeekClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DeepSeekClient {
    override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult =
        withContext(dispatcher) {
            if (request.config.baseUrl.isBlank() || request.config.model.isBlank()) {
                return@withContext DeepSeekActionResult.Failure("API Base URL 和模型名称不能为空")
            }

            val connection = try {
                URL("${request.config.baseUrl.trimEnd('/')}/chat/completions")
                    .openConnection() as HttpURLConnection
            } catch (_: IOException) {
                return@withContext DeepSeekActionResult.Failure("DeepSeek Relay 地址不可用")
            } catch (_: IllegalArgumentException) {
                return@withContext DeepSeekActionResult.Failure("DeepSeek Relay 地址不可用")
            }

            val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion {
                connection.disconnect()
            }
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (request.config.apiKey.isNotBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer ${request.config.apiKey}")
                }
                connection.outputStream.bufferedWriter().use { writer ->
                    writer.write(buildRequest(request))
                }

                val responseCode = connection.responseCode
                if (responseCode !in HTTP_SUCCESS_RANGE) {
                    return@withContext DeepSeekActionResult.Failure(
                        "DeepSeek 请求失败，HTTP $responseCode",
                    )
                }
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                DeepSeekActionResult.Success(parseMessageContent(responseBody))
            } catch (error: CancellationException) {
                throw error
            } catch (_: SocketTimeoutException) {
                DeepSeekActionResult.Failure("DeepSeek 请求超时")
            } catch (_: SerializationException) {
                DeepSeekActionResult.Failure("DeepSeek 返回格式无法理解")
            } catch (_: IOException) {
                DeepSeekActionResult.Failure("DeepSeek 网络请求失败")
            } finally {
                cancellationHandle.dispose()
                connection.disconnect()
            }
        }

    private fun buildRequest(request: DeepSeekVisionRequest): String = buildJsonObject {
        put("model", request.config.model)
        putJsonObject("thinking") {
            put("type", "disabled")
        }
        putJsonObject("response_format") {
            put("type", "json_object")
        }
        put("stream", false)
        put("max_tokens", MAX_OUTPUT_TOKENS)
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", buildUserPrompt(request))
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", request.frame.dataUrl)
                                    put("detail", "low")
                                }
                            },
                        )
                    }
                },
            )
        }
    }.toString()

    private fun buildUserPrompt(request: DeepSeekVisionRequest): String = buildString {
        appendLine("根据当前 Android 截图执行用户任务。")
        appendLine("只返回一个动作 JSON，不要 Markdown、解释或 Shell 命令。")
        appendLine("用户任务：${request.config.task}")
        appendLine("视觉输入尺寸：${request.frame.width}x${request.frame.height}")
        appendLine("物理屏幕尺寸：${request.frame.physicalWidth}x${request.frame.physicalHeight}")
        appendLine(
            "屏幕方向：${if (request.frame.physicalWidth >= request.frame.physicalHeight) "landscape" else "portrait"}",
        )
        appendLine("剩余最大步骤：${request.remainingSteps}")
        appendLine("最近动作：")
        if (request.history.isEmpty()) {
            appendLine("无")
        } else {
            request.history.takeLast(MAX_HISTORY_ITEMS).forEach { appendLine(it) }
        }
    }

    private fun parseMessageContent(rawResponse: String): String {
        val response = JSON.decodeFromString<ChatResponse>(rawResponse)
        return response.choices
            .firstOrNull()
            ?.message
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw SerializationException("message.content 为空")
    }

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice> = emptyList(),
    )

    @Serializable
    private data class Choice(
        val message: Message,
    )

    @Serializable
    private data class Message(
        val content: String? = null,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 120_000
        const val MAX_OUTPUT_TOKENS = 512
        const val MAX_HISTORY_ITEMS = 6
        val HTTP_SUCCESS_RANGE = 200..299
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
        const val SYSTEM_PROMPT = """
            You are a cautious Android UI operator.
            Return exactly one JSON object and nothing else. Never return Markdown, explanations,
            shell commands, press_back, description, or any action name outside this protocol.
            Allowed actions and fields are exactly:
            {"action":"tap","x":0,"y":0,"reason":"short reason"}
            {"action":"swipe","x1":0,"y1":0,"x2":0,"y2":0,"duration_ms":300,"reason":"short reason"}
            {"action":"open_app","package_name":"com.android.settings","reason":"short reason"}
            {"action":"type","text":"safe ASCII text","reason":"short reason"}
            {"action":"key","key":"BACK","reason":"short reason"}
            {"action":"wait","duration_ms":500,"reason":"short reason"}
            {"action":"ask_user","message":"why user must take over"}
            {"action":"finish","success":true,"message":"result"}
            Coordinates must be integers from 0 to 1000. Use only BACK, HOME, or ENTER for key.
            Type text must contain only letters, digits, dot, underscore, at-sign, plus, or hyphen.
            The only allowed open_app package_name is com.android.settings. Prefer open_app for Android Settings
            instead of key HOME or guessing a launcher icon.
            Ask the user before passwords, verification codes, payment, deletion, authorization,
            biometric actions, sending messages, or other sensitive operations.
            Use ask_user only when human takeover is genuinely required; use key, tap, and swipe
            for ordinary navigation that is visible in the current or next screenshot.
            If the current screenshot is the RootPilot control panel, never use key BACK because it
            closes the agent. Use key HOME to leave RootPilot, then inspect the next screenshot.
            Use key BACK only after the screenshot shows the target app or another non-RootPilot page.
        """
    }
}
