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
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed, opt-in, transport-only diagnosis. Returned actions and tools are never dispatched. */
@RunWith(AndroidJUnit4::class)
class VirtualCalculatorUsageProbeInstrumentedTest {
    @Test
    fun diagnosesRetainedResultWithoutDeviceActions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveVirtualUsageProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = RootPilotService.uiState.value
        requireFixed(!state.running && state.pendingAction == null && state.status == RootPilotStatus.FAILED &&
            state.step == 6, "prior_state_required")
        val history = RootPilotService.historyState(context)
        val originalHistory = history.value
        val prior = originalHistory.records.singleOrNull { it.id == PRIOR_ID } ?: reject("prior_record_missing")
        requireFixed(originalHistory.error == null && prior == originalHistory.records.maxByOrNull { it.startedAtEpochMs } &&
            !prior.eventsTruncated && prior.status == RunHistoryStatus.FAILED &&
            prior.events.lastOrNull()?.let { it.event == TraceEvent.RUN_END && it.step == 6 &&
                it.modelFailure?.protocolReason == ModelProtocolReason.OUTPUT_LIMIT } == true, "prior_failure_required")
        val expected = listOf(TraceActionType.OPEN_APP to 0) + (0..5).map { TraceActionType.TAP to it }
        val executions = prior.events.filter { it.stage == TraceStage.EXECUTION }
        requireFixed(executions.size == 14 && executions.chunked(2).zip(expected).all { (pair, action) ->
            pair.all { it.actionType == action.first && it.step == action.second } &&
                pair.map { it.event } == listOf(TraceEvent.START, TraceEvent.RESULT) &&
                pair.map { it.status } == listOf(TraceStatus.STARTED, TraceStatus.SUCCESS)
        } && prior.events.filter { it.event == TraceEvent.CONFIRMED }.map { it.actionType to it.step } == expected,
            "prior_execution_required")
        requireFixed(prior.events.none { it.stage == TraceStage.INFORMATION || it.event == TraceEvent.TODO_SAVED },
            "prior_information_mismatch")
        val receipt = File(context.cacheDir, PRIOR_DIRECTORY).resolve("metadata.json")
        requireFixed(receipt.isFile && receipt.length() in 1..16_384, "receipt_required")
        val metadata = Json.parseToJsonElement(receipt.readText()).jsonObject
        requireFixed(metadata["runId"]?.jsonPrimitive?.contentOrNull == PRIOR_ID &&
            metadata["firstKeyIndex"]?.jsonPrimitive?.intOrNull == 1 &&
            metadata["currentRunApprovedKeyCount"]?.jsonPrimitive?.intOrNull == 6 &&
            listOf("result5535Observed", "resultVirtualImageSaved", "remainingKeysApproved", "runEnd", "displayGone",
                "cleanupConfirmed", "imeUnchanged", "configRestored", "allowlistBytesRestored")
                .all { metadata[it]?.jsonPrimitive?.booleanOrNull == true }, "receipt_mismatch")
        val frame = state.frame ?: reject("retained_frame_required")
        val resultFile = receipt.parentFile!!.resolve("result.jpg")
        requireFixed(resultFile.isFile && resultFile.length() in 8..1_048_576 &&
            frame.bytes.size in 8..1_048_576 && frame.bytes.contentEquals(resultFile.readBytes()), "frame_receipt_mismatch")
        val digest = MessageDigest.getInstance("SHA-256").digest(frame.bytes).joinToString("") { "%02x".format(it) }
        requireFixed(digest == "85a75634263e46c9541c0dac80630ffe4a9e94326965800adede2819ce82ba27" &&
            frame.width == 720 && frame.height == 1280 && frame.physicalWidth == 1080 && frame.physicalHeight == 1920 &&
            frame.dataUrl == "data:image/jpeg;base64," + Base64.encodeToString(frame.bytes, Base64.NO_WRAP), "frame_invalid")
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size, dimensions)
        requireFixed(dimensions.outMimeType == "image/jpeg" && dimensions.outWidth == 720 && dimensions.outHeight == 1280,
            "frame_decode_invalid")
        val manager = context.getSystemService(DisplayManager::class.java) ?: reject("display_manager_required")
        fun unchanged() = requireFixed(RootPilotService.uiState.value === state && history.value == originalHistory &&
            manager.displays.none { it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX) }, "environment_changed")
        unchanged()
        val saved = try { RootPilotApiConfigStore.create(context).read() } catch (_: Exception) { null }
            ?: reject("saved_config_required")
        requireFixed(saved.baseUrl.trimEnd('/') == "https://api.deepseek.com" && saved.apiKey.isNotBlank(), "endpoint_required")
        val request = DeepSeekVisionRequest(
            saved.applyTo(RootPilotConfig(task = TASK, allowScreenUpload = true,
                executionDisplay = ExecutionDisplay.VIRTUAL, virtualDisplayStartPackage = "com.miui.calculator")),
            frame, listOf(447 to 830, 675 to 830, 875 to 676, 308 to 781, 493 to 749, 878 to 902).mapIndexed { index, xy ->
                "step=$index action=tap(${xy.first},${xy.second}) result=success"
            }, remainingSteps = 14, step = 6,
            availableApps = listOf(RootPilotApp("com.miui.calculator", "计算器", "com.miui.calculator.cal.CalculatorActivity")),
            observation = null, observationStartedAtMillis = null,
        )
        // Only pure prompt construction is reflected; no execution or lifecycle internals are invoked.
        val promptBuilder = HttpDeepSeekClient::class.java.getDeclaredMethod("buildUserPrompt",
            DeepSeekVisionRequest::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val prompt = promptBuilder.invoke(HttpDeepSeekClient(), request, true) as String
        val system = HttpDeepSeekClient::class.java.getField("SYSTEM_PROMPT").get(null) as String
        val high = Json.parseToJsonElement(buildDeviceDecisionRequest(request, emptyList(), true, system, prompt)).jsonObject
        requireFixed(high["max_tokens"] == JsonPrimitive(8192) && high["reasoning_effort"] == JsonPrimitive("high"),
            "baseline_changed")
        val noThinking = JsonObject(high.toMutableMap().apply {
            put("thinking", buildJsonObject { put("type", "disabled") }); remove("reasoning_effort")
        })
        val switches = setOf("thinking", "reasoning_effort")
        requireFixed(high.filterKeys { it !in switches } == noThinking.filterKeys { it !in switches }, "context_changed")
        // A fixed receipt-scoped directory makes this authorization single-use, including after process death.
        val directory = File(context.cacheDir, "virtual-model-usage-probe-$PRIOR_ID")
        requireFixed(directory.mkdir(), "authorization_already_attempted")
        val outcomes = mutableListOf<JsonObject>()
        var attempts = 0
        fun publish() {
            val report = buildJsonObject {
                put("source", "verified_result_frame_reconstructed_last_step"); put("originalWireReplay", false)
                put("liveScreenObservationIncluded", false); put("modelRequestsAttempted", attempts); put("maxModelRequests", 2)
                put("deviceActions", 0); put("toolDispatches", 0); put("serviceCommands", 0); put("configurationChanged", false)
                putJsonArray("outcomes") { outcomes.forEach(::add) }
            }.toString()
            directory.resolve("metadata.json").writeText(report)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("artifactDirectoryName", directory.name); putString("modelUsageProbe", report)
            })
        }
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        try {
            for ((index, body) in listOf(high, noThinking).withIndex()) {
                unchanged()
                if (index > 0) {
                    if (outcomes.single()["reasonCode"] != JsonPrimitive("response_protocol_output_limit")) break
                    delay(10_000)
                }
                val outcome = withContext(Dispatchers.IO) {
                    unchanged()
                    requireFixed(directory.resolve("request-${index + 1}.attempted").createNewFile(), "request_already_attempted")
                    attempts++
                    publish()
                    unchanged()
                    compare(http, saved.apiKey, body.toString(), if (index == 0) "high" else "none")
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
        var reason = "none"
        var parsed: Boolean? = null
        var finishParsed: Boolean? = null
        var status: Int? = null
        var capture: UsageCapture? = null
        try {
            call.execute().use { response ->
                try {
                    status = response.code
                    if (status !in 200..299) reason = "http"
                    else {
                        capture = UsageCapture(response.body?.source() ?: reject("response_source_missing"))
                        val result = readDeepSeekToolStream(capture!!.buffer()) {}
                        if (result.toolCalls.isNotEmpty()) reason = "tool_call_not_dispatched"
                        else {
                            val action = ActionParser().parse(result.content)
                            parsed = action is ActionParseResult.Success
                            finishParsed = action is ActionParseResult.Success && action.action is RootPilotAction.Finish
                            if (!parsed!!) reason = "action_parse_failed"
                        }
                    }
                } finally { call.cancel() }
            }
        } catch (error: ModelProtocolException) {
            reason = "response_protocol_" + error.reason.name.lowercase()
        } catch (_: SerializationException) { reason = "response_protocol_unspecified"
        } catch (_: InterruptedIOException) { reason = "timeout"
        } catch (_: IOException) { reason = "network"
        } finally { call.cancel() }
        return buildJsonObject {
            put("effort", effort); put("maxTokens", 8192); put("elapsedMs", SystemClock.elapsedRealtime() - started)
            put("reasonCode", reason); put("actionParsed", parsed); put("finishParsed", finishParsed); put("httpStatus", status)
            capture?.report()?.forEach { (name, value) -> put(name, value) }
        }
    }

    @Test fun numericCaptureRetainsUsageOnRejectedLengthWithoutRawText() = runBlocking {
        val wire = synthetic("length", "{\"prompt_tokens\":12,\"completion_tokens\":8192,\"total_tokens\":8204," +
            "\"completion_tokens_details\":{\"reasoning_tokens\":8192}}")
        val capture = UsageCapture(Buffer().writeUtf8(wire))
        try { readDeepSeekToolStream(capture.buffer()) {}; fail("length_accepted") }
        catch (error: ModelProtocolException) { assertEquals(ModelProtocolReason.OUTPUT_LIMIT, error.reason) }
        val report = capture.report()
        assertEquals(JsonPrimitive(12), report["promptTokens"])
        assertEquals(JsonPrimitive(8192), report["reasoningTokens"])
        assertEquals(JsonPrimitive(6), report["reasoningChars"])
        assertEquals(JsonPrimitive(2), report["contentChars"])
        assertEquals(JsonPrimitive(2), report["contentWhitespaceChars"])
        assertFalse(report.toString().contains("SECRET"))
    }

    @Test fun malformedUsageIsNotTreatedAsZeroOrPrinted() = runBlocking {
        val capture = UsageCapture(Buffer().writeUtf8(synthetic("length", "{\"completion_tokens\":\"SECRET\"}")))
        try { readDeepSeekToolStream(capture.buffer()) {}; fail("length_accepted") } catch (_: ModelProtocolException) { }
        assertEquals(JsonPrimitive(true), capture.report()["usageMalformed"])
        assertEquals(JsonNull, capture.report()["completionTokens"])
        assertFalse(capture.report().toString().contains("SECRET"))
    }

    @Test fun laterUsageDoesNotRetainEarlierReasoningTokens() = runBlocking {
        val wire = synthetic("stop", "{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16," +
            "\"completion_tokens_details\":{\"reasoning_tokens\":3}}")
            .replace("  ", "{}")
            .replace("data: [DONE]", "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12," +
                "\"completion_tokens\":2,\"total_tokens\":14}}\n\ndata: [DONE]")
        val capture = UsageCapture(Buffer().writeUtf8(wire))
        assertEquals("{}", readDeepSeekToolStream(capture.buffer()) {}.content)
        assertEquals(JsonPrimitive(2), capture.report()["completionTokens"])
        assertEquals(JsonPrimitive(14), capture.report()["totalTokens"])
        assertEquals(JsonNull, capture.report()["reasoningTokens"])
        assertEquals(JsonPrimitive(false), capture.report()["usageMalformed"])
    }

    @Test fun capturePreservesProductionSuccessAndBoundsIncompleteEvents() = runBlocking {
        val wire = synthetic("stop", "{\"completion_tokens\":4}").replace("  ", "{}")
        val capture = UsageCapture(Buffer().writeUtf8(wire))
        assertEquals("{}", readDeepSeekToolStream(capture.buffer()) {}.content)
        assertEquals(JsonPrimitive("stop"), capture.report()["finishReason"])
        val oversized = UsageCapture(Buffer().writeUtf8("data: " + "x".repeat(270_000)))
        try { readDeepSeekToolStream(oversized.buffer()) {}; fail("oversized_accepted") }
        catch (error: ModelProtocolException) { assertEquals(ModelProtocolReason.STREAM_LIMIT, error.reason) }
        assertEquals(JsonPrimitive(false), oversized.report()["countsAvailable"])
    }

    private fun synthetic(finish: String, usage: String) =
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"SECRET\",\"content\":\"  \"}}]}\n\n" +
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"$finish\"}],\"usage\":$usage}\n\n" +
            "data: [DONE]\n\n"

    private companion object {
        const val PRIOR_ID = "315d1a6b-776e-4c31-8723-79e4f31e1084"
        const val PRIOR_DIRECTORY = "virtual-service-acceptance-e5078abd-8833-408d-8a18-fc32baa9e03a"
        const val TASK = "副屏计算器已打开。之前的任务已输入1，本任务仅继续点击2、3、×、4、5、=，每键一次，不重放已有输入，不清除。根据每轮最新截图与最近动作判断下一键，不把初始值当成每轮的值。不要重新打开应用、调用信息工具或执行其他操作。等号后读取当前行结果（不是上方历史）；看到5535才成功finish并报告5535，否则失败finish。每轮只输出一个动作JSON。"
        fun requireFixed(value: Boolean, code: String) { if (!value) reject(code) }
        fun reject(code: String): Nothing = throw AssertionError(code)
    }
}

/** A bounded numeric observer; the production reader alone decides whether a response is valid. */
private class UsageCapture(source: Source) : ForwardingSource(source) {
    private val pending = Buffer()
    private val event = StringBuilder()
    private var valid = true
    private var done = false
    private var bytes = 0L
    private var eventBytes = 0L
    private var reasoningChars = 0L
    private var contentChars = 0L
    private var whitespaceChars = 0L
    private var finish = "missing"
    private var usageMalformed = false
    private var promptTokens: Long? = null
    private var completionTokens: Long? = null
    private var reasoningTokens: Long? = null
    private var totalTokens: Long? = null

    override fun read(sink: Buffer, byteCount: Long): Long {
        val offset = sink.size
        val count = super.read(sink, byteCount)
        if (count <= 0 || !valid || done) return count
        bytes += count
        if (bytes > 4_194_304) { invalidate(); return count }
        sink.copyTo(pending, offset, count)
        while (valid && !done) {
            val end = pending.indexOf('\n'.code.toByte())
            if (end < 0) { if (pending.size > 262_144) invalidate(); break }
            if (end > 262_144) { invalidate(); break }
            val line = pending.readUtf8LineStrict()
            eventBytes += end + 1
            if (eventBytes > 262_144) { invalidate(); break }
            if (line.isEmpty()) {
                inspectEvent(); event.setLength(0); eventBytes = 0
            } else if (line == "data" || line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(if (line == "data") "" else line.substring(5).removePrefix(" "))
            }
        }
        if (done) { pending.clear(); event.setLength(0) }
        return count
    }

    private fun invalidate() { valid = false; pending.clear(); event.setLength(0) }

    private fun inspectEvent() {
        if (event.isEmpty()) return
        if (event.toString() == "[DONE]") { done = true; return }
        val root = try { Json.parseToJsonElement(event.toString()) as? JsonObject }
            catch (_: SerializationException) { null }
        if (root == null) { invalidate(); return }
        val choices = root["choices"] as? JsonArray
        if (choices == null || choices.size > 1) { invalidate(); return }
        if (choices.isNotEmpty()) {
            val choice = choices[0] as? JsonObject
            val delta = choice?.get("delta") as? JsonObject
            if (delta == null) { invalidate(); return }
            fun text(key: String): String {
                val value = delta[key]
                if (value == null || value == JsonNull) return ""
                if (value !is JsonPrimitive || !value.isString) { invalidate(); return "" }
                return value.content
            }
            reasoningChars += text("reasoning_content").length
            val content = text("content")
            contentChars += content.length
            whitespaceChars += content.count(Char::isWhitespace)
            if (reasoningChars + contentChars > MAX_STREAM_TEXT_CHARS) invalidate()
            val end = choice["finish_reason"] as? JsonPrimitive
            if (end != null && end != JsonNull) {
                finish = if (end.isString && end.content in setOf("stop", "length", "tool_calls", "content_filter"))
                    end.content else "other"
            }
        }
        val usage = root["usage"]
        if (usage != null && usage != JsonNull) {
            if (usage !is JsonObject) usageMalformed = true
            else {
                fun number(parent: JsonObject, name: String): Long? {
                    val value = parent[name] ?: return null
                    val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                    if (number == null || number !in 0..2_000_000) { usageMalformed = true; return null }
                    return number
                }
                promptTokens = number(usage, "prompt_tokens")
                completionTokens = number(usage, "completion_tokens")
                totalTokens = number(usage, "total_tokens")
                val details = usage["completion_tokens_details"]
                reasoningTokens = when (details) {
                    is JsonObject -> number(details, "reasoning_tokens")
                    null, JsonNull -> null
                    else -> { usageMalformed = true; null }
                }
                if (reasoningTokens != null && completionTokens != null && reasoningTokens!! > completionTokens!!) usageMalformed = true
                if (promptTokens != null && completionTokens != null && totalTokens != null &&
                    promptTokens!! + completionTokens!! != totalTokens) usageMalformed = true
            }
        }
        if (finish == "length") done = true
    }

    fun report() = buildJsonObject {
        put("countsAvailable", valid); put("finishReason", finish); put("usageMalformed", usageMalformed)
        put("reasoningChars", if (valid) reasoningChars else null); put("contentChars", if (valid) contentChars else null)
        put("contentWhitespaceChars", if (valid) whitespaceChars else null)
        put("promptTokens", if (!usageMalformed) promptTokens else null)
        put("completionTokens", if (!usageMalformed) completionTokens else null)
        put("reasoningTokens", if (!usageMalformed) reasoningTokens else null)
        put("totalTokens", if (!usageMalformed) totalTokens else null)
    }
}
