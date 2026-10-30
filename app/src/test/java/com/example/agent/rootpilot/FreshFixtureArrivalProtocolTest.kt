package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test

class FreshFixtureArrivalProtocolTest {
    private val epoch = "911c6529-ebd6-4cdc-a989-3999e75bb6fb"
    private val session = "a71115f8-402a-4a10-814c-78cd7e128f5a"
    private val instance = "0706c94e-f735-487c-92cd-d16601798cf1"
    private val old = "681f9942-61f5-4bb2-a74a-89f0ed3368f0"
    private fun values(phase: String = "SEEN", process: Any? = epoch, sid: Any? = session, iid: Any? = instance) =
        mapOf("processEpoch" to process, "arrival" to mapOf("phase" to phase,
            "sessionId" to if (phase == "SEEN") sid else null, "instanceId" to if (phase == "SEEN") iid else null))
    private fun classify(values: Map<String, Any?> = values(), baseline: String? = epoch) =
        FreshFixtureArrivalProtocol.classify(baseline, values, session, old)
    private fun arrival(values: Map<String, Any?>) = values["arrival"] as Map<*, *>
    private fun state() = mapOf("freshSessionId" to session, "instanceId" to instance,
        "processId" to 15234, "fixtureTaskId" to 812, "fixtureDisplayId" to 0,
        "fixtureProcessStartTicks" to 4294967296L, "ready" to true, "empty" to true,
        "matches" to false, "fieldId" to 16908291, "commitCalls" to 0)

    @Test fun onlyCleanEmptyBaselineSameEpochBoundSessionAndNewInstanceCanProveSeen() {
        assertEquals(epoch, FreshFixtureArrivalProtocol.baseline { values("EMPTY") })
        assertEquals("SEEN", classify())
        assertEquals("UNKNOWN", classify(baseline = null))
        assertEquals("UNKNOWN", classify(values(process = old)))
        assertEquals("UNKNOWN", classify(values(sid = old)))
        assertEquals("UNKNOWN", classify(values(iid = old)))
        listOf("EMPTY", "AMBIGUOUS").forEach { assertEquals("UNKNOWN", classify(values(it))) }
        listOf("SEEN", "AMBIGUOUS").forEach { assertNull(FreshFixtureArrivalProtocol.baseline { values(it) }) }
    }

    @Test fun invalidBaselineNeverGetsRepairedByPositivePostObservation() {
        val invalid = listOf(emptyMap(), values("EMPTY") - "processEpoch", values("EMPTY") + ("extra" to true),
            values("EMPTY", process = 1), values("EMPTY", process = epoch.uppercase()),
            values("EMPTY") + ("arrival" to mapOf("phase" to "EMPTY", "sessionId" to session, "instanceId" to null)))
        invalid.forEach { evidence ->
            val baseline = FreshFixtureArrivalProtocol.baseline { evidence }
            assertNull(baseline)
            assertEquals("UNKNOWN", classify(baseline = baseline))
        }
        assertNull(FreshFixtureArrivalProtocol.baseline { error("synthetic_decode_failure") })
    }

    @Test fun closedDiagnosticKeysAndEveryFieldRuntimeTypeAreRequired() {
        values().keys.forEach { assertEquals("UNKNOWN", classify(values() - it)) }
        arrival(values()).keys.forEach { key ->
            assertEquals("UNKNOWN", classify(values() + ("arrival" to (arrival(values()) - key))))
        }
        assertEquals("UNKNOWN", classify(values() + ("extra" to false)))
        assertEquals("UNKNOWN", classify(values() + ("arrival" to (arrival(values()) + ("extra" to false)))))
        listOf<Any?>(null, true, 1, 1L, "bad", session.uppercase(), " $session", "$session\n").forEach { bad ->
            assertEquals("UNKNOWN", classify(values(process = bad)))
            assertEquals("UNKNOWN", classify(values(sid = bad)))
            assertEquals("UNKNOWN", classify(values(iid = bad)))
        }
        listOf<Any?>(null, "SEEN", false, emptyList<Any>()).forEach { bad ->
            assertEquals("UNKNOWN", classify(values() + ("arrival" to bad)))
        }
        listOf<Any?>(null, true, 1, "seen", "UNKNOWN", "SEEN\n").forEach { bad ->
            assertEquals("UNKNOWN", classify(values() + ("arrival" to (arrival(values()) + ("phase" to bad)))))
        }
    }

    @Test fun absentOrAmbiguousIdsMustBeExplicitNullNotMissingOrContaminated() {
        listOf("EMPTY", "AMBIGUOUS").forEach { phase ->
            val valid = values(phase)
            listOf("sessionId", "instanceId").forEach { key ->
                val contaminated = valid + ("arrival" to (arrival(valid) + (key to session)))
                assertNull(FreshFixtureArrivalProtocol.baseline { contaminated })
                assertEquals("UNKNOWN", classify(contaminated))
                assertNull(FreshFixtureArrivalProtocol.baseline { valid + ("arrival" to (arrival(valid) - key)) })
            }
        }
    }

    @Test fun diagnosticsAndEmissionFailuresStillRunOriginalStrictStateAndNeverFabricateEmpty() {
        val original = state()
        val observed = mutableListOf<String>()
        assertSame(original, FreshFixtureArrivalProtocol.observeThenRead({ error("synthetic_decode_failure") }, observed::add) { original })
        assertEquals(listOf("UNKNOWN"), observed)
        var strictStateRan = false
        val identity = FreshFixtureArrivalProtocol.observeThenRead({ "SEEN" }, { error("synthetic_emit_failure") }) {
            strictStateRan = true
            FreshInputReadinessProtocol.state(original, session, old, 100)
        }
        assertTrue(strictStateRan)
        assertEquals(instance, identity.instanceId)
        val failure = IllegalStateException("original_state_failure")
        val thrown = assertThrows(IllegalStateException::class.java) {
            FreshFixtureArrivalProtocol.observeThenRead({ "SEEN" }, { error("synthetic_emit_failure") }) { throw failure }
        }
        assertSame(failure, thrown)
    }

    @Test fun diagnosticPositiveNeverWeakensOriginalStateKeysReadyOrIdentity() {
        val invalid = listOf(state() - "ready", state() + ("extra" to false), state() + ("ready" to false),
            state() + ("instanceId" to old), state() + ("processId" to 100), emptyMap())
        invalid.forEach { original ->
            val observations = mutableListOf<String>()
            assertThrows(Exception::class.java) {
                FreshFixtureArrivalProtocol.observeThenRead({ classify() }, observations::add) {
                    FreshInputReadinessProtocol.state(original, session, old, 100)
                }
            }
            assertEquals(listOf("SEEN"), observations)
            assertEquals(original, original.toMap())
        }
    }

    @Test fun fixedStatusIsOnceEvenIfFirstSendThrowsAndHasNoRawFallback() {
        val values = mutableListOf<String>()
        val once = FreshFixtureArrivalProtocol.Once { values.add(it); error("synthetic_emit_failure") }
        once.report("SEEN")
        once.report("UNKNOWN")
        once.report("SEEN")
        assertEquals(listOf("SEEN"), values)
        val unknown = mutableListOf<String>()
        FreshFixtureArrivalProtocol.Once(unknown::add).report("RAW_NOT_ALLOWED")
        assertEquals(listOf("UNKNOWN"), unknown)
        val emitted = mutableListOf<String>()
        FreshFixtureArrivalProtocol.observeThenRead({ "RAW_NOT_ALLOWED" }, emitted::add) { Unit }
        assertEquals(listOf("UNKNOWN"), emitted)
    }
}
