package com.example.agent.rootpilot

import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.ScreenshotFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
