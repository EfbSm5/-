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
