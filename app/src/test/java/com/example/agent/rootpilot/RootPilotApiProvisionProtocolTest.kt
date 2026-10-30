package com.example.agent.rootpilot

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RootPilotApiProvisionProtocolTest {
    @Test
    fun onlyRootAndShellPeersAreAdmitted() {
        for (uid in listOf(-1, 1, 1000, 1001, 2001, 10000, Int.MAX_VALUE)) {
            assertFalse(RootPilotApiProvisionProtocol.trustedPeer(uid))
        }
        assertTrue(RootPilotApiProvisionProtocol.trustedPeer(0))
        assertTrue(RootPilotApiProvisionProtocol.trustedPeer(2000))
    }

    @Test
    fun helloBindsExactVersionToIndependentChallenge() {
        val challenge = ByteArray(32) { it.toByte() }
        assertArrayEquals("RPAP1\n".toByteArray(Charsets.US_ASCII) + challenge,
            RootPilotApiProvisionProtocol.hello(challenge))
        assertEquals(38, RootPilotApiProvisionProtocol.hello(challenge).size)
        failure(RootPilotApiProvisionProtocol.Code.CHALLENGE) {
            RootPilotApiProvisionProtocol.hello(ByteArray(31))
        }
    }

    @Test
    fun acceptsExactPrintableAsciiFrameIncludingBounds() {
        for (payload in listOf(byteArrayOf(0x21), byteArrayOf(0x7e),
            "synthetic-only-key".toByteArray(), ByteArray(4096) { 0x61 })) {
            val received = RootPilotApiProvisionProtocol.readKey(ByteArrayInputStream(frame(payload)))
            assertArrayEquals(payload, received)
            received.fill(0)
        }
    }

    @Test
    fun fragmentedHeaderAndPayloadAreReadWithDeadlineBeforeEveryRead() {
        val bytes = frame("synthetic-only-key".toByteArray())
        val source = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, 1)
        }
        var calls = 0
        val key = RootPilotApiProvisionProtocol.readKey(source) { calls++ }
        assertEquals(bytes.size + 1, calls)
        key.fill(0)
    }

    @Test
    fun rejectsZeroOversizedAndUnsignedOverflowBeforeAllocatingBody() {
        for (length in listOf(0, 4097, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            failure(RootPilotApiProvisionProtocol.Code.LENGTH) {
                RootPilotApiProvisionProtocol.readKey(ByteArrayInputStream(header(length)))
            }
        }
    }

    @Test
    fun rejectsEveryTruncatedHeaderAndPayload() {
        val bytes = frame("synthetic-only-key".toByteArray())
        for (size in 0 until bytes.size) {
            failure(RootPilotApiProvisionProtocol.Code.TRUNCATED) {
                RootPilotApiProvisionProtocol.readKey(ByteArrayInputStream(bytes.copyOf(size)))
            }
        }
    }

    @Test
    fun rejectsTrailingBytesAndWipesReceivedBuffer() {
        val source = recording(frame("synthetic-only-key".toByteArray()) + 0x21)
        failure(RootPilotApiProvisionProtocol.Code.TRAILING) { RootPilotApiProvisionProtocol.readKey(source) }
        assertTrue(source.payload!!.all { it == 0.toByte() })
    }

    @Test
    fun rejectsWhitespaceControlAndNonAsciiWithoutIncludingInputInFailure() {
        for (bad in listOf(0, 9, 10, 13, 32, 127, 128, 255)) {
            val source = recording(frame(byteArrayOf(0x61, bad.toByte(), 0x62)))
            failure(RootPilotApiProvisionProtocol.Code.KEY_FORMAT) { RootPilotApiProvisionProtocol.readKey(source) }
            assertTrue(source.payload!!.all { it == 0.toByte() })
        }
    }

    @Test
    fun truncationAfterSomePayloadAlsoWipesReceivedBuffer() {
        val source = recording(header(8) + byteArrayOf(0x61, 0x62))
        failure(RootPilotApiProvisionProtocol.Code.TRUNCATED) { RootPilotApiProvisionProtocol.readKey(source) }
        assertTrue(source.payload!!.all { it == 0.toByte() })
    }

    @Test
    fun ioExceptionDoesNotRetainOrRenderReceivedContent() {
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic-private-marker")
        }
        try {
            RootPilotApiProvisionProtocol.readKey(source)
            fail("failure_required")
        } catch (error: RootPilotApiProvisionProtocol.Failure) {
            assertEquals("IO", error.message)
            assertNull(error.cause)
            assertFalse(error.stackTraceToString().contains("synthetic-private-marker"))
        }
    }

    @Test
    fun deadlineFailureIsFixedAndWipesPartiallyReceivedPayload() {
        val source = recording(frame("synthetic-only-key".toByteArray()))
        var calls = 0
        failure(RootPilotApiProvisionProtocol.Code.IO) {
            RootPilotApiProvisionProtocol.readKey(source) {
                if (++calls == 3) throw IOException("synthetic-deadline-detail")
            }
        }
        assertTrue(source.payload!!.all { it == 0.toByte() })
    }

    @Test
    fun zeroProgressCannotCauseUnboundedLoop() {
        val source = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        failure(RootPilotApiProvisionProtocol.Code.TRUNCATED) { RootPilotApiProvisionProtocol.readKey(source) }
    }

    private fun failure(code: RootPilotApiProvisionProtocol.Code, block: () -> Unit) {
        try {
            block()
            fail("failure_required")
        } catch (error: RootPilotApiProvisionProtocol.Failure) {
            assertEquals(code, error.code)
            assertEquals(code.name, error.message)
            assertNull(error.cause)
        }
    }

    private fun header(size: Int): ByteArray = ByteArray(4) { index ->
        (size ushr ((3 - index) * 8)).toByte()
    }

    private fun frame(payload: ByteArray): ByteArray = header(payload.size) + payload

    private fun recording(bytes: ByteArray) = RecordingInput(bytes)

    private class RecordingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var payload: ByteArray? = null
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= 4) payload = buffer
            return super.read(buffer, offset, length)
        }
    }
}
