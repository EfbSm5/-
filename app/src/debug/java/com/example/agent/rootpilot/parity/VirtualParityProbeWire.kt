package com.example.agent.rootpilot.parity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

internal enum class ProbeProfile { PLAIN, DECORATED }

internal enum class ProbeCommand {
    OPEN_FIXTURE, HOME, IME_LOCAL, IME_HIDE, LANDSCAPE, PORTRAIT, ROTATE_90, ROTATE_0, CAPTURE_METADATA,
}

internal data class ProbeDisplayIdentity(
    val displayId: Int,
    val uniqueId: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
    val imePolicy: Int,
    val frameBytes: Int,
    val helperPid: Int,
)

internal enum class ProbeReason {
    OK, ARGUMENT, PROTOCOL, STATE, IO, TIMEOUT, DISPLAY, COMMAND, CAPTURE, RELEASE, CANCELLED,
    CREATE_CONTEXT, CREATE_PREFLIGHT, CREATE_BINDERS, CREATE_ROTATION_API, CREATE_SURFACE,
    CREATE_ALLOCATE, CREATE_BINDING, CREATE_IME, CREATE_IDENTITY,
}
internal class ProbeFailure(val reason: ProbeReason) : IOException("parity_${reason.name.lowercase(Locale.ROOT)}")
internal fun fail(reason: ProbeReason): Nothing = throw ProbeFailure(reason)
internal fun checkProbe(condition: Boolean, reason: ProbeReason = ProbeReason.PROTOCOL) {
    if (!condition) fail(reason)
}

/** Fixed operations and bounded frames on anonymous parent/child pipes; no display ID in requests. */
internal object ProbeWire {
    const val ENTRY = "com.example.agent.rootpilot.parity.OwnedVirtualDisplayProbeHelper"
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val DPI = 320
    const val MAX_PNG = 12 * 1024 * 1024
    const val START_MS = 20_000L
    const val REQUEST_MS = 12_000L
    const val CLOSE_MS = 8_000L
    const val COMMAND_MS = 5_000L
    const val FRAME_MS = 3_000L
    const val CAPTURE_MS = 4_000L
    const val LIFETIME_MS = 180_000L
    const val IDLE_MS = 30_000L
    const val MAX_REQUESTS = 128L
    private const val MAGIC = 0x52505056
    private const val MAX_BODY = 512

    enum class Op { HELLO, OPEN_FIXTURE, HOME, IME_LOCAL, IME_HIDE, LANDSCAPE, PORTRAIT, ROTATE_90, ROTATE_0, CAPTURE_METADATA, CLOSE, RELEASED }
    data class Frame(val session: UUID, val sequence: Long, val op: Op, val response: Boolean, val body: ByteArray)
    data class Release(val pid: Int, val displayId: Int, val uniqueId: String)

    fun name(session: UUID) = "rootpilot-parity-$session"
    fun op(command: ProbeCommand): Op = Op.valueOf(command.name)
    fun read(input: InputStream, response: Boolean, firstByte: () -> Unit = {}): Frame? {
        val first = input.read()
        if (first < 0) return null
        firstByte()
        val data = DataInputStream(input)
        val magic = (first shl 24) or (data.readUnsignedByte() shl 16) or
            (data.readUnsignedByte() shl 8) or data.readUnsignedByte()
        checkProbe(magic == MAGIC && data.readInt() == 1)
        val operation = data.readInt()
        val direction = data.readUnsignedByte()
        checkProbe(operation in Op.entries.indices && direction == (if (response) 1 else 0))
        val session = UUID(data.readLong(), data.readLong())
        val sequence = data.readLong()
        val length = data.readInt()
        checkProbe(sequence in 1..(MAX_REQUESTS + 1) && length in 0..(if (response) MAX_BODY else 0))
        return Frame(session, sequence, Op.entries[operation], response, ByteArray(length).also(data::readFully))
    }

