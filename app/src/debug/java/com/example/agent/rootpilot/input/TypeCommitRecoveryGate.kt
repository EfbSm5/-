package com.example.agent.rootpilot.input

import android.os.Process
import android.os.SystemClock
import com.example.agent.rootpilot.root.RootExecutionResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout

/** In-process instrumentation only; a paused submission can only be cancelled, never resumed. */
internal object TypeCommitRecoveryGate {
    private val gate = CommitBoundaryGate(SystemClock::elapsedRealtime, Process::myPid)
    fun arm(id: String, expectedPid: Int, boundary: CommitBoundaryGate.Boundary) = gate.arm(id, expectedPid, boundary)
    fun receipt(id: String): CommitBoundaryGate.Receipt? = gate.receipt(id)
    fun cancel(id: String) = gate.cancel(id)
    suspend fun aroundSubmission(targetPackage: String, submit: suspend () -> RootExecutionResult): RootExecutionResult =
        gate.aroundSubmission(targetPackage, submit)
}

internal class CommitBoundaryGate(private val clock: () -> Long, private val processId: () -> Int) {
    enum class Boundary { BEFORE_SUBMIT, AFTER_SUBMIT }
    data class Receipt(val id: String, val pid: Int, val boundary: Boundary, val reachedAt: Long, val deadline: Long)
    private class Session(val id: String, val pid: Int, val boundary: Boundary, val deadline: Long) {
        val cancellation = CompletableDeferred<Nothing>()
        var owner: Job? = null
        var entered = false
        var reachedAt: Long? = null
    }
    private val lock = Any()
    private var session: Session? = null

    fun arm(id: String, expectedPid: Int, boundary: Boundary) = synchronized(lock) {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "boundary_receipt_invalid" }
        require(expectedPid > 0 && expectedPid == processId()) { "boundary_pid_mismatch" }
        check(session == null) { "boundary_already_armed" }
        session = Session(id, expectedPid, boundary, clock() + TIMEOUT_MILLIS)
    }

    fun receipt(id: String): Receipt? = synchronized(lock) {
        val active = session ?: return@synchronized null
        if (active.id != id || active.reachedAt == null || clock() >= active.deadline ||
            active.cancellation.isCompleted) return@synchronized null
        Receipt(active.id, active.pid, active.boundary, active.reachedAt!!, active.deadline)
    }

    fun cancel(id: String) {
        val active = synchronized(lock) {
            val value = session ?: return
            if (value.id != id) return
            value.cancellation.cancel(CancellationException("boundary_cancelled"))
            // Keep an unconsumed cancellation latched: STOP delivery can race with submission entry.
            // Only that entry (or process death) may clear it; re-arming must not bypass it.
            value
        }
        active.owner?.cancel(CancellationException("boundary_cancelled"))
    }

    suspend fun aroundSubmission(targetPackage: String, submit: suspend () -> RootExecutionResult): RootExecutionResult {
        val owner = currentCoroutineContext()[Job]
        val active = synchronized(lock) {
            session?.also {
                check(!it.entered) { "boundary_already_entered" }
                it.entered = true
                it.owner = owner
            }
        } ?: return submit()
        try {
            if (active.pid != processId() || targetPackage != FIXTURE_PACKAGE || clock() >= active.deadline ||
                active.cancellation.isCompleted) throw CancellationException("boundary_binding_invalid")
            if (active.boundary == Boundary.BEFORE_SUBMIT) pause(active)
            val result = submit()
            if (result is RootExecutionResult.Success && active.boundary == Boundary.AFTER_SUBMIT) pause(active)
            return result
        } finally {
            synchronized(lock) {
                active.cancellation.cancel()
                if (session === active) session = null
            }
        }
    }

    private suspend fun pause(active: Session): Nothing {
        synchronized(lock) {
            if (session !== active || active.cancellation.isCompleted || clock() >= active.deadline) {
                throw CancellationException("boundary_no_longer_active")
            }
            active.reachedAt = clock()
        }
        // No successful completion is possible. Suspending keeps the Android main looper responsive.
        return withTimeout((active.deadline - clock()).coerceAtLeast(1L)) { active.cancellation.await() }
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val FIXTURE_PACKAGE = "com.example.rootpilot.fixture"
    }
}
