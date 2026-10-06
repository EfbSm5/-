package com.example.agent.rootpilot.virtualdisplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Private pipe protocol. Neither requests nor command payloads accept a display identifier. */
internal object VirtualDisplayProtocol {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val DPI = 320
    const val DISPLAY_PREFIX = "RootPilot-Private-"
    const val ENTRY = "com.example.agent.rootpilot.virtualdisplay.VirtualDisplayHelper"
    const val VERSION = 1
    const val MAGIC = 0x52505644
    const val MAX_REQUEST_BYTES = 1024
    const val MAX_PNG_BYTES = 12 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = MAX_PNG_BYTES + 1024
    const val START_TIMEOUT_MS = 20_000L
    const val REQUEST_TIMEOUT_MS = 15_000L
    const val COMMAND_TIMEOUT_MS = 8_000L
    const val CAPTURE_TIMEOUT_MS = 4_000L
    const val CLOSE_TIMEOUT_MS = 5_000L
    const val FRAME_TIMEOUT_MS = 3_000L
    const val IDLE_TIMEOUT_MS = 300_000L
    const val LIFETIME_MS = 1_800_000L
    const val RESPONSE_BIT = 0x100

    enum class Op(val wire: Int) {
        HELLO(1), VALIDATE(2), CAPTURE(3), OPEN_APP(4), TAP(5), WAIT(6), CLOSE(7), RELEASED(8), SWIPE(9), KEY(10);
        companion object {
            fun fromWire(value: Int): Op = entries.firstOrNull { it.wire == value }
                ?: fail(Reason.PROTOCOL)
        }
    }

    enum class Reason(val wire: Int, val code: String) {
        OK(0, "vd_ok"), PROTOCOL(1, "vd_protocol"), CANCELLED(2, "vd_cancelled"),
        STATE(3, "vd_state"), TIMEOUT(4, "vd_timeout"), START(5, "vd_start_failed"),
        DISPLAY_EXISTS(6, "vd_display_exists"), DISPLAY_INVALID(7, "vd_display_invalid"),
        CAPTURE(8, "vd_capture_failed"), COMMAND(9, "vd_command_failed"),
        UNSUPPORTED(10, "vd_action_unsupported"), ARGUMENT(11, "vd_invalid_argument"),
        RELEASE(12, "vd_release_unconfirmed"), IO(13, "vd_ipc_failed");
        companion object {
            fun fromWire(value: Int): Reason = entries.firstOrNull { it.wire == value }
                ?: fail(PROTOCOL)
        }
    }

    class Failure(val reason: Reason) : IOException(reason.code)
    fun fail(reason: Reason): Nothing = throw Failure(reason)
    fun requireProtocol(value: Boolean) { if (!value) fail(Reason.PROTOCOL) }

    data class Frame(val session: UUID, val sequence: Long, val op: Op, val response: Boolean, val payload: ByteArray)
    data class Identity(val displayId: Int, val uniqueId: String) {
        fun check() {
            requireProtocol(displayId > 0 && uniqueId.isNotEmpty() && uniqueId.length <= 256)
        }
    }

