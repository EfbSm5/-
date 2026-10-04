package com.example.agent.rootpilot.virtualdisplay

import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Frame
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Identity
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Op
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Reason
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayProtocolTest {
    private val session = UUID.fromString("03f579d2-7212-4ddc-a8ea-5d82662f0f66")
    private fun request(op: Op = Op.HELLO, sequence: Long = 1, payload: ByteArray = byteArrayOf()) =
        Frame(session, sequence, op, false, payload)
    private fun encode(frame: Frame) = ByteArrayOutputStream().also { VirtualDisplayProtocol.write(it, frame) }.toByteArray()

    @Test fun frameRoundTripAndCleanEof() {
        val payload = VirtualDisplayProtocol.payload { writeInt(100); writeInt(200) }
        val frame = VirtualDisplayProtocol.read(ByteArrayInputStream(encode(request(Op.TAP, 2, payload))), false)!!
        assertEquals(session, frame.session)
        assertEquals(2L, frame.sequence)
        assertEquals(Op.TAP, frame.op)
        assertArrayEquals(payload, frame.payload)
        assertNull(VirtualDisplayProtocol.read(ByteArrayInputStream(byteArrayOf()), false))
    }

    @Test fun everyPartialHeaderOrBodyFailsInsteadOfActingAsEof() {
        val encoded = encode(request(Op.TAP, 2, VirtualDisplayProtocol.payload { writeInt(2); writeInt(3) }))
        for (size in 1 until encoded.size) {
            assertThrows(IOException::class.java) { VirtualDisplayProtocol.read(ByteArrayInputStream(encoded.copyOf(size)), false) }
        }
    }

    @Test fun rejectsLogPollutionVersionUnknownOpcodeAndWrongDirection() {
        val clean = encode(request())
        val polluted = "startup log\n".toByteArray() + clean
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.read(ByteArrayInputStream(polluted), false) }
        for ((offset, value) in listOf(4 to 2, 8 to 99, 8 to (Op.HELLO.wire or VirtualDisplayProtocol.RESPONSE_BIT), 36 to -1, 36 to 1025)) {
            val changed = clean.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
            assertThrows(IOException::class.java) { VirtualDisplayProtocol.read(ByteArrayInputStream(changed), false) }
        }
    }

    @Test fun oversizeResponseRejectedBeforeReadingOrAllocatingBody() {
        val header = encode(VirtualDisplayProtocol.reply(request(), Reason.OK)).copyOf(40)
        ByteBuffer.wrap(header).putInt(36, Int.MAX_VALUE)
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.read(ByteArrayInputStream(header), true) }
    }

    @Test fun bindsVersionSessionAndMonotonicRequestsWithoutReplay() {
        val gate = VirtualDisplayProtocol.RequestSequence(session)
        gate.accept(request())
        assertThrows(IOException::class.java) { gate.accept(request()) }
        assertThrows(IOException::class.java) { gate.accept(request(Op.TAP, 3)) }
        assertThrows(IOException::class.java) { gate.accept(request(Op.TAP, 2).copy(session = UUID.randomUUID())) }
        assertThrows(IOException::class.java) { gate.accept(request(Op.HELLO, 2)) }
        assertThrows(IOException::class.java) { gate.accept(request(Op.RELEASED, 2)) }
        gate.accept(request(Op.TAP, 2))
    }

    @Test fun responsesCannotSwitchSessionSequenceOrOperation() {
        val request = request()
        val reply = VirtualDisplayProtocol.reply(request, Reason.OK)
        for (bad in listOf(reply.copy(session = UUID.randomUUID()), reply.copy(sequence = 2), reply.copy(op = Op.CAPTURE))) {
            assertThrows(IOException::class.java) { VirtualDisplayProtocol.response(request, bad) }
        }
    }

    @Test fun helloRejectsPrimaryDisplayEmptyIdentityChangedGeometryAndTrailingFields() {
        val good = VirtualDisplayProtocol.hello(Identity(7, "virtual:test"))
        assertEquals(Identity(7, "virtual:test"), VirtualDisplayProtocol.parseHello(good))
        for ((offset, value) in listOf(0 to 0, 4 to 1920, 8 to 1080, 12 to 240, 16 to 1)) {
            val changed = good.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
            assertThrows(IOException::class.java) { VirtualDisplayProtocol.parseHello(changed) }
        }
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.hello(Identity(7, "")) }
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.parseHello(good + 0) }
    }

    @Test fun failuresContainOnlyKnownReasonsAndNoExceptionText() {
        val original = request()
        val reply = VirtualDisplayProtocol.reply(original, Reason.DISPLAY_EXISTS)
        assertEquals(4, reply.payload.size)
        val error = assertThrows(VirtualDisplayProtocol.Failure::class.java) { VirtualDisplayProtocol.response(original, reply) }
        assertEquals("vd_display_exists", error.message)
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.response(original, reply.copy(payload = reply.payload + "private".toByteArray())) }
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.response(original, reply.copy(payload = VirtualDisplayProtocol.payload { writeInt(999) })) }
    }

    @Test fun terminalReceiptBoundToAcceptedRequestRangeAndPositiveHelperPid() {
        val receipt = VirtualDisplayProtocol.released(session, 2, 1001)
        assertEquals(1001, VirtualDisplayProtocol.checkReleased(receipt, session, 2, 1))
        assertEquals(1001, VirtualDisplayProtocol.checkReleased(VirtualDisplayProtocol.released(session, 0, 1001), session, 0, 0))
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.checkReleased(receipt, UUID.randomUUID(), 2, 1) }
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.checkReleased(receipt, session, 1, 1) }
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.checkReleased(receipt, session, 2, 3) }
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.checkReleased(VirtualDisplayProtocol.released(session, 2, 0), session, 2, 1) }
    }

    @Test fun appLaunchUsesFixedArgumentsAndBoundDisplay() {
        val bytes = VirtualDisplayProtocol.payload { writeUTF("com.example.app"); writeUTF(".MainActivity") }
        val args = VirtualDisplayCommands.arguments(Op.OPEN_APP, bytes, 41)
        assertEquals("/system/bin/cmd", args.first())
        assertEquals("41", args[args.indexOf("--display") + 1])
        assertEquals("com.example.app/.MainActivity", args.last())
        assertFalse(args.contains("sh"))
        assertThrows(IOException::class.java) { VirtualDisplayCommands.arguments(Op.OPEN_APP, bytes, 0) }
        assertThrows(IOException::class.java) { VirtualDisplayCommands.arguments(Op.OPEN_APP, bytes + 0, 41) }
    }

    @Test fun rejectsComponentInjectionAndOutOfBoundsCoordinatesOrWaits() {
        for (invalid in listOf("x;id", "--display", "a/b", "x\ny", "x y", "a..b", "\u0000")) {
            assertThrows(IOException::class.java) { VirtualDisplayProtocol.validateApp(invalid, "Main") }
            assertThrows(IOException::class.java) { VirtualDisplayProtocol.validateApp("com.example.app", invalid) }
        }
        for ((x, y) in listOf(-1 to 0, 0 to -1, 1080 to 0, 0 to 1920, Int.MAX_VALUE to 1)) {
            assertThrows(IOException::class.java) { VirtualDisplayProtocol.tap(x, y) }
        }
        for (duration in listOf(-1, 0, 299, 5001, Int.MAX_VALUE)) {
            assertThrows(IOException::class.java) { VirtualDisplayProtocol.waitDuration(duration) }
        }
        VirtualDisplayProtocol.tap(1079, 1919)
        VirtualDisplayProtocol.waitDuration(5000)
    }

    @Test fun tapAlwaysUsesExplicitDisplayAndHasNoExtraParameters() {
        val bytes = VirtualDisplayProtocol.payload { writeInt(3); writeInt(8) }
        assertEquals(listOf("/system/bin/cmd", "input", "-d", "9", "tap", "3", "8"), VirtualDisplayCommands.arguments(Op.TAP, bytes, 9))
        assertThrows(IOException::class.java) { VirtualDisplayCommands.arguments(Op.CAPTURE, byteArrayOf(), 9) }
    }

    @Test fun shellCommandQuotesOnlyCurrentApkPath() {
        val value = VirtualDisplayProtocol.launchCommand("/data/app/a'b/base.apk", session)
        assertEquals("CLASSPATH='/data/app/a'\\''b/base.apk' exec /system/bin/app_process /system/bin ${VirtualDisplayProtocol.ENTRY} $session", value)
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.launchCommand("/data/app/a\nbase.apk", session) }
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.launchCommand("base.apk", session) }
    }

    @Test fun successfulExitWithoutAmSuccessTextIsNotLaunchSuccess() {
        assertTrue(VirtualDisplayCommands.launchSucceeded("Starting: Intent\nStatus: ok\n"))
        assertFalse(VirtualDisplayCommands.launchSucceeded("Starting: Intent\n"))
        assertFalse(VirtualDisplayCommands.launchSucceeded("Status: ok\nError: unknown\n"))
    }

    @Test fun pngMustHaveExpectedSignatureAndFixedDimensions() {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + VirtualDisplayProtocol.payload {
            writeInt(13); writeInt(0x49484452); writeInt(1080); writeInt(1920); write(ByteArray(9))
        }
        VirtualDisplayProtocol.checkPng(png)
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.checkPng(png.copyOf(20)) }
        assertThrows(IOException::class.java) { VirtualDisplayProtocol.checkPng(png.copyOf().also { ByteBuffer.wrap(it).putInt(16, 1200) }) }
    }
}
