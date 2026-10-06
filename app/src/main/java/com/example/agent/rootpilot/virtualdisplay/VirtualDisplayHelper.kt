package com.example.agent.rootpilot.virtualdisplay

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Failure
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Frame
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Op
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Reason
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** app_process entry point. No Android component, socket, credentials or main-display fallback. */
internal object VirtualDisplayHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // Keep a dedicated duplicate for frames; framework/native stdout and all stderr are discarded.
            val output = FileOutputStream(Os.dup(FileDescriptor.out))
            val sink = Os.open("/dev/null", OsConstants.O_RDWR, 0)
            try {
                Os.dup2(sink, OsConstants.STDOUT_FILENO)
                Os.dup2(sink, OsConstants.STDERR_FILENO)
            } finally { Os.close(sink) }
            if (args.size != 1) Runtime.getRuntime().halt(1)
            val session = UUID.fromString(args.single())
            if (session.toString() != args.single()) Runtime.getRuntime().halt(1)
            RuntimeSession(session, FileInputStream(FileDescriptor.`in`), output).run()
        } catch (_: Throwable) {
            // No exception message, stack, command output or captured content may escape this entry.
            Runtime.getRuntime().halt(1)
        }
    }

    private class RuntimeSession(
        private val session: UUID,
        private val input: FileInputStream,
        private val output: FileOutputStream,
    ) {
        private val main = Thread.currentThread()
        private val stopped = AtomicBoolean(false)
        private val closeAcknowledged = AtomicBoolean(false)
        private val requests = ArrayBlockingQueue<Frame>(1)
        private val child = AtomicReference<java.lang.Process?>(null)
        private val startedAt = SystemClock.elapsedRealtime()
        private val operationDeadline = AtomicLong(startedAt + VirtualDisplayProtocol.START_TIMEOUT_MS)
        private val frameDeadline = AtomicLong(Long.MAX_VALUE)
        private val commandDeadline = AtomicLong(Long.MAX_VALUE)
        private val shutdownDeadline = AtomicLong(Long.MAX_VALUE)
        private val idleDeadline = AtomicLong(startedAt + VirtualDisplayProtocol.IDLE_TIMEOUT_MS)
        private val lastAccepted = AtomicLong(0)
        private val owner = VirtualDisplayOwner(session, ::stop)
        private var released = false

        fun run() {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> stop() }
            thread("RootPilot-VdWatchdog") {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= minOf(operationDeadline.get(), frameDeadline.get(), commandDeadline.get(),
                            shutdownDeadline.get(), idleDeadline.get(), startedAt + VirtualDisplayProtocol.LIFETIME_MS)) {
                        // A stuck Binder call cannot prevent Binder death from destroying the display.
                        try { child.get()?.destroyForcibly() } catch (_: Throwable) { }
                        Process.killProcess(Process.myPid())
                        Runtime.getRuntime().halt(1)
                    }
                    Thread.sleep(50)
                }
            }
            thread("RootPilot-VdReader") {
                val sequence = VirtualDisplayProtocol.RequestSequence(session)
                try {
                    while (!stopped.get()) {
                        val request = VirtualDisplayProtocol.read(input, response = false) {
                            frameDeadline.set(SystemClock.elapsedRealtime() + VirtualDisplayProtocol.FRAME_TIMEOUT_MS)
                        }
                        frameDeadline.set(Long.MAX_VALUE)
                        if (request == null) {
                            if (!closeAcknowledged.get()) stop()
                            return@thread
                        }
                        sequence.accept(request)
                        lastAccepted.set(request.sequence)
                        if (!requests.offer(request)) throw Failure(Reason.PROTOCOL)
                    }
                } catch (_: Throwable) { stop() }
            }
            var exit = 1
            try {
                var ready = false
                while (!stopped.get()) {
                    val request = requests.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    operationDeadline.set(SystemClock.elapsedRealtime() +
                        if (ready) VirtualDisplayProtocol.REQUEST_TIMEOUT_MS else VirtualDisplayProtocol.START_TIMEOUT_MS)
                    try {
                        val body = when (request.op) {
                            Op.HELLO -> {
                                empty(request)
                                if (ready) throw Failure(Reason.PROTOCOL)
                                val identity = owner.create()
                                ready = true
                                VirtualDisplayProtocol.hello(identity)
                            }
                            Op.CLOSE -> {
                                empty(request)
                                if (!ready) throw Failure(Reason.STATE)
                                if (!owner.release()) throw Failure(Reason.RELEASE)
                                released = true
                                closeAcknowledged.set(true)
                                byteArrayOf()
                            }
                            else -> {
                                if (!ready) throw Failure(Reason.STATE)
                                val identity = owner.validate()
                                val result = when (request.op) {
                                    Op.VALIDATE -> { empty(request); VirtualDisplayProtocol.hello(identity) }
                                    Op.CAPTURE -> { empty(request); owner.capture() }
                                    Op.TAP, Op.OPEN_APP, Op.SWIPE, Op.KEY -> {
                                        command(VirtualDisplayCommands.arguments(request.op, request.payload, identity.displayId), request.op)
                                        byteArrayOf()
                                    }
                                    Op.WAIT -> {
                                        val duration = VirtualDisplayProtocol.parse(request.payload) {
                                            readInt().also(VirtualDisplayProtocol::waitDuration)
                                        }
                                        Thread.sleep(duration.toLong())
                                        byteArrayOf()
                                    }
                                    else -> throw Failure(Reason.PROTOCOL)
                                }
                                if (owner.validate() != identity) throw Failure(Reason.DISPLAY_INVALID)
                                result
                            }
                        }
                        if (stopped.get()) throw Failure(Reason.CANCELLED)
                        VirtualDisplayProtocol.write(output, VirtualDisplayProtocol.reply(request, Reason.OK, body))
                        if (request.op == Op.CLOSE) { exit = 0; break }
                        idleDeadline.set(SystemClock.elapsedRealtime() + VirtualDisplayProtocol.IDLE_TIMEOUT_MS)
                        operationDeadline.set(Long.MAX_VALUE)
                    } catch (error: Throwable) {
                        val reason = (error as? Failure)?.reason ?: if (ready) Reason.DISPLAY_INVALID else Reason.START
                        if (!stopped.get()) VirtualDisplayProtocol.write(output, VirtualDisplayProtocol.reply(request, reason))
                        break
                    }
                }
            } catch (_: Throwable) {
                // EOF, cancellation and interrupted operations all lead to bounded teardown.
            } finally {
                shutdownDeadline.compareAndSet(Long.MAX_VALUE, SystemClock.elapsedRealtime() + 2_500)
                Thread.interrupted()
                try { child.get()?.destroyForcibly() } catch (_: Throwable) { }
                if (!released) released = try { owner.release() } catch (_: Throwable) { false }
                if (released) {
                    try {
                        VirtualDisplayProtocol.write(output, VirtualDisplayProtocol.released(session, lastAccepted.get(), Process.myPid()))
                    } catch (_: Throwable) { }
                }
                Runtime.getRuntime().halt(exit)
            }
        }

        private fun command(arguments: List<String>, op: Op) {
            commandDeadline.set(SystemClock.elapsedRealtime() + VirtualDisplayProtocol.COMMAND_TIMEOUT_MS)
            val process = ProcessBuilder(arguments).redirectError(File("/dev/null")).start()
            child.set(process)
            try {
                if (stopped.get()) throw Failure(Reason.CANCELLED)
                process.outputStream.close()
                val captured = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = process.inputStream.read(buffer)
                    if (count < 0) break
                    if (count > 16 * 1024 - captured.size()) throw Failure(Reason.COMMAND)
                    captured.write(buffer, 0, count)
                }
                if (!process.waitFor(500, TimeUnit.MILLISECONDS) || process.exitValue() != 0 ||
                    (op == Op.OPEN_APP && !VirtualDisplayCommands.launchSucceeded(captured.toString("UTF-8")))) {
                    throw Failure(Reason.COMMAND)
                }
            } finally {
                try { process.destroyForcibly() } catch (_: Throwable) { }
                try { process.outputStream.close() } catch (_: Throwable) { }
                try { process.inputStream.close() } catch (_: Throwable) { }
                try { process.errorStream.close() } catch (_: Throwable) { }
                child.compareAndSet(process, null)
                commandDeadline.set(Long.MAX_VALUE)
            }
        }

        private fun stop() {
            if (stopped.compareAndSet(false, true)) {
                shutdownDeadline.set(SystemClock.elapsedRealtime() + 2_500)
                main.interrupt()
                try { child.get()?.destroyForcibly() } catch (_: Throwable) { }
            }
        }

        private fun empty(request: Frame) = VirtualDisplayProtocol.requireProtocol(request.payload.isEmpty())
        private fun thread(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true; start() }
    }
}
