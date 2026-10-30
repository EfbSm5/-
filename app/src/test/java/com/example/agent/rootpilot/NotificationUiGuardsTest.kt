package com.example.agent.rootpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NotificationUiGuardsTest {
    private enum class Reason { MISSING, AMBIGUOUS, DISABLED, NOT_CLICKABLE, WAIT_TIMEOUT, LABEL_EMPTY }
    private class GuardFailure(val reason: Reason) : AssertionError(reason.name)
    private val guards = NotificationUiGuards<Reason> { throw GuardFailure(it) }

    @Test fun falseGuardUsesOnlyItsFixedReason() {
        for (reason in Reason.values()) {
            val failure = failure { guards.require(false, reason) }
            assertEquals(reason, failure.reason)
            assertEquals(reason.name, failure.message)
        }
    }

    @Test fun trueGuardDoesNotInvokeFailure() {
        var calls = 0
        NotificationUiGuards<Reason> { calls++; throw GuardFailure(it) }.require(true, Reason.WAIT_TIMEOUT)
        assertEquals(0, calls)
    }

    @Test fun zeroCandidatesUsesMissingReason() {
        assertEquals(Reason.MISSING, failure {
            guards.single(emptyList<Any>(), Reason.MISSING, Reason.AMBIGUOUS)
        }.reason)
    }

    @Test fun oneCandidateReturnsTheSameObjectWithoutReadingItsContents() {
        val candidate = privateCandidate()
        assertSame(candidate, guards.single(listOf(candidate), Reason.MISSING, Reason.AMBIGUOUS))
    }

    @Test fun multipleCandidatesUseAmbiguousReasonWithoutReadingTheirContents() {
        for (count in 2..3) {
            val failure = failure {
                guards.single(List(count) { privateCandidate() }, Reason.MISSING, Reason.AMBIGUOUS)
            }
            assertEquals(Reason.AMBIGUOUS, failure.reason)
            assertEquals("AMBIGUOUS", failure.message)
        }
    }

    @Test fun failureIsPropagatedUnchangedWithoutWrappingOrReadingItsMessage() {
        val expected = AssertionError("fixed")
        val guards = NotificationUiGuards<Reason> { throw expected }
        try {
            guards.single(emptyList<Any>(), Reason.MISSING, Reason.AMBIGUOUS)
            throw AssertionError("failure expected")
        } catch (actual: AssertionError) {
            assertSame(expected, actual)
        }
    }

    @Test fun disabledGuardStopsBeforeClickableReadOrClick() {
        var clickableReads = 0
        var clicks = 0
        assertEquals(Reason.DISABLED, failure {
            guards.require(false, Reason.DISABLED)
            guards.require(run { clickableReads++; true }, Reason.NOT_CLICKABLE)
            clicks++
        }.reason)
        assertEquals(0, clickableReads)
        assertEquals(0, clicks)
    }

    @Test fun nonClickableGuardStopsBeforeClick() {
        var clicks = 0
        assertEquals(Reason.NOT_CLICKABLE, failure {
            guards.require(true, Reason.DISABLED)
            guards.require(false, Reason.NOT_CLICKABLE)
            clicks++
        }.reason)
        assertEquals(0, clicks)
    }

    @Test fun emptyAppLabelStopsBeforeEitherScopedQuery() {
        var queries = 0
        assertEquals(Reason.LABEL_EMPTY, failure {
            guards.require(false, Reason.LABEL_EMPTY)
            guards.single(run { queries++; listOf(privateCandidate()) }, Reason.MISSING, Reason.AMBIGUOUS)
            guards.single(run { queries++; listOf(privateCandidate()) }, Reason.MISSING, Reason.AMBIGUOUS)
        }.reason)
        assertEquals(0, queries)
    }

    @Test fun nonUniqueAppLabelStopsBeforeRowTitleQuery() {
        for (count in listOf(0, 2)) {
            var titleQueries = 0
            val failure = failure {
                guards.require(true, Reason.LABEL_EMPTY)
                guards.single(List(count) { privateCandidate() }, Reason.MISSING, Reason.AMBIGUOUS)
                guards.single(run { titleQueries++; listOf(privateCandidate()) }, Reason.MISSING, Reason.AMBIGUOUS)
            }
            assertEquals(if (count == 0) Reason.MISSING else Reason.AMBIGUOUS, failure.reason)
            assertEquals(0, titleQueries)
        }
    }

    @Test fun successfulGuardsPreserveOrderAndDoNotSkipLaterChecks() {
        val order = mutableListOf<Int>()
        guards.require(run { order += 1; true }, Reason.LABEL_EMPTY)
        guards.single(run { order += 2; listOf(privateCandidate()) }, Reason.MISSING, Reason.AMBIGUOUS)
        guards.single(run { order += 3; listOf(privateCandidate()) }, Reason.MISSING, Reason.AMBIGUOUS)
        guards.require(run { order += 4; true }, Reason.DISABLED)
        guards.require(run { order += 5; true }, Reason.NOT_CLICKABLE)
        assertEquals(listOf(1, 2, 3, 4, 5), order)
    }

    private fun privateCandidate(): Any = object {
        override fun toString(): String = throw AssertionError("candidate contents must not be read")
        override fun equals(other: Any?): Boolean = throw AssertionError("candidate equality must not be read")
        override fun hashCode(): Int = throw AssertionError("candidate hash must not be read")
    }

    private fun failure(block: () -> Unit): GuardFailure {
        try { block() } catch (failure: GuardFailure) { return failure }
        throw AssertionError("guard failure expected")
    }
}
