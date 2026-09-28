package com.example.agent.rootpilot.loop

import com.example.agent.rootpilot.deepseek.DeepSeekActionResult
import com.example.agent.rootpilot.deepseek.DeepSeekClient
import com.example.agent.rootpilot.deepseek.DeepSeekVisionRequest
import com.example.agent.rootpilot.model.ExecutableRootAction
import com.example.agent.rootpilot.model.RootPilotConfig
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.root.RootExecutor
import com.example.agent.rootpilot.root.RootScreenshotResult
import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.ScreenshotCaptureResult
import com.example.agent.rootpilot.screen.ScreenshotFrame
import com.example.agent.rootpilot.screen.ScreenshotProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentLoopObservationTest {
    private val initial = ScreenObservation("com.test.app", "com.test.app.Main", "com.test.app", "123", false, 10)

    @Test fun stableWindowExecutesAfterOverlayRemovalAndIncludesObservation() = runTest {
        val fixture = Fixture()
        fixture.run()
        assertEquals(1, fixture.executions)
        assertEquals(listOf("observe", "capture", "observe", "model", "confirm", "hide", "observe", "execute"), fixture.order)
        assertEquals(initial, fixture.requests.single().observation)
        assertEquals(10L, fixture.requests.single().observationStartedAtMillis)
    }

    @Test fun captureWindowChangeDoesNotUpload() = runTest {
        val fixture = Fixture(listOf(initial, initial.copy(focusedWindowId = "456")))
        fixture.run()
        assertTrue(fixture.requests.isEmpty())
        assertEquals(0, fixture.executions)
        assertTrue(fixture.events.last() is AgentLoopEvent.Failed)
    }

    @Test fun unknownForegroundDoesNotUpload() = runTest {
        val fixture = Fixture(listOf(initial.copy(foregroundActivity = null)))
        fixture.run()
        assertTrue(fixture.requests.isEmpty())
        assertEquals(0, fixture.executions)
    }

    @Test fun changedOrUnknownWindowAfterConfirmationNeverExecutes() = runTest {
        for (changed in listOf(initial.copy(focusedWindowId = "456"), initial.copy(focusedPackage = null),
            initial.copy(foregroundActivity = "com.test.app.Other"), initial.copy(keyboardVisible = true),
            initial.copy(keyboardVisible = null))) {
            val fixture = Fixture(listOf(initial, initial, changed))
            fixture.run()
            assertEquals(1, fixture.requests.size)
            assertEquals(0, fixture.executions)
            assertTrue(fixture.events.last() is AgentLoopEvent.Failed)
        }
    }

    @Test fun typeAllowsImeVisibilityChangeButNotTargetChange() = runTest {
        val fixture = Fixture(listOf(initial, initial, initial.copy(keyboardVisible = true)), type = true)
        fixture.run()
        assertEquals(1, fixture.executions)
        val wrongTarget = Fixture(type = true, target = "com.other.app")
        wrongTarget.run()
        assertEquals(0, wrongTarget.executions)
    }

    @Test fun rejectionDoesNotObserveAgainOrExecute() = runTest {
        val fixture = Fixture()
        fixture.run(approve = false)
        assertEquals(2, fixture.order.count { it == "observe" })
        assertEquals(0, fixture.executions)
        assertTrue(fixture.events.last() is AgentLoopEvent.Stopped)
    }

    @Test fun missingUploadConsentDoesNotCollect() = runTest {
        val fixture = Fixture()
        fixture.run(consent = false)
        assertTrue(fixture.order.isEmpty())
    }

    @Test fun cancellationDuringFinalObservationDoesNotExecute() = runTest {
        val fixture = Fixture(cancelAt = 2)
        try {
            fixture.run()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, fixture.executions)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun keyboardChangeDuringCaptureDoesNotUpload() = runTest {
        val fixture = Fixture(listOf(initial, initial.copy(keyboardVisible = true)))
        fixture.run()
        assertTrue(fixture.requests.isEmpty())
        assertEquals(0, fixture.executions)
    }

    private inner class Fixture(
        private val samples: List<ScreenObservation> = listOf(initial),
        private val type: Boolean = false,
        private val target: String = "com.test.app",
        private val cancelAt: Int? = null,
    ) {
        val order = mutableListOf<String>()
        val events = mutableListOf<AgentLoopEvent>()
        val requests = mutableListOf<DeepSeekVisionRequest>()
        var executions = 0
        private var sampleIndex = 0
        private val root = object : RootExecutor {
            override suspend fun observeScreen(): ScreenObservation {
                order += "observe"
                if (sampleIndex == cancelAt) throw CancellationException("fixture")
                return samples[(sampleIndex++).coerceAtMost(samples.lastIndex)]
            }
            override suspend fun checkRoot() = RootExecutionResult.Success()
            override suspend fun captureScreen() = RootScreenshotResult.Failure("fixture")
            override suspend fun execute(action: ExecutableRootAction): RootExecutionResult {
                order += "execute"
                executions++
                return RootExecutionResult.Success()
            }
            override suspend fun executeConfirmed(action: ExecutableRootAction, confirm: suspend (String?) -> Boolean): RootExecutionResult =
                if (confirm(if (type) target else null)) execute(action) else RootExecutionResult.Failure("cancelled")
            override fun cancel() = Unit
        }
        suspend fun run(approve: Boolean = true, consent: Boolean = true) {
            val loop = AgentLoop(object : ScreenshotProvider {
                override suspend fun capture(): ScreenshotCaptureResult {
                    order += "capture"
                    return ScreenshotCaptureResult.Success(ScreenshotFrame(byteArrayOf(1), 100, 100, "fixture"))
                }
            }, object : DeepSeekClient {
                override suspend fun requestAction(request: DeepSeekVisionRequest): DeepSeekActionResult {
                    requests += request
                    order += "model"
                    return DeepSeekActionResult.Success(if (type)
                        """{"action":"type","text":"测试","reason":"fixture"}""" else
                        """{"action":"tap","x":500,"y":500,"reason":"fixture"}""")
                }
            }, root)
            loop.run(AgentLoopRequest(RootPilotConfig(allowScreenUpload = consent, manualConfirmation = true), 1, true)) {
                events += it
                if (it is AgentLoopEvent.AwaitingConfirmation) {
                    order += "confirm"
                    if (approve) it.approval.approve() else it.approval.reject()
                }
                if (it is AgentLoopEvent.Executing) order += "hide"
            }
        }
    }
}
