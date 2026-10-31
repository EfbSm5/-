package com.example.agent.rootpilot

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.util.Base64
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.deepseek.ModelFailure
import com.example.agent.rootpilot.deepseek.ModelFailureCategory
import com.example.agent.rootpilot.deepseek.ModelProtocolReason
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.deepseek.ToolChatResult
import com.example.agent.rootpilot.deepseek.ToolChatTurn
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotStatus
import com.example.agent.rootpilot.screen.ScreenshotFrame
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Debug-only, process-lifetime acceptance session: a fixed local response, or one guarded real-model
 * path that uploads only the fixture's own View export. No configurable model or transport.
 */
internal object RootPilotModelClientFactory {
    data class FixtureIdentity(val session: String, val instance: String, val pid: Int,
        val task: Int, val display: Int, val startTicks: Long)
    data class Evidence(val fixedRequests: Int, val responseReturned: Boolean,
        val ownerActive: Boolean?, val ownerCompleted: Boolean?, val closed: Boolean,
        val registeredHttpConstructions: Int, val delegatedRequests: Int = 0)

    const val REAL_TASK = "RootPilot 两阶段输入验收。当前专用测试页的第一个输入框已经聚焦。只提出一次输入“执行模式验收通过”的 type 动作，" +
        "等待人工确认，不重复输入。不调用工具，不点击、不滑动、不按键、不等待、不启动应用、不保存、不发送，不提前报告完成。"
    // The initial request plus AgentLoop's single parse retry; tools are disabled on this path.
    const val MAX_REAL_REQUESTS = 2
    private const val FIXTURE_PACKAGE = "com.example.rootpilot.fixture"
    private const val FIXTURE_AUTHORITY = "com.example.rootpilot.fixture.state"

    private val lock = Any()
    private var registration: Session? = null
    private var httpConstructions = 0
    private var httpAtArm = 0
    fun task(nonce: String): String = "两阶段本地输入验收:$nonce"

    fun arm(nonce: String, expectedPid: Int, fixture: FixtureIdentity, real: Boolean = false) {
        check(Looper.myLooper() === Looper.getMainLooper())
        check(expectedPid == Process.myPid())
        check(registration == null)
        val state = RootPilotService.uiState.value
        check(!state.running && state.pendingAction == null && state.status != RootPilotStatus.RECOVERY_REQUIRED)
        check(ServiceDestroyObservation.current?.let { !it.destroyed && it.service.get() != null } != true)
        synchronized(lock) {
            check(registration == null)
            registration = Session(nonce, expectedPid, fixture, real)
            httpAtArm = httpConstructions
        }
    }

    fun create(service: RootPilotService): DeepSeekClient {
        check(Looper.myLooper() === Looper.getMainLooper())
        return synchronized(lock) {
            val session = registration
            if (session == null) {
                httpConstructions++
                HttpDeepSeekClient()
            } else {
                val interruptedRun = service.filesDir.listFiles()?.any {
                    it.name == "rootpilot_run.json" ||
                        (it.name.startsWith("rootpilot_run.json.") && it.name.endsWith(".tmp"))
                } ?: true
                val interruptedIme = listOf("rootpilot_original_ime", "rootpilot_original_ime.bak",
                    "rootpilot_original_ime.new").any { File(service.noBackupFilesDir, it).exists() }
                if (interruptedRun || interruptedIme) session.reject()
                session.bind(service)
                if (session.real) {
                    val delegate = HttpDeepSeekClient()
                    object : DeepSeekClient {
                        override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult =
                            session.reject()

                        override suspend fun requestDecision(
                            request: DeepSeekVisionRequest,
                            toolHistory: List<ToolChatTurn>,
                            allowTools: Boolean,
                            onUpdate: suspend (ModelStreamSnapshot) -> Unit,
                        ): ToolChatResult = session.requestReal(service, Process.myPid(), request, toolHistory,
                            delegate, { fixtureFrame(service, session.fixture) }, onUpdate)
                    }
                } else object : DeepSeekClient {
                    override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult =
                        session.request(service, Process.myPid(), request)
                }
            }
        }
    }

    fun evidence(nonce: String): Evidence = synchronized(lock) {
        val session = registration?.takeIf { it.nonce == nonce } ?: error("No local session")
        session.evidence(httpConstructions - httpAtArm)
    }

    suspend fun sealAndJoin(nonce: String): Boolean {
        val session = synchronized(lock) { registration?.takeIf { it.nonce == nonce } }
            ?: return false
        return session.sealAndJoin()
    }

