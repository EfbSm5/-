package com.example.agent.rootpilot

import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.action.ActionParseResult
import com.example.agent.rootpilot.action.ActionParser
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.deepseek.ModelProtocolException
import com.example.agent.rootpilot.deepseek.ModelProtocolReason
import com.example.agent.rootpilot.deepseek.buildDeviceDecisionRequest
import com.example.agent.rootpilot.deepseek.readDeepSeekToolStream
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.log.TraceActionType
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Three opt-in transport-only comparisons; no Service command, tool dispatch or device action. */
@RunWith(AndroidJUnit4::class)
class VirtualCalculatorModelBudgetProbeInstrumentedTest {
    @Test
    fun comparesThreeBudgetsWithoutExecutingModelOutput() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveVirtualBudgetComparison") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = RootPilotService.uiState.value
        check(!state.running && state.pendingAction == null && state.status == RootPilotStatus.FAILED && state.step == 1,
            "prior_state_required")
        val history = RootPilotService.historyState(context)
        val originalHistory = history.value
        check(originalHistory.error == null, "history_required")
        val id = args.getString("priorServiceRunId").orEmpty()
        check(UUID_REGEX.matches(id), "prior_id_invalid")
        val prior = originalHistory.records.singleOrNull { it.id == id } ?: fail("prior_record_missing")
        check(prior == originalHistory.records.maxByOrNull { it.startedAtEpochMs } &&
            prior.status == RunHistoryStatus.FAILED && !prior.eventsTruncated &&
            prior.events.lastOrNull()?.modelFailure?.protocolReason == ModelProtocolReason.OUTPUT_LIMIT,
            "prior_failure_required")
        val executions = prior.events.filter { it.stage == TraceStage.EXECUTION }
        check(executions.size == 4 && executions.map { it.actionType } == listOf(
            TraceActionType.OPEN_APP, TraceActionType.OPEN_APP, TraceActionType.TAP, TraceActionType.TAP) &&
            executions.map { it.event } == listOf(TraceEvent.START, TraceEvent.RESULT, TraceEvent.START, TraceEvent.RESULT) &&
            executions.map { it.status } == listOf(TraceStatus.STARTED, TraceStatus.SUCCESS, TraceStatus.STARTED, TraceStatus.SUCCESS),
            "prior_execution_mismatch")
        val metadata = metadata(context.cacheDir, args.getString("priorServiceAcceptanceDirectory").orEmpty(),
            "virtual-service-acceptance-")
        check(metadata["runId"]?.jsonPrimitive?.contentOrNull == id &&
            metadata["firstKeyIndex"]?.jsonPrimitive?.intOrNull == 0 &&
            metadata["currentRunApprovedKeyCount"]?.jsonPrimitive?.intOrNull == 1 &&
            listOf("cleanupConfirmed", "runEnd", "displayGone", "imeUnchanged", "configRestored", "allowlistBytesRestored")
                .all { metadata[it]?.jsonPrimitive?.booleanOrNull == true }, "prior_cleanup_required")
        val inspection = metadata(context.cacheDir, args.getString("inspectionDirectory").orEmpty(), "virtual-display-acceptance-")
        check(inspection["passed"]?.jsonPrimitive?.booleanOrNull == true &&
            inspection["networkUsed"]?.jsonPrimitive?.booleanOrNull == false &&
            inspection["executedTaps"]?.jsonPrimitive?.intOrNull == 0 &&
            inspection["cleanupConfirmed"]?.jsonPrimitive?.booleanOrNull == true, "readonly_inspection_required")
        val samples = inspection["expressionSamples"]?.jsonArray ?: fail("inspection_samples_required")
        val lowest = samples.map { it.jsonObject }.maxByOrNull { it["bottom"]?.jsonPrimitive?.intOrNull ?: -1 }
        check(lowest?.get("knownExpression")?.jsonPrimitive?.contentOrNull == "ONE", "current_one_required")
        val manager = context.getSystemService(DisplayManager::class.java) ?: fail("display_manager_required")
        fun unchanged() {
            check(RootPilotService.uiState.value === state && history.value == originalHistory &&
                manager.displays.none { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) }, "environment_changed")
        }
        unchanged()
        val frame = state.frame ?: fail("retained_frame_required")
        check(frame.physicalWidth == 1080 && frame.physicalHeight == 1920 && frame.width == 720 && frame.height == 1280 &&
            frame.bytes.size in 8..1_048_576 && frame.dataUrl == "data:image/jpeg;base64," + Base64.encodeToString(frame.bytes, Base64.NO_WRAP),
            "retained_frame_invalid")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size, options)
        check(options.outMimeType == "image/jpeg" && options.outWidth == 720 && options.outHeight == 1280, "frame_decode_invalid")
        val action = state.lastAction as? RootPilotAction.Tap ?: fail("retained_action_required")
        check(action.x == 319 && action.y == 838 && state.logs.any { line ->
            val event = try { Json.parseToJsonElement(line).jsonObject } catch (_: Exception) { return@any false }
            event["runId"]?.jsonPrimitive?.contentOrNull == id && event["step"]?.jsonPrimitive?.intOrNull == 1 &&
                event["stage"]?.jsonPrimitive?.contentOrNull == "screenshot" &&
                event["event"]?.jsonPrimitive?.contentOrNull == "result" && event["status"]?.jsonPrimitive?.contentOrNull == "success"
        }, "retained_frame_binding_required")
        val saved = try { RootPilotApiConfigStore.create(context).read() } catch (_: Exception) { null }
            ?: fail("saved_config_required")
        check(saved.baseUrl.trimEnd('/') == "https://api.deepseek.com" && saved.apiKey.isNotBlank(), "endpoint_required")

        // Reflection is limited to pure prompt builders, never lifecycle or execution internals.
        // No production visibility/configuration change is needed for this opt-in comparison.
        val task = VirtualDisplayServiceAcceptanceInstrumentedTest::class.java.getField("TASK").get(null) as String
        val request = DeepSeekVisionRequest(
            config = saved.applyTo(RootPilotConfig(task = task, allowScreenUpload = true,
                executionDisplay = ExecutionDisplay.VIRTUAL, virtualDisplayStartPackage = "com.miui.calculator")),
            frame = frame, history = listOf("step=0 action=tap(319,838) result=success"), remainingSteps = 19, step = 1,
            availableApps = listOf(RootPilotApp("com.miui.calculator", "计算器", "com.miui.calculator.cal.CalculatorActivity")),
            // The released display's live observation is unavailable; never fabricate a session.
            observation = null, observationStartedAtMillis = null,
        )
        val client = HttpDeepSeekClient()
        val promptMethod = HttpDeepSeekClient::class.java.getDeclaredMethod("buildUserPrompt",
            DeepSeekVisionRequest::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val prompt = promptMethod.invoke(client, request, true) as String
        val system = HttpDeepSeekClient::class.java.getField("SYSTEM_PROMPT").get(null) as String
        val base = Json.parseToJsonElement(buildDeviceDecisionRequest(request, emptyList(), true, system, prompt)).jsonObject
        check(base["max_tokens"] == JsonPrimitive(4096) && base["reasoning_effort"] == JsonPrimitive("high"), "baseline_request_changed")
        val variants = listOf("high" to 4096, "low" to 4096, "high" to 8192)
        val bodies = variants.map { (effort, tokens) ->
            JsonObject(base.toMutableMap().apply { put("reasoning_effort", JsonPrimitive(effort)); put("max_tokens", JsonPrimitive(tokens)) })
        }
        val changedKeys = setOf("reasoning_effort", "max_tokens")
        check(bodies.all { body -> body.filterKeys { it !in changedKeys } == base.filterKeys { it !in changedKeys } },
            "comparison_context_changed")
        val directory = File(context.cacheDir, "virtual-model-budget-probe-${UUID.randomUUID()}")
        check(directory.mkdir(), "artifact_directory_failed")
        val outcomes = mutableListOf<JsonObject>()
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        try {
            variants.forEachIndexed { index, (effort, tokens) ->
                unchanged()
                if (index > 0) delay(10_000)
                val outcome = withContext(Dispatchers.IO) {
                    unchanged()
                    compare(http, saved.apiKey, bodies[index].toString(), effort, tokens)
                }
                outcomes += outcome
                unchanged()
                val report = buildJsonObject {
                    put("source", "retained_virtual_frame_reconstructed_fixed_step")
                    put("originalWireReplay", false); put("liveScreenObservationIncluded", false)
                    put("modelRequests", outcomes.size); put("maxModelRequests", 3)
                    put("deviceActions", 0); put("toolDispatches", 0); put("serviceCommands", 0)
                    put("configurationChanged", false); put("sameContextAcrossVariants", true)
                    putJsonArray("outcomes") { outcomes.forEach(::add) }
                }.toString()
                directory.resolve("metadata.json").writeText(report)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("artifactDirectoryName", directory.name)
                    putString("modelBudgetComparison", report)
                })
                check(outcome["httpStatus"]?.jsonPrimitive?.intOrNull !in setOf(401, 402, 403), "authorization_or_quota_required")
            }
        } finally {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
        unchanged()
    }

    private suspend fun compare(http: OkHttpClient, key: String, body: String, effort: String, tokens: Int): JsonObject {
        val payload = body.toRequestBody("application/json; charset=utf-8".toMediaType())
        val call = http.newCall(Request.Builder().url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $key").post(object : RequestBody() {
                override fun contentType() = payload.contentType()
                override fun contentLength() = payload.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = payload.writeTo(sink)
            }).build())
        val started = SystemClock.elapsedRealtime()
        var reason = "none"
        var parsed: Boolean? = null
        var status: Int? = null
        try {
            call.execute().use { response ->
                try {
                    status = response.code
                    if (response.code !in 200..299) reason = "http"
                    else {
                        val result = readDeepSeekToolStream(response.body?.source() ?: fail("response_source_missing")) {}
                        if (result.toolCalls.isNotEmpty()) reason = "tool_call_not_dispatched"
                        else {
                            parsed = ActionParser().parse(result.content) is ActionParseResult.Success
                            if (parsed != true) reason = "action_parse_failed"
                        }
                    }
                } finally {
                    // Abort before response.close can drain an unfinished or invalid stream.
                    call.cancel()
                }
            }
        } catch (error: ModelProtocolException) {
            reason = "response_protocol_" + error.reason.name.lowercase()
        } catch (_: SerializationException) {
            reason = "response_protocol_unspecified"
        } catch (_: InterruptedIOException) {
            reason = "timeout"
        } catch (_: IOException) {
            reason = "network"
        } finally {
            call.cancel()
        }
        return buildJsonObject {
            put("effort", effort); put("maxTokens", tokens); put("elapsedMs", SystemClock.elapsedRealtime() - started)
            put("reasonCode", reason); put("actionParsed", parsed); put("httpStatus", status)
        }
    }

    private fun metadata(parent: File, name: String, prefix: String): JsonObject {
        check(name.startsWith(prefix) && UUID_REGEX.matches(name.removePrefix(prefix)), "receipt_path_invalid")
        val file = parent.resolve(name).resolve("metadata.json")
        check(file.isFile && file.length() in 1..65_536, "receipt_size_invalid")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private companion object {
        val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun check(value: Boolean, code: String) { if (!value) fail(code) }
        fun fail(code: String): Nothing = throw AssertionError(code)
    }
}
