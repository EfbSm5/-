package com.example.agent.rootpilot.virtualdisplay

import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Failure
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Identity
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Op
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Reason
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serial pipe ownership makes EOF cleanup testable without Android or a privileged process. */
internal class VirtualDisplayTransport(
    private val session: UUID,
    private val displayAbsent: () -> Boolean,
    private val helperExited: (Int) -> Boolean,
    private val launch: () -> Process,
) {
    private enum class State { NEW, STARTING, ACTIVE, CLOSING, CLOSED, FAILED, CANCELLED }
    private val state = AtomicReference(State.NEW)
    @Volatile private var identity: Identity? = null
    val displayId: Int get() = identity?.displayId ?: -1
    private val process = AtomicReference<Process?>(null)
    private val mutex = Mutex()
    private val pending = AtomicReference<((Reason) -> Unit)?>(null)
    private val cleanupStarted = AtomicBoolean(false)
    private val inputCloseStarted = AtomicBoolean(false)
    private val cleanup = CompletableFuture<Boolean>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RootPilot-VdClient").apply { isDaemon = true }
    }
    private var sequence = 0L
    private var lastReply = 0L
    private var releaseReceived = false
    private var helperPid = -1

    suspend fun start() = operation(VirtualDisplayProtocol.START_TIMEOUT_MS, setOf(State.NEW)) {
        if (!state.compareAndSet(State.NEW, State.STARTING)) throw Failure(Reason.CANCELLED)
        val child = launch()
        process.set(child)
        // A handle may arrive after cancellation; it still belongs to this one-shot session.
        if (state.get() != State.STARTING) {
            closeInput()
            throw Failure(Reason.CANCELLED)
        }
        identity = VirtualDisplayProtocol.parseHello(exchange(Op.HELLO))
        if (!state.compareAndSet(State.STARTING, State.ACTIVE)) throw Failure(Reason.CANCELLED)
    }

    suspend fun validate() = operation(VirtualDisplayProtocol.REQUEST_TIMEOUT_MS, setOf(State.ACTIVE)) {
        if (VirtualDisplayProtocol.parseHello(exchange(Op.VALIDATE)) != identity) throw Failure(Reason.DISPLAY_INVALID)
    }

    suspend fun capture(): ByteArray = operation(VirtualDisplayProtocol.REQUEST_TIMEOUT_MS, setOf(State.ACTIVE)) {
        exchange(Op.CAPTURE).also(VirtualDisplayProtocol::checkPng)
    }

    suspend fun execute(op: Op, payload: ByteArray) = operation(VirtualDisplayProtocol.REQUEST_TIMEOUT_MS, setOf(State.ACTIVE)) {
        if (op !in setOf(Op.OPEN_APP, Op.TAP, Op.WAIT)) throw Failure(Reason.UNSUPPORTED)
        VirtualDisplayProtocol.requireProtocol(exchange(op, payload).isEmpty())
    }

    suspend fun close(): Boolean {
        if (state.get() == State.CLOSED) return true
        if (state.get() in setOf(State.NEW, State.ACTIVE)) {
            try {
                operation(VirtualDisplayProtocol.CLOSE_TIMEOUT_MS, setOf(State.NEW, State.ACTIVE)) {
                    if (state.compareAndSet(State.NEW, State.CLOSING)) {
                        finishCleanup(true)
                    } else {
                        if (!state.compareAndSet(State.ACTIVE, State.CLOSING)) throw Failure(Reason.CANCELLED)
                        VirtualDisplayProtocol.requireProtocol(exchange(Op.CLOSE).isEmpty())
                        closeInput()
                        scheduleCleanup()
                    }
                }
            } catch (error: CancellationException) {
                cancel()
                throw error
            } catch (_: Exception) { /* A terminal operation already initiated EOF cleanup. */ }
        } else if (state.get() == State.STARTING) {
            cancel()
        }
        return awaitCleanup()
    }

    fun cancel() = terminate(Reason.CANCELLED)

    private fun exchange(op: Op, payload: ByteArray = byteArrayOf()): ByteArray {
        if (terminal()) throw Failure(Reason.STATE)
        val child = process.get() ?: throw Failure(Reason.STATE)
        val request = VirtualDisplayProtocol.Frame(session, ++sequence, op, false, payload)
        VirtualDisplayProtocol.write(child.outputStream, request)
        val reply = VirtualDisplayProtocol.read(child.inputStream, response = true) ?: throw Failure(Reason.IO)
        if (reply.op == Op.RELEASED) {
            receiveRelease(reply)
            throw Failure(Reason.CANCELLED)
        }
        VirtualDisplayProtocol.requireProtocol(reply.session == session && reply.sequence == request.sequence && reply.op == op)
        lastReply = reply.sequence
        return VirtualDisplayProtocol.response(request, reply)
    }

    private fun receiveRelease(frame: VirtualDisplayProtocol.Frame) {
        helperPid = VirtualDisplayProtocol.checkReleased(frame, session, sequence, lastReply)
        releaseReceived = true
    }

    private suspend fun <T> operation(timeout: Long, allowed: Set<State>, block: () -> T): T = try {
        mutex.withLock {
            if (state.get() !in allowed) throw Failure(Reason.STATE)
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                val fail: (Reason) -> Unit = { why ->
                    if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.failure(Failure(why)))
                }
                pending.set(fail)
                continuation.invokeOnCancellation { terminate(Reason.CANCELLED) }
                if (terminal()) fail(Reason.CANCELLED)
                val timer = timers.schedule({
                    if (finished.compareAndSet(false, true)) {
                        terminate(Reason.TIMEOUT)
                        continuation.resumeWith(Result.failure(Failure(Reason.TIMEOUT)))
                    }
                }, timeout, TimeUnit.MILLISECONDS)
                if (finished.get()) {
                    timer.cancel(false)
                    pending.compareAndSet(fail, null)
                } else {
                    try {
                        worker.execute {
                            try {
                                if (finished.get()) return@execute
                                val result = block()
                                if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.success(result))
                            } catch (error: Throwable) {
                                terminate(if (error is Failure) error.reason else Reason.IO)
                            } finally {
                                timer.cancel(false)
                                pending.compareAndSet(fail, null)
                            }
                        }
                    } catch (_: Exception) {
                        timer.cancel(false)
                        terminate(Reason.STATE)
                        pending.compareAndSet(fail, null)
                    }
                }
            }
        }
    } catch (error: CancellationException) {
        cancel()
        throw error
    }

    private fun terminal() = state.get() in setOf(State.CANCELLED, State.FAILED, State.CLOSED)

    private fun terminate(reason: Reason) {
        while (true) {
            val old = state.get()
            if (old == State.CLOSED) return
            if (old == State.CANCELLED || old == State.FAILED) break
            if (state.compareAndSet(old, if (reason == Reason.CANCELLED) State.CANCELLED else State.FAILED)) break
        }
        pending.get()?.invoke(reason)
        closeInput()
        scheduleCleanup()
    }

    private fun closeInput() {
        val child = process.get() ?: return
        if (inputCloseStarted.compareAndSet(false, true)) {
            // Deliver cancellation immediately; stream close may contend with a native pipe write.
            Thread({ try { child.outputStream.close() } catch (_: Exception) { } }, "RootPilot-VdEof")
                .apply { isDaemon = true; start() }
        }
    }

    private fun scheduleCleanup() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        val timer = timers.schedule({
            finishCleanup(false)
            // No release proof: preserve identity. EOF and the helper watchdog still own root teardown.
            process.get()?.let { child ->
                Thread({ dispose(child) }, "RootPilot-VdAbort").apply { isDaemon = true; start() }
            }
        }, VirtualDisplayProtocol.CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        worker.execute {
            val child = process.get()
            try {
                if (child == null) {
                    // This runs behind launch on the same worker, so no late create can still start.
                    finishCleanup(true)
                } else {
                    closeInput()
                    if (!releaseReceived) {
                        var receipt = VirtualDisplayProtocol.read(child.inputStream, response = true) ?: throw Failure(Reason.RELEASE)
                        // Cancellation can close stdin after the last write but before its reply is read.
                        if (receipt.op != Op.RELEASED) {
                            VirtualDisplayProtocol.requireProtocol(receipt.session == session && receipt.sequence == sequence &&
                                receipt.sequence > lastReply)
                            lastReply = receipt.sequence
                            receipt = VirtualDisplayProtocol.read(child.inputStream, response = true) ?: throw Failure(Reason.RELEASE)
                        }
                        receiveRelease(receipt)
                    }
                    // An ordinary su-wrapper exit alone never proves that its root helper has exited.
                    // RELEASED is emitted after the helper's last possible create/release operation.
                    if (!child.waitFor(2_000, TimeUnit.MILLISECONDS)) throw Failure(Reason.RELEASE)
                    finishCleanup(releaseReceived && helperExited(helperPid) && displayAbsent())
                }
            } catch (_: Throwable) {
                finishCleanup(false)
            } finally {
                try {
                    child?.let(::dispose)
                } finally {
                    timer.cancel(false)
                    worker.shutdown()
                }
            }
        }
    }

    private fun finishCleanup(confirmed: Boolean) {
        synchronized(cleanup) {
            if (cleanup.isDone) return
            if (confirmed) {
                state.set(State.CLOSED)
                identity = null
                process.set(null)
                worker.shutdown()
            }
            cleanup.complete(confirmed)
        }
    }

    private suspend fun awaitCleanup(): Boolean = suspendCancellableCoroutine { continuation ->
        val delivered = AtomicBoolean(false)
        val timer = timers.schedule({
            if (delivered.compareAndSet(false, true)) continuation.resumeWith(Result.success(false))
        }, VirtualDisplayProtocol.CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        cleanup.whenComplete { result, error ->
            if (delivered.compareAndSet(false, true)) continuation.resumeWith(Result.success(error == null && result == true))
            timer.cancel(false)
        }
        continuation.invokeOnCancellation {
            delivered.set(true)
            timer.cancel(false)
            cancel()
        }
    }

    private fun dispose(child: Process) {
        try { child.outputStream.close() } catch (_: Exception) { }
        try { child.destroyForcibly() } catch (_: Exception) { }
        try { child.inputStream.close() } catch (_: Exception) { }
        try { child.errorStream.close() } catch (_: Exception) { }
    }

    private companion object {
        val timers = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "RootPilot-VdDeadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}
