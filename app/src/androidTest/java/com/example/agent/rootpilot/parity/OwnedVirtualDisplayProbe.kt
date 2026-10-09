package com.example.agent.rootpilot.parity

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit test-only primitive; construction neither starts a helper nor changes product settings. */
internal class OwnedVirtualDisplayProbe(context: Context, testApkPath: String, profile: ProbeProfile) {
    private val session = UUID.randomUUID()
    val sessionId: String = session.toString()
    private val appContext = context.applicationContext
    private val client = ProbeClient(session, displayAbsent = { id ->
        val manager = appContext.getSystemService(DisplayManager::class.java) ?: fail(ProbeReason.RELEASE)
        manager.displays.none { it.name == ProbeWire.name(session) || it.displayId == id }
    }, helperExited = { pid ->
        try {
            Os.kill(pid, 0)
            false
        } catch (error: ErrnoException) {
            error.errno == OsConstants.ESRCH
        }
    }, launch = {
        // Resolve only installed code paths, including when the caller supplies the test Context.
        val packages = appContext.packageManager
        val mainApk = packages.getApplicationInfo("com.example.agent", 0).sourceDir
        val installedTestApk = packages.getApplicationInfo("com.example.agent.test", 0).sourceDir
        if (testApkPath != installedTestApk) fail(ProbeReason.ARGUMENT)
        ProcessBuilder("su", "-c", ProbeWire.launch(mainApk, testApkPath, session, profile))
            .redirectError(File("/dev/null")).start()
    })

    suspend fun start(): ProbeDisplayIdentity = client.start()
    suspend fun request(command: ProbeCommand): ProbeDisplayIdentity = client.request(command)
    suspend fun close(): Boolean = client.close()
}

