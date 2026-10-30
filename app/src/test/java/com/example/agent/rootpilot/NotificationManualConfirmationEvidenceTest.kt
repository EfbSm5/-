package com.example.agent.rootpilot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class NotificationManualConfirmationEvidenceTest {
    private val activity = Any()
    private fun lifecycle(evidence: NotificationManualConfirmationEvidence, now: Long, instance: Any = activity) {
        evidence.onCreated(instance, true, now)
        evidence.onResumed(instance, true, now + 1)
        evidence.onDestroyed(instance, true, now + 2)
    }
    private fun waiting() = NotificationManualConfirmationEvidence().also {
        it.arm(100)
        it.beginWait(200)
    }
    private fun failure(evidence: NotificationManualConfirmationEvidence, expected: NotificationManualConfirmationEvidence.Failure) {
        val value = evidence.snapshot(300)
        assertEquals(expected, value.failure)
        assertFalse(value.complete)
    }

    @Test fun sameInstanceLifecycleCompletesAndCanCloseAfterDeadline() {
        val evidence = waiting()
        lifecycle(evidence, 201)
        val value = evidence.finish(150_000, requireComplete = true)
        assertTrue(value.armed && value.waiting && value.complete && value.closed)
        assertTrue(value.noAutomaticApproval)
        assertEquals(0, value.automaticApprovalAttempts)
        assertNull(value.failure)
    }

    @Test fun fullWindowStartsAfterOpeningReturnsNotAtArm() {
        val evidence = NotificationManualConfirmationEvidence()
        evidence.arm(100)
        evidence.beginWait(50_000)
        assertNull(evidence.snapshot(169_996).failure)
        lifecycle(evidence, 169_997)
        assertTrue(evidence.snapshot(170_000).complete)
    }

    @Test fun completeLifecycleDuringOpenIsRetained() {
        val evidence = NotificationManualConfirmationEvidence()
        evidence.arm(100)
        lifecycle(evidence, 101)
        assertFalse(evidence.snapshot(104).complete)
        assertTrue(evidence.beginWait(50_000).complete)
    }

    @Test fun partialLifecycleDuringOpenContinuesAfterReturn() {
        val evidence = NotificationManualConfirmationEvidence()
        evidence.arm(100)
        evidence.onCreated(activity, true, 101)
        evidence.beginWait(200)
        evidence.onResumed(activity, true, 201)
        evidence.onDestroyed(activity, true, 202)
        assertTrue(evidence.snapshot(203).complete)
    }

    @Test fun eachTargetCallbackBeforeArmFailsPermanently() {
        val callbacks: List<(NotificationManualConfirmationEvidence) -> Unit> = listOf(
            { it.onCreated(activity, true, 10) },
            { it.onResumed(activity, true, 10) },
            { it.onDestroyed(activity, true, 10) },
        )
        callbacks.forEach { callback ->
            val evidence = NotificationManualConfirmationEvidence()
            callback(evidence)
            evidence.arm(100)
            evidence.beginWait(200)
            lifecycle(evidence, 201)
            failure(evidence, NotificationManualConfirmationEvidence.Failure.EARLY_ACTIVITY)
        }
    }

    @Test fun otherOwnActivityCreateResumeOrStartCannotSubstitute() {
        val callbacks: List<(NotificationManualConfirmationEvidence) -> Unit> = listOf(
            { it.onCreated(Any(), false, 201) },
            { it.onResumed(Any(), false, 201) },
            { it.onOtherActivityStarted(201) },
        )
        callbacks.forEach { callback ->
            val evidence = waiting()
            callback(evidence)
            lifecycle(evidence, 202)
            failure(evidence, NotificationManualConfirmationEvidence.Failure.ACTIVITY)
        }
    }

    @Test fun otherOwnActivityDuringPrepareAlsoFails() {
        val evidence = NotificationManualConfirmationEvidence()
        evidence.onOtherActivityStarted(10)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.EARLY_ACTIVITY)
    }

    @Test fun destructionOfPreExistingMainActivityIsNotAnEntry() {
        val evidence = NotificationManualConfirmationEvidence()
        evidence.onDestroyed(Any(), false, 10)
        evidence.arm(100)
        evidence.beginWait(200)
        lifecycle(evidence, 201)
        assertTrue(evidence.snapshot(204).complete)
    }

    @Test fun equalButDifferentActivityInstancesCannotComplete() {
        data class Instance(val id: Int)
        val created = Instance(1)
        val replacement = Instance(1)
        val evidence = waiting()
        evidence.onCreated(created, true, 201)
        evidence.onResumed(replacement, true, 202)
        evidence.onDestroyed(created, true, 203)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.ACTIVITY)
    }

    @Test fun differentDestroyedInstanceCannotComplete() {
        val evidence = waiting()
        evidence.onCreated(activity, true, 201)
        evidence.onResumed(activity, true, 202)
        evidence.onDestroyed(Any(), true, 203)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.ACTIVITY)
    }

    @Test fun missingCreateResumeOrReorderedLifecycleFails() {
        val evidence = waiting()
        evidence.onResumed(activity, true, 201)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.ACTIVITY)
        val noResume = waiting()
        noResume.onCreated(activity, true, 201)
        noResume.onDestroyed(activity, true, 202)
        failure(noResume, NotificationManualConfirmationEvidence.Failure.ACTIVITY)
    }

    @Test fun duplicateCreateResumeDestroyAndAnotherEntryInvalidateSuccess() {
        val callbacks: List<(NotificationManualConfirmationEvidence) -> Unit> = listOf(
            { it.onCreated(activity, true, 210) },
            { it.onResumed(activity, true, 210) },
            { it.onDestroyed(activity, true, 210) },
            { it.onCreated(Any(), true, 210) },
            { it.onOtherActivityStarted(210) },
        )
        callbacks.forEach { callback ->
            val evidence = waiting()
            lifecycle(evidence, 201)
            assertTrue(evidence.snapshot(204).complete)
            callback(evidence)
            failure(evidence, NotificationManualConfirmationEvidence.Failure.ACTIVITY)
        }
    }

    @Test fun entire120SecondWindowIsAvailableAndBoundaryIsRejected() {
        val evidence = waiting()
        assertNull(evidence.snapshot(120_199).failure)
        assertEquals(NotificationManualConfirmationEvidence.Failure.TIMEOUT, evidence.snapshot(120_200).failure)
        lifecycle(evidence, 120_201)
        assertFalse(evidence.snapshot(120_204).complete)
    }

    @Test fun creationBeforeDeadlineDoesNotPermitLateResumeOrDestroy() {
        val evidence = waiting()
        evidence.onCreated(activity, true, 120_199)
        evidence.onResumed(activity, true, 120_200)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.TIMEOUT)
        val lateDestroy = waiting()
        lateDestroy.onCreated(activity, true, 120_198)
        lateDestroy.onResumed(activity, true, 120_199)
        lateDestroy.onDestroyed(activity, true, 120_200)
        failure(lateDestroy, NotificationManualConfirmationEvidence.Failure.TIMEOUT)
    }

    @Test fun directLateCallbackFailsEvenWithoutTimeoutPoll() {
        val evidence = waiting()
        evidence.onCreated(activity, true, 120_200)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.TIMEOUT)
    }

    @Test fun duplicateArmAndWaitCannotExtendDeadline() {
        val evidence = waiting()
        evidence.arm(250)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.STATE)
        val secondWait = waiting()
        secondWait.beginWait(250)
        failure(secondWait, NotificationManualConfirmationEvidence.Failure.STATE)
    }

    @Test fun invalidTimeAndWaitBeforeArmFailClosed() {
        val evidence = NotificationManualConfirmationEvidence()
        failureAfter(evidence, { it.beginWait(0) })
        failureAfter(NotificationManualConfirmationEvidence(), { it.arm(-1) })
        failureAfter(NotificationManualConfirmationEvidence().also { it.arm(100) }, { it.beginWait(99) })
        failureAfter(NotificationManualConfirmationEvidence().also { it.arm(0) }, { it.beginWait(Long.MAX_VALUE) })
    }
    private fun failureAfter(evidence: NotificationManualConfirmationEvidence, action: (NotificationManualConfirmationEvidence) -> Unit) {
        action(evidence)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.STATE)
    }

    @Test fun automaticApprovalAttemptPoisonsEvenCompletedLifecycle() {
        val evidence = waiting()
        lifecycle(evidence, 201)
        evidence.automaticApprovalAttempt()
        evidence.automaticApprovalAttempt()
        val value = evidence.snapshot(210)
        assertEquals(2, value.automaticApprovalAttempts)
        assertFalse(value.noAutomaticApproval)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.AUTOMATIC_APPROVAL)
    }

    @Test fun closeWithoutCompleteCannotCreateSuccessAndFailureIsSticky() {
        val evidence = waiting()
        val value = evidence.finish(201, requireComplete = true)
        assertTrue(value.closed)
        failure(evidence, NotificationManualConfirmationEvidence.Failure.STATE)
        val failed = waiting()
        failed.onCreated(Any(), false, 201)
        failed.automaticApprovalAttempt()
        failure(failed, NotificationManualConfirmationEvidence.Failure.ACTIVITY)
    }

    @Test fun callbackThreadDuringOpenIsVisibleToBeginWaitAndPollingThread() {
        val evidence = NotificationManualConfirmationEvidence()
        val armed = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val callback = Thread {
            try {
                check(armed.await(5, TimeUnit.SECONDS))
                lifecycle(evidence, 101)
            } catch (failure: Throwable) { error.set(failure) }
            finally { delivered.countDown() }
        }
        callback.start()
        evidence.arm(100)
        armed.countDown()
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        callback.join(5_000)
        assertFalse(callback.isAlive)
        assertNull(error.get())
        assertTrue(evidence.beginWait(50_000).complete)
        assertTrue(evidence.finish(50_001, requireComplete = true).complete)
    }
}