    /** Null means clean EOF before a header; a partial header/body is always a failure. */
    fun read(input: InputStream, response: Boolean, firstByte: () -> Unit = {}): Frame? {
        val first = input.read()
        if (first == -1) return null
        firstByte()
        val data = DataInputStream(input)
        val magic = (first shl 24) or (data.readUnsignedByte() shl 16) or
            (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
        requireProtocol(magic == MAGIC && data.readInt() == VERSION)
        val wire = data.readInt()
        requireProtocol((wire and RESPONSE_BIT != 0) == response)
        val op = Op.fromWire(wire and RESPONSE_BIT.inv())
        val session = UUID(data.readLong(), data.readLong())
        val sequence = data.readLong()
        val length = data.readInt()
        val limit = if (response) MAX_RESPONSE_BYTES else MAX_REQUEST_BYTES
        requireProtocol(sequence > 0 && length in 0..limit)
        return Frame(session, sequence, op, response, ByteArray(length).also(data::readFully))
    }

    fun write(output: OutputStream, frame: Frame) {
        val limit = if (frame.response) MAX_RESPONSE_BYTES else MAX_REQUEST_BYTES
        requireProtocol(frame.sequence > 0 && frame.payload.size <= limit)
        DataOutputStream(output).apply {
            writeInt(MAGIC)
            writeInt(VERSION)
            writeInt(frame.op.wire or if (frame.response) RESPONSE_BIT else 0)
            writeLong(frame.session.mostSignificantBits)
            writeLong(frame.session.leastSignificantBits)
            writeLong(frame.sequence)
            writeInt(frame.payload.size)
            write(frame.payload)
            flush()
        }
    }

    class RequestSequence(private val session: UUID) {
        private var next = 1L
        fun accept(frame: Frame) {
            requireProtocol(!frame.response && frame.op != Op.RELEASED && frame.session == session && frame.sequence == next)
            requireProtocol((next == 1L) == (frame.op == Op.HELLO) && next < Long.MAX_VALUE)
            next++
        }
    }

    fun reply(request: Frame, reason: Reason, body: ByteArray = byteArrayOf()): Frame {
        requireProtocol(reason == Reason.OK || body.isEmpty())
        return request.copy(response = true, payload = payload { writeInt(reason.wire); write(body) })
    }

    fun response(request: Frame, reply: Frame): ByteArray {
        requireProtocol(reply.response && request.session == reply.session && request.sequence == reply.sequence && request.op == reply.op)
        val data = DataInputStream(ByteArrayInputStream(reply.payload))
        val reason = Reason.fromWire(data.readInt())
        if (reason != Reason.OK) {
            requireProtocol(data.available() == 0)
            fail(reason)
        }
        return data.readBytes()
    }

    /** A terminal receipt acknowledges all accepted requests and forbids any subsequent creation. */
    fun released(session: UUID, lastAccepted: Long, helperPid: Int): Frame = Frame(
        session, lastAccepted + 1, Op.RELEASED, true, payload { writeInt(Reason.OK.wire); writeInt(helperPid) },
    )

    fun checkReleased(frame: Frame, session: UUID, lastSent: Long, lastReply: Long): Int {
        requireProtocol(frame.response && frame.op == Op.RELEASED && frame.session == session &&
            frame.sequence in (lastReply + 1)..(lastSent + 1))
        return parse(frame.payload) {
            requireProtocol(readInt() == Reason.OK.wire)
            readInt().also { requireProtocol(it > 0) }
        }
    }

    fun hello(identity: Identity): ByteArray = payload {
        identity.check()
        writeInt(identity.displayId)
        writeInt(WIDTH); writeInt(HEIGHT); writeInt(DPI); writeInt(0)
        writeUTF(identity.uniqueId)
    }

    fun parseHello(bytes: ByteArray): Identity = parse(bytes) {
        val id = readInt()
        requireProtocol(readInt() == WIDTH && readInt() == HEIGHT && readInt() == DPI && readInt() == 0)
        Identity(id, readUTF()).also { it.check() }
    }

    fun payload(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray()

    fun <T> parse(bytes: ByteArray, block: DataInputStream.() -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val value = data.block()
            requireProtocol(data.available() == 0)
            value
        }

    fun validateApp(packageName: String, activityName: String) {
        if (packageName.length !in 1..255 || activityName.length !in 1..255 ||
            !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName) ||
            !Regex("\\.?[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*").matches(activityName)
        ) fail(Reason.ARGUMENT)
    }

    fun tap(x: Int, y: Int) {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) fail(Reason.ARGUMENT)
    }

    fun waitDuration(duration: Int) {
        if (duration !in 300..5_000) fail(Reason.ARGUMENT)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int) {
        tap(x1, y1); tap(x2, y2)
        if (duration !in 100..2_000) fail(Reason.ARGUMENT)
    }

    fun key(code: Int) {
        // HOME can affect system navigation beyond the owned application display.
        if (code != 4 && code != 66) fail(Reason.ARGUMENT)
    }

    fun checkPng(bytes: ByteArray) {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        requireProtocol(bytes.size in 33..MAX_PNG_BYTES && bytes.copyOfRange(0, 8).contentEquals(signature))
        parse(bytes.copyOfRange(8, 24)) {
            requireProtocol(readInt() == 13 && readInt() == 0x49484452 && readInt() == WIDTH && readInt() == HEIGHT)
        }
    }

    /** Only an installed APK path and a generated UUID enter the one required su shell command. */
    fun launchCommand(sourceDir: String, session: UUID): String {
        if (!sourceDir.startsWith('/') || sourceDir.length > 4096 ||
            sourceDir.any { it == '\u0000' || it == '\n' || it == '\r' }
        ) fail(Reason.ARGUMENT)
        val quotedPath = "'" + sourceDir.replace("'", "'\\''") + "'"
        return "CLASSPATH=$quotedPath exec /system/bin/app_process /system/bin $ENTRY $session"
    }
}
