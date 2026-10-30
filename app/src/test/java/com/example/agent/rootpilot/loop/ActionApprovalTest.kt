package com.example.agent.rootpilot.loop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionApprovalTest {
    private fun typeScope() = TypeApprovalScope(0,
        com.example.agent.rootpilot.model.RootPilotAction.Type("fixture", "fixture"))

    @Test
    fun twoStageGenericApprovalAndCrossStageTokensAreRejected() = runTest {
        val scope = typeScope()
        val a = scope.preparation
        a.approve()
        assertFalse(a.approve(a.token))
        assertFalse(scope.approveTypeFromIme(a.token))
        assertEquals(null, scope.beginCommit())
        assertTrue(scope.acceptPreparationFromNotification(a.token))
        assertTrue(a.await())
        val b = checkNotNull(scope.beginCommit())
        assertNotEquals(a.token, b.token)
        b.approve()
        assertFalse(b.approve(b.token))
        assertFalse(scope.acceptPreparationFromNotification(b.token))
        assertFalse(scope.approveTypeFromIme(a.token))
        assertTrue(scope.approveTypeFromIme(b.token))
        assertFalse(scope.approveTypeFromIme(b.token))
        assertTrue(b.await())
    }

    @Test
    fun cancelledPreparationNeverCreatesCommit() = runTest {
        val scope = typeScope()
        scope.close()
        assertFalse(scope.preparation.await())
        assertFalse(scope.acceptPreparationFromNotification(scope.preparation.token))
        assertEquals(null, scope.beginCommit())
    }

    @Test
    fun cancellationInPreparationCommitGapRevokesScope() = runTest {
        val scope = typeScope()
        assertTrue(scope.acceptPreparationFromNotification(scope.preparation.token))
        scope.close()
        assertTrue(scope.isClosed)
        assertEquals(null, scope.beginCommit())
    }

    @Test
    fun cancelledCommitAndOldScopeTokensCannotApprove() = runTest {
        val old = typeScope()
        old.acceptPreparationFromNotification(old.preparation.token)
        val b = checkNotNull(old.beginCommit())
        old.close()
        assertFalse(b.await())
        assertFalse(old.approveTypeFromIme(b.token))
        val next = typeScope()
        assertFalse(next.acceptPreparationFromNotification(old.preparation.token))
        next.acceptPreparationFromNotification(next.preparation.token)
        val nextB = checkNotNull(next.beginCommit())
        assertFalse(next.approveTypeFromIme(b.token))
        assertTrue(next.approveTypeFromIme(nextB.token))
    }

    @Test
    fun concurrentPreparationAndFinalClicksEachAcceptOnce() = runTest {
        val scope = typeScope()
        val aResults = List(8) { async(Dispatchers.Default) {
            scope.acceptPreparationFromNotification(scope.preparation.token)
        } }.awaitAll()
        assertEquals(1, aResults.count { it })
        val b = checkNotNull(scope.beginCommit())
        assertEquals(null, scope.beginCommit())
        val bResults = List(8) { async(Dispatchers.Default) {
            scope.approveTypeFromIme(b.token)
        } }.awaitAll()
        assertEquals(1, bResults.count { it })
        assertTrue(b.await())
    }

    @Test
    fun tokenApprovesOnlyOnce() = runTest {
        val approval = ActionApproval(CompletableDeferred())
        assertTrue(approval.approve(approval.token))
        assertFalse(approval.approve(approval.token))
        assertTrue(approval.await())
    }

    @Test
    fun oldOrMissingTokenCannotApproveAnotherAction() = runTest {
        val old = ActionApproval(CompletableDeferred())
        old.approve()
        val nextDecision = CompletableDeferred<Boolean>()
        val next = ActionApproval(nextDecision)
        assertNotEquals(old.token, next.token)
        assertFalse(next.approve(old.token))
        assertFalse(next.approve(""))
        assertFalse(nextDecision.isCompleted)
    }

    @Test
    fun stoppedActionCannotBeApproved() = runTest {
        val approval = ActionApproval(CompletableDeferred())
        approval.reject()
        assertFalse(approval.approve(approval.token))
        assertFalse(approval.await())
    }

    @Test
    fun confirmationFromExistingUiConsumesNotificationApproval() = runTest {
        val approval = ActionApproval(CompletableDeferred())
        approval.approve()
        assertFalse(approval.approve(approval.token))
        assertTrue(approval.await())
    }

    @Test
    fun concurrentNotificationClicksConsumeApprovalOnlyOnce() = runTest {
        val approval = ActionApproval(CompletableDeferred())
        val results = List(8) {
            async(Dispatchers.Default) { approval.approve(approval.token) }
        }.awaitAll()
        assertEquals(1, results.count { it })
        assertTrue(approval.await())
    }
}
