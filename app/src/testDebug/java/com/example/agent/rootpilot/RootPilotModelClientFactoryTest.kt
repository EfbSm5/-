package com.example.agent.rootpilot

import com.example.agent.rootpilot.deepseek.ChatToolCall
import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.deepseek.ModelProtocolReason
import com.example.agent.rootpilot.deepseek.ModelStreamSnapshot
import com.example.agent.rootpilot.deepseek.ToolChatResult
import com.example.agent.rootpilot.deepseek.ToolChatTurn
import com.example.agent.rootpilot.model.RootPilotApp
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.ScreenshotFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootPilotModelClientFactoryTest {
    private val nonce = "3b082e1f-4544-4229-8bdb-846336ab2f61"
    private fun session() = RootPilotModelClientFactory.Session(nonce, 101,
        RootPilotModelClientFactory.FixtureIdentity(
            "b6222350-2eb1-4f55-8f32-6fc43932c8c4", "5151c183-f981-4b51-bd5c-9d6ce597636c", 102, 1, 0, 23))
    private fun request() = DeepSeekVisionRequest(
        RootPilotConfig(task = RootPilotModelClientFactory.task(nonce), manualConfirmation = true, allowScreenUpload = true),
        ScreenshotFrame(byteArrayOf(), 1, 1, ""), emptyList(), 1,
        observation = ScreenObservation("com.example.rootpilot.fixture",
            "com.example.rootpilot.fixture.ExecutionFixtureActivity", "com.example.rootpilot.fixture", "window", false, 1))

    @Test fun returnedResponseRetainsActualTaskUntilSealedAndJoined() = runTest {
        val session = session()
        val service = Any()
        session.bind(service)
        var response = false
        val owner = launch {
            response = session.request(service, 101, request()) is DeepSeekActionResult.Success
            awaitCancellation()
        }
        runCurrent()
        assertTrue(response)
        assertTrue(owner.isActive)
        assertEquals(true, session.evidence(0).ownerActive)
        assertEquals(1, session.evidence(0).fixedRequests)
        assertTrue(session.sealAndJoin())
        assertTrue(owner.isCompleted)
        assertEquals(true, session.evidence(0).ownerCompleted)
        assertTrue(session.evidence(0).closed)
    }

    @Test fun secondConstructionClosesSessionAndCancelsOriginalOwner() = runTest {
        val session = session()
        val service = Any()
        session.bind(service)
        val owner = launch { session.request(service, 101, request()); awaitCancellation() }
        runCurrent()
        assertThrows(CancellationException::class.java) { session.bind(Any()) }
        owner.join()
        assertTrue(owner.isCancelled)
        assertTrue(session.evidence(0).closed)
    }

    @Test fun secondRequestCannotReturnAnotherAction() = runTest {
        val session = session()
        val service = Any()
        session.bind(service)
        var secondReturned = false
        val owner = launch {
            session.request(service, 101, request())
            session.request(service, 101, request())
            secondReturned = true
        }
        owner.join()
        assertTrue(owner.isCancelled)
        assertFalse(secondReturned)
        assertEquals(1, session.evidence(0).fixedRequests)
        assertTrue(session.evidence(0).closed)
    }

    @Test fun serviceAndPidMismatchCannotReceiveFixedAction() = runTest {
        for (wrongService in listOf(true, false)) {
            val session = session()
            val service = Any()
            session.bind(service)
            val owner = launch { session.request(if (wrongService) Any() else service,
                if (wrongService) 101 else 999, request()); fail("returned") }
            owner.join()
            assertTrue(owner.isCancelled)
            assertEquals(0, session.evidence(0).fixedRequests)
            assertTrue(session.evidence(0).closed)
        }
    }

    @Test fun cancelledBeforeRequestStaysClosedWithoutClaimingTaskJoined() = runTest {
        val session = session()
        val service = Any()
        session.bind(service)
        assertFalse(session.sealAndJoin())
        val owner = launch { session.request(service, 101, request()); fail("returned") }
        owner.join()
        assertTrue(owner.isCancelled)
        assertNull(session.evidence(0).ownerCompleted)
        assertEquals(0, session.evidence(0).fixedRequests)
    }

    @Test fun wrongFixtureContextFailsClosed() = runTest {
        val session = session()
        val service = Any()
        session.bind(service)
        val wrong = request().copy(observation = request().observation!!.copy(focusedPackage = "other"))
        val owner = launch { session.request(service, 101, wrong); fail("returned") }
        owner.join()
        assertTrue(owner.isCancelled)
        assertFalse(session.evidence(0).responseReturned)
        assertTrue(session.evidence(0).closed)
    }

    @Test fun invalidFixtureIdentityRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RootPilotModelClientFactory.Session(nonce, 101,
                RootPilotModelClientFactory.FixtureIdentity(nonce, nonce, 101, 1, 0, 1))
        }
    }

    private fun realSession() = RootPilotModelClientFactory.Session(nonce, 101,
        RootPilotModelClientFactory.FixtureIdentity(
            "b6222350-2eb1-4f55-8f32-6fc43932c8c4", "5151c183-f981-4b51-bd5c-9d6ce597636c", 102, 1, 0, 23), real = true)
    private val fixtureFrame = ScreenshotFrame(byteArrayOf(1), 2, 2, "fixture")
    private fun realRequest() = request().copy(
        config = request().config.copy(task = RootPilotModelClientFactory.REAL_TASK),
        frame = ScreenshotFrame(byteArrayOf(9), 9, 9, "display"),
        availableApps = listOf(RootPilotApp("com.example.other", "Other", "com.example.other.Main")))

    private class Delegate(val result: ToolChatResult = ToolChatResult.Success("{}", "", emptyList()),
        val gate: CompletableDeferred<Unit>? = null) : DeepSeekClient {
        val requests = mutableListOf<Triple<DeepSeekVisionRequest, List<ToolChatTurn>, Boolean>>()
        override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult = error("unused")
        override suspend fun requestDecision(request: DeepSeekVisionRequest, toolHistory: List<ToolChatTurn>,
            allowTools: Boolean, onUpdate: suspend (ModelStreamSnapshot) -> Unit): ToolChatResult {
            requests += Triple(request, toolHistory, allowTools)
            gate?.await()
            return result
        }
    }

    private suspend fun RootPilotModelClientFactory.Session.real(service: Any, delegate: DeepSeekClient,
        request: DeepSeekVisionRequest = realRequest(), history: List<ToolChatTurn> = emptyList(),
        frame: suspend () -> ScreenshotFrame = { fixtureFrame }) =
        requestReal(service, 101, request, history, delegate, frame) {}

    @Test fun realUploadsOnlyFixtureFrameWithoutToolsOrApps() = runTest {
        val session = realSession()
        val service = Any()
        session.bind(service)
        val delegate = Delegate()
        var result: ToolChatResult? = null
        val owner = launch { result = session.real(service, delegate); awaitCancellation() }
        runCurrent()
        assertTrue(result is ToolChatResult.Success)
        val (sent, history, tools) = delegate.requests.single()
        assertSame(fixtureFrame, sent.frame)
        assertTrue(sent.availableApps.isEmpty() && history.isEmpty() && !tools)
        assertEquals(1, session.evidence(0).delegatedRequests)
        assertTrue(session.evidence(0).responseReturned)
        assertTrue(session.sealAndJoin())
        assertTrue(owner.isCompleted)
    }

    @Test fun realFixtureExportFailureNeverReachesModel() = runTest {
        val session = realSession()
        val service = Any()
        session.bind(service)
        val delegate = Delegate()
        val owner = launch { session.real(service, delegate, frame = { error("other window") }); fail("returned") }
        owner.join()
        assertTrue(owner.isCancelled)
        assertTrue(delegate.requests.isEmpty())
        assertEquals(0, session.evidence(0).delegatedRequests)
        assertTrue(session.evidence(0).closed)
    }

    @Test fun realRejectsToolHistoryWrongTaskAndLocalPath() = runTest {
        for (case in 0..2) {
            val session = realSession()
            val service = Any()
            session.bind(service)
            val delegate = Delegate()
            val owner = launch {
                when (case) {
                    0 -> session.real(service, delegate, history = listOf(ToolChatTurn("tool", "{}", toolCallId = "1")))
                    1 -> session.real(service, delegate, request = request())
                    else -> session.request(service, 101, realRequest())
                }
                fail("returned")
            }
            owner.join()
            assertTrue(owner.isCancelled)
            assertTrue(delegate.requests.isEmpty())
            assertTrue(session.evidence(0).closed)
        }
        val local = session()
        val service = Any()
        local.bind(service)
        val delegate = Delegate()
        val owner = launch { local.real(service, delegate); fail("returned") }
        owner.join()
        assertTrue(owner.isCancelled && delegate.requests.isEmpty())
    }

    @Test fun realToolCallResponseIsRejected() = runTest {
        val session = realSession()
        val service = Any()
        session.bind(service)
        val delegate = Delegate(ToolChatResult.Success("", "", listOf(ChatToolCall("1", "get_ui_tree", "{}"))))
        var result: ToolChatResult? = null
        launch { result = session.real(service, delegate) }.join()
        assertEquals(ModelProtocolReason.TOOL_NOT_ALLOWED, (result as ToolChatResult.Failure).diagnostic.protocolReason)
        assertFalse(session.evidence(0).responseReturned)
    }

    @Test fun realSealedDuringRequestDiscardsResponse() = runTest {
        val session = realSession()
        val service = Any()
        session.bind(service)
        val gate = CompletableDeferred<Unit>()
        val delegate = Delegate(gate = gate)
        var returned = false
        val owner = launch { session.real(service, delegate); returned = true }
        runCurrent()
        assertEquals(1, delegate.requests.size)
        val sealing = launch { session.sealAndJoin() }
        runCurrent()
        gate.complete(Unit)
        sealing.join()
        assertTrue(owner.isCancelled)
        assertFalse(returned)
        assertFalse(session.evidence(0).responseReturned)
    }

    @Test fun realAllowsOnlyOneRetryFromSameOwner() = runTest {
        val session = realSession()
        val service = Any()
        session.bind(service)
        val delegate = Delegate()
        val owner = launch {
            repeat(RootPilotModelClientFactory.MAX_REAL_REQUESTS + 1) { session.real(service, delegate) }
            fail("returned")
        }
        owner.join()
        assertTrue(owner.isCancelled)
        assertEquals(RootPilotModelClientFactory.MAX_REAL_REQUESTS, delegate.requests.size)
        val other = realSession()
        other.bind(service)
        launch { other.real(service, delegate); awaitCancellation() }
        runCurrent()
        val second = launch { other.real(service, delegate); fail("returned") }
        second.join()
        assertTrue(second.isCancelled)
        assertTrue(other.evidence(0).closed)
    }
}
