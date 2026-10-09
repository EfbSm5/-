package com.example.agent.rootpilot.parity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class VirtualParityProbeWireTest {
    private val session = UUID.fromString("ad053dd5-dfe3-4970-8f04-e5a8d2b6f6c7")
    private val request get() = ProbeWire.Frame(session, 1, ProbeWire.Op.HELLO, false, byteArrayOf())
    private val identity get() = ProbeDisplayIdentity(7, "virtual:probe", 1080, 1920, 320, 0, 2, 0, 321)

    private fun encode(frame: ProbeWire.Frame): ByteArray = ByteArrayOutputStream().also { ProbeWire.write(it, frame) }.toByteArray()
    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected_probe_rejection") }
        catch (_: ProbeFailure) { }
    }

    @Test fun requestsRoundTripWithoutAnyBodyOrTargetDisplayArgument() {
        for (command in ProbeCommand.entries) {
            val frame = request.copy(op = ProbeWire.op(command))
            val decoded = requireNotNull(ProbeWire.read(ByteArrayInputStream(encode(frame)), false))
            assertEquals(session, decoded.session)
            assertEquals(1L, decoded.sequence)
            assertEquals(frame.op, decoded.op)
            assertFalse(decoded.response)
            assertEquals(0, decoded.body.size)
        }
    }

    @Test fun endOfPipeIsNotAnEmptyValidFrame() {
        assertNull(ProbeWire.read(ByteArrayInputStream(byteArrayOf()), false))
    }

    @Test fun incompleteFrameCannotBeAccepted() {
        val bytes = encode(request)
        for (length in 1 until bytes.size) {
            try { ProbeWire.read(ByteArrayInputStream(bytes.copyOf(length)), false); fail("partial_frame_accepted") }
            catch (_: EOFException) { }
        }
    }

    @Test fun invalidMagicVersionOperationOrDirectionIsRejected() {
        for ((offset, value) in listOf(0 to 0, 4 to 2, 8 to -1, 8 to 999)) {
            val bytes = encode(request)
            ByteBuffer.wrap(bytes).putInt(offset, value)
            rejected { ProbeWire.read(ByteArrayInputStream(bytes), false) }
        }
        rejected { ProbeWire.read(ByteArrayInputStream(encode(request)), true) }
        val malformedDirection = encode(request).apply { this[12] = 2 }
        rejected { ProbeWire.read(ByteArrayInputStream(malformedDirection), false) }
    }

    @Test fun invalidSequenceAndNegativeOrOversizedBodyIsRejectedBeforeAllocation() {
        for (sequence in listOf(0L, -1L, ProbeWire.MAX_REQUESTS + 2)) {
            val bytes = encode(request)
            ByteBuffer.wrap(bytes).putLong(29, sequence)
            rejected { ProbeWire.read(ByteArrayInputStream(bytes), false) }
        }
        for (length in listOf(-1, 1, Int.MAX_VALUE)) {
            val bytes = encode(request)
            ByteBuffer.wrap(bytes).putInt(37, length)
            rejected { ProbeWire.read(ByteArrayInputStream(bytes), false) }
        }
        val reply = encode(ProbeWire.reply(request, ProbeReason.OK))
        ByteBuffer.wrap(reply).putInt(37, 513)
        rejected { ProbeWire.read(ByteArrayInputStream(reply), true) }
    }

    @Test fun writerCannotEmitRequestParametersOrUnboundedResponses() {
        rejected { encode(request.copy(body = byteArrayOf(1))) }
        rejected { encode(request.copy(sequence = 0)) }
        rejected { encode(request.copy(response = true, body = ByteArray(513))) }
    }

    @Test fun identityAndSuccessfulReplyRoundTripWithExactSessionAndSequence() {
        val reply = ProbeWire.reply(request, ProbeReason.OK, ProbeWire.identity(identity))
        val parsed = requireNotNull(ProbeWire.read(ByteArrayInputStream(encode(reply)), true))
        assertEquals(identity, ProbeWire.identity(ProbeWire.response(request, parsed)))
    }

    @Test fun foreignSessionSequenceOperationAndRequestDirectionCannotBeReplies() {
        val reply = ProbeWire.reply(request, ProbeReason.OK)
        for (other in listOf(reply.copy(session = UUID.randomUUID()), reply.copy(sequence = 2),
            reply.copy(op = ProbeWire.Op.HOME), reply.copy(response = false))) {
            rejected { ProbeWire.response(request, other) }
        }
    }

    @Test fun errorReplyContainsOnlyFixedReasonAndNoPayload() {
        try {
            ProbeWire.response(request, ProbeWire.reply(request, ProbeReason.DISPLAY))
            fail("error_reply_accepted")
        } catch (error: ProbeFailure) {
            assertEquals(ProbeReason.DISPLAY, error.reason)
            assertEquals("parity_display", error.message)
        }
        rejected { ProbeWire.response(request, ProbeWire.reply(request, ProbeReason.DISPLAY, byteArrayOf(1))) }
        rejected { ProbeWire.response(request, request.copy(response = true, body = ProbeWire.payload { writeInt(999) })) }
    }

    @Test fun everyFixedFailureIncludingCreateCheckpointsRejectsPayloadAndRoundTrips() {
        for (reason in ProbeReason.entries.filter { it != ProbeReason.OK }) {
            val reply = ProbeWire.reply(request, reason)
            val decoded = requireNotNull(ProbeWire.read(ByteArrayInputStream(encode(reply)), true))
            try {
                ProbeWire.response(request, decoded)
                fail("failure_reply_accepted")
            } catch (error: ProbeFailure) {
                assertEquals(reason, error.reason)
                assertEquals("parity_${reason.name.lowercase(java.util.Locale.ROOT)}", error.message)
            }
            try {
                ProbeWire.response(request, ProbeWire.reply(request, reason, byteArrayOf(1)))
                fail("failure_payload_accepted")
            } catch (error: ProbeFailure) {
                assertEquals(ProbeReason.PROTOCOL, error.reason)
            }
        }
    }

    @Test fun primaryDisplayInvalidGeometryImePolicyOrFrameSizeIsRejected() {
        for (invalid in listOf(identity.copy(displayId = 0), identity.copy(displayId = -1),
            identity.copy(helperPid = 0), identity.copy(width = 720), identity.copy(height = 0),
            identity.copy(densityDpi = 0), identity.copy(rotation = 2), identity.copy(imePolicy = 1),
            identity.copy(frameBytes = -1), identity.copy(frameBytes = 32), identity.copy(frameBytes = ProbeWire.MAX_PNG + 1))) {
            rejected { ProbeWire.identity(invalid) }
        }
    }

    @Test fun onlyBoundedPrintableUniqueIdsAreAllowed() {
        for (invalid in listOf("", "a".repeat(257), "with space", "line\n", "\u0000", "中文")) {
            rejected { ProbeWire.identity(identity.copy(uniqueId = invalid)) }
        }
    }

    @Test fun bothSupportedGeometriesAndCaptureLimitsRemainExplicit() {
        val landscape = identity.copy(width = 1920, height = 1080, rotation = 1, imePolicy = 0)
        assertEquals(landscape, ProbeWire.identity(ProbeWire.identity(landscape)))
        for (bytes in listOf(33, ProbeWire.MAX_PNG)) {
            val sample = identity.copy(frameBytes = bytes)
            assertEquals(sample, ProbeWire.identity(ProbeWire.identity(sample)))
        }
    }

    @Test fun trailingIdentityOrReleaseDataCannotBeIgnored() {
        rejected { ProbeWire.identity(ProbeWire.identity(identity) + byteArrayOf(1)) }
        val release = ProbeWire.released(session, 1, ProbeWire.Release(321, 7, identity.uniqueId))
        rejected { ProbeWire.release(release.copy(body = release.body + byteArrayOf(1)), session, 1, 1) }
    }

    @Test fun releaseRequiresOwnedSessionAndLegalTerminalSequence() {
        val receipt = ProbeWire.Release(321, 7, identity.uniqueId)
        val frame = ProbeWire.released(session, 1, receipt)
        assertEquals(receipt, ProbeWire.release(frame, session, 1, 1))
        for (invalid in listOf(frame.copy(session = UUID.randomUUID()), frame.copy(sequence = 1),
            frame.copy(sequence = 3), frame.copy(op = ProbeWire.Op.CLOSE), frame.copy(response = false))) {
            rejected { ProbeWire.release(invalid, session, 1, 1) }
        }
    }

    @Test fun releaseDoesNotAcceptDisplayZeroOrUnknownNegativeDisplays() {
        for (invalid in listOf(ProbeWire.Release(321, 0, identity.uniqueId), ProbeWire.Release(0, 7, identity.uniqueId),
            ProbeWire.Release(321, -2, ""), ProbeWire.Release(321, -1, identity.uniqueId), ProbeWire.Release(321, 7, ""))) {
            rejected { ProbeWire.release(ProbeWire.released(session, 1, invalid), session, 1, 1) }
        }
        val notCreated = ProbeWire.Release(321, -1, "")
        assertEquals(notCreated, ProbeWire.release(ProbeWire.released(session, 1, notCreated), session, 1, 1))
    }

    @Test fun apkPathsCannotInjectClasspathEntriesOrLines() {
        for (path in listOf("relative.apk", "/tmp/not-apk", "/a:other.apk", "/a\n.apk", "/a\r.apk", "/a\u0000.apk", "/" + "a".repeat(4096) + ".apk")) {
            rejected { ProbeWire.launch(path, "/tmp/test.apk", session, ProbeProfile.PLAIN) }
            rejected { ProbeWire.launch("/tmp/main.apk", path, session, ProbeProfile.PLAIN) }
        }
    }

    @Test fun shellMetacharactersInInstalledPathRemainInsideQuotedClasspath() {
        val path = "/tmp/a' $ dollar.apk"
        val launch = ProbeWire.launch(path, "/tmp/test.apk", session, ProbeProfile.DECORATED)
        assertEquals("CLASSPATH='/tmp/a'\\'' $ dollar.apk:/tmp/test.apk' exec /system/bin/app_process /system/bin ${ProbeWire.ENTRY} $session DECORATED", launch)
        assertTrue(launch.endsWith("$session DECORATED"))
    }
}