    /** The fixture draws only its own page View; the display capture never leaves the device. */
    @Suppress("DEPRECATION")
    private suspend fun fixtureFrame(context: Context, fixture: FixtureIdentity): ScreenshotFrame =
        withContext(Dispatchers.IO) {
            val packages = context.packageManager
            check(packages.resolveContentProvider(FIXTURE_AUTHORITY, 0)?.packageName == FIXTURE_PACKAGE &&
                packages.checkSignatures(context.packageName, FIXTURE_PACKAGE) == PackageManager.SIGNATURE_MATCH)
            val snapshot = checkNotNull(context.contentResolver.call(Uri.parse("content://$FIXTURE_AUTHORITY"),
                "snapshot", null, null))
            check(snapshot["freshSessionId"] == fixture.session && snapshot["instanceId"] == fixture.instance &&
                snapshot["processId"] == fixture.pid && snapshot["fixtureTaskId"] == fixture.task &&
                snapshot["fixtureDisplayId"] == fixture.display &&
                snapshot["fixtureProcessStartTicks"] == fixture.startTicks &&
                snapshot["ready"] == true && snapshot["empty"] == true && snapshot["commitCalls"] == 0)
            val png = checkNotNull(snapshot.getByteArray("png"))
            check(png.isNotEmpty() && png.size <= 512 * 1024)
            val width = snapshot.getInt("width")
            val height = snapshot.getInt("height")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            check(width in 1..1280 && height in 1..1280 && bounds.outWidth == width && bounds.outHeight == height)
            ScreenshotFrame(png, width, height, "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
        }

    internal class Session(val nonce: String, val pid: Int, val fixture: FixtureIdentity, val real: Boolean = false) {
        private val lock = Any()
        private var bound = false
        private var service: Any? = null
        private var closed = false
        private var requests = 0
        private var delegated = 0
        private var returned = false
        private var owner: Job? = null
        private var joined = false
        init {
            require(canonical(nonce) && canonical(fixture.session) && canonical(fixture.instance))
            require(pid > 0 && fixture.pid > 0 && fixture.pid != pid && fixture.task > 0 &&
                fixture.display == 0 && fixture.startTicks > 0)
        }
        fun reject(): Nothing = synchronized(lock) {
            closed = true
            service = null
            owner?.cancel()
            throw CancellationException("Local session rejected")
        }
        fun bind(service: Any) = synchronized(lock) {
            if (bound || closed) {
                closed = true
                owner?.cancel()
                throw CancellationException("Local session closed")
            }
            this.service = service
            bound = true
        }
        suspend fun request(service: Any, currentPid: Int, request: DeepSeekVisionRequest): DeepSeekActionResult {
            val context = currentCoroutineContext()
            val job = context[Job] ?: throw CancellationException("Missing task owner")
            synchronized(lock) {
                admit(service, currentPid, request, job, realPath = false)
                context.ensureActive()
                returned = true
            }
            return DeepSeekActionResult.Success("""{"action":"type","text":"执行模式验收通过","reason":"fixture"}""")
        }

        /** Sends the fixture-only frame without tools or app list; any session close discards the response. */
        suspend fun requestReal(
            service: Any,
            currentPid: Int,
            request: DeepSeekVisionRequest,
            toolHistory: List<ToolChatTurn>,
            delegate: DeepSeekClient,
            fixtureFrame: suspend () -> ScreenshotFrame,
            onUpdate: suspend (ModelStreamSnapshot) -> Unit,
        ): ToolChatResult {
            val context = currentCoroutineContext()
            val job = context[Job] ?: throw CancellationException("Missing task owner")
            synchronized(lock) {
                admit(service, currentPid, request, job, realPath = true)
                if (toolHistory.isNotEmpty()) closeAndCancel(job)
            }
            val frame = try { fixtureFrame() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { synchronized(lock) { closeAndCancel(job) } }
            synchronized(lock) {
                if (closed) closeAndCancel(job)
                context.ensureActive()
                delegated++
            }
            val result = delegate.requestDecision(request.copy(frame = frame, availableApps = emptyList()),
                emptyList(), allowTools = false, onUpdate)
            synchronized(lock) {
                if (closed) closeAndCancel(job)
                context.ensureActive()
                if (result is ToolChatResult.Success && result.toolCalls.isNotEmpty()) {
                    return ToolChatResult.Failure("验收通道不接受工具调用", ModelFailure(
                        ModelFailureCategory.RESPONSE_PROTOCOL, protocolReason = ModelProtocolReason.TOOL_NOT_ALLOWED),
                        result.usage)
                }
                if (result is ToolChatResult.Success) returned = true
            }
            return result
        }
        // Caller holds lock. The first admitted request captures the task owner; later ones must match it.
        private fun admit(service: Any, currentPid: Int, request: DeepSeekVisionRequest, job: Job, realPath: Boolean) {
            if (closed || !bound || realPath != real || this.service !== service || currentPid != pid ||
                requests >= (if (real) MAX_REAL_REQUESTS else 1) || (owner != null && owner !== job)) {
                closed = true
                owner?.cancel()
                job.cancel()
                throw CancellationException("Local session closed")
            }
            owner = job // Captured before any child dispatcher or request coroutine exists.
            requests++
            val observation = request.observation
            if (request.config.task != (if (real) REAL_TASK else task(nonce)) || !request.config.manualConfirmation ||
                !request.config.allowScreenUpload || request.config.executionDisplay != ExecutionDisplay.MAIN ||
                request.step != 0 || observation?.displayId != 0 || observation.sessionId != null ||
                observation.foregroundPackage != FIXTURE_PACKAGE || observation.focusedPackage != FIXTURE_PACKAGE ||
                observation.foregroundActivity !in setOf("com.example.rootpilot.fixture.ExecutionFixtureActivity",
                    "com.example.rootpilot.fixture/.ExecutionFixtureActivity", ".ExecutionFixtureActivity")) {
                closed = true
                job.cancel()
                throw CancellationException("Local fixture context rejected")
            }
        }
        // Caller holds lock.
        private fun closeAndCancel(job: Job): Nothing {
            closed = true
            job.cancel()
            throw CancellationException("Local session closed")
        }
        fun evidence(http: Int) = synchronized(lock) {
            Evidence(requests, returned, owner?.isActive, if (joined) true else owner?.isCompleted, closed, http, delegated)
        }
        suspend fun sealAndJoin(): Boolean {
            val job = synchronized(lock) { closed = true; service = null; owner }
            if (job == null || job === currentCoroutineContext()[Job]) return false
            job.cancel()
            job.join()
            return synchronized(lock) {
                joined = job.isCompleted
                if (joined) owner = null
                joined
            }
        }
        private fun canonical(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
