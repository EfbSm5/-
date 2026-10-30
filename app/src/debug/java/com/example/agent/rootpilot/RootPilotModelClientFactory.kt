package com.example.agent.rootpilot

import android.os.Looper
import android.os.Process
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.HttpDeepSeekClient
import com.example.agent.rootpilot.model.ExecutionDisplay
import com.example.agent.rootpilot.model.RootPilotStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Debug-only, process-lifetime, fixed fixture response. No configurable model or transport. */
internal object RootPilotModelClientFactory {
    data class FixtureIdentity(val session: String, val instance: String, val pid: Int,
        val task: Int, val display: Int, val startTicks: Long)
    data class Evidence(val fixedRequests: Int, val responseReturned: Boolean,
        val ownerActive: Boolean?, val ownerCompleted: Boolean?, val closed: Boolean,
        val registeredHttpConstructions: Int)

    private val lock = Any()
    private var registration: Session? = null
    private var httpConstructions = 0
    private var httpAtArm = 0
    fun task(nonce: String): String = "两阶段本地输入验收:$nonce"

    fun arm(nonce: String, expectedPid: Int, fixture: FixtureIdentity) {
        check(Looper.myLooper() === Looper.getMainLooper())
        check(expectedPid == Process.myPid())
        check(registration == null)
        val state = RootPilotService.uiState.value
        check(!state.running && state.pendingAction == null && state.status != RootPilotStatus.RECOVERY_REQUIRED)
        check(ServiceDestroyObservation.current?.let { !it.destroyed && it.service.get() != null } != true)
        synchronized(lock) {
            check(registration == null)
            registration = Session(nonce, expectedPid, fixture)
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
                object : DeepSeekClient {
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

    internal class Session(val nonce: String, val pid: Int, val fixture: FixtureIdentity) {
        private val lock = Any()
        private var bound = false
        private var service: Any? = null
        private var closed = false
        private var requests = 0
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
                if (closed || !bound || this.service !== service || currentPid != pid || requests != 0) {
                    closed = true
                    owner?.cancel()
                    job.cancel()
                    throw CancellationException("Local session closed")
                }
                owner = job // Captured before any child dispatcher or request coroutine exists.
                requests++
                val observation = request.observation
                if (request.config.task != task(nonce) || !request.config.manualConfirmation ||
                    !request.config.allowScreenUpload || request.config.executionDisplay != ExecutionDisplay.MAIN ||
                    request.step != 0 || observation?.displayId != 0 || observation.sessionId != null ||
                    observation.foregroundPackage != "com.example.rootpilot.fixture" ||
                    observation.focusedPackage != "com.example.rootpilot.fixture" ||
                    observation.foregroundActivity !in setOf("com.example.rootpilot.fixture.ExecutionFixtureActivity",
                        "com.example.rootpilot.fixture/.ExecutionFixtureActivity", ".ExecutionFixtureActivity")) {
                    closed = true
                    job.cancel()
                    throw CancellationException("Local fixture context rejected")
                }
                context.ensureActive()
                returned = true
            }
            return DeepSeekActionResult.Success("""{"action":"type","text":"执行模式验收通过","reason":"fixture"}""")
        }
        fun evidence(http: Int) = synchronized(lock) {
            Evidence(requests, returned, owner?.isActive, if (joined) true else owner?.isCompleted, closed, http)
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