    fun write(output: OutputStream, frame: Frame) {
        checkProbe(frame.sequence in 1..(MAX_REQUESTS + 1) && frame.body.size <= MAX_BODY)
        checkProbe(frame.response || frame.body.isEmpty())
        DataOutputStream(output).apply {
            writeInt(MAGIC); writeInt(1); writeInt(frame.op.ordinal); writeBoolean(frame.response)
            writeLong(frame.session.mostSignificantBits); writeLong(frame.session.leastSignificantBits)
            writeLong(frame.sequence); writeInt(frame.body.size); write(frame.body); flush()
        }
    }

    fun payload(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).use(block)
    }.toByteArray().also { checkProbe(it.size <= MAX_BODY) }

    fun <T> parse(bytes: ByteArray, block: DataInputStream.() -> T): T =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            val value = it.block()
            checkProbe(it.available() == 0)
            value
        }

    fun reply(request: Frame, reason: ProbeReason, body: ByteArray = byteArrayOf()) =
        request.copy(response = true, body = payload { writeInt(reason.ordinal); write(body) })

    fun response(request: Frame, reply: Frame): ByteArray {
        checkProbe(reply.response && reply.session == request.session && reply.sequence == request.sequence && reply.op == request.op)
        val data = DataInputStream(ByteArrayInputStream(reply.body))
        val code = data.readInt()
        checkProbe(code in ProbeReason.entries.indices)
        val reason = ProbeReason.entries[code]
        if (reason != ProbeReason.OK) {
            checkProbe(data.available() == 0)
            fail(reason)
        }
        return data.readBytes()
    }

    fun identity(value: ProbeDisplayIdentity): ByteArray = payload {
        checkIdentity(value)
        writeInt(value.displayId); writeUTF(value.uniqueId); writeInt(value.width); writeInt(value.height)
        writeInt(value.densityDpi); writeInt(value.rotation); writeInt(value.imePolicy)
        writeInt(value.frameBytes); writeInt(value.helperPid)
    }

    fun identity(bytes: ByteArray): ProbeDisplayIdentity = parse(bytes) {
        ProbeDisplayIdentity(readInt(), readUTF(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt())
            .also(::checkIdentity)
    }

    fun checkIdentity(value: ProbeDisplayIdentity) {
        checkProbe(value.displayId > 0 && value.helperPid > 0 && safeUniqueId(value.uniqueId))
        checkProbe((value.width == WIDTH && value.height == HEIGHT) || (value.width == HEIGHT && value.height == WIDTH))
        checkProbe(value.densityDpi == DPI && value.rotation in 0..1 && value.imePolicy in setOf(0, 2))
        checkProbe(value.frameBytes == 0 || value.frameBytes in 33..MAX_PNG)
    }

    fun safeUniqueId(value: String) = value.length in 1..256 && value.all { it.code in 33..126 }
    fun released(session: UUID, accepted: Long, release: Release) = Frame(session, accepted + 1, Op.RELEASED, true,
        payload { writeInt(release.pid); writeInt(release.displayId); writeUTF(release.uniqueId) })

    fun release(frame: Frame, session: UUID, sent: Long, replied: Long): Release {
        checkProbe(frame.response && frame.op == Op.RELEASED && frame.session == session &&
            frame.sequence in (replied + 1)..(sent + 1))
        return parse(frame.body) {
            Release(readInt(), readInt(), readUTF()).also {
                checkProbe(it.pid > 0 && ((it.displayId > 0 && safeUniqueId(it.uniqueId)) ||
                    (it.displayId == -1 && it.uniqueId.isEmpty())))
            }
        }
    }

    fun launch(mainApk: String, testApk: String, session: UUID, profile: ProbeProfile): String {
        fun apk(path: String) {
            checkProbe(path.startsWith('/') && path.endsWith(".apk") && path.length <= 4096 &&
                path.none { it == ':' || it == '\u0000' || it == '\n' || it == '\r' }, ProbeReason.ARGUMENT)
        }
        apk(mainApk); apk(testApk)
        val classpath = "'" + "$mainApk:$testApk".replace("'", "'\\''") + "'"
        return "CLASSPATH=$classpath exec /system/bin/app_process /system/bin $ENTRY $session ${profile.name}"
    }
}