private class ProbeClient(
    private val session: UUID,
    private val displayAbsent: (Int) -> Boolean,
    private val helperExited: (Int) -> Boolean,
    private val launch: () -> java.lang.Process,
) {
    private enum class State { NEW, STARTING, ACTIVE, CLOSING, CLOSED, FAILED, CANCELLED }
    private val state = AtomicReference(State.NEW)
    private val process = AtomicReference<java.lang.Process?>(null)
    private val pending = AtomicReference<((ProbeReason) -> Unit)?>(null)
    private val cleanupStarted = AtomicBoolean(false)
    private val inputCloseStarted = AtomicBoolean(false)
    private val cleanup = CompletableFuture<Boolean>()
    private val mutex = Mutex()
    private val worker = Executors.newSingleThreadExecutor { task -> daemon("RootPilot-ParityClient", task::run) }
    private var sequence = 0L
    private var lastReply = 0L
    private var identity: ProbeDisplayIdentity? = null
    private var release: ProbeWire.Release? = null

    suspend fun start(): ProbeDisplayIdentity = operation(ProbeWire.START_MS, setOf(State.NEW)) {
        checkProbe(state.compareAndSet(State.NEW, State.STARTING), ProbeReason.STATE)
        val child = launch()
        process.set(child)
        if (state.get() != State.STARTING) {
            closeInput()
            fail(ProbeReason.CANCELLED)
        }
        val created = ProbeWire.identity(exchange(ProbeWire.Op.HELLO))
        checkProbe(created.width == ProbeWire.WIDTH && created.height == ProbeWire.HEIGHT && created.rotation == 0 &&
            created.imePolicy == 2 && created.frameBytes == 0, ProbeReason.DISPLAY)
        identity = created
        checkProbe(state.compareAndSet(State.STARTING, State.ACTIVE), ProbeReason.CANCELLED)
        created
    }

    suspend fun request(command: ProbeCommand): ProbeDisplayIdentity = operation(ProbeWire.REQUEST_MS, setOf(State.ACTIVE)) {
        val held = identity ?: fail(ProbeReason.STATE)
        val result = ProbeWire.identity(exchange(ProbeWire.op(command)))
        checkProbe(result.displayId == held.displayId && result.uniqueId == held.uniqueId && result.helperPid == held.helperPid,
            ProbeReason.DISPLAY)
        checkProbe(if (command == ProbeCommand.CAPTURE_METADATA) result.imePolicy == 2 && result.frameBytes >= 33
            else result.frameBytes == 0)
        identity = result
        result
    }

    suspend fun close(): Boolean {
        if (state.get() == State.CLOSED) return true
        if (state.compareAndSet(State.NEW, State.CLOSING)) {
            // No helper was started, so there can be no release acknowledgment.
            finishCleanup(false)
            worker.shutdown()
            return false
        }
        if (state.get() == State.ACTIVE) {
            try {
                operation(ProbeWire.CLOSE_MS, setOf(State.ACTIVE)) {
                    checkProbe(state.compareAndSet(State.ACTIVE, State.CLOSING), ProbeReason.CANCELLED)
                    checkProbe(exchange(ProbeWire.Op.CLOSE).isEmpty())
                    closeInput()
                    scheduleCleanup()
                }
            } catch (error: CancellationException) {
                terminate(ProbeReason.CANCELLED)
                throw error
            } catch (_: Exception) { /* Failure already initiated bounded EOF cleanup. */ }
        } else if (state.get() == State.STARTING) {
            terminate(ProbeReason.CANCELLED)
        }
        return awaitCleanup()
    }

    private fun exchange(op: ProbeWire.Op): ByteArray {
        checkProbe(!terminal() && sequence < ProbeWire.MAX_REQUESTS, ProbeReason.STATE)
        val child = process.get() ?: fail(ProbeReason.STATE)
        val request = ProbeWire.Frame(session, ++sequence, op, false, byteArrayOf())
        ProbeWire.write(child.outputStream, request)
        val response = ProbeWire.read(child.inputStream, true) ?: fail(ProbeReason.IO)
        if (response.op == ProbeWire.Op.RELEASED) {
            receiveRelease(response)
            fail(ProbeReason.CANCELLED)
        }
        val body = ProbeWire.response(request, response)
        lastReply = response.sequence
        return body
    }

    private fun receiveRelease(frame: ProbeWire.Frame) {
        val receipt = ProbeWire.release(frame, session, sequence, lastReply)
        identity?.let {
            checkProbe(receipt.pid == it.helperPid && receipt.displayId == it.displayId && receipt.uniqueId == it.uniqueId,
                ProbeReason.RELEASE)
        }
        release = receipt
    }

    private suspend fun <T> operation(timeout: Long, allowed: Set<State>, block: () -> T): T = try {
        mutex.withLock {
            checkProbe(state.get() in allowed, ProbeReason.STATE)
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                val reject: (ProbeReason) -> Unit = { why ->
                    if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.failure(ProbeFailure(why)))
                }
                pending.set(reject)
                continuation.invokeOnCancellation { terminate(ProbeReason.CANCELLED) }
                val timer = timers.schedule({
                    if (finished.compareAndSet(false, true)) {
                        terminate(ProbeReason.TIMEOUT)
                        continuation.resumeWith(Result.failure(ProbeFailure(ProbeReason.TIMEOUT)))
                    }
                }, timeout, TimeUnit.MILLISECONDS)
                if (terminal()) reject(ProbeReason.CANCELLED)
                if (finished.get()) {
                    timer.cancel(false)
                    pending.compareAndSet(reject, null)
                } else {
                    try {
                        worker.execute {
                            try {
                                if (!finished.get()) {
                                    val result = block()
                                    if (finished.compareAndSet(false, true)) continuation.resumeWith(Result.success(result))
                                }
                            } catch (error: Throwable) {
                                terminate((error as? ProbeFailure)?.reason ?: ProbeReason.IO)
                            } finally {
                                timer.cancel(false)
                                pending.compareAndSet(reject, null)
                            }
                        }
                    } catch (_: Exception) {
                        timer.cancel(false)
                        terminate(ProbeReason.STATE)
                        pending.compareAndSet(reject, null)
                    }
                }
            }
        }
    } catch (error: CancellationException) {
        terminate(ProbeReason.CANCELLED)
        throw error
    }

    private fun terminal() = state.get() in setOf(State.FAILED, State.CANCELLED, State.CLOSED)
    private fun terminate(reason: ProbeReason) {
        while (true) {
            val previous = state.get()
            if (previous == State.CLOSED) return
            if (previous in setOf(State.CANCELLED, State.FAILED)) break
            if (state.compareAndSet(previous, if (reason == ProbeReason.CANCELLED) State.CANCELLED else State.FAILED)) break
        }
        pending.get()?.invoke(reason)
        closeInput()
        scheduleCleanup()
    }

    private fun closeInput() {
        val child = process.get() ?: return
        if (inputCloseStarted.compareAndSet(false, true)) {
            daemon("RootPilot-ParityEof") { try { child.outputStream.close() } catch (_: Throwable) { } }.start()
        }
    }

    private fun scheduleCleanup() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        val timer = timers.schedule({
            finishCleanup(false)
            process.get()?.let { child -> daemon("RootPilot-ParityAbort") { dispose(child) }.start() }
        }, ProbeWire.CLOSE_MS, TimeUnit.MILLISECONDS)
        worker.execute {
            val child = process.get()
            try {
                if (child == null) {
                    // Serialized behind launch; no helper remains, but release still lacks an ack.
                    finishCleanup(false)
                } else {
                    closeInput()
                    if (release == null) {
                        var frame = ProbeWire.read(child.inputStream, true) ?: fail(ProbeReason.RELEASE)
                        if (frame.op != ProbeWire.Op.RELEASED) {
                            checkProbe(frame.session == session && frame.sequence == sequence && frame.sequence > lastReply)
                            checkProbe(frame.op != ProbeWire.Op.HELLO || identity == null)
                            lastReply = frame.sequence
                            frame = ProbeWire.read(child.inputStream, true) ?: fail(ProbeReason.RELEASE)
                        }
                        receiveRelease(frame)
                    }
                    val receipt = release ?: fail(ProbeReason.RELEASE)
                    checkProbe(child.waitFor(2_000, TimeUnit.MILLISECONDS), ProbeReason.RELEASE)
                    val until = SystemClock.elapsedRealtime() + 1_500
                    var confirmed: Boolean
                    do {
                        confirmed = helperExited(receipt.pid) && displayAbsent(receipt.displayId)
                        if (confirmed || SystemClock.elapsedRealtime() >= until) break
                        Thread.sleep(20)
                    } while (true)
                    finishCleanup(confirmed)
                }
            } catch (_: Throwable) {
                finishCleanup(false)
            } finally {
                try { child?.let(::dispose) } finally { timer.cancel(false); worker.shutdown() }
            }
        }
    }

    private fun finishCleanup(confirmed: Boolean) {
        synchronized(cleanup) {
            if (cleanup.isDone) return
            if (confirmed) { state.set(State.CLOSED); worker.shutdown() }
            cleanup.complete(confirmed)
        }
    }

    private suspend fun awaitCleanup(): Boolean = suspendCancellableCoroutine { continuation ->
        val delivered = AtomicBoolean(false)
        val timer = timers.schedule({
            if (delivered.compareAndSet(false, true)) continuation.resumeWith(Result.success(false))
        }, ProbeWire.CLOSE_MS, TimeUnit.MILLISECONDS)
        cleanup.whenComplete { result, error ->
            if (delivered.compareAndSet(false, true)) continuation.resumeWith(Result.success(error == null && result == true))
            timer.cancel(false)
        }
        continuation.invokeOnCancellation { delivered.set(true); timer.cancel(false); terminate(ProbeReason.CANCELLED) }
    }

    private fun dispose(child: java.lang.Process) {
        try { child.destroyForcibly() } catch (_: Throwable) { }
        try { child.outputStream.close() } catch (_: Throwable) { }
        try { child.inputStream.close() } catch (_: Throwable) { }
        try { child.errorStream.close() } catch (_: Throwable) { }
    }

    private companion object {
        val timers = ScheduledThreadPoolExecutor(1) { task -> daemon("RootPilot-ParityDeadline", task::run) }
            .apply { removeOnCancelPolicy = true }
    }
}

