package com.example.agent.rootpilot

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agent.rootpilot.action.ActionParseResult
import com.example.agent.rootpilot.action.ActionParser
import com.example.agent.rootpilot.apps.AppLaunchAllowlistStore
import com.example.agent.rootpilot.deepseek.ModelProtocolReason
import com.example.agent.rootpilot.history.RunHistoryRecord
import com.example.agent.rootpilot.history.RunHistoryStatus
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.input.LiveExecutionInstrumentedTest.FixtureClient
import com.example.agent.rootpilot.log.TraceActionType
import com.example.agent.rootpilot.log.TraceEvent
import com.example.agent.rootpilot.log.TraceReason
import com.example.agent.rootpilot.log.TraceStage
import com.example.agent.rootpilot.log.TraceStatus
import com.example.agent.rootpilot.log.RunTraceEvent
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.model.RootPilotUiState
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in production Service/model/overlay acceptance. Launch RootPilot normally beforehand and
 * run instrumentation with --no-restart and liveVirtualCalculatorAcceptance=true.
 * No UiAutomation, screenshot command, or direct confirmation API is used. The isolation entry
 * additionally requests three host ADB taps on a signed, content-free main-display fixture.
 * The fixed calculation may append history but never clears it. Only calculator frames
 * already captured by the production virtual run are saved to a unique local cache directory.
 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayServiceAcceptanceInstrumentedTest {
    @Test
    fun realServiceStopsDuringVirtualModelRequest() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualStopModelAcceptance") == "true")
        Acceptance(InstrumentationRegistry.getInstrumentation().targetContext, mode = Mode.STOP_MODEL).run()
    }

    @Test
    fun realServiceStopsAtVirtualTapConfirmation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualStopApprovalAcceptance") == "true")
        Acceptance(InstrumentationRegistry.getInstrumentation().targetContext, mode = Mode.STOP_APPROVAL).run()
    }

    @Test
    fun realServiceCalculatesWhileMainDisplayIsUsed() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualIsolationAcceptance") == "true")
        Acceptance(InstrumentationRegistry.getInstrumentation().targetContext, mode = Mode.ISOLATION).run()
    }

    @Test
    fun realServiceCalculatesOnOwnedVirtualDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveVirtualCalculatorAcceptance") == "true")
        runBlocking { withRootPilotAcceptanceScreen { Acceptance(InstrumentationRegistry.getInstrumentation().targetContext).run() } }
    }

    @Test
    fun realServiceContinuesVerifiedOneWithoutReplayingIt() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveVirtualContinueVerifiedOne") == "true")
        val prior = PriorRun(
            arguments.getString("priorServiceRunId").orEmpty(),
            arguments.getString("priorServiceAcceptanceDirectory").orEmpty(),
        )
        runBlocking { Acceptance(InstrumentationRegistry.getInstrumentation().targetContext, PriorChain(1, listOf(prior))).run() }
    }

    @Test
    fun realServiceContinuesVerifiedPrefixWithoutReplayingIt() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveVirtualContinueVerifiedPrefix") == "true")
        val first = arguments.getString("firstKeyIndex")?.toIntOrNull() ?: fail(Reason.PRIOR_RECEIPT_INVALID)
        check(first in 1..6, Reason.PRIOR_RECEIPT_INVALID)
        runBlocking { withRootPilotAcceptanceScreen {
            Acceptance(InstrumentationRegistry.getInstrumentation().targetContext, priorChain(first)).run()
        } }
    }

    @Test
    fun verifiesSixKeyReceiptAndRejectsOverclaimsWithoutDeviceActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyVirtualSixKeyReceipt") == "true")
        verifyReceiptCases(6)
    }

    @Test
    fun verifiesOneKeyReceiptAndRejectsOverclaimsWithoutDeviceActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyVirtualOneKeyReceipt") == "true")
        verifyReceiptCases(1)
    }

    @Test
    fun verifiesThreeKeyStoppedReceiptAndRejectsOverclaimsWithoutDeviceActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyVirtualThreeKeyStoppedReceipt") == "true")
        verifyReceiptCases(3)
    }

    @Test
    fun verifiesThreeKeyParseFailureReceiptWithoutDeviceActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyVirtualThreeKeyParseReceipt") == "true")
        verifyReceiptCases(3)
    }

    @Test
    fun parseFailureTailRejectsConfirmedOrExecutedActions() {
        val id = "9c7b3d27-8894-430a-a48f-5d125cf43992"
        fun event(stage: TraceStage, event: TraceEvent, status: TraceStatus, reason: TraceReason) =
            RunTraceEvent(id, 3, 0, TraceActionType.NONE, stage, event, status, reason)
        val events = listOf(
            event(TraceStage.PARSE, TraceEvent.PARSE_RETRY, TraceStatus.FAILED, TraceReason.PARSE_FAILED),
            event(TraceStage.MODEL, TraceEvent.RESULT, TraceStatus.SUCCESS, TraceReason.NONE),
            event(TraceStage.PARSE, TraceEvent.RESULT, TraceStatus.FAILED, TraceReason.PARSE_FAILED),
            event(TraceStage.PARSE, TraceEvent.RUN_END, TraceStatus.FAILED, TraceReason.PARSE_FAILED),
        )
        val record = RunHistoryRecord(id, 0, status = RunHistoryStatus.FAILED, events = events)
        check(parseFailedBeforeAction(record), Reason.UNEXPECTED)
        val end = events.last()
        val invalid = listOf(
            record.copy(status = RunHistoryStatus.RUNNING), record.copy(eventsTruncated = true),
            record.copy(events = events.drop(1)),
            record.copy(events = events.dropLast(1) + end.copy(reason = TraceReason.MODEL_FAILED)),
            record.copy(events = events.dropLast(1) + end.copy(status = TraceStatus.CANCELLED)),
            record.copy(events = events.dropLast(1) + end.copy(actionType = TraceActionType.TAP)),
            record.copy(events = events.dropLast(1) + end.copy(runId = "other")),
            record.copy(events = events.dropLast(2) + listOf(
                event(TraceStage.APPROVAL, TraceEvent.CONFIRMED, TraceStatus.SUCCESS, TraceReason.NONE),
            ) + events.takeLast(2)),
            record.copy(events = events.dropLast(2) + listOf(
                event(TraceStage.EXECUTION, TraceEvent.START, TraceStatus.STARTED, TraceReason.NONE),
            ) + events.takeLast(2)),
        )
        invalid.forEach { check(!parseFailedBeforeAction(it), Reason.UNEXPECTED) }
    }

    private fun verifyReceiptCases(firstKeyIndex: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = RootPilotService.uiState.value
        val history = RootPilotService.historyState(context)
        val beforeHistory = history.value
        check(!state.running && state.pendingAction == null && beforeHistory.error == null, Reason.SERVICE_BUSY)
        val chain = priorChain(firstKeyIndex)
        check(chain.receipts.size == 1, Reason.PRIOR_RECEIPT_INVALID)
        Acceptance(context, chain).verifyPriorWithoutActions()
        for (invalid in listOf(chain.copy(firstKeyIndex = firstKeyIndex - 1), chain.copy(firstKeyIndex = firstKeyIndex + 1),
            chain.copy(receipts = chain.receipts + chain.receipts))) {
            var rejected = false
            try { Acceptance(context, invalid).verifyPriorWithoutActions() }
            catch (error: AcceptanceFailure) { rejected = error.reason == Reason.PRIOR_RECEIPT_INVALID }
            check(rejected, Reason.PRIOR_RECEIPT_INVALID)
        }
        check(RootPilotService.uiState.value === state && history.value == beforeHistory, Reason.CONFIG_CHANGED)
    }

    @Test
    fun inspectsRetainedActionParseFailureWithoutDeviceActions() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("inspectVirtualActionParseFailure") == "true")
        val id = arguments.getString("parseFailureRunId").orEmpty()
        val expectedPid = arguments.getString("parseFailurePid")?.toIntOrNull()
        check(UUID_PATTERN.matches(id) && expectedPid == android.os.Process.myPid(), Reason.RUN_IDENTITY)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = RootPilotService.uiState.value
        val history = RootPilotService.historyState(context)
        val before = history.value
        val record = before.records.singleOrNull { it.id == id } ?: fail(Reason.HISTORY_UNAVAILABLE)
        check(!state.running && state.pendingAction == null && state.status == RootPilotStatus.FAILED &&
            !state.modelReportedResult && before.error == null && !record.eventsTruncated &&
            record.status == RunHistoryStatus.FAILED && record == before.records.maxByOrNull { it.startedAtEpochMs },
            Reason.PRIOR_RECEIPT_INVALID)
        val end = record.events.lastOrNull() ?: fail(Reason.HISTORY_INCOMPLETE)
        val liveEnd = Json.parseToJsonElement(state.logs.last()).jsonObject
        check(end.event == TraceEvent.RUN_END && end.stage == TraceStage.PARSE &&
            end.status == TraceStatus.FAILED && end.reason == TraceReason.PARSE_FAILED &&
            end.step == state.step && liveEnd["runId"]?.jsonPrimitive?.contentOrNull == id &&
            liveEnd["event"]?.jsonPrimitive?.contentOrNull == "run_end", Reason.RUN_IDENTITY)
        val classification = retainedParseFailureCode(state.errorMessage.orEmpty())
        val report = buildJsonObject {
            put("runId", id); put("parseFailureCode", classification.name)
            put("modelRequests", 0); put("deviceActions", 0); put("screenshotReads", 0)
        }.toString()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("retainedActionParseFailure", report)
        })
        check(RootPilotService.uiState.value === state && history.value == before, Reason.CONFIG_CHANGED)
    }

    @Test
    fun parseFailureClassificationNeverExportsMessageContents() {
        val cases = mapOf(
            "动作字段不符合协议，期望：private，实际：private" to ParseFailureCode.FIELD_SET,
            "动作 JSON 不合法：Encountered an unknown key 'private'" to ParseFailureCode.JSON_UNKNOWN_FIELD,
            "动作 JSON 不合法：Unexpected JSON token at offset private" to ParseFailureCode.JSON_DECODING,
            "x 必须在 0 到 1000 之间" to ParseFailureCode.COORDINATE_RANGE,
            "reason 不合法" to ParseFailureCode.REASON_VALUE,
            "message 不能为空" to ParseFailureCode.MESSAGE_VALUE,
            "不支持的 action：private" to ParseFailureCode.ACTION_VALUE,
            "private" to ParseFailureCode.UNKNOWN,
        )
        cases.forEach { (message, expected) -> check(classifyParseFailure(message) == expected, Reason.UNEXPECTED) }
        val parserCases = mapOf(
            """{"action":"tap","x":1.5,"y":2,"reason":"fixed"}""" to ParseFailureCode.JSON_NUMERIC_VALUE,
            """{"action":"tap","x":"private","y":2,"reason":"fixed"}""" to ParseFailureCode.JSON_NUMERIC_VALUE,
            """{"action":"tap","x":1,"y":2,"reason":42}""" to ParseFailureCode.JSON_STRING_VALUE,
            """{"x":1,"y":2,"reason":"fixed"}""" to ParseFailureCode.JSON_REQUIRED_FIELD,
            """{"action":"finish","success":"private","message":"fixed"}""" to ParseFailureCode.JSON_BOOLEAN_VALUE,
            """{"action":"tap","x":1,"y":2,"reason":"fixed"}{}""" to ParseFailureCode.JSON_TRAILING_DATA,
        )
        parserCases.forEach { (input, expected) ->
            val failure = ActionParser().parse(input) as? ActionParseResult.Failure ?: fail(Reason.UNEXPECTED)
            check(classifyParseFailure(failure.message) == expected, Reason.UNEXPECTED)
        }
        check(classifyParseFailure("动作 JSON 不合法：Unexpected JSON token\nJSON input: numeric literal private") ==
            ParseFailureCode.JSON_DECODING, Reason.UNEXPECTED)
    }

    @Test
    fun overwrittenPreflightErrorsCannotBeAttributedToRetainedParseFailure() {
        val overwrittenMessages = listOf(
            "副屏退出未确认，禁止启动任务；请先核对副屏已消失再重启 RootPilot",
            "恢复记录不可用，请先核对已执行结果，再放弃记录",
            "请先处理上次中断的任务", "请先输入自然语言任务",
            "发送截图前请先打开上传确认", "副屏仅支持完整任务，不支持任务间保留单步会话",
            "请选择副屏起始应用", "",
        )
        overwrittenMessages.forEach { message ->
            var rejected = false
            try { retainedParseFailureCode(message) }
            catch (error: AcceptanceFailure) { rejected = error.reason == Reason.PARSE_MESSAGE_UNCONFIRMED }
            check(rejected, Reason.UNEXPECTED)
        }
    }

    @Test
    fun inspectsRetainedFirstKeyFailureWithoutDeviceActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectVirtualFirstKeyFailure") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = RootPilotService.uiState.value
        val history = RootPilotService.historyState(context)
        val before = history.value
        val id = "7e713634-713e-4bb0-beaf-b2ab9afecdd7"
        val record = before.records.singleOrNull { it.id == id } ?: fail(Reason.HISTORY_UNAVAILABLE)
        check(android.os.Process.myPid() == 25205 && !state.running && state.pendingAction == null &&
            state.status == RootPilotStatus.FAILED && state.step == 1 && state.modelReportedResult &&
            before.error == null && record == before.records.maxByOrNull { it.startedAtEpochMs } &&
            !record.eventsTruncated && record.events.last().reason == TraceReason.MODEL_REPORTED_FAILURE,
            Reason.PRIOR_RECEIPT_INVALID)
        val liveEnd = Json.parseToJsonElement(state.logs.last()).jsonObject
        check(liveEnd["runId"]?.jsonPrimitive?.contentOrNull == id &&
            liveEnd["event"]?.jsonPrimitive?.contentOrNull == "run_end", Reason.RUN_IDENTITY)
        val receipts = record.events.filter { it.stage == TraceStage.EXECUTION && it.event == TraceEvent.RESULT }
        check(receipts.map { it.actionType } == listOf(TraceActionType.OPEN_APP, TraceActionType.TAP) &&
            receipts.all { it.status == TraceStatus.SUCCESS } &&
            record.events.any { it.step == 1 && it.stage == TraceStage.SCREENSHOT &&
                it.event == TraceEvent.RESULT && it.status == TraceStatus.SUCCESS }, Reason.EXECUTION_COUNT)
        val frame = state.frame ?: fail(Reason.FRAME_UNAVAILABLE)
        check(frame.width == 720 && frame.height == 1280 && frame.physicalWidth == WIDTH && frame.physicalHeight == HEIGHT,
            Reason.FRAME_INVALID)
        val directory = File(context.cacheDir, "virtual-failure-observation-$id")
        check(directory.mkdir(), Reason.ARTIFACT_WRITE)
        directory.resolve("retained.jpg").writeBytes(frame.bytes)
        val message = state.errorMessage.orEmpty()
        val report = buildJsonObject {
            put("runId", id); put("modelReportedFailure", true)
            put("mentionsInitialCondition", message.contains("初始") || message.contains("initial", ignoreCase = true))
            put("mentionsZero", message.contains("0")); put("mentionsProduct", message.contains("5535"))
            put("retainedFrameOnly", true); put("modelRequests", 0); put("deviceActions", 0)
        }.toString()
        directory.resolve("metadata.json").writeText(report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("artifactDirectoryName", directory.name); putString("retainedFailureObservation", report)
        })
        check(RootPilotService.uiState.value === state && history.value == before, Reason.CONFIG_CHANGED)
    }

    @Test
    fun realServiceObservesVerifiedProductWithoutReplayingEquals() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveVirtualObserveVerifiedProduct") == "true")
        runBlocking { Acceptance(InstrumentationRegistry.getInstrumentation().targetContext, priorChain(7)).run() }
    }

    private fun priorChain(first: Int): PriorChain {
        val arguments = InstrumentationRegistry.getArguments()
        val ids = arguments.getString("priorServiceRunIds").orEmpty().split(',')
        val directories = arguments.getString("priorServiceAcceptanceDirectories").orEmpty().split(',')
        check(ids.size == directories.size && ids.size in 1..7, Reason.PRIOR_RECEIPT_INVALID)
        return PriorChain(first, ids.zip(directories).map { (id, directory) -> PriorRun(id, directory) })
    }

    private data class PriorRun(val id: String, val directory: String)
    private data class PriorChain(val firstKeyIndex: Int, val receipts: List<PriorRun>)

    private enum class Mode { FULL, STOP_MODEL, STOP_APPROVAL, ISOLATION }

    private class Acceptance(private val context: Context, private val prior: PriorChain? = null,
        private val mode: Mode = Mode.FULL) {
        private val firstKeyIndex = prior?.firstKeyIndex ?: 0
        private val remainingKeys = KEYS.size - firstKeyIndex
        private val manager = context.getSystemService(DisplayManager::class.java) ?: fail(Reason.DISPLAY_IDENTITY)
        private val history = RootPilotService.historyState(context)
        private val selection = context.noBackupFilesDir.resolve("rootpilot_app_launch_allowlist.json")
        private val originalState = RootPilotService.uiState.value
        private val originalConfig = originalState.config
        private val previousIds = history.value.records.map { it.id }.toSet()
        private val evidence = Evidence(firstKeyIndex, prior?.receipts?.lastOrNull()?.id.orEmpty(), mode)
        private val fixture = if (mode == Mode.ISOLATION) FixtureClient(context) else null
        private var fixtureLaunched = false
        private var originalSelection: ByteArray? = null
        private var installedSelection: ByteArray? = null
        private var originalIme: String? = null
        private var configChanged = false
        private var selectionAttempted = false
        private var startSent = false
        private var requestedAt = Long.MAX_VALUE
        private var testConfig: RootPilotConfig? = null
        private var owned: OwnedDisplay? = null
        private var approvedAction: RootPilotAction? = null
        private var approvedStep = -1
        private val resultStep: Int get() = if (remainingKeys == 0) 0 else approvedStep + 1
        private var beforeEqualsFrame: ScreenshotFrame? = null
        private var failure: Reason? = null

        suspend fun run() {
            try {
                withTimeout(RUN_TIMEOUT_MS) {
                    preflight()
                    execute()
                }
            } catch (error: Throwable) {
                evidence.failureStage = evidence.stage
                failure = reason(error)
            } finally {
                withContext(NonCancellable) {
                    cleanup()
                    try {
                        record()?.let { evidence.directory?.resolve("history.json")?.writeText(Json.encodeToString(it)) }
                    } catch (_: Exception) { if (failure == null) failure = Reason.ARTIFACT_WRITE }
                    evidence.failure = failure?.name ?: "none"
                    evidence.passed = failure == null && evidence.bodyComplete && evidence.cleanupConfirmed &&
                        evidence.configRestored && evidence.allowlistRestored
                    try { evidence.publish() } catch (_: Throwable) {
                        if (failure == null) failure = Reason.ARTIFACT_WRITE
                    }
                }
            }
            failure?.let { fail(it) }
            check(evidence.passed, Reason.ACCEPTANCE_INCOMPLETE)
        }

        private fun preflight() {
            check(context.packageName == "com.example.agent", Reason.TARGET_PACKAGE)
            check(!originalState.running && originalState.pendingAction == null && originalState.status in IDLE_STATES,
                Reason.SERVICE_BUSY)
            check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists(), Reason.RECOVERY_PENDING)
            check(!context.noBackupFilesDir.resolve("rootpilot_original_ime").exists(), Reason.IME_RECOVERY_PENDING)
            originalIme = currentIme()
            check(!originalIme.isNullOrBlank(), Reason.IME_UNAVAILABLE)
            check(context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false, Reason.DEVICE_LOCKED)
            check(Settings.canDrawOverlays(context), Reason.OVERLAY_PERMISSION)
            check(RootPilotAccessibilityService.connectedService != null, Reason.ACCESSIBILITY_UNAVAILABLE)
            check(privateDisplays().isEmpty(), Reason.EXISTING_PRIVATE_DISPLAY)
            check(history.value.error == null, Reason.HISTORY_UNAVAILABLE)
            fixture?.verifyIdentity()
            if (prior != null) verifyPriorPrefix(prior)
            val component = context.packageManager.getLaunchIntentForPackage(CALCULATOR)?.component
            check(component != null && component.packageName == CALCULATOR &&
                component.className == "com.miui.calculator.cal.CalculatorActivity", Reason.CALCULATOR_IDENTITY)
            val saved = try { RootPilotApiConfigStore.create(context).read() }
            catch (_: Exception) { fail(Reason.SAVED_CONFIG_UNAVAILABLE) }
            check(saved != null && saved.apiKey.isNotBlank() && saved.model.isNotBlank(), Reason.SAVED_CONFIG_UNAVAILABLE)
            check(saved!!.baseUrl.trimEnd('/') == "https://api.deepseek.com", Reason.DEEPSEEK_ENDPOINT)
            // A null API configuration can only restore the API defaults exactly.
            check(originalState.apiConfigured || originalConfig.apiKey.isEmpty() &&
                originalConfig.baseUrl == RootPilotApiConfig().baseUrl &&
                originalConfig.model == RootPilotApiConfig().model, Reason.ORIGINAL_CONFIG_UNRESTORABLE)
            evidence.directory = File(context.cacheDir, "virtual-service-acceptance-${UUID.randomUUID()}")
                .also { check(it.mkdir(), Reason.ARTIFACT_WRITE) }
            originalSelection = if (selection.exists()) selection.readBytes() else null
            selectionAttempted = true
            AppLaunchAllowlistStore.create(context).save(setOf(CALCULATOR))
            installedSelection = selection.readBytes()
            configChanged = true
            RootPilotService.updateApiConfig(saved)
            testConfig = saved.applyTo(RootPilotConfig(
                task = if (prior == null) TASK else continuationTask(firstKeyIndex),
                manualConfirmation = true,
                allowScreenUpload = true,
                executionDisplay = ExecutionDisplay.VIRTUAL,
                virtualDisplayStartPackage = CALCULATOR,
            ))
        }

        private fun priorMetadata(prior: PriorRun): JsonObject {
            check(UUID_PATTERN.matches(prior.id) &&
                prior.directory.matches(Regex("virtual-service-acceptance-$UUID_PATTERN")), Reason.PRIOR_RECEIPT_INVALID)
            val file = context.cacheDir.resolve(prior.directory).resolve("metadata.json")
            check(file.isFile && file.length() in 1..16_384, Reason.PRIOR_RECEIPT_INVALID)
            val metadata = Json.parseToJsonElement(file.readText()).jsonObject
            fun text(name: String) = metadata[name]?.jsonPrimitive?.contentOrNull
            fun flag(name: String) = metadata[name]?.jsonPrimitive?.booleanOrNull == true
            check(text("runId") == prior.id && text("executionSource") == "production_service" &&
                text("confirmationSource") == "script_local_view_click" && text("cleanupReason") == "none" &&
                text("failure") in setOf("TERMINAL_NOT_COMPLETED", "TAP_NOT_ON_EXPECTED_KEY", "CURRENT_ROW_UNAVAILABLE") && !flag("passed") &&
                !flag("sevenKeysApproved") && !flag("result5535Observed") &&
                listOf("sessionIdentityVerified", "bootstrapOpenApproved", "initialExpressionKnown",
                    "initialVirtualImageSaved", "runEnd", "displayGone", "cleanupConfirmed", "imeUnchanged",
                    "configRestored", "allowlistBytesRestored").all(::flag), Reason.PRIOR_RECEIPT_INVALID)
            return metadata
        }

        private fun priorRecord(prior: PriorRun): RunHistoryRecord =
            (history.value.records.singleOrNull { it.id == prior.id } ?: fail(Reason.PRIOR_RECEIPT_INVALID)).also {
                check(it.status in setOf(RunHistoryStatus.FAILED, RunHistoryStatus.STOPPED) && !it.eventsTruncated &&
                    it.events.isNotEmpty() && it.events.all { event -> event.runId == prior.id }, Reason.PRIOR_RECEIPT_INVALID)
            }

        private fun verifyExecutedPrefix(previous: RunHistoryRecord, taps: Int, equalsExecuted: Boolean) {
            check(taps in 1..6, Reason.PRIOR_RECEIPT_INVALID)
            val executions = previous.events.filter { it.stage == TraceStage.EXECUTION }
            val expected = listOf(TraceActionType.OPEN_APP to 0) + (0 until taps).map { TraceActionType.TAP to it }
            check(executions.size == expected.size * 2, Reason.PRIOR_RECEIPT_INVALID)
            executions.chunked(2).zip(expected).forEach { (pair, key) ->
                check(pair.all { it.actionType == key.first && it.step == key.second } &&
                    pair.map { it.event } == listOf(TraceEvent.START, TraceEvent.RESULT) &&
                    pair.map { it.status } == listOf(TraceStatus.STARTED, TraceStatus.SUCCESS), Reason.PRIOR_RECEIPT_INVALID)
            }
            check(previous.events.filter { it.event == TraceEvent.CONFIRMED }.map { it.actionType to it.step } == expected &&
                previous.events.none { it.event in FORBIDDEN_TOOLS || it.event == TraceEvent.TODO_SAVED },
                Reason.PRIOR_RECEIPT_INVALID)
            val end = previous.events.last()
            check(end.event == TraceEvent.RUN_END && end.status in setOf(TraceStatus.FAILED, TraceStatus.CANCELLED) &&
                end.step == (if (equalsExecuted) taps - 1 else taps), Reason.PRIOR_RECEIPT_INVALID)
            if (equalsExecuted) check(end.stage == TraceStage.SETTLE && end.status == TraceStatus.CANCELLED &&
                previous.events.any { it.event == TraceEvent.STOP_REQUESTED && it.step == taps - 1 },
                Reason.PRIOR_RECEIPT_INVALID)
        }

        private fun verifyPriorPrefix(chain: PriorChain) {
            check(chain.firstKeyIndex in 1..7 && chain.receipts.size in 1..7 &&
                chain.receipts.map { it.id }.distinct().size == chain.receipts.size, Reason.PRIOR_RECEIPT_INVALID)
            var prefixSize = 0
            var previous: RunHistoryRecord? = null
            chain.receipts.forEachIndexed { index, receipt ->
                val metadata = priorMetadata(receipt)
                val record = priorRecord(receipt)
                val taps: Int
                if (index == 0) {
                    check(metadata["firstKeyIndex"]?.jsonPrimitive?.intOrNull.let { it == null || it == 0 },
                        Reason.PRIOR_RECEIPT_INVALID)
                    val end = record.events.last()
                    val modelFailed = metadata["failure"]?.jsonPrimitive?.contentOrNull == "TERMINAL_NOT_COMPLETED" &&
                        record.status == RunHistoryStatus.FAILED &&
                        end.let {
                            end.modelFailure?.protocolReason == ModelProtocolReason.OUTPUT_LIMIT ||
                                end.reason == TraceReason.MODEL_REPORTED_FAILURE && end.stage == TraceStage.PARSE &&
                                end.actionType == TraceActionType.FINISH || parseFailedBeforeAction(record)
                        }
                    val rejectedBeforeConfirmation =
                        metadata["failure"]?.jsonPrimitive?.contentOrNull == "TAP_NOT_ON_EXPECTED_KEY" &&
                            metadata["stopRequested"]?.jsonPrimitive?.booleanOrNull == true &&
                            record.status == RunHistoryStatus.STOPPED && end.stage == TraceStage.APPROVAL &&
                            end.status == TraceStatus.CANCELLED && end.reason == TraceReason.CANCELLED &&
                            end.actionType == TraceActionType.TAP && record.events.takeLast(3).let { tail ->
                                tail.map { it.event } == listOf(TraceEvent.WAITING, TraceEvent.STOP_REQUESTED, TraceEvent.RUN_END) &&
                                    tail.all { it.step == end.step && it.actionType == TraceActionType.TAP }
                            }
                    check(modelFailed || rejectedBeforeConfirmation, Reason.PRIOR_RECEIPT_INVALID)
                    taps = if ("currentRunApprovedKeyCount" in metadata)
                        metadata["currentRunApprovedKeyCount"]?.jsonPrimitive?.intOrNull
                            ?: fail(Reason.PRIOR_RECEIPT_INVALID)
                    else 1
                } else {
                    check(metadata["firstKeyIndex"]?.jsonPrimitive?.intOrNull == prefixSize &&
                        metadata["priorRunId"]?.jsonPrimitive?.contentOrNull == previous?.id &&
                        record.startedAtEpochMs > previous!!.startedAtEpochMs, Reason.PRIOR_RECEIPT_INVALID)
                    taps = metadata["currentRunApprovedKeyCount"]?.jsonPrimitive?.intOrNull
                        ?: fail(Reason.PRIOR_RECEIPT_INVALID)
                }
                val equalsExecuted = prefixSize + taps == 7
                if (equalsExecuted) check(index == chain.receipts.lastIndex &&
                    metadata["failure"]?.jsonPrimitive?.contentOrNull == "CURRENT_ROW_UNAVAILABLE" &&
                    metadata["remainingKeysApproved"]?.jsonPrimitive?.booleanOrNull == true &&
                    metadata["stopRequested"]?.jsonPrimitive?.booleanOrNull == true, Reason.PRIOR_RECEIPT_INVALID)
                else check(metadata["failure"]?.jsonPrimitive?.contentOrNull != "CURRENT_ROW_UNAVAILABLE",
                    Reason.PRIOR_RECEIPT_INVALID)
                verifyExecutedPrefix(record, taps, equalsExecuted)
                prefixSize += taps
                check(prefixSize <= 7, Reason.PRIOR_RECEIPT_INVALID)
                previous = record
            }
            check(prefixSize == chain.firstKeyIndex, Reason.PRIOR_RECEIPT_INVALID)
            val first = priorRecord(chain.receipts.first())
            val verifiedIds = chain.receipts.map { it.id }.toSet()
            // All other later runs must be free of input, including interrupted attempts.
            check(history.value.records.filter { it.id !in verifiedIds && it.startedAtEpochMs >= first.startedAtEpochMs }
                .all { later -> !later.eventsTruncated && later.status != RunHistoryStatus.RUNNING &&
                    later.events.none { it.event == TraceEvent.TODO_SAVED ||
                        it.stage == TraceStage.EXECUTION && it.actionType != TraceActionType.OPEN_APP } },
                Reason.PRIOR_RECEIPT_SUPERSEDED)
        }

        fun verifyPriorWithoutActions() {
            verifyPriorPrefix(prior ?: fail(Reason.PRIOR_RECEIPT_INVALID))
        }

        private suspend fun execute() {
            if (fixture != null) {
                evidence.stage = "main_fixture_launch"
                fixtureLaunched = true
                context.startActivity(Intent().setClassName("com.example.rootpilot.fixture",
                    "com.example.rootpilot.fixture.ExecutionFixtureActivity")
                    .putExtra("rootpilotIsolation", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                evidence.stage = "main_fixture_wait"
                withTimeout(5_000) {
                    while (!fixture.call("state").getBoolean("isolationReady")) delay(POLL_MS)
                }
                check(fixture.call("state").getInt("isolationClicks") == 0, Reason.MAIN_FIXTURE_CHANGED)
            }
            evidence.stage = "start"
            requestedAt = System.currentTimeMillis()
            // A dispatch exception does not prove the Service failed to receive the command.
            startSent = true
            RootPilotService.send(context, RootPilotService.ACTION_AUTO_EXECUTE, testConfig)
            withTimeout(10_000) {
                while (bindRecord() == null) delay(POLL_MS)
            }
            evidence.stage = "confirm"
            while (true) {
                currentCoroutineContext().ensureActive()
                val state = RootPilotService.uiState.value
                check(state.config == testConfig, Reason.CONFIG_CHANGED)
                val events = liveEvents()
                checkNoUnsupportedTools(events)
                if (mode == Mode.STOP_MODEL && state.status == RootPilotStatus.REQUESTING_MODEL &&
                    evidence.opened && !evidence.stopRequested && modelRequestInFlight(events)) {
                    if (stopOverlay(state)) evidence.stopRequested = true
                }
                if (mode == Mode.STOP_APPROVAL && state.status == RootPilotStatus.WAITING_CONFIRMATION &&
                    evidence.opened && !evidence.stopRequested) {
                    check(state.pendingAction is RootPilotAction.Tap && evidence.approvedKeys == 0,
                        Reason.ACTION_OUTSIDE_SCOPE)
                    if (stopOverlay(state)) evidence.stopRequested = true
                }
                if (mode == Mode.STOP_MODEL && evidence.opened && !evidence.stopRequested &&
                    state.status == RootPilotStatus.WAITING_CONFIRMATION && state.pendingAction is RootPilotAction.Tap) {
                    fail(Reason.STOP_BOUNDARY_MISSED)
                }
                // Observe after the production settle/capture cycle, not during the equals animation.
                if (evidence.opened && evidence.approvedKeys == remainingKeys && !evidence.result5535 &&
                    state.step >= resultStep && state.frame != null &&
                    events.any { it.step == resultStep && it.stage == "screenshot" &&
                        it.event == "result" && it.status == "success" } &&
                    (remainingKeys == 0 || events.any { it.executionSuccess("tap") && it.step == approvedStep })) {
                    observeResult()
                }
                if (evidence.result5535 && !evidence.resultImage) saveResultFrame()
                if (state.status == RootPilotStatus.WAITING_CONFIRMATION &&
                    (state.pendingAction !== approvedAction || state.step != approvedStep) &&
                    !evidence.stopRequested && (mode !in setOf(Mode.STOP_MODEL, Mode.STOP_APPROVAL) || !evidence.opened)) {
                    approve(state, events)
                }
                if (!state.running && state.status in TERMINAL) break
                delay(POLL_MS)
            }
            val terminal = RootPilotService.uiState.value
            if (mode == Mode.STOP_MODEL || mode == Mode.STOP_APPROVAL) {
                verifyStopped()
                return
            }
            check(terminal.status == RootPilotStatus.COMPLETED, Reason.TERMINAL_NOT_COMPLETED)
            check(evidence.opened && evidence.approvedKeys == remainingKeys, Reason.APPROVAL_COUNT)
            check(terminal.pendingAction == null && terminal.savedTodos.isEmpty(), Reason.UNEXPECTED_SIDE_EFFECT)
            check(evidence.result5535 && evidence.resultImage, Reason.RESULT_NOT_OBSERVED)
            evidence.modelReported5535 = terminal.modelReportedResult &&
                normalize(terminal.errorMessage.orEmpty()).contains("5535")
            check(evidence.modelReported5535, Reason.MODEL_RESULT_MISMATCH)
            withTimeout(5_000) {
                while (record()?.status == RunHistoryStatus.RUNNING ||
                    record()?.events?.none { it.event == TraceEvent.RUN_END } != false) delay(POLL_MS)
            }
            val completed = record() ?: fail(Reason.HISTORY_UNAVAILABLE)
            check(completed.status == RunHistoryStatus.COMPLETED && !completed.eventsTruncated &&
                history.value.error == null, Reason.HISTORY_INCOMPLETE)
            val events = completed.events
            check(events.all { it.runId == evidence.runId }, Reason.RUN_IDENTITY)
            check(events.none { it.event in FORBIDDEN_TOOLS }, Reason.UNSUPPORTED_TOOL)
            val starts = events.filter { it.stage == TraceStage.EXECUTION && it.event == TraceEvent.START }
            val receipts = events.filter { it.stage == TraceStage.EXECUTION && it.event == TraceEvent.RESULT &&
                it.status == TraceStatus.SUCCESS }
            check(starts.size == remainingKeys + 1 && receipts.size == remainingKeys + 1 && receipts.first().actionType == TraceActionType.OPEN_APP &&
                receipts.drop(1).all { it.actionType == TraceActionType.TAP }, Reason.EXECUTION_COUNT)
            check(events.count { it.event == TraceEvent.CONFIRMED } == remainingKeys + 1, Reason.APPROVAL_COUNT)
            check(events.any { it.step >= resultStep && it.stage == TraceStage.MODEL &&
                it.event == TraceEvent.START }, Reason.FINAL_MODEL_REQUEST_MISSING)
            evidence.executionsMatched = true
            if (fixture != null) {
                val finalFixture = fixture.call("state")
                check(evidence.mainClicks == 3 && finalFixture.getInt("isolationClicks") == 3 &&
                    finalFixture.getBoolean("empty"), Reason.MAIN_FIXTURE_CHANGED)
            }
            evidence.bodyComplete = true
        }

        private fun modelRequestInFlight(events: List<LiveEvent>) =
            events.count { it.stage == "model" && it.event == "start" } == 1 &&
                events.none { it.stage == "model" && it.event == "result" }

        private fun stopOverlay(expected: RootPilotUiState): Boolean {
            var clicked = false
            var rejected: Throwable? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try {
                    val current = RootPilotService.uiState.value
                    if (!current.running || current.status != expected.status ||
                        current.pendingAction !== expected.pendingAction || current.step != expected.step) return@runOnMainSync
                    check(current.config == testConfig, Reason.CONFIG_CHANGED)
                    validateDisplay(owned ?: fail(Reason.DISPLAY_IDENTITY))
                    if (mode == Mode.STOP_MODEL && !modelRequestInFlight(liveEvents())) return@runOnMainSync
                    val panel = WindowInspector.getGlobalWindowViews().singleOrNull {
                        it.isAttachedToWindow && (it.layoutParams as? WindowManager.LayoutParams)?.title == "RootPilotOverlay"
                    } ?: return@runOnMainSync
                    check((panel.layoutParams as WindowManager.LayoutParams).flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0, Reason.OVERLAY_IDENTITY)
                    val button = views(panel).filterIsInstance<Button>().singleOrNull { it.text.toString() == "停止" }
                        ?: return@runOnMainSync
                    if (button.isShown && button.isEnabled) clicked = button.performClick()
                } catch (error: Throwable) { rejected = error }
            }
            rejected?.let { throw it }
            return clicked
        }

        private suspend fun verifyStopped() {
            check(evidence.stopRequested && evidence.opened && evidence.approvedKeys == 0,
                Reason.STOP_BOUNDARY_MISSED)
            withTimeout(5_000) {
                while (record()?.events?.lastOrNull()?.event != TraceEvent.RUN_END ||
                    record()?.status == RunHistoryStatus.RUNNING) delay(POLL_MS)
            }
            val state = RootPilotService.uiState.value
            val stopped = record() ?: fail(Reason.HISTORY_UNAVAILABLE)
            check(state.status == RootPilotStatus.STOPPED && !state.running && state.pendingAction == null &&
                state.savedTodos.isEmpty() && stopped.status == RunHistoryStatus.STOPPED && !stopped.eventsTruncated,
                Reason.TERMINAL_NOT_STOPPED)
            val events = stopped.events
            val executions = events.filter { it.stage == TraceStage.EXECUTION }
            check(executions.map { it.event } == listOf(TraceEvent.START, TraceEvent.RESULT) &&
                executions.all { it.actionType == TraceActionType.OPEN_APP } &&
                executions.last().status == TraceStatus.SUCCESS &&
                events.count { it.event == TraceEvent.CONFIRMED } == 1,
                Reason.EXECUTION_COUNT)
            val modelStarts = events.count { it.stage == TraceStage.MODEL && it.event == TraceEvent.START }
            check(if (mode == Mode.STOP_MODEL) modelStarts == 1 else modelStarts > 0 &&
                events.count { it.stage == TraceStage.MODEL && it.event == TraceEvent.RESULT &&
                    it.status == TraceStatus.SUCCESS } == modelStarts, Reason.EXECUTION_COUNT)
            val stopIndex = events.indexOfFirst { it.event == TraceEvent.STOP_REQUESTED }
            check(stopIndex >= 0 && events.drop(stopIndex + 1).none {
                it.stage == TraceStage.EXECUTION || it.stage == TraceStage.SCREENSHOT ||
                    it.stage == TraceStage.MODEL && it.event == TraceEvent.START
            } && events.none { it.event in FORBIDDEN_TOOLS || it.event == TraceEvent.TODO_SAVED },
                Reason.ACTION_AFTER_STOP)
            val end = events.last()
            check(end.status == TraceStatus.CANCELLED && end.stage ==
                if (mode == Mode.STOP_MODEL) TraceStage.MODEL else TraceStage.APPROVAL, Reason.STOP_BOUNDARY_MISSED)
            evidence.executionsMatched = true
            evidence.bodyComplete = true
        }

        private suspend fun useMainDisplay() {
            val client = fixture ?: return
            val before = client.call("state")
            evidence.mainFocusLostBeforeTap = evidence.mainFocusLostBeforeTap || !before.getBoolean("isolationFocused")
            check(before.getBoolean("isolationReady") && before.getBoolean("empty") &&
                before.getInt("isolationClicks") == evidence.mainClicks, Reason.MAIN_FIXTURE_CHANGED)
            val x = before.getInt("buttonX")
            val y = before.getInt("buttonY")
            var safe = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                safe = WindowInspector.getGlobalWindowViews().filter {
                    it.isAttachedToWindow && it.isShown &&
                        it.display?.displayId == Display.DEFAULT_DISPLAY &&
                        (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }.none {
                    val location = IntArray(2)
                    it.getLocationOnScreen(location)
                    Rect().let { bounds ->
                        val visible = it.getLocalVisibleRect(bounds)
                        bounds.offset(location[0], location[1])
                        visible && bounds.contains(x, y)
                    }
                }
            }
            check(x > 0 && y > 0 && safe, Reason.MAIN_INPUT_TARGET)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putBoolean("mainInputReady", true)
                putInt("mainInputSequence", evidence.mainClicks + 1)
                putInt("mainInputX", x); putInt("mainInputY", y)
            })
            // The host may inject exactly one display-0 tap at the fresh fixed button coordinates.
            withTimeout(15_000) {
                while (true) {
                    val observed = client.call("state")
                    check(observed.getBoolean("empty") &&
                        observed.getInt("isolationClicks") in evidence.mainClicks..evidence.mainClicks + 1,
                        Reason.MAIN_FIXTURE_CHANGED)
                    if (observed.getInt("isolationClicks") == evidence.mainClicks + 1 &&
                        observed.getBoolean("isolationReady") && observed.getBoolean("isolationFocused")) break
                    delay(POLL_MS)
                }
            }
            evidence.mainClicks++
            validateDisplay(owned ?: fail(Reason.DISPLAY_IDENTITY))
        }

        private suspend fun approve(expected: RootPilotUiState, events: List<LiveEvent>) {
            check(expected.running, Reason.SERVICE_BUSY)
            val action = expected.pendingAction ?: fail(Reason.ACTION_OUTSIDE_SCOPE)
            if (owned == null) owned = bindDisplay()
            val display = owned ?: fail(Reason.DISPLAY_IDENTITY)
            validateDisplay(display)
            when (action) {
                is RootPilotAction.OpenApp -> {
                    check(!evidence.opened && evidence.approvedKeys == 0 && expected.step == 0 &&
                        action.packageName == CALCULATOR, Reason.ACTION_OUTSIDE_SCOPE)
                    check(expected.frame == null && events.none { it.stage == "model" || it.stage == "screenshot" },
                        Reason.BOOTSTRAP_ORDER)
                }
                is RootPilotAction.Tap -> {
                    check(evidence.opened && evidence.approvedKeys < remainingKeys, Reason.ACTION_OUTSIDE_SCOPE)
                    if (fixture != null && evidence.approvedKeys in setOf(0, 3, 6) &&
                        evidence.mainClicks == evidence.approvedKeys / 3) useMainDisplay()
                    val successful = events.count { it.executionSuccess("tap") }
                    check(successful == evidence.approvedKeys &&
                        events.count { it.executionSuccess("open_app") } == 1, Reason.EXECUTION_ORDER)
                    val frame = expected.frame ?: fail(Reason.FRAME_UNAVAILABLE)
                    checkFrame(frame)
                    val index = firstKeyIndex + evidence.approvedKeys
                    val sample = collect(display, KEYS[index])
                    check(if (index == 0) sample.knownInitial() else sample.expression == KEYS[index].before,
                        Reason.EXPRESSION_MISMATCH)
                    if (index == firstKeyIndex) {
                        evidence.initialExpressionKnown = true
                        if (!evidence.initialImage) {
                            saveFrame(frame, "initial.jpg")
                            evidence.initialImage = true
                        }
                    }
                    check(action.x in 0..1000 && action.y in 0..1000, Reason.TAP_COORDINATES)
                    val x = action.x * (WIDTH - 1) / 1000
                    val y = action.y * (HEIGHT - 1) / 1000
                    check(sample.keyBounds?.contains(x, y) == true, Reason.TAP_NOT_ON_EXPECTED_KEY)
                    check(SystemClock.elapsedRealtime() - sample.finishedAt <= 1_000, Reason.STALE_TARGET)
                }
                else -> fail(Reason.ACTION_OUTSIDE_SCOPE)
            }
            if (confirmOverlay(expected, display)) {
                approvedAction = action
                approvedStep = expected.step
                if (action is RootPilotAction.OpenApp) evidence.opened = true
                else {
                    evidence.approvedKeys++
                    if (evidence.approvedKeys == remainingKeys) beforeEqualsFrame = expected.frame
                }
            }
        }

        private suspend fun observeResult() {
            evidence.stage = "read_result"
            val display = owned ?: fail(Reason.DISPLAY_IDENTITY)
            // No compensating tap: up to five observations of the same current row only.
            repeat(5) { attempt ->
                ensureResultWindow()
                val sample = collect(display, null)
                ensureResultWindow()
                if (sample.knownProduct()) {
                    if (remainingKeys == 0) {
                        evidence.initialExpressionKnown = true
                        saveFrame(RootPilotService.uiState.value.frame ?: fail(Reason.FRAME_UNAVAILABLE), "initial.jpg")
                        evidence.initialImage = true
                    }
                    evidence.result5535 = true
                    evidence.resultDuringModelRequest = liveEvents().any {
                        it.step >= resultStep && it.stage == "model" && it.event == "start"
                    }
                    evidence.resultBeforeNextModelResponse = true
                    return
                }
                if (attempt < 4) delay(500)
            }
            fail(Reason.RESULT_NOT_OBSERVED)
        }

        private fun ensureResultWindow() {
            validateDisplay(owned ?: fail(Reason.DISPLAY_IDENTITY))
            val state = RootPilotService.uiState.value
            check(state.running && state.config == testConfig, Reason.RESULT_WINDOW_MISSED)
            check(liveEvents().none {
                it.step >= resultStep && it.stage == "model" && it.event == "result"
            }, Reason.RESULT_WINDOW_MISSED)
        }

        private fun saveResultFrame() {
            val state = RootPilotService.uiState.value
            val frame = state.frame ?: return
            if (frame === beforeEqualsFrame || state.step < resultStep) return
            val events = liveEvents()
            if (events.none { it.step == state.step && it.stage == "screenshot" &&
                    it.event == "result" && it.status == "success" }) return
            validateDisplay(owned ?: fail(Reason.DISPLAY_IDENTITY))
            check(state.config == testConfig && state.running, Reason.RESULT_WINDOW_MISSED)
            saveFrame(frame, "result.jpg")
            evidence.resultImage = true
        }

        private fun confirmOverlay(expected: RootPilotUiState, display: OwnedDisplay): Boolean {
            var clicked = false
            var rejected: Throwable? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try {
                    val current = RootPilotService.uiState.value
                    if (!current.running || current.status != RootPilotStatus.WAITING_CONFIRMATION ||
                        current.pendingAction !== expected.pendingAction || current.step != expected.step ||
                        current.frame !== expected.frame) return@runOnMainSync
                    check(current.config == testConfig, Reason.CONFIG_CHANGED)
                    validateDisplay(display)
                    checkNoUnsupportedTools(liveEvents())
                    val panel = WindowInspector.getGlobalWindowViews().singleOrNull {
                        it.isAttachedToWindow &&
                            (it.layoutParams as? WindowManager.LayoutParams)?.title == "RootPilotOverlay"
                    } ?: return@runOnMainSync
                    check((panel.layoutParams as WindowManager.LayoutParams).flags and
                        WindowManager.LayoutParams.FLAG_SECURE != 0, Reason.OVERLAY_IDENTITY)
                    val button = views(panel).filterIsInstance<Button>()
                        .filter { it.text.toString() == "确认" }.singleOrNull() ?: return@runOnMainSync
                    if (button.isEnabled && button.isShown) clicked = button.performClick()
                } catch (error: Throwable) { rejected = error }
            }
            rejected?.let { throw it }
            return clicked
        }

        private fun bindRecord(): RunHistoryRecord? {
            val candidates = history.value.records.filter {
                it.id !in previousIds && it.startedAtEpochMs >= requestedAt
            }
            check(candidates.size <= 1, Reason.RUN_IDENTITY)
            val fresh = candidates.singleOrNull() ?: return null
            check(UUID.fromString(fresh.id).toString() == fresh.id, Reason.RUN_IDENTITY)
            if (evidence.runId.isEmpty()) evidence.runId = fresh.id
            check(fresh.id == evidence.runId, Reason.RUN_IDENTITY)
            return fresh
        }

        private fun record(): RunHistoryRecord? = if (evidence.runId.isEmpty()) bindRecord()
            else history.value.records.singleOrNull { it.id == evidence.runId }

        private fun liveEvents(): List<LiveEvent> = RootPilotService.uiState.value.logs.mapNotNull { line ->
            val value = try { Json.parseToJsonElement(line).jsonObject } catch (_: Exception) { return@mapNotNull null }
            if (value["runId"]?.jsonPrimitive?.contentOrNull != evidence.runId) return@mapNotNull null
            fun field(name: String) = value[name]?.jsonPrimitive?.contentOrNull.orEmpty()
            LiveEvent(value["step"]?.jsonPrimitive?.intOrNull ?: -1,
                field("stage"), field("event"), field("status"), field("actionType"))
        }

        private fun privateDisplays(): List<Display> = manager.displays.filter {
            it.name.startsWith(VirtualDisplayProtocol.DISPLAY_PREFIX)
        }

        private fun bindDisplay(): OwnedDisplay {
            val display = privateDisplays().singleOrNull() ?: fail(Reason.DISPLAY_IDENTITY)
            val session = display.name.removePrefix(VirtualDisplayProtocol.DISPLAY_PREFIX)
            check(UUID.fromString(session).toString() == session, Reason.DISPLAY_IDENTITY)
            return OwnedDisplay(display.displayId, display.name, session).also {
                validateDisplay(it)
                evidence.sessionValid = true
            }
        }

        private fun validateDisplay(expected: OwnedDisplay) {
            val display = privateDisplays().singleOrNull() ?: fail(Reason.DISPLAY_IDENTITY)
            check(display.displayId == expected.id && expected.id > 0 && display.name == expected.name &&
                display.name == VirtualDisplayProtocol.DISPLAY_PREFIX + expected.session &&
                display.isValid && display.rotation == Surface.ROTATION_0,
                Reason.DISPLAY_IDENTITY)
            val size = Point()
            display.getRealSize(size)
            check(size.x == WIDTH && size.y == HEIGHT, Reason.DISPLAY_GEOMETRY)
        }

        private fun checkFrame(frame: ScreenshotFrame) {
            check(frame.physicalWidth == WIDTH && frame.physicalHeight == HEIGHT &&
                frame.bytes.size in 8..(16 * 1024 * 1024), Reason.FRAME_INVALID)
        }

        private fun saveFrame(frame: ScreenshotFrame, filename: String) {
            checkFrame(frame)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size, options)
            check(options.outMimeType == "image/jpeg" && options.outWidth == frame.width &&
                options.outHeight == frame.height && frame.width > 0 && frame.height > 0, Reason.FRAME_INVALID)
            (evidence.directory ?: fail(Reason.ARTIFACT_WRITE)).resolve(filename).writeBytes(frame.bytes)
        }

        private suspend fun collect(expected: OwnedDisplay, key: KeySpec?): Sample = withContext(Dispatchers.IO) {
            validateDisplay(expected)
            val started = SystemClock.elapsedRealtime()
            val service = RootPilotAccessibilityService.connectedService ?: fail(Reason.ACCESSIBILITY_UNAVAILABLE)
            fun window(): AccessibilityWindowInfo {
                val windows = service.windowsOnAllDisplays[expected.id] ?: fail(Reason.WINDOW_UNAVAILABLE)
                check(windows.size <= 16, Reason.TREE_LIMIT)
                return windows.filter { it.displayId == expected.id && it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.isFocused }.singleOrNull() ?: fail(Reason.WINDOW_UNAVAILABLE)
            }
            val selected = window()
            val root = selected.root ?: fail(Reason.WINDOW_UNAVAILABLE)
            check(root.packageName?.toString() == CALCULATOR && root.windowId == selected.id &&
                root.isVisibleToUser, Reason.WINDOW_IDENTITY)
            fun safe(node: AccessibilityNodeInfo) {
                checkQueryDeadline(started)
                check(node.windowId == selected.id && node.packageName?.toString() == CALCULATOR, Reason.WINDOW_IDENTITY)
                check(!node.isPassword && !node.isAccessibilityDataSensitive, Reason.SENSITIVE_NODE)
            }
            fun unique(id: String): AccessibilityNodeInfo {
                val nodes = root.findAccessibilityNodeInfosByViewId("$CALCULATOR:id/$id")
                check(nodes.size <= 128, Reason.TREE_LIMIT)
                val visible = nodes.filter { safe(it); it.isVisibleToUser }
                check(visible.size == 1, Reason.TARGET_UNAVAILABLE)
                return visible.single().also {
                    check(it.refresh() && it.isVisibleToUser, Reason.TARGET_UNAVAILABLE)
                    safe(it)
                }
            }
            data class ExpressionTarget(val node: AccessibilityNodeInfo, val row: AccessibilityNodeInfo, val viewport: Rect)
            val expressions = root.findAccessibilityNodeInfosByViewId("$CALCULATOR:id/expression")
            check(expressions.size <= 128, Reason.TREE_LIMIT)
            val targets = expressions.mapNotNull { expression ->
                safe(expression)
                if (!expression.isVisibleToUser) return@mapNotNull null
                check(expression.refresh(), Reason.TARGET_UNAVAILABLE)
                safe(expression)
                var parent = expression.parent
                var row: AccessibilityNodeInfo? = null
                var viewport: Rect? = null
                repeat(32) {
                    val node = parent ?: return@repeat
                    safe(node)
                    check(node.isVisibleToUser, Reason.TARGET_UNAVAILABLE)
                    if (node.viewIdResourceName == "$CALCULATOR:id/history_item" && row == null) row = node
                    if (node.viewIdResourceName == "$CALCULATOR:id/listView") {
                        viewport = bounds(node)
                        parent = null
                    } else parent = node.parent
                }
                val rowNode = row ?: fail(Reason.CURRENT_ROW_UNAVAILABLE)
                val displayBounds = viewport ?: fail(Reason.CURRENT_ROW_UNAVAILABLE)
                val rectangle = bounds(expression)
                if (!inside(rectangle) || !displayBounds.contains(rectangle)) return@mapNotNull null
                check(bounds(rowNode).contains(rectangle), Reason.CURRENT_ROW_UNAVAILABLE)
                ExpressionTarget(expression, rowNode, displayBounds)
            }
            check(targets.isNotEmpty(), Reason.CURRENT_ROW_UNAVAILABLE)
            // This fixed calculator places history above the current row. Select only a
            // unique lowest row with no overlap, never a historical value matching the task.
            val bottom = targets.maxOf { bounds(it.node).bottom }
            val lowest = targets.filter { bounds(it.node).bottom == bottom }
            check(lowest.map { it.row }.distinct().size == 1, Reason.CURRENT_ROW_AMBIGUOUS)
            val current = lowest.first()
            val currentTop = bounds(current.node).top
            check(targets.all { it in lowest || bounds(it.node).bottom <= currentTop }, Reason.CURRENT_ROW_AMBIGUOUS)
            val expression = current.node
            val rowNode = current.row
            val viewport = current.viewport
            val expressionLabels = lowest.flatMap { labels(it.node).map(::expressionValue) }
                .filter { it != Expression.OTHER }.distinct()
            check(expressionLabels.size == 1, Reason.EXPRESSION_MISMATCH)
            val rowBounds = bounds(rowNode)
            val expressionBounds = bounds(expression)
            check(inside(expressionBounds) && rowBounds.contains(expressionBounds) &&
                viewport.contains(expressionBounds), Reason.CURRENT_ROW_UNAVAILABLE)
            var product = false
            var complete = false
            val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            pending.add(rowNode to 0)
            var visited = 0
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (node, depth) = pending.removeLast()
                safe(node)
                check(++visited <= 128 && depth <= 32, Reason.TREE_LIMIT)
                if (!node.isVisibleToUser) continue
                val rectangle = bounds(node)
                if (inside(rectangle) && rowBounds.contains(rectangle) && viewport.contains(rectangle)) {
                    val values = labels(node).map(::expressionValue)
                    product = product || Expression.PRODUCT in values || Expression.EQUATION in values
                    complete = complete || Expression.COMPLETE in values || Expression.EQUATION in values
                }
                check(node.childCount <= 128 - visited - pending.size, Reason.TREE_LIMIT)
                repeat(node.childCount) { index ->
                    pending.add((node.getChild(index) ?: fail(Reason.TARGET_UNAVAILABLE)) to depth + 1)
                }
            }
            val keyBounds = key?.let {
                val node = unique(it.id)
                check(node.isEnabled && node.isClickable && labels(node).any { label -> label in it.labels },
                    Reason.KEY_UNAVAILABLE)
                bounds(node).also { rectangle -> check(inside(rectangle), Reason.KEY_UNAVAILABLE) }
            }
            safe(expression)
            check(expression.refresh() && labels(expression).map(::expressionValue)
                .filter { it != Expression.OTHER }.distinct() == expressionLabels, Reason.STALE_TARGET)
            validateDisplay(expected)
            check(RootPilotAccessibilityService.connectedService === service && window().id == selected.id,
                Reason.WINDOW_IDENTITY)
            Sample(expressionLabels.single(), product, complete, keyBounds, SystemClock.elapsedRealtime())
        }

        private suspend fun cleanup() {
            evidence.stage = "cleanup"
            var settled = !startSent
            if (startSent) {
                try {
                    val ended = record()?.events?.any { it.event == TraceEvent.RUN_END } == true
                    val stopSent = !ended || RootPilotService.uiState.value.running
                    evidence.stopRequested = evidence.stopRequested || stopSent
                    if (stopSent) RootPilotService.send(context, RootPilotService.ACTION_STOP)
                    withTimeout(20_000) {
                        while (true) {
                            val events = record()?.events.orEmpty()
                            val state = RootPilotService.uiState.value
                            val stopHandled = !stopSent || state.status == RootPilotStatus.STOPPED ||
                                events.any { it.event == TraceEvent.STOP_REQUESTED }
                            val displayGone = privateDisplays().isEmpty() &&
                                (owned?.let { manager.getDisplay(it.id) == null } ?: true)
                            if (events.any { it.event == TraceEvent.RUN_END } && !state.running && stopHandled && displayGone) {
                                settled = true
                                evidence.runEnd = true
                                evidence.displayGone = true
                                break
                            }
                            delay(POLL_MS)
                        }
                    }
                } catch (_: Throwable) {
                    evidence.cleanupReason = Reason.CLEANUP_UNCONFIRMED.name
                    if (failure == null) failure = Reason.CLEANUP_UNCONFIRMED
                }
            }
            evidence.cleanupConfirmed = settled
            // A missing STOP/release receipt deliberately retains the active test environment.
            if (!settled) return
            if (fixtureLaunched) {
                try {
                    fixture!!.call("finish")
                    withTimeout(5_000) {
                        while (!fixture.call("state").isEmpty) delay(POLL_MS)
                    }
                    evidence.fixtureClosed = true
                } catch (_: Exception) {
                    evidence.cleanupReason = Reason.MAIN_FIXTURE_CLEANUP.name
                    if (failure == null) failure = Reason.MAIN_FIXTURE_CLEANUP
                }
            }
            try {
                check(currentIme() == originalIme && !context.noBackupFilesDir.resolve("rootpilot_original_ime").exists(),
                    Reason.IME_CHANGED)
                check(!context.filesDir.resolve(RootPilotRunStore.FILE_NAME).exists(), Reason.RECOVERY_PENDING)
                evidence.imeUnchanged = true
                if (selectionAttempted) {
                    val installed = installedSelection ?: fail(Reason.ALLOWLIST_RESTORE_UNCERTAIN)
                    check(selection.exists() && selection.readBytes().contentEquals(installed), Reason.ALLOWLIST_CHANGED)
                    val original = originalSelection
                    if (original == null) check(selection.delete(), Reason.ALLOWLIST_RESTORE_FAILED)
                    else {
                        val temporary = Files.createTempFile(selection.parentFile!!.toPath(), "virtual-calc-restore-", ".tmp")
                        try {
                            temporary.toFile().outputStream().use { output -> output.write(original); output.fd.sync() }
                            Files.move(temporary, selection.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally { Files.deleteIfExists(temporary) }
                        check(selection.readBytes().contentEquals(original), Reason.ALLOWLIST_RESTORE_FAILED)
                    }
                }
                evidence.allowlistRestored = true
                if (configChanged) {
                    check(!RootPilotService.uiState.value.running, Reason.SERVICE_BUSY)
                    RootPilotService.updateConfig(originalConfig)
                    RootPilotService.updateApiConfig(if (originalState.apiConfigured)
                        RootPilotApiConfig(originalConfig.apiKey, originalConfig.baseUrl, originalConfig.model) else null)
                }
                check(RootPilotService.uiState.value.config == originalConfig &&
                    RootPilotService.uiState.value.apiConfigured == originalState.apiConfigured, Reason.CONFIG_RESTORE_FAILED)
                evidence.configRestored = true
            } catch (error: Throwable) {
                evidence.cleanupReason = reason(error).name
                if (failure == null) failure = reason(error)
            }
        }

        private fun currentIme(): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }

    private data class OwnedDisplay(val id: Int, val name: String, val session: String)
    private data class LiveEvent(val step: Int, val stage: String, val event: String, val status: String, val action: String) {
        fun executionSuccess(type: String) = stage == "execution" && event == "result" && status == "success" && action == type
    }
    private enum class Expression { ZERO, ONE, TWELVE, HUNDRED_TWENTY_THREE, MULTIPLY, MULTIPLY_FOUR, COMPLETE, PRODUCT, EQUATION, OTHER }
    private data class KeySpec(val id: String, val labels: Set<String>, val before: Expression)
    private data class Sample(val expression: Expression, val product: Boolean, val complete: Boolean,
        val keyBounds: Rect?, val finishedAt: Long) {
        fun knownInitial() = expression == Expression.ZERO || knownProduct()
        fun knownProduct() = product && complete && expression in setOf(Expression.COMPLETE, Expression.PRODUCT, Expression.EQUATION)
    }

    private class Evidence(private val firstKeyIndex: Int, private val priorRunId: String, private val mode: Mode) {
        var directory: File? = null
        var runId = ""
        var stage = "preflight"
        var failure = "none"
        var failureStage = "none"
        var cleanupReason = "none"
        var passed = false
        var bodyComplete = false
        var opened = false
        var approvedKeys = 0
        var sessionValid = false
        var initialExpressionKnown = false
        var initialImage = false
        var resultImage = false
        var result5535 = false
        var modelReported5535 = false
        var resultDuringModelRequest = false
        var resultBeforeNextModelResponse = false
        var executionsMatched = false
        var stopRequested = false
        var cleanupConfirmed = false
        var runEnd = false
        var displayGone = false
        var imeUnchanged = false
        var configRestored = false
        var allowlistRestored = false
        var mainClicks = 0
        var fixtureClosed = false
        var mainFocusLostBeforeTap = false

        fun publish() {
            val metadata = buildJsonObject {
                put("runId", runId); put("stage", stage); put("failure", failure); put("cleanupReason", cleanupReason)
                put("failureStage", failureStage)
                put("passed", passed); put("bodyComplete", bodyComplete)
                put("mode", mode.name); put("mainDisplayButtonClicks", mainClicks)
                put("mainInputSource", if (mode == Mode.ISOLATION) "host_adb_display_0" else "none")
                put("fixtureClosed", fixtureClosed)
                put("mainFocusLostBeforeTap", mainFocusLostBeforeTap)
                put("executionSource", "production_service")
                put("confirmationSource", "script_local_view_click")
                put("physicalHumanConfirmation", false)
                put("firstKeyIndex", firstKeyIndex); put("priorRunId", priorRunId)
                put("currentRunApprovedKeyCount", approvedKeys)
                put("remainingKeysApproved", approvedKeys == KEYS.size - firstKeyIndex)
                put("sessionIdentityVerified", sessionValid); put("bootstrapOpenApproved", opened)
                put("sevenKeysApproved", approvedKeys == 7); put("initialExpressionKnown", initialExpressionKnown)
                put("initialVirtualImageSaved", initialImage); put("resultVirtualImageSaved", resultImage)
                put("result5535Observed", result5535); put("modelReported5535", modelReported5535)
                put("resultDuringNextModelRequest", resultDuringModelRequest)
                put("resultBeforeNextModelResponse", resultBeforeNextModelResponse)
                put("exactExecutionReceipts", executionsMatched); put("stopRequested", stopRequested)
                put("runEnd", runEnd); put("displayGone", displayGone); put("cleanupConfirmed", cleanupConfirmed)
                put("imeUnchanged", imeUnchanged); put("configRestored", configRestored)
                put("allowlistBytesRestored", allowlistRestored)
            }.toString()
            directory?.resolve("metadata.json")?.writeText(metadata)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("virtualDisplayServiceAcceptance", metadata)
                putString("artifactDirectoryName", directory?.name.orEmpty())
            })
        }
    }

    private class AcceptanceFailure(val reason: Reason) : AssertionError(reason.name)
    private enum class ParseFailureCode {
        FIELD_SET, JSON_UNKNOWN_FIELD, JSON_NUMERIC_VALUE, JSON_STRING_VALUE, JSON_REQUIRED_FIELD,
        JSON_BOOLEAN_VALUE, JSON_TRAILING_DATA, JSON_DECODING, COORDINATE_RANGE, REASON_VALUE,
        MESSAGE_VALUE, ACTION_VALUE, UNKNOWN,
    }
    private enum class Reason {
        TARGET_PACKAGE, SERVICE_BUSY, RECOVERY_PENDING, IME_RECOVERY_PENDING, IME_UNAVAILABLE, DEVICE_LOCKED,
        OVERLAY_PERMISSION, ACCESSIBILITY_UNAVAILABLE, EXISTING_PRIVATE_DISPLAY, HISTORY_UNAVAILABLE,
        CALCULATOR_IDENTITY, SAVED_CONFIG_UNAVAILABLE, DEEPSEEK_ENDPOINT, ORIGINAL_CONFIG_UNRESTORABLE,
        ARTIFACT_WRITE, CONFIG_CHANGED, RUN_IDENTITY, DISPLAY_IDENTITY, DISPLAY_GEOMETRY, BOOTSTRAP_ORDER,
        ACTION_OUTSIDE_SCOPE, EXECUTION_ORDER, FRAME_UNAVAILABLE, FRAME_INVALID, EXPRESSION_MISMATCH,
        TAP_COORDINATES, TAP_NOT_ON_EXPECTED_KEY, STALE_TARGET, OVERLAY_IDENTITY, UNSUPPORTED_TOOL,
        WINDOW_UNAVAILABLE, WINDOW_IDENTITY, SENSITIVE_NODE, TREE_LIMIT, TREE_TIMEOUT, TARGET_UNAVAILABLE,
        CURRENT_ROW_UNAVAILABLE, CURRENT_ROW_AMBIGUOUS, KEY_UNAVAILABLE, LABEL_LIMIT, RESULT_WINDOW_MISSED, RESULT_NOT_OBSERVED,
        TERMINAL_NOT_COMPLETED, APPROVAL_COUNT, UNEXPECTED_SIDE_EFFECT, MODEL_RESULT_MISMATCH,
        HISTORY_INCOMPLETE, EXECUTION_COUNT, FINAL_MODEL_REQUEST_MISSING, CLEANUP_UNCONFIRMED, IME_CHANGED,
        ALLOWLIST_RESTORE_UNCERTAIN, ALLOWLIST_CHANGED, ALLOWLIST_RESTORE_FAILED, CONFIG_RESTORE_FAILED,
        ACCEPTANCE_INCOMPLETE, PRIOR_RECEIPT_INVALID, PRIOR_RECEIPT_SUPERSEDED, TIMEOUT, CANCELLED, UNEXPECTED,
        MAIN_FIXTURE_CHANGED, MAIN_INPUT_TARGET, MAIN_FIXTURE_CLEANUP,
        STOP_BOUNDARY_MISSED, TERMINAL_NOT_STOPPED, ACTION_AFTER_STOP,
        PERMISSION_DENIED, PARSE_MESSAGE_UNCONFIRMED,
        FIXTURE_INVALID_COMMAND, FIXTURE_REMOTE_STATE, FIXTURE_MISSING_RESULT, FIXTURE_TRANSPORT_FAILURE,
        FIXTURE_UNKNOWN_PROVIDER, FIXTURE_INVALID_ARGUMENT,
    }

    private companion object {
        const val CALCULATOR = "com.miui.calculator"
        const val WIDTH = VirtualDisplayProtocol.WIDTH
        const val HEIGHT = VirtualDisplayProtocol.HEIGHT
        const val RUN_TIMEOUT_MS = 240_000L
        const val POLL_MS = 25L
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun parseFailedBeforeAction(record: RunHistoryRecord): Boolean {
            val tail = record.events.takeLast(2)
            val end = tail.lastOrNull() ?: return false
            return record.status == RunHistoryStatus.FAILED && !record.eventsTruncated && tail.size == 2 &&
                tail.map { it.event } == listOf(TraceEvent.RESULT, TraceEvent.RUN_END) &&
                tail.all { it.runId == record.id && it.step == end.step && it.stage == TraceStage.PARSE &&
                    it.actionType == TraceActionType.NONE && it.status == TraceStatus.FAILED && it.reason == TraceReason.PARSE_FAILED } &&
                record.events.count { it.step == end.step && it.event == TraceEvent.PARSE_RETRY &&
                    it.stage == TraceStage.PARSE && it.status == TraceStatus.FAILED && it.reason == TraceReason.PARSE_FAILED } == 1 &&
                record.events.none { it.step >= end.step && (it.stage == TraceStage.APPROVAL ||
                    it.stage == TraceStage.EXECUTION || it.event == TraceEvent.TODO_SAVED) }
        }
        fun retainedParseFailureCode(message: String): ParseFailureCode = classifyParseFailure(message).also {
            check(it != ParseFailureCode.UNKNOWN, Reason.PARSE_MESSAGE_UNCONFIRMED)
        }
        fun classifyParseFailure(message: String): ParseFailureCode {
            val header = message.substringBefore('\n')
            return when {
                header.startsWith("动作字段不符合协议，") -> ParseFailureCode.FIELD_SET
                header.startsWith("动作 JSON 不合法：") -> when {
                    header.contains("Encountered an unknown key") -> ParseFailureCode.JSON_UNKNOWN_FIELD
                    header.contains("numeric literal") || header.contains("Numeric value overflow") ||
                        header.contains("Failed to parse int") || header.contains("to Long") -> ParseFailureCode.JSON_NUMERIC_VALUE
                    header.contains("is required for type") || header.contains("are required for type") ->
                        ParseFailureCode.JSON_REQUIRED_FIELD
                    header.contains("boolean literal") -> ParseFailureCode.JSON_BOOLEAN_VALUE
                    header.contains("Expected EOF after parsing") -> ParseFailureCode.JSON_TRAILING_DATA
                    header.contains("quotation mark") || header.contains("string literal") -> ParseFailureCode.JSON_STRING_VALUE
                    else -> ParseFailureCode.JSON_DECODING
                }
                header in setOf("x 必须在 0 到 1000 之间", "y 必须在 0 到 1000 之间") ->
                    ParseFailureCode.COORDINATE_RANGE
                header in setOf("reason 不合法", "reason 不能为空") -> ParseFailureCode.REASON_VALUE
                header in setOf("message 不合法", "message 不能为空") -> ParseFailureCode.MESSAGE_VALUE
                header.startsWith("不支持的 action：") || header == "action 不能为空" -> ParseFailureCode.ACTION_VALUE
                else -> ParseFailureCode.UNKNOWN
            }
        }
        const val TASK = "请只在本次独立副屏中的系统计算器完成固定计算。启动由启动确认完成，不要再次打开应用。根据每轮最新副屏截图与最近成功动作判断下一键。初始条件只在本任务尚未执行任何点击时检查：当前行只能为0或同一行完整的123×45=5535，否则失败结束。开始点击后，1、12、123、123×、123×4、123×45都是正常中间态，不得再套用初始条件拒绝，也不要重放已经成功的按键。依次且仅点击1、2、3、×、4、5、=，每键一次，不清除历史，不输入文本，不使用系统按键、滑动、等待动作或其他功能。禁止调用get_ui_tree和get_activity_stack，副屏不支持这些工具。最后一次等号后，必须根据新截图视觉读取当前123×45的结果，只有看到5535才报告读到5535并成功finish；否则失败finish。"
        fun continuationTask(first: Int): String {
            check(first in 1..7, Reason.PRIOR_RECEIPT_INVALID)
            if (first == 7) return "副屏计算器已打开，之前的任务已经逐键完成123×45及等号。现在只读取当前行算式和结果，不是上方历史。禁止点击、重新打开应用、信息工具或其他动作。当前行确为123×45且结果为5535时，返回成功finish并报告5535；否则失败finish。不要重算，不要再按等号。只返回一个finish动作JSON。"
            val prefix = listOf("", "1", "12", "123", "123×", "123×4", "123×45")[first]
            val remaining = listOf("1", "2", "3", "×", "4", "5", "=").drop(first).joinToString("、")
            return "副屏计算器已打开。之前的任务已输入$prefix，本任务仅继续点击$remaining，每键一次，不重放已有输入，不清除。根据每轮最新截图与最近动作判断下一键，不把初始值当成每轮的值。不要重新打开应用、调用信息工具或执行其他操作。等号后读取当前行结果（不是上方历史）；看到5535才成功finish并报告5535，否则失败finish。每轮只输出一个动作JSON。"
        }
        val IDLE_STATES = setOf(RootPilotStatus.IDLE, RootPilotStatus.STOPPED, RootPilotStatus.COMPLETED, RootPilotStatus.FAILED)
        val TERMINAL = setOf(RootPilotStatus.STOPPED, RootPilotStatus.COMPLETED, RootPilotStatus.FAILED, RootPilotStatus.RECOVERY_REQUIRED)
        val FORBIDDEN_TOOLS = setOf(TraceEvent.READ_UI_TREE, TraceEvent.READ_ACTIVITY_STACK)
        val KEYS = listOf(
            KeySpec("digit_1", setOf("1"), Expression.ZERO),
            KeySpec("digit_2", setOf("2"), Expression.ONE),
            KeySpec("digit_3", setOf("3"), Expression.TWELVE),
            KeySpec("op_mul", setOf("×", "乘", "乘号"), Expression.HUNDRED_TWENTY_THREE),
            KeySpec("digit_4", setOf("4"), Expression.MULTIPLY),
            KeySpec("digit_5", setOf("5"), Expression.MULTIPLY_FOUR),
            KeySpec("btn_equal_s", setOf("=", "等于"), Expression.COMPLETE),
        )
        fun normalize(value: String) = value.filterNot { it.isWhitespace() || it == ',' }
        fun expressionValue(value: String): Expression = when (normalize(value).removePrefix("=")) {
            "0" -> Expression.ZERO
            "1" -> Expression.ONE
            "12" -> Expression.TWELVE
            "123" -> Expression.HUNDRED_TWENTY_THREE
            "123×" -> Expression.MULTIPLY
            "123×4" -> Expression.MULTIPLY_FOUR
            "123×45" -> Expression.COMPLETE
            "5535" -> Expression.PRODUCT
            "123×45=5535" -> Expression.EQUATION
            else -> Expression.OTHER
        }
        fun labels(node: AccessibilityNodeInfo) = listOfNotNull(node.text, node.contentDescription).map {
            check(it.length <= 128, Reason.LABEL_LIMIT)
            it.toString().trim()
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun inside(rect: Rect) = !rect.isEmpty && rect.left >= 0 && rect.top >= 0 && rect.right <= WIDTH && rect.bottom <= HEIGHT
        fun checkQueryDeadline(started: Long) {
            check(SystemClock.elapsedRealtime() - started < 3_000, Reason.TREE_TIMEOUT)
        }
        fun checkNoUnsupportedTools(events: List<LiveEvent>) {
            check(events.none { it.event == "read_ui_tree" || it.event == "read_activity_stack" }, Reason.UNSUPPORTED_TOOL)
        }
        fun views(root: View): List<View> {
            val pending = ArrayDeque<View>()
            val found = mutableListOf<View>()
            pending.add(root)
            while (pending.isNotEmpty()) {
                check(found.size + pending.size <= 128, Reason.OVERLAY_IDENTITY)
                val view = pending.removeFirst()
                found.add(view)
                if (view is ViewGroup) repeat(view.childCount) { pending.add(view.getChildAt(it)) }
            }
            return found
        }
        fun check(value: Boolean, reason: Reason) { if (!value) fail(reason) }
        fun fail(reason: Reason): Nothing = throw AcceptanceFailure(reason)
        fun reason(error: Throwable): Reason = when (error) {
            is AcceptanceFailure -> error.reason
            is TimeoutCancellationException -> Reason.TIMEOUT
            is CancellationException -> Reason.CANCELLED
            is SecurityException -> Reason.PERMISSION_DENIED
            is IllegalStateException -> when (error.message) {
                "fixture_call_failed:state:permission_denied", "fixture_call_failed:finish:permission_denied" -> Reason.PERMISSION_DENIED
                "fixture_call_failed:state:invalid_command", "fixture_call_failed:finish:invalid_command" -> Reason.FIXTURE_INVALID_COMMAND
                "fixture_call_failed:state:unknown_provider", "fixture_call_failed:finish:unknown_provider" -> Reason.FIXTURE_UNKNOWN_PROVIDER
                "fixture_call_failed:state:invalid_argument", "fixture_call_failed:finish:invalid_argument" -> Reason.FIXTURE_INVALID_ARGUMENT
                "fixture_call_failed:state:remote_state", "fixture_call_failed:finish:remote_state" -> Reason.FIXTURE_REMOTE_STATE
                "fixture_call_failed:state:missing_result", "fixture_call_failed:finish:missing_result" -> Reason.FIXTURE_MISSING_RESULT
                "fixture_call_failed:state:transport_failure", "fixture_call_failed:finish:transport_failure" -> Reason.FIXTURE_TRANSPORT_FAILURE
                else -> Reason.UNEXPECTED
            }
            else -> Reason.UNEXPECTED
        }
    }
}
