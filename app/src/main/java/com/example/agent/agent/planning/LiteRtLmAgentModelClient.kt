package com.example.agent.agent.planning

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiteRtLmAgentModelClient(
    context: Context,
    private val modelFile: File,
    private val backends: List<Backend> = listOf(Backend.CPU()),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AgentModelClient, AutoCloseable {
    private val appContext = context.applicationContext
    private val engineLock = Any()
    private var engine: Engine? = null
    private var selectedBackend: Backend? = null
    private val schemaText by lazy {
        appContext.assets.open(SCHEMA_ASSET_NAME).bufferedReader().use { it.readText() }
    }

    override suspend fun generatePlanJson(userRequest: String): AgentModelResult = withContext(dispatcher) {
        if (!modelFile.isFile || modelFile.length() == 0L) {
            return@withContext AgentModelResult.Failure(
                AgentModelFailure.Unknown("端侧模型文件未安装"),
            )
        }

        try {
            synchronized(engineLock) {
                val activeEngine = getOrCreateEngine()
                activeEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                    ),
                ).use { conversation ->
                    val response = conversation.sendMessage(buildPrompt(userRequest))
                    val text = response.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString(separator = "") { it.text }
                    AgentModelResult.Success(text)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AgentModelResult.Failure(
                AgentModelFailure.Unknown("端侧模型推理失败"),
            )
        }
    }

    override fun close() {
        synchronized(engineLock) {
            engine?.close()
            engine = null
            selectedBackend = null
        }
    }

    private fun getOrCreateEngine(): Engine {
        engine?.let { return it }

        var lastError: Exception? = null
        for (candidateBackend in backends) {
            try {
                val newEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = candidateBackend,
                        cacheDir = appContext.cacheDir.absolutePath,
                    ),
                )
                newEngine.initialize()
                engine = newEngine
                selectedBackend = candidateBackend
                Log.i(TAG, "LiteRT-LM backend initialized: $candidateBackend")
                return newEngine
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "LiteRT-LM backend failed: $candidateBackend", error)
            }
        }
        throw lastError ?: IllegalStateException("没有可用的 LiteRT-LM backend")
    }

    private fun buildPrompt(userRequest: String): String = """
        $PLANNER_PROMPT

        JSON Schema:
        $schemaText

        User request:
        <user_request>
        $userRequest
        </user_request>
    """.trimIndent()

    private companion object {
        const val TAG = "LiteRtLmAgentModelClient"
        const val SCHEMA_ASSET_NAME = "agent_plan.schema.json"
        const val SYSTEM_INSTRUCTION = """
            You are an Android task planner.
            Return only one JSON object. Do not use Markdown fences or explanations.
            The JSON must follow the provided schema. Never invent tool results.
        """
        const val PLANNER_PROMPT = """
            Convert the user request into a safe, not-yet-executed Agent plan.
            Use ask_user when important information is missing.
        """
    }
}
