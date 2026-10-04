package com.example.agent.rootpilot

import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.action.ActionParseResult
import com.example.agent.rootpilot.action.ActionParser
import com.example.agent.rootpilot.deepseek.*
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.log.*
import com.example.agent.rootpilot.model.*
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Receipt-scoped transport probe. It never captures a screen or dispatches returned actions/tools. */
@RunWith(AndroidJUnit4::class)
class VirtualCalculatorEqualityEffortProbeInstrumentedTest {
    @Test
    fun comparesRetainedEqualityFailureWithoutDeviceActions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEqualityEffortProbe") == "true")
        try { diagnose() }
        catch (error: ProbeFailure) { throw error }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { reject("probe_unexpected") }
    }

    private suspend fun diagnose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = RootPilotService.uiState.value
        requireFixed(Process.myPid() == 27899 && !state.running && state.pendingAction == null &&
            state.status == RootPilotStatus.FAILED && state.step == 6 &&
            (state.lastAction as? RootPilotAction.Tap)?.let { it.x == 514 && it.y == 753 } == true,
            "retained_state_required")
        val history = RootPilotService.historyState(context)
        val originalHistory = history.value
        val prior = originalHistory.records.singleOrNull { it.id == PRIOR_ID } ?: reject("prior_record_missing")
        requireFixed(originalHistory.error == null && prior == originalHistory.records.maxByOrNull { it.startedAtEpochMs } &&
            prior.status == RunHistoryStatus.FAILED && !prior.eventsTruncated && prior.events.size == 62 &&
            prior.events.all { it.runId == PRIOR_ID }, "prior_identity_required")
        val expected = listOf(TraceActionType.OPEN_APP to 0) + (0..5).map { TraceActionType.TAP to it }
        val executions = prior.events.filter { it.stage == TraceStage.EXECUTION }
        requireFixed(executions.size == 14 && executions.chunked(2).zip(expected).all { (pair, action) ->
            pair.all { it.actionType == action.first && it.step == action.second } &&
                pair.map { it.event } == listOf(TraceEvent.START, TraceEvent.RESULT) &&
                pair.map { it.status } == listOf(TraceStatus.STARTED, TraceStatus.SUCCESS)
        } && prior.events.filter { it.event == TraceEvent.CONFIRMED }.map { it.actionType to it.step } == expected,
            "six_key_receipts_required")
        requireFixed(prior.events.none { it.event in setOf(TraceEvent.TODO_SAVED, TraceEvent.READ_UI_TREE,
            TraceEvent.READ_ACTIVITY_STACK) }, "unsupported_side_effect")
        val last = prior.events.takeLast(4)
        requireFixed(last.map { it.stage to it.event } == listOf(TraceStage.SCREENSHOT to TraceEvent.RESULT,
            TraceStage.MODEL to TraceEvent.START, TraceStage.MODEL to TraceEvent.RESULT, TraceStage.MODEL to TraceEvent.RUN_END) &&
            last.all { it.step == 6 } && last.first().status == TraceStatus.SUCCESS &&
            last.takeLast(2).all { it.status == TraceStatus.FAILED &&
                it.modelFailure?.protocolReason == ModelProtocolReason.OUTPUT_LIMIT } &&
            last[2].modelUsage?.let { it.completionTokens == 8192L && it.reasoningTokens == 8192L &&
                it.observedContentChars == 0L && !it.usageMalformed && it.finishReason == ModelFinishReason.LENGTH } == true,
            "thinking_exhaustion_required")
        // The live log tail binds the retained in-memory frame to the latest completed production run.
        val liveTail = state.logs.takeLast(4).map { Json.parseToJsonElement(it).jsonObject }
        requireFixed(liveTail.size == 4 && liveTail.zip(last).all { (line, event) ->
            line["runId"] == JsonPrimitive(PRIOR_ID) && line["step"] == JsonPrimitive(event.step) &&
                line["stage"] == JsonPrimitive(event.stage.name.lowercase()) &&
                line["event"] == JsonPrimitive(event.event.name.lowercase()) &&
                line["elapsedMs"] == JsonPrimitive(event.elapsedMs)
        }, "live_frame_run_mismatch")
        val receipt = File(context.cacheDir, PRIOR_DIRECTORY).resolve("metadata.json")
        requireFixed(receipt.isFile && receipt.length() in 1..16_384, "receipt_required")
        val metadata = Json.parseToJsonElement(receipt.readText()).jsonObject
        requireFixed(metadata["runId"] == JsonPrimitive(PRIOR_ID) &&
            metadata["failure"] == JsonPrimitive("TERMINAL_NOT_COMPLETED") &&
            metadata["cleanupReason"] == JsonPrimitive("none") &&
            metadata["executionSource"] == JsonPrimitive("production_service") &&
            metadata["firstKeyIndex"] == JsonPrimitive(0) && metadata["currentRunApprovedKeyCount"] == JsonPrimitive(6) &&
            listOf("sessionIdentityVerified", "bootstrapOpenApproved", "initialExpressionKnown", "runEnd", "displayGone",
                "cleanupConfirmed", "imeUnchanged", "configRestored", "allowlistBytesRestored")
                .all { metadata[it] == JsonPrimitive(true) } &&
            listOf("passed", "sevenKeysApproved", "result5535Observed", "remainingKeysApproved")
                .all { metadata[it] == JsonPrimitive(false) }, "receipt_mismatch")
        val frame = state.frame ?: reject("retained_frame_required")
        requireFixed(frame.bytes.size in 8..1_048_576 && frame.width == 720 && frame.height == 1280 &&
            frame.physicalWidth == 1080 && frame.physicalHeight == 1920 &&
            frame.dataUrl == "data:image/jpeg;base64," + Base64.encodeToString(frame.bytes, Base64.NO_WRAP), "frame_invalid")
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size, dimensions)
        requireFixed(dimensions.outMimeType == "image/jpeg" && dimensions.outWidth == 720 && dimensions.outHeight == 1280,
            "frame_decode_invalid")
        val digest = MessageDigest.getInstance("SHA-256").digest(frame.bytes).toList()
        val manager = context.getSystemService(DisplayManager::class.java) ?: reject("display_manager_required")
        fun unchanged() = requireFixed(RootPilotService.uiState.value === state && history.value == originalHistory &&
            MessageDigest.getInstance("SHA-256").digest(frame.bytes).toList() == digest &&
            manager.displays.none { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) } &&
            !context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists() &&
            !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists(), "environment_changed")
        unchanged()
        val saved = try { RootPilotApiConfigStore.create(context).read() } catch (_: Exception) { null }
            ?: reject("saved_config_required")
        requireFixed(saved.baseUrl.trimEnd('/') == "https://api.deepseek.com" && saved.apiKey.isNotBlank(), "endpoint_required")
        val request = DeepSeekVisionRequest(saved.applyTo(RootPilotConfig(task = TASK, allowScreenUpload = true,
            executionDisplay = ExecutionDisplay.VIRTUAL, virtualDisplayStartPackage = "com.miui.calculator")),
            frame, listOf(317 to 827, 499 to 827, 681 to 827, 854 to 680, 319 to 753, 514 to 753).mapIndexed { index, xy ->
                "step=$index action=tap(${xy.first},${xy.second}) result=success"
            }, remainingSteps = 14, step = 6,
            availableApps = listOf(RootPilotApp("com.miui.calculator", "计算器", "com.miui.calculator.cal.CalculatorActivity")),
            observation = null, observationStartedAtMillis = null)
        val promptBuilder = HttpDeepSeekClient::class.java.getDeclaredMethod("buildUserPrompt",
            DeepSeekVisionRequest::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val prompt = promptBuilder.invoke(HttpDeepSeekClient(), request, true) as String
        val system = HttpDeepSeekClient::class.java.getField("SYSTEM_PROMPT").get(null) as String
        val high = Json.parseToJsonElement(buildDeviceDecisionRequest(request, emptyList(), true, system, prompt)).jsonObject
        requireFixed(high["max_tokens"] == JsonPrimitive(8192) && high["reasoning_effort"] == JsonPrimitive("high"),
            "baseline_changed")
        val bodies = variants(high)
        // Persistent, receipt-specific authorization is consumed even if this process dies mid-request.
        val directory = File(context.noBackupFilesDir, "equality-effort-probe-$PRIOR_ID")
        requireFixed(directory.mkdir(), "authorization_already_attempted")
        val outcomes = mutableListOf<JsonObject>()
        var attempts = 0
        fun publish() {
            val report = buildJsonObject {
                put("priorRunId", PRIOR_ID); put("source", "retained_step6_frame_reconstructed_request")
                put("originalWireReplay", false); put("liveScreenObservationIncluded", false)
                put("modelRequestsAttempted", attempts); put("maxModelRequests", 2)
                put("deviceActions", 0); put("toolDispatches", 0); put("serviceCommands", 0)
                put("configurationChanged", false)
                putJsonArray("outcomes") { outcomes.forEach(::add) }
            }.toString()
            directory.resolve("metadata.json").writeText(report)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("artifactDirectoryName", directory.name); putString("equalityEffortProbe", report)
            })
        }
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        try {
            for ((index, body) in bodies.withIndex()) {
                unchanged()
                if (index > 0) {
                    if (!allowsSecond(outcomes.single())) break
                    delay(10_000)
                }
                val outcome = withContext(Dispatchers.IO) {
                    unchanged()
                    requireFixed(directory.resolve("request-${index + 1}.attempted").createNewFile(), "request_already_attempted")
                    attempts++
                    publish()
                    unchanged()
                    compare(http, saved.apiKey, body.toString(), if (index == 0) "low" else "none")
                }
                outcomes += outcome
                publish()
                unchanged()
            }
        } finally {
            http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        }
        unchanged()
    }

    private suspend fun compare(http: OkHttpClient, key: String, body: String, effort: String): JsonObject {
        val payload = body.toRequestBody("application/json; charset=utf-8".toMediaType())
        val call = http.newCall(Request.Builder().url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $key").post(object : RequestBody() {
                override fun contentType() = payload.contentType()
                override fun contentLength() = payload.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = payload.writeTo(sink)
            }).build())
        val started = SystemClock.elapsedRealtime()
        val usage = ModelUsageCollector()
        var reason = "none"
        var status: Int? = null
        var parsed: Boolean? = null
        var tap: Boolean? = null
        try {
            call.execute().use { response ->
                try {
                    status = response.code
                    if (status !in 200..299) reason = "http"
                    else {
                        val result = readDeepSeekToolStream(response.body?.source() ?: reject("response_source_missing"), usage) {}
                        if (result.toolCalls.isNotEmpty()) reason = "tool_call_not_dispatched"
                        else {
                            val action = ActionParser().parse(result.content)
                            parsed = action is ActionParseResult.Success
                            tap = action is ActionParseResult.Success && action.action is RootPilotAction.Tap
                            if (parsed == false) reason = "action_parse_failed"
                        }
                    }
                } finally { call.cancel() }
            }
        } catch (error: ModelProtocolException) { reason = "response_protocol_" + error.reason.name.lowercase()
        } catch (_: SerializationException) { reason = "response_protocol_unspecified"
        } catch (_: InterruptedIOException) { reason = "timeout"
        } catch (_: IOException) { reason = "network"
        } finally { call.cancel() }
        return buildJsonObject {
            put("effort", effort); put("maxTokens", 8192); put("elapsedMs", SystemClock.elapsedRealtime() - started)
            put("reasonCode", reason); put("httpStatus", status); put("actionParsed", parsed); put("tapParsed", tap)
            put("usage", Json.encodeToJsonElement(usage.snapshot()))
        }
    }

    @Test fun variantsKeepContextAndBudgetIdentical() {
        val high = buildJsonObject {
            put("max_tokens", 8192); put("reasoning_effort", "high")
            putJsonObject("thinking") { put("type", "enabled") }
            put("messages", "synthetic"); put("tool_choice", "auto")
        }
        val (low, none) = variants(high)
        assertEquals(JsonPrimitive("low"), low["reasoning_effort"])
        assertEquals(high["thinking"], low["thinking"])
        assertEquals(JsonPrimitive("disabled"), none["thinking"]?.jsonObject?.get("type"))
        assertFalse(none.containsKey("reasoning_effort"))
        val controls = setOf("thinking", "reasoning_effort")
        assertEquals(high.filterKeys { it !in controls }, low.filterKeys { it !in controls })
        assertEquals(high.filterKeys { it !in controls }, none.filterKeys { it !in controls })
    }

    @Test fun onlyOutputLimitPermitsSecondRequest() {
        listOf("none", "http", "timeout", "network", "action_parse_failed", "tool_call_not_dispatched").forEach {
            assertFalse(allowsSecond(buildJsonObject { put("reasonCode", it) }))
        }
        assertTrue(allowsSecond(buildJsonObject { put("reasonCode", "response_protocol_output_limit") }))
    }

    private class ProbeFailure(code: String) : AssertionError(code)
    private companion object {
        const val PRIOR_ID = "f2f503f5-1cd2-4afe-91b3-d8d4f744b913"
        const val PRIOR_DIRECTORY = "virtual-service-acceptance-265c0be1-dc8a-4661-9b2a-85333d67c39b"
        const val TASK = "请只在本次独立副屏中的系统计算器完成固定计算。启动由启动确认完成，不要再次打开应用。只根据副屏截图观察；初始只能为0或同一行完整的123×45=5535，其他状态请失败结束。依次且仅点击1、2、3、×、4、5、=，不清除历史，不输入文本，不使用系统按键、滑动、等待动作或其他功能。禁止调用get_ui_tree和get_activity_stack，副屏不支持这些工具。最后一次等号后，必须根据新截图视觉读取当前123×45的结果，只有看到5535才报告读到5535并成功finish；否则失败finish。"
        fun variants(high: JsonObject): List<JsonObject> {
            val low = JsonObject(high.toMutableMap().apply { put("reasoning_effort", JsonPrimitive("low")) })
            val none = JsonObject(high.toMutableMap().apply {
                put("thinking", buildJsonObject { put("type", "disabled") }); remove("reasoning_effort")
            })
            return listOf(low, none)
        }
        fun allowsSecond(outcome: JsonObject) = outcome["reasonCode"] == JsonPrimitive("response_protocol_output_limit")
        fun requireFixed(value: Boolean, code: String) { if (!value) reject(code) }
        fun reject(code: String): Nothing = throw ProbeFailure(code)
    }
}