private fun daemon(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true }

/** Fixed app_process entry, present only in the test APK. No Android runner or network listener. */
internal object OwnedVirtualDisplayProbeHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val output = FileOutputStream(Os.dup(FileDescriptor.out))
            val sink = Os.open("/dev/null", OsConstants.O_RDWR, 0)
            try {
                Os.dup2(sink, OsConstants.STDOUT_FILENO)
                Os.dup2(sink, OsConstants.STDERR_FILENO)
            } finally { Os.close(sink) }
            checkProbe(Process.myUid() == 0 && args.size == 2, ProbeReason.ARGUMENT)
            val session = UUID.fromString(args[0])
            checkProbe(session.toString() == args[0] && session.version() == 4 && session.variant() == 2, ProbeReason.ARGUMENT)
            val profile = ProbeProfile.entries.singleOrNull { it.name == args[1] } ?: fail(ProbeReason.ARGUMENT)
            RuntimeSession(session, profile, FileInputStream(FileDescriptor.`in`), output).run()
        } catch (_: Throwable) { Runtime.getRuntime().halt(1) }
    }

    private class RuntimeSession(
        private val session: UUID,
        profile: ProbeProfile,
        private val input: FileInputStream,
        private val output: FileOutputStream,
    ) {
        private val main = Thread.currentThread()
        private val stopped = AtomicBoolean(false)
        private val closeAcknowledged = AtomicBoolean(false)
        private val child = AtomicReference<java.lang.Process?>(null)
        private val requests = ArrayBlockingQueue<ProbeWire.Frame>(1)
        private val accepted = AtomicLong(0)
        private val started = SystemClock.elapsedRealtime()
        private val operationDeadline = AtomicLong(started + ProbeWire.START_MS)
        private val frameDeadline = AtomicLong(Long.MAX_VALUE)
        private val idleDeadline = AtomicLong(started + ProbeWire.IDLE_MS)
        private val shutdownDeadline = AtomicLong(Long.MAX_VALUE)
        private val owner = ProbeOwner(session, profile)

        fun run() {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> stop() }
            daemon("RootPilot-ParityWatchdog") {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= minOf(operationDeadline.get(), frameDeadline.get(), idleDeadline.get(),
                            shutdownDeadline.get(), started + ProbeWire.LIFETIME_MS)) {
                        try { child.get()?.destroyForcibly() } catch (_: Throwable) { }
                        // Binder death destroys the owned display even when a Binder operation is stuck.
                        Process.killProcess(Process.myPid())
                        Runtime.getRuntime().halt(1)
                    }
                    Thread.sleep(50)
                }
            }.start()
            daemon("RootPilot-ParityReader") {
                try {
                    var next = 1L
                    while (!stopped.get()) {
                        val frame = ProbeWire.read(input, false) {
                            frameDeadline.set(SystemClock.elapsedRealtime() + ProbeWire.FRAME_MS)
                        }
                        frameDeadline.set(Long.MAX_VALUE)
                        if (frame == null) {
                            if (!closeAcknowledged.get()) stop()
                            return@daemon
                        }
                        checkProbe(frame.session == session && frame.sequence == next && next <= ProbeWire.MAX_REQUESTS &&
                            frame.op != ProbeWire.Op.RELEASED && ((next == 1L) == (frame.op == ProbeWire.Op.HELLO)))
                        next++
                        accepted.set(frame.sequence)
                        checkProbe(requests.offer(frame))
                    }
                } catch (_: Throwable) { stop() }
            }.start()
            var ready = false
            var released = false
            var exit = 1
            try {
                while (!stopped.get()) {
                    val request = requests.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    operationDeadline.set(SystemClock.elapsedRealtime() + if (ready) ProbeWire.REQUEST_MS else ProbeWire.START_MS)
                    try {
                        checkProbe(Process.myUid() == 0 && request.session == session, ProbeReason.STATE)
                        val body = when (request.op) {
                            ProbeWire.Op.HELLO -> {
                                checkProbe(!ready, ProbeReason.STATE)
                                ProbeWire.identity(owner.create()).also { ready = true }
                            }
                            ProbeWire.Op.CLOSE -> {
                                checkProbe(ready, ProbeReason.STATE)
                                checkProbe(owner.release(), ProbeReason.RELEASE)
                                released = true
                                closeAcknowledged.set(true)
                                byteArrayOf()
                            }
                            else -> {
                                checkProbe(ready, ProbeReason.STATE)
                                val before = owner.identity()
                                var frameBytes = 0
                                when (request.op) {
                                    ProbeWire.Op.OPEN_FIXTURE -> {
                                        owner.discardFrames()
                                        owner.identity()
                                        command(listOf("/system/bin/cmd", "activity", "start-activity",
                                            "-W", "--display", before.displayId.toString(), "-n",
                                            "com.example.rootpilot.fixture/.VirtualCapabilityActivity"))
                                    }
                                    ProbeWire.Op.HOME -> {
                                        owner.discardFrames()
                                        owner.identity()
                                        command(listOf("/system/bin/input", "-d", before.displayId.toString(), "keyevent", "3"))
                                    }
                                    ProbeWire.Op.IME_LOCAL -> owner.ime(0)
                                    ProbeWire.Op.IME_HIDE -> owner.ime(2)
                                    ProbeWire.Op.LANDSCAPE -> owner.resize(ProbeWire.HEIGHT, ProbeWire.WIDTH)
                                    ProbeWire.Op.PORTRAIT -> owner.resize(ProbeWire.WIDTH, ProbeWire.HEIGHT)
                                    ProbeWire.Op.ROTATE_90 -> owner.rotate(1)
                                    ProbeWire.Op.ROTATE_0 -> owner.rotate(0)
                                    ProbeWire.Op.CAPTURE_METADATA -> frameBytes = owner.captureMetadata()
                                    else -> fail(ProbeReason.PROTOCOL)
                                }
                                val after = owner.identity(frameBytes)
                                checkProbe(after.displayId == before.displayId && after.uniqueId == before.uniqueId, ProbeReason.DISPLAY)
                                ProbeWire.identity(after)
                            }
                        }
                        checkProbe(!stopped.get(), ProbeReason.CANCELLED)
                        ProbeWire.write(output, ProbeWire.reply(request, ProbeReason.OK, body))
                        if (request.op == ProbeWire.Op.CLOSE) { exit = 0; break }
                        idleDeadline.set(SystemClock.elapsedRealtime() + ProbeWire.IDLE_MS)
                        operationDeadline.set(Long.MAX_VALUE)
                    } catch (error: Throwable) {
                        if (!stopped.get()) ProbeWire.write(output, ProbeWire.reply(request,
                            (error as? ProbeFailure)?.reason ?: ProbeReason.DISPLAY))
                        break
                    }
                }
            } catch (_: Throwable) {
                // EOF, malformed frames and cancellation share the same bounded release path.
            } finally {
                shutdownDeadline.compareAndSet(Long.MAX_VALUE, SystemClock.elapsedRealtime() + 2_500)
                Thread.interrupted()
                try { child.get()?.destroyForcibly() } catch (_: Throwable) { }
                if (!released) released = try { owner.release() } catch (_: Throwable) { false }
                if (released) try {
                    ProbeWire.write(output, ProbeWire.released(session, accepted.get(), owner.releaseIdentity()))
                } catch (_: Throwable) { }
                Runtime.getRuntime().halt(exit)
            }
        }

        private fun command(arguments: List<String>) {
            val process = ProcessBuilder(arguments).redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
            child.set(process)
            try {
                process.outputStream.close()
                checkProbe(!stopped.get(), ProbeReason.CANCELLED)
                checkProbe(process.waitFor(ProbeWire.COMMAND_MS, TimeUnit.MILLISECONDS) && process.exitValue() == 0, ProbeReason.COMMAND)
            } finally {
                try { process.destroyForcibly() } catch (_: Throwable) { }
                try { process.outputStream.close() } catch (_: Throwable) { }
                try { process.inputStream.close() } catch (_: Throwable) { }
                try { process.errorStream.close() } catch (_: Throwable) { }
                child.compareAndSet(process, null)
            }
        }

        private fun stop() {
            if (stopped.compareAndSet(false, true)) {
                shutdownDeadline.set(SystemClock.elapsedRealtime() + 2_500)
                main.interrupt()
                try { child.get()?.destroyForcibly() } catch (_: Throwable) { }
            }
        }
    }
}

