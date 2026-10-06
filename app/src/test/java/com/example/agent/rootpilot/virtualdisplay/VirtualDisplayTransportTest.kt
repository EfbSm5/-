package com.example.agent.rootpilot.virtualdisplay

import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Failure
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Identity
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Op
import com.example.agent.rootpilot.virtualdisplay.VirtualDisplayProtocol.Reason
import java.io.ByteArrayInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayTransportTest {
    @Test fun swipeAndAllowedKeysCrossTheActualFramedTransport() = runBlocking {
        val peer = Peer()
        val transport = peer.transport()
        val swipe = VirtualDisplayProtocol.payload { writeInt(3); writeInt(1800); writeInt(3); writeInt(100); writeInt(400) }
        val back = VirtualDisplayProtocol.payload { writeInt(4) }
        val enter = VirtualDisplayProtocol.payload { writeInt(66) }
        transport.start()
        transport.execute(Op.SWIPE, swipe)
        transport.execute(Op.KEY, back)
        transport.execute(Op.KEY, enter)
        transport.validate()
        assertTrue(transport.close())
        val actions = peer.received.filter { it.op == Op.SWIPE || it.op == Op.KEY }
        assertEquals(listOf(Op.SWIPE, Op.KEY, Op.KEY), actions.map { it.op })
        assertEquals(listOf(2L, 3L, 4L), actions.map { it.sequence })
        assertTrue(actions.all { it.session == peer.session })
        assertArrayEquals(swipe, actions[0].payload)
        assertArrayEquals(back, actions[1].payload)
        assertArrayEquals(enter, actions[2].payload)
        peer.assertStreamsClosed()
    }

    @Test fun nonActionOpcodeIsNotSentByExecute() = runBlocking {
        val peer = Peer()
        val transport = peer.transport()
        transport.start()
        val error = runCatching { transport.execute(Op.CAPTURE, byteArrayOf()) }.exceptionOrNull() as Failure
        assertEquals(Reason.UNSUPPORTED, error.reason)
        assertTrue(transport.close())
        assertEquals(listOf(Op.HELLO), peer.received.map { it.op })
    }

    @Test fun malformedActionReceiptTerminatesInsteadOfAcceptingOrReplaying() = runBlocking {
        val peer = Peer(extraActionReplyBytes = true)
        val transport = peer.transport()
        transport.start()
        assertEquals(Reason.PROTOCOL, (runCatching {
            transport.execute(Op.KEY, VirtualDisplayProtocol.payload { writeInt(4) })
        }.exceptionOrNull() as Failure).reason)
        assertTrue(runCatching { transport.validate() }.exceptionOrNull() is Failure)
        assertTrue(transport.close())
        assertEquals(1, peer.received.count { it.op == Op.KEY })
    }

    @Test fun cancellationDuringSwipeClosesWithoutReplay() = runBlocking {
        val peer = Peer(holdInputAction = true)
        val transport = peer.transport()
        transport.start()
        val action = async(Dispatchers.Default) {
            transport.execute(Op.SWIPE, VirtualDisplayProtocol.payload {
                writeInt(3); writeInt(1800); writeInt(3); writeInt(100); writeInt(400)
            })
        }
        assertTrue(peer.inputActionReceived.await(1, TimeUnit.SECONDS))
        action.cancelAndJoin()
        assertTrue(transport.close())
        assertEquals(1, peer.received.count { it.op == Op.SWIPE })
        peer.assertStreamsClosed()
    }

    @Test fun emptyDisplayStartsWithoutCaptureAndCloseIsIdempotent() = runBlocking {
        val peer = Peer()
        val transport = peer.transport()
        transport.start()
        assertEquals(42, transport.displayId)
        assertEquals(0, peer.captures.get())
        assertTrue(transport.close())
        assertTrue(transport.close())
        assertEquals(-1, transport.displayId)
        assertTrue(peer.stdinClosed.await(1, TimeUnit.SECONDS))
        peer.assertStreamsClosed()
        assertTrue(runCatching { transport.start() }.exceptionOrNull() is Failure)
        assertEquals(1, peer.launches.get())
    }

    @Test fun closeBeforeStartCreatesNothingAndCannotBeRestarted() = runBlocking {
        val launches = AtomicInteger()
        val transport = VirtualDisplayTransport(UUID.randomUUID(), { true }, { true }) { launches.incrementAndGet(); error("unexpected launch") }
        assertTrue(transport.close())
        assertTrue(transport.close())
        assertTrue(runCatching { transport.start() }.exceptionOrNull() is Failure)
        assertEquals(0, launches.get())
    }

    @Test fun idleCancellationReceivesEofReleaseAndDoesNotLockClose() = runBlocking {
        val peer = Peer()
        val transport = peer.transport()
        transport.start()
        transport.cancel()
        assertTrue(transport.close())
        assertTrue(transport.close())
        assertEquals(-1, transport.displayId)
        assertEquals(1, peer.launches.get())
    }

    @Test fun cancellationWhileWaitingFailsCallPromptlyButWaitsForReleaseProof() = runBlocking {
        val peer = Peer(holdWait = true)
        val transport = peer.transport()
        transport.start()
        val action = async(Dispatchers.Default) { runCatching { transport.execute(Op.WAIT, VirtualDisplayProtocol.payload { writeInt(5000) }) } }
        assertTrue(peer.waitReceived.await(1, TimeUnit.SECONDS))
        transport.cancel()
        assertTrue(withTimeout(1000) { action.await() }.exceptionOrNull() is Failure)
        assertTrue(transport.close())
        assertEquals(1, peer.launches.get())
    }

    @Test fun coroutineCancellationAlsoClosesInputWithoutRetry() = runBlocking {
        val peer = Peer(holdWait = true)
        val transport = peer.transport()
        transport.start()
        val action = async(Dispatchers.Default) { transport.execute(Op.WAIT, VirtualDisplayProtocol.payload { writeInt(5000) }) }
        assertTrue(peer.waitReceived.await(1, TimeUnit.SECONDS))
        action.cancelAndJoin()
        assertTrue(transport.close())
        assertTrue(runCatching { transport.start() }.exceptionOrNull() is Failure)
        assertEquals(1, peer.launches.get())
    }

    @Test fun startupCancellationOwnsLateProcessAndConfirmsNoLateDisplayCreate() = runBlocking {
        val peer = Peer()
        val entered = CountDownLatch(1)
        val permit = CountDownLatch(1)
        val transport = VirtualDisplayTransport(peer.session, { !peer.displayPresent.get() }, { !peer.isAlive }) {
            entered.countDown()
            check(permit.await(2, TimeUnit.SECONDS))
            peer.launches.incrementAndGet()
            peer
        }
        val start = async(Dispatchers.Default) { runCatching { transport.start() } }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        transport.cancel()
        assertTrue(withTimeout(1000) { start.await() }.exceptionOrNull() is Failure)
        permit.countDown()
        assertTrue(transport.close())
        assertEquals(0, peer.hellos.get())
        assertEquals(1, peer.launches.get())
        peer.assertStreamsClosed()
    }

    @Test fun createFailureCanCloseOnlyAfterTerminalReleaseAndExit() = runBlocking {
        val peer = Peer(startFailure = Reason.DISPLAY_EXISTS)
        val transport = peer.transport()
        val error = runCatching { transport.start() }.exceptionOrNull() as Failure
        assertEquals(Reason.DISPLAY_EXISTS, error.reason)
        assertTrue(transport.close())
        assertEquals(-1, transport.displayId)
    }

    @Test fun wrapperExitWithoutHelperReceiptIsNotReleaseProof() = runBlocking {
        val peer = Peer(sendReceipt = false)
        val transport = peer.transport()
        transport.start()
        assertFalse(transport.close())
        assertEquals(42, transport.displayId)
        assertFalse(transport.close())
        peer.assertStreamsClosed()
        assertEquals(1, peer.launches.get())
    }

    @Test fun malformedReleaseReceiptClosesStreamsAndKeepsFailureTerminal() = runBlocking {
        val peer = Peer(malformedReceipt = true)
        val transport = peer.transport()
        transport.start()
        assertFalse(transport.close())
        peer.assertStreamsClosed()
        assertEquals(42, transport.displayId)
        assertFalse(transport.close())
        assertTrue(runCatching { transport.start() }.exceptionOrNull() is Failure)
        assertEquals(1, peer.launches.get())
    }

    @Test fun validReceiptCannotHideStillPresentDisplay() = runBlocking {
        val peer = Peer(keepDisplay = true)
        val transport = peer.transport()
        transport.start()
        assertFalse(transport.close())
        assertEquals(42, transport.displayId)
    }

    @Test fun validateIdentityMismatchPermanentlyStopsSession() = runBlocking {
        val peer = Peer(changeIdentity = true)
        val transport = peer.transport()
        transport.start()
        assertTrue(runCatching { transport.validate() }.exceptionOrNull() is Failure)
        assertTrue(runCatching { transport.execute(Op.TAP, VirtualDisplayProtocol.payload { writeInt(1); writeInt(1) }) }.exceptionOrNull() is Failure)
        assertEquals(0, peer.taps.get())
        assertTrue(transport.close())
    }

    /** A byte-pipe peer, not an Android/display mock: tests the actual framing and EOF handshake. */
    private class Peer(
        private val holdWait: Boolean = false,
        private val startFailure: Reason? = null,
        private val sendReceipt: Boolean = true,
        private val malformedReceipt: Boolean = false,
        private val keepDisplay: Boolean = false,
        private val changeIdentity: Boolean = false,
        private val holdInputAction: Boolean = false,
        private val extraActionReplyBytes: Boolean = false,
    ) : Process() {
        val session: UUID = UUID.randomUUID()
        val launches = AtomicInteger()
        val captures = AtomicInteger()
        val hellos = AtomicInteger()
        val taps = AtomicInteger()
        val displayPresent = AtomicBoolean(false)
        val stdinClosed = CountDownLatch(1)
        private val stdoutClosed = CountDownLatch(1)
        private val stderrClosed = CountDownLatch(1)
        val waitReceived = CountDownLatch(1)
        val inputActionReceived = CountDownLatch(1)
        val received = Collections.synchronizedList(mutableListOf<VirtualDisplayProtocol.Frame>())
        private val exited = CountDownLatch(1)
        private val helperInput = PipedInputStream(4096)
        private val clientInput = object : PipedInputStream(4096) {
            override fun close() { try { super.close() } finally { stdoutClosed.countDown() } }
        }
        private val clientError = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { try { super.close() } finally { stderrClosed.countDown() } }
        }
        private val helperOutput = PipedOutputStream(clientInput)
        private val clientOutput = object : FilterOutputStream(PipedOutputStream(helperInput)) {
            override fun close() { try { super.close() } finally { stdinClosed.countDown() } }
        }

        init {
            Thread({
                var accepted = 0L
                try {
                    while (true) {
                        val request = VirtualDisplayProtocol.read(helperInput, false) ?: break
                        received += request
                        accepted = request.sequence
                        when (request.op) {
                            Op.HELLO -> {
                                hellos.incrementAndGet()
                                if (startFailure != null) {
                                    VirtualDisplayProtocol.write(helperOutput, VirtualDisplayProtocol.reply(request, startFailure))
                                    break
                                }
                                displayPresent.set(true)
                                VirtualDisplayProtocol.write(helperOutput, VirtualDisplayProtocol.reply(request, Reason.OK,
                                    VirtualDisplayProtocol.hello(Identity(42, "owned-display"))))
                            }
                            Op.VALIDATE -> VirtualDisplayProtocol.write(helperOutput, VirtualDisplayProtocol.reply(request, Reason.OK,
                                VirtualDisplayProtocol.hello(Identity(42, if (changeIdentity) "replacement" else "owned-display"))))
                            Op.WAIT -> {
                                waitReceived.countDown()
                                if (!holdWait) VirtualDisplayProtocol.write(helperOutput, VirtualDisplayProtocol.reply(request, Reason.OK))
                            }
                            Op.SWIPE, Op.KEY -> {
                                inputActionReceived.countDown()
                                if (!holdInputAction) VirtualDisplayProtocol.write(helperOutput,
                                    VirtualDisplayProtocol.reply(request, Reason.OK,
                                        if (extraActionReplyBytes) byteArrayOf(1) else byteArrayOf()))
                            }
                            Op.CLOSE -> {
                                VirtualDisplayProtocol.write(helperOutput, VirtualDisplayProtocol.reply(request, Reason.OK))
                                break
                            }
                            Op.CAPTURE -> captures.incrementAndGet()
                            Op.TAP -> taps.incrementAndGet()
                            else -> error("unexpected operation")
                        }
                    }
                    if (!keepDisplay) displayPresent.set(false)
                    if (sendReceipt) {
                        val receipt = VirtualDisplayProtocol.released(session, accepted, 1234)
                        VirtualDisplayProtocol.write(helperOutput, if (malformedReceipt) receipt.copy(payload = byteArrayOf()) else receipt)
                    }
                } finally {
                    helperOutput.close()
                    exited.countDown()
                }
            }, "virtual-display-protocol-peer").apply { isDaemon = true; start() }
        }

        fun transport() = VirtualDisplayTransport(session, { !displayPresent.get() }, { !isAlive }) { launches.incrementAndGet(); this }
        fun assertStreamsClosed() {
            assertTrue("owned stdin must close", stdinClosed.await(1, TimeUnit.SECONDS))
            assertTrue("owned stdout must close", stdoutClosed.await(1, TimeUnit.SECONDS))
            assertTrue("owned stderr must close", stderrClosed.await(1, TimeUnit.SECONDS))
        }
        override fun getOutputStream(): OutputStream = clientOutput
        override fun getInputStream(): InputStream = clientInput
        override fun getErrorStream(): InputStream = clientError
        override fun waitFor(): Int { exited.await(); return exitValue() }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int {
            if (exited.count != 0L) throw IllegalThreadStateException()
            return if (startFailure == null) 0 else 1
        }
        override fun destroy() { clientOutput.close(); helperOutput.close(); exited.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = exited.count != 0L
    }
}
