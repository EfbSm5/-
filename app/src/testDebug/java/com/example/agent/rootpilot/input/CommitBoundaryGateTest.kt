package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.root.RootExecutionResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitBoundaryGateTest {
    private val target = CommitBoundaryGate.FIXTURE_PACKAGE
    private fun id() = UUID.randomUUID().toString()

    @Test fun unarmedDoesNotWaitOrRestrictTarget() = runTest {
        val gate = CommitBoundaryGate({ testScheduler.currentTime }, { 42 })
        var calls = 0
        val result = gate.aroundSubmission("another.allowed.app") { calls++; RootExecutionResult.Success() }
        assertTrue(result is RootExecutionResult.Success)
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun beforeSubmitTimeoutNeverInjectsAndUnwindsFinally() = runTest {
        val gate = CommitBoundaryGate({ 1000 + testScheduler.currentTime }, { 42 })
        val id = id()
        gate.arm(id, 42, CommitBoundaryGate.Boundary.BEFORE_SUBMIT)
        var calls = 0
        var returned = false
        var restored = false
        val task = launch {
            try {
                gate.aroundSubmission(target) { calls++; RootExecutionResult.Success() }
                returned = true
            } finally { restored = true }
        }
        runCurrent()
        assertEquals(1000L, gate.receipt(id)!!.reachedAt)
        assertEquals(16000L, gate.receipt(id)!!.deadline)
        assertEquals(0, calls)
        advanceTimeBy(15_000)
        runCurrent()
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(returned)
        assertTrue(restored)
        assertEquals(0, calls)
        assertNull(gate.receipt(id))
        gate.arm(id(), 42, CommitBoundaryGate.Boundary.BEFORE_SUBMIT)
    }

    @Test fun afterSubmitCancellationDoesNotReturnSuccessOrRepeatInjection() = runTest {
        val gate = CommitBoundaryGate({ 1000 + testScheduler.currentTime }, { 42 })
        val id = id()
        gate.arm(id, 42, CommitBoundaryGate.Boundary.AFTER_SUBMIT)
        var calls = 0
        var returned = false
        var restored = false
        val task = launch {
            try {
                gate.aroundSubmission(target) { calls++; RootExecutionResult.Success() }
                returned = true
            } finally { restored = true }
        }
        runCurrent()
        assertEquals(1, calls)
        assertNotNull(gate.receipt(id))
        gate.cancel("different-receipt")
        assertNotNull(gate.receipt(id))
        gate.cancel(id)
        runCurrent()
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(returned)
        assertTrue(restored)
        assertEquals(1, calls)
        assertNull(gate.receipt(id))
    }

    @Test fun duplicateArmCannotReplaceReceipt() = runTest {
        val gate = CommitBoundaryGate({ 1000 }, { 42 })
        val id = id()
        gate.arm(id, 42, CommitBoundaryGate.Boundary.BEFORE_SUBMIT)
        assertThrows(IllegalStateException::class.java) { gate.arm(id(), 42, CommitBoundaryGate.Boundary.AFTER_SUBMIT) }
        gate.cancel(id)
        assertThrows(IllegalStateException::class.java) { gate.arm(id(), 42, CommitBoundaryGate.Boundary.AFTER_SUBMIT) }
    }

    @Test fun cancellationBeforeEntryRemainsLatchedAndPreventsSubmission() = runTest {
        for (boundary in CommitBoundaryGate.Boundary.entries) {
            val gate = CommitBoundaryGate({ testScheduler.currentTime }, { 42 })
            val receiptId = id()
            gate.arm(receiptId, 42, boundary)
            gate.cancel(receiptId)
            gate.cancel(receiptId)
            advanceTimeBy(CommitBoundaryGate.TIMEOUT_MILLIS + 1)
            assertNull(gate.receipt(receiptId))
            assertThrows(IllegalStateException::class.java) {
                gate.arm(id(), 42, boundary)
            }
            var calls = 0
            var restored = false
            val task = launch {
                try {
                    gate.aroundSubmission(target) { calls++; RootExecutionResult.Success() }
                } finally {
                    restored = true
                }
            }
            runCurrent()
            task.join()
            assertTrue(task.isCancelled)
            assertTrue(restored)
            assertEquals(0, calls)
            gate.arm(id(), 42, boundary)
        }
    }

    @Test fun invalidIdentityCannotArm() = runTest {
        val gate = CommitBoundaryGate({ 1000 }, { 42 })
        assertThrows(IllegalArgumentException::class.java) { gate.arm("not-a-uuid", 42, CommitBoundaryGate.Boundary.BEFORE_SUBMIT) }
        assertThrows(IllegalArgumentException::class.java) { gate.arm(id(), 43, CommitBoundaryGate.Boundary.BEFORE_SUBMIT) }
    }

    @Test fun wrongTargetCancelsWithoutInjectionAndClearsToken() = runTest {
        val gate = CommitBoundaryGate({ 1000 }, { 42 })
        gate.arm(id(), 42, CommitBoundaryGate.Boundary.AFTER_SUBMIT)
        var calls = 0
        val error = runCatching { gate.aroundSubmission("wrong.package") { calls++; RootExecutionResult.Success() } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, calls)
        gate.arm(id(), 42, CommitBoundaryGate.Boundary.BEFORE_SUBMIT)
    }

    @Test fun expiredUnreachedGateCannotSubmit() = runTest {
        var time = 1000L
        val gate = CommitBoundaryGate({ time }, { 42 })
        gate.arm(id(), 42, CommitBoundaryGate.Boundary.AFTER_SUBMIT)
        time += 15_000
        var calls = 0
        val error = runCatching { gate.aroundSubmission(target) { calls++; RootExecutionResult.Success() } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, calls)
    }

    @Test fun failedSubmitDoesNotPauseAndClearsToken() = runTest {
        val gate = CommitBoundaryGate({ 1000 }, { 42 })
        gate.arm(id(), 42, CommitBoundaryGate.Boundary.AFTER_SUBMIT)
        val result = gate.aroundSubmission(target) { RootExecutionResult.Failure("fixed_failure") }
        assertTrue(result is RootExecutionResult.Failure)
        gate.arm(id(), 42, CommitBoundaryGate.Boundary.BEFORE_SUBMIT)
    }
}