@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "WrongConstant")
private class ProbeOwner(private val session: UUID, private val profile: ProbeProfile) {
    private val name = ProbeWire.name(session)
    private val imageLock = Object()
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var imageThread: HandlerThread? = null
    private var imageFailure = false
    private var discardedTimestamp = Long.MIN_VALUE
    private var closing = false
    private var displayId = -1
    private var uniqueId = ""
    private var width = ProbeWire.WIDTH
    private var height = ProbeWire.HEIGHT
    private var rotation = 0
    private var imePolicy = 2
    private var rotationTouched = false
    private lateinit var displayService: Any
    private lateinit var systemContext: Context
    private lateinit var displayInterface: Class<*>
    private lateinit var windowService: Any
    private lateinit var windowInterface: Class<*>
    private lateinit var trustService: Any
    private lateinit var trustInterface: Class<*>
    private lateinit var powerService: Any
    private lateinit var powerInterface: Class<*>
    private lateinit var freeze: Method
    private lateinit var thaw: Method

    fun create(): ProbeDisplayIdentity {
        checkProbe(Process.myUid() == 0 && virtualDisplay == null && !closing, ProbeReason.STATE)
        var checkpoint = ProbeReason.CREATE_CONTEXT
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            systemContext = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            checkpoint = ProbeReason.CREATE_BINDERS
            windowInterface = Class.forName("android.view.IWindowManager")
            windowService = service("window", "android.view.IWindowManager")
            trustInterface = Class.forName("android.app.trust.ITrustManager")
            trustService = service("trust", "android.app.trust.ITrustManager")
            powerInterface = Class.forName("android.os.IPowerManager")
            powerService = service("power", "android.os.IPowerManager")
            checkpoint = ProbeReason.CREATE_PREFLIGHT
            preflight()
            val shellContext = object : ContextWrapper(systemContext) {
                override fun getPackageName() = "com.android.shell"
                override fun getOpPackageName() = "com.android.shell"
                override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(Process.SHELL_UID)
                    .setPackageName("com.android.shell").build()
            }
            checkpoint = ProbeReason.CREATE_BINDERS
            displayInterface = Class.forName("android.hardware.display.IDisplayManager")
            displayService = service("display", "android.hardware.display.IDisplayManager")
            // SDK public stubs omit IWindowManager; accept only its two known display-bound signatures.
            checkpoint = ProbeReason.CREATE_ROTATION_API
            freeze = rotationMethod("freezeDisplayRotation", listOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!))
            thaw = rotationMethod("thawDisplayRotation", listOf(Int::class.javaPrimitiveType!!))
            checkpoint = ProbeReason.CREATE_SURFACE
            checkProbe(dedicatedDisplays().isEmpty(), ProbeReason.DISPLAY)
            val manager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                .newInstance(shellContext)
            imageThread = HandlerThread("RootPilot-ParityImages").also { it.start() }
            reader = newReader(width, height)
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                (1 shl 6) or (1 shl 8) or (1 shl 10) or (1 shl 11) or (1 shl 14) or
                if (profile == ProbeProfile.DECORATED) (1 shl 9) else 0
            checkpoint = ProbeReason.CREATE_PREFLIGHT
            preflight()
            checkpoint = ProbeReason.CREATE_ALLOCATE
            checkProbe(dedicatedDisplays().isEmpty(), ProbeReason.DISPLAY)
            val display = manager.createVirtualDisplay(name, width, height, ProbeWire.DPI, reader!!.surface, flags)
                ?: fail(ProbeReason.DISPLAY)
            virtualDisplay = display
            displayId = display.display.displayId
            checkpoint = ProbeReason.CREATE_BINDING
            checkProbe(displayId > 0, ProbeReason.DISPLAY)
            val info = ownedInfo(requireUnique = false)
            uniqueId = string(info, "uniqueId")
            checkProbe(ProbeWire.safeUniqueId(uniqueId), ProbeReason.DISPLAY)
            ownedInfo()
            checkpoint = ProbeReason.CREATE_IME
            synchronized(imageLock) {
                setIme(2)
                discardFramesLocked()
            }
            checkpoint = ProbeReason.CREATE_IDENTITY
            return identity()
        } catch (error: Throwable) {
            // Only fixed initialization checkpoints cross the pipe, never reflection error text.
            if (error is ProbeFailure && error.reason in setOf(ProbeReason.STATE, ProbeReason.CANCELLED)) throw error
            fail(checkpoint)
        }
    }

    fun identity(frameBytes: Int = 0): ProbeDisplayIdentity {
        synchronized(imageLock) { checkProbe(!imageFailure, ProbeReason.DISPLAY) }
        val info = ownedInfo()
        checkGeometry(info)
        val currentIme = windowInterface.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
            .invoke(windowService, displayId) as Int
        checkProbe(currentIme == imePolicy, ProbeReason.DISPLAY)
        return ProbeDisplayIdentity(displayId, uniqueId, int(info, "logicalWidth"), int(info, "logicalHeight"),
            int(info, "logicalDensityDpi"), int(info, "rotation"), currentIme, frameBytes, Process.myPid())
            .also(ProbeWire::checkIdentity)
    }

    fun ime(policy: Int) {
        identity()
        checkProbe(policy == 0 || policy == 2, ProbeReason.ARGUMENT)
        synchronized(imageLock) {
            discardFramesLocked()
            setIme(policy)
            imePolicy = policy
            discardFramesLocked()
        }
        identity()
    }

    private fun setIme(policy: Int) {
        ownedInfo()
        windowInterface.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(windowService, displayId, policy)
    }

    fun resize(newWidth: Int, newHeight: Int) {
        identity()
        checkProbe((newWidth == ProbeWire.WIDTH && newHeight == ProbeWire.HEIGHT) ||
            (newWidth == ProbeWire.HEIGHT && newHeight == ProbeWire.WIDTH), ProbeReason.ARGUMENT)
        val next = newReader(newWidth, newHeight)
        var adopted = false
        try {
            synchronized(imageLock) {
                val display = virtualDisplay ?: fail(ProbeReason.DISPLAY)
                ownedInfo()
                discardFramesLocked()
                display.surface = null
                reader?.setOnImageAvailableListener(null, null)
                latest?.close(); latest = null
                display.resize(newWidth, newHeight, ProbeWire.DPI)
                display.surface = next.surface
                val previous = reader
                reader = next
                adopted = true
                width = newWidth; height = newHeight
                discardedTimestamp = Long.MIN_VALUE
                previous?.close()
            }
            awaitGeometry()
        } finally { if (!adopted) next.close() }
    }

    fun rotate(target: Int) {
        identity()
        checkProbe(target in 0..1, ProbeReason.ARGUMENT)
        ownedInfo()
        discardFrames()
        ownedInfo()
        rotationTouched = true
        if (freeze.parameterCount == 3) freeze.invoke(windowService, displayId, target, "RootPilotParity")
        else freeze.invoke(windowService, displayId, target)
        rotation = target
        awaitGeometry()
    }

    fun captureMetadata(): Int {
        val before = identity()
        checkProbe(before.imePolicy == 2, ProbeReason.CAPTURE)
        val until = SystemClock.elapsedRealtime() + ProbeWire.CAPTURE_MS
        val bitmap = synchronized(imageLock) {
            while (latest == null && !closing && !imageFailure && imePolicy == 2) {
                val remaining = until - SystemClock.elapsedRealtime()
                checkProbe(remaining > 0, ProbeReason.CAPTURE)
                imageLock.wait(remaining)
            }
            checkProbe(!closing && !imageFailure && imePolicy == 2, ProbeReason.CAPTURE)
            checkProbe(identity().imePolicy == 2, ProbeReason.CAPTURE)
            val image = latest ?: fail(ProbeReason.CAPTURE)
            latest = null
            discardedTimestamp = maxOf(discardedTimestamp, image.timestamp)
            try {
                // Without ROTATES_WITH_CONTENT the physical buffer retains its resize dimensions.
                checkProbe(image.width == width && image.height == height && image.format == PixelFormat.RGBA_8888 &&
                    image.planes.size == 1, ProbeReason.CAPTURE)
                val plane = image.planes[0]
                checkProbe(plane.pixelStride == 4 && plane.rowStride in width * 4..width * 4 + 4096, ProbeReason.CAPTURE)
                val buffer = plane.buffer.duplicate()
                val start = buffer.position()
                val row = ByteArray(width * 4)
                val pixels = IntArray(width * height)
                for (y in 0 until height) {
                    buffer.position(start + y * plane.rowStride)
                    buffer.get(row)
                    for (x in 0 until width) {
                        val offset = x * 4
                        pixels[y * width + x] = ((row[offset + 3].toInt() and 255) shl 24) or
                            ((row[offset].toInt() and 255) shl 16) or ((row[offset + 1].toInt() and 255) shl 8) or
                            (row[offset + 2].toInt() and 255)
                    }
                }
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            } finally { image.close() }
        }
        try {
            val count = PngCount()
            checkProbe(identity().imePolicy == 2, ProbeReason.CAPTURE)
            checkProbe(bitmap.compress(Bitmap.CompressFormat.PNG, 100, count) && count.size in 33..ProbeWire.MAX_PNG, ProbeReason.CAPTURE)
            checkProbe(identity() == before, ProbeReason.DISPLAY)
            return count.size
        } finally { bitmap.recycle() }
    }

    fun releaseIdentity() = ProbeWire.Release(Process.myPid(), displayId, uniqueId)

    fun discardFrames() {
        identity()
        synchronized(imageLock) { discardFramesLocked() }
    }

    private fun discardFramesLocked() {
        latest?.let { discardedTimestamp = maxOf(discardedTimestamp, it.timestamp); it.close() }
        latest = null
        // acquireLatestImage drains older queued buffers. The reader's finite capacity bounds this drain.
        repeat(3) {
            reader?.acquireLatestImage()?.let {
                try { discardedTimestamp = maxOf(discardedTimestamp, it.timestamp) } finally { it.close() }
            }
        }
    }

    fun release(): Boolean {
        synchronized(imageLock) { closing = true; imageLock.notifyAll() }
        var clean = true
        fun attempt(block: () -> Unit) { try { block() } catch (_: Throwable) { clean = false } }
        // Thaw only the same still-owned non-default display; never restore global rotation.
        if (rotationTouched) attempt {
            ownedInfo(allowClosing = true)
            if (thaw.parameterCount == 2) thaw.invoke(windowService, displayId, "RootPilotParity")
            else thaw.invoke(windowService, displayId)
        }
        attempt { virtualDisplay?.release() }
        attempt {
            synchronized(imageLock) {
                reader?.setOnImageAvailableListener(null, null)
                latest?.close(); latest = null
                reader?.close(); reader = null
            }
        }
        attempt { imageThread?.quitSafely() }
        if (virtualDisplay != null) attempt {
            checkProbe(displayId > 0 && ProbeWire.safeUniqueId(uniqueId), ProbeReason.RELEASE)
            val until = SystemClock.elapsedRealtime() + 1_500
            while (info(displayId) != null && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            checkProbe(info(displayId) == null && dedicatedDisplays().none { it.second == name }, ProbeReason.RELEASE)
        }
        return clean
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val source = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        try {
            source.setOnImageAvailableListener({ images ->
                try {
                    synchronized(imageLock) {
                        if (!closing && reader === images) {
                            images.acquireLatestImage()?.let { image ->
                                if (imePolicy != 2 || image.timestamp <= discardedTimestamp) {
                                    image.close()
                                } else {
                                    latest?.close(); latest = image; imageLock.notifyAll()
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    synchronized(imageLock) { imageFailure = true; imageLock.notifyAll() }
                }
            }, Handler(imageThread?.looper ?: fail(ProbeReason.STATE)))
            return source
        } catch (error: Throwable) { source.close(); throw error }
    }

    private fun ownedInfo(requireUnique: Boolean = true, allowClosing: Boolean = false): Any {
        checkProbe(Process.myUid() == 0, ProbeReason.STATE)
        if (!allowClosing) preflight()
        checkProbe((allowClosing || !closing) && displayId > 0 && virtualDisplay?.display?.displayId == displayId, ProbeReason.DISPLAY)
        val current = info(displayId) ?: fail(ProbeReason.DISPLAY)
        checkProbe(string(current, "name") == name && int(current, "type") == 5 &&
            (!requireUnique || string(current, "uniqueId") == uniqueId), ProbeReason.DISPLAY)
        checkProbe(dedicatedDisplays() == listOf(displayId to name), ProbeReason.DISPLAY)
        return current
    }

    private fun checkGeometry(info: Any) {
        checkProbe(matchesGeometry(info), ProbeReason.DISPLAY)
    }

    private fun matchesGeometry(info: Any): Boolean {
        val logicalWidth = if (rotation == 0) width else height
        val logicalHeight = if (rotation == 0) height else width
        return int(info, "logicalWidth") == logicalWidth && int(info, "logicalHeight") == logicalHeight &&
            int(info, "logicalDensityDpi") == ProbeWire.DPI && int(info, "rotation") == rotation
    }

    private fun awaitGeometry() {
        // Resize and freeze enqueue display traversal; only the requested metadata can satisfy it.
        val until = SystemClock.elapsedRealtime() + 1_500
        while (!matchesGeometry(ownedInfo())) {
            checkProbe(SystemClock.elapsedRealtime() < until, ProbeReason.DISPLAY)
            Thread.sleep(20)
        }
        identity()
    }

    private fun dedicatedDisplays(): List<Pair<Int, String>> {
        val ids = displayInterface.getMethod("getDisplayIds", Boolean::class.javaPrimitiveType)
            .invoke(displayService, true) as IntArray
        return ids.map { id ->
            val current = info(id) ?: fail(ProbeReason.DISPLAY)
            val displayName = string(current, "name")
            // Includes the production RootPilot-Private- prefix and every rootpilot-parity- session.
            (id to displayName).takeIf { displayName.lowercase(Locale.ROOT).startsWith("rootpilot") }
        }.filterNotNull().sortedBy { it.first }
    }

    private fun rotationMethod(name: String, parameters: List<Class<*>>): Method {
        return try {
            windowInterface.getMethod(name, *(parameters + String::class.java).toTypedArray())
        } catch (_: NoSuchMethodException) {
            windowInterface.getMethod(name, *parameters.toTypedArray())
        }
    }

    private fun preflight() {
        checkProbe(Process.myUid() == 0 && ::systemContext.isInitialized, ProbeReason.STATE)
        // A standalone app_process cannot construct this ROM's public KeyguardManager.
        // These are the same read-only Binder states; failures never substitute an unlocked value.
        val userId = Context::class.java.getMethod("getUserId").invoke(systemContext) as? Int
        val deviceId = systemContext.deviceId
        checkProbe(userId == 0 && deviceId == 0, ProbeReason.STATE)
        val locked = trustInterface.getMethod("isDeviceLocked", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(trustService, userId, deviceId) as? Boolean
        val keyguardLocked = windowInterface.getMethod("isKeyguardLocked").invoke(windowService) as? Boolean
        val interactive = powerInterface.getMethod("isInteractive").invoke(powerService) as? Boolean
        checkProbe(ProbeEnvironmentPolicy.usable(userId, deviceId, locked, keyguardLocked, interactive), ProbeReason.STATE)
    }

    private fun info(id: Int): Any? = displayInterface.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        .invoke(displayService, id)
    private fun int(info: Any, field: String) = info.javaClass.getField(field).getInt(info)
    private fun string(info: Any, field: String) = info.javaClass.getField(field).get(info) as String
    private fun service(name: String, type: String): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name)
        return Class.forName("$type\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: fail(ProbeReason.STATE)
    }

    /** Compression goes to a bounded counter, never to a byte array, file or IPC payload. */
    private class PngCount : OutputStream() {
        var size = 0
            private set
        override fun write(value: Int) = add(1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkProbe(offset >= 0 && length >= 0 && offset <= bytes.size - length, ProbeReason.CAPTURE)
            add(length)
        }
        private fun add(length: Int) {
            checkProbe(length <= ProbeWire.MAX_PNG - size, ProbeReason.CAPTURE)
            size += length
        }
    }
}
