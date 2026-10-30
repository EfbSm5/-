package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test

class FreshInputReadinessProtocolTest {
    private val session = "a71115f8-402a-4a10-814c-78cd7e128f5a"
    private val instance = "0706c94e-f735-487c-92cd-d16601798cf1"
    private val old = "681f9942-61f5-4bb2-a74a-89f0ed3368f0"
    private fun identity(): Map<String, Any?> = mapOf("freshSessionId" to session, "instanceId" to instance,
        "processId" to 15234, "fixtureTaskId" to 812, "fixtureDisplayId" to 0, "fixtureProcessStartTicks" to 4294967296L)
    private fun ready() = identity() + mapOf("creationOutcome" to "READY", "creationReason" to "NONE",
        "rejectedAdmissionSelfFinishAttempted" to false)
    private fun state() = identity() + mapOf("ready" to true, "empty" to true, "matches" to false, "fieldId" to 16908291, "commitCalls" to 0)
    private fun reject(reason: String, selfFinish: Boolean = true) = mapOf("freshSessionId" to session,
        "creationOutcome" to "REJECTED", "creationReason" to reason, "rejectedAdmissionSelfFinishAttempted" to selfFinish)
    private fun callback(values: Map<String, Any?> = ready(), code: Int = 1) =
        FreshInputReadinessProtocol.callback(code, values, session, old, 100)
    private fun parseState(values: Map<String, Any?> = state()) = FreshInputReadinessProtocol.state(values, session, old, 100)
    private fun invalid(block: () -> Unit) { assertThrows(Exception::class.java) { block() } }

    @Test fun readyAndSignedStateHaveSameIndependentTuple() {
        assertEquals(parseState(), callback().identity)
        assertEquals(4294967296L, parseState().processStartTicks)
        assertFalse(callback().selfFinish!!)
    }
    @Test fun ticksArePositiveInt64NotInt32OrCoercibleScalars() {
        listOf<Any?>(0L, -1L, 1, "4294967296", true, 2.0, null).forEach { value ->
            invalid { parseState(state() + ("fixtureProcessStartTicks" to value)) }
        }
        assertEquals(Long.MAX_VALUE, parseState(state() + ("fixtureProcessStartTicks" to Long.MAX_VALUE)).processStartTicks)
    }
    @Test fun strictKeysAndRuntimeTypes() {
        state().keys.forEach { key -> invalid { parseState(state() - key) } }
        ready().keys.forEach { key -> invalid { callback(ready() - key) } }
        invalid { parseState(state() + ("extra" to false)) }
        invalid { callback(ready() + ("extra" to false)) }
        listOf("processId", "fixtureTaskId", "fixtureDisplayId", "fieldId", "commitCalls").forEach { key ->
            invalid { parseState(state() + (key to 0L)) }
        }
        listOf("ready", "empty", "matches").forEach { key -> invalid { parseState(state() + (key to "true")) } }
    }
    @Test fun rejectsOldRecreatedForeignOrNoncanonicalIdentity() {
        invalid { parseState(state() + ("instanceId" to old)) }
        invalid { parseState(state() + ("instanceId" to instance.uppercase())) }
        invalid { parseState(state() + ("freshSessionId" to old)) }
        invalid { parseState(state() + ("processId" to 100)) }
        invalid { parseState(state() + ("processId" to 0)) }
        invalid { parseState(state() + ("fixtureTaskId" to -1)) }
        invalid { parseState(state() + ("fixtureDisplayId" to 1)) }
        assertNotEquals(parseState(), parseState(state() + ("instanceId" to "aa06c94e-f735-487c-92cd-d16601798cf1")))
        assertNotEquals(parseState(), parseState(state() + ("fixtureProcessStartTicks" to 1L)))
        assertNotEquals(parseState(), parseState(state() + ("fixtureTaskId" to 813)))
    }
    @Test fun fixedEmptyPageRequiredWithoutTextTransfer() {
        invalid { parseState(state() + ("ready" to false)) }
        invalid { parseState(state() + ("empty" to false)) }
        invalid { parseState(state() + ("matches" to true)) }
        invalid { parseState(state() + ("commitCalls" to 1)) }
        invalid { parseState(state() + ("fieldId" to 1)) }
    }
    @Test fun rejectionScopesSelfFinishAndNeverClaimsDestroyedClosure() {
        listOf("CONFLICT", "INVALID_REQUEST", "PROCESS_IDENTITY_UNAVAILABLE").forEach { reason ->
            assertTrue(callback(reject(reason), 0).selfFinish!!)
            invalid { callback(reject(reason, false), 0) }
        }
        assertFalse(callback(reject("DESTROYED", false), 0).selfFinish!!)
        invalid { callback(reject("DESTROYED"), 0) }
        invalid { callback(reject("anything"), 0) }
        invalid { callback(reject("CONFLICT"), 1) }
        invalid { callback(code = 0) }
    }
    @Test fun malformedFirstCallbackTerminatesAndCannotBeRepaired() {
        val gate = FreshInputReadinessProtocol.Handoff()
        assertEquals("INVALID_CALLBACK", gate.offer { callback(ready() - "instanceId") }!!.reason)
        assertNull(gate.offer { callback() })
    }
    @Test fun timeoutCloseAndDuplicatesNeverReviveChannel() {
        val closed = FreshInputReadinessProtocol.Handoff(); closed.close()
        assertNull(closed.offer { callback() })
        val ready = FreshInputReadinessProtocol.Handoff()
        assertEquals("READY", ready.offer { callback() }!!.outcome)
        assertNull(ready.offer { callback(reject("DESTROYED", false), 0) })
    }
    @Test fun lifecycleEvidenceIsFixedAndSeparateFromTerminalHandoff() {
        val progress = mapOf("freshSessionId" to session, "event" to "CREATE", "state" to "00uuuu")
        assertEquals("CREATE:00uuuu", FreshInputReadinessProtocol.lifecycle(2, progress, session))
        listOf("CREATE", "RESUME", "FOCUS_GAINED", "FOCUS_LOST", "LAYOUT").forEach {
            assertEquals("$it:01010u", FreshInputReadinessProtocol.lifecycle(2, progress + ("event" to it) + ("state" to "01010u"), session))
        }
        invalid { FreshInputReadinessProtocol.lifecycle(1, progress, session) }
        invalid { FreshInputReadinessProtocol.lifecycle(2, progress + ("freshSessionId" to old), session) }
        invalid { FreshInputReadinessProtocol.lifecycle(2, progress + ("event" to "EXTRA"), session) }
        listOf("1", "00000x", "0000000", "00000\n").forEach { bad ->
            invalid { FreshInputReadinessProtocol.lifecycle(2, progress + ("state" to bad), session) }
        }
        invalid { FreshInputReadinessProtocol.lifecycle(2, progress + ("extra" to "text"), session) }
        invalid { FreshInputReadinessProtocol.lifecycle(2, progress - "state", session) }
        invalid { FreshInputReadinessProtocol.lifecycle(2, progress + ("state" to true), session) }
        invalid { callback(progress, 2) }
    }
    @Test fun independentHandoffUsesNoInventedOldIdentityButSharesStrictTuple() {
        val values = state() + ("instanceId" to old)
        val independent = FreshInputReadinessProtocol.independentState(values, session, 100)
        assertEquals(old, independent.instanceId)
        invalid { parseState(values) }
        assertEquals(independent, FreshInputReadinessProtocol.independentCallback(1,
            ready() + ("instanceId" to old), session, 100).identity)
    }
    @Test fun independentStateAndCallbackRejectAllMissingExtraAndCoercedFields() {
        fun read(values: Map<String, Any?>) = FreshInputReadinessProtocol.independentState(values, session, 100)
        fun receive(values: Map<String, Any?>, code: Int = 1) =
            FreshInputReadinessProtocol.independentCallback(code, values, session, 100)
        state().keys.forEach { invalid { read(state() - it) } }
        ready().keys.forEach { invalid { receive(ready() - it) } }
        invalid { read(state() + ("extra" to false)) }; invalid { receive(ready() + ("extra" to false)) }
        listOf("freshSessionId" to old, "instanceId" to instance.uppercase(), "processId" to 100,
            "fixtureTaskId" to 0, "fixtureDisplayId" to 1, "fixtureProcessStartTicks" to 1,
            "ready" to false, "empty" to false, "matches" to true, "commitCalls" to 1, "fieldId" to 1).forEach { (key, value) ->
            invalid { read(state() + (key to value)) }
        }
        invalid { receive(ready(), 0) }; invalid { receive(ready() + ("rejectedAdmissionSelfFinishAttempted" to true)) }
        invalid { receive(ready() + ("freshSessionId" to old)) }
    }
    @Test fun independentTimeoutAndMalformedFirstResultCannotReviveHandoff() {
        val gate = FreshInputReadinessProtocol.Handoff()
        gate.close()
        assertNull(gate.offer { FreshInputReadinessProtocol.independentCallback(1, ready(), session, 100) })
        val malformed = FreshInputReadinessProtocol.Handoff()
        assertEquals("INVALID_CALLBACK", malformed.offer {
            FreshInputReadinessProtocol.independentCallback(1, ready() - "processId", session, 100)
        }!!.reason)
        assertNull(malformed.offer { FreshInputReadinessProtocol.independentCallback(1, ready(), session, 100) })
    }

    @Test fun disposalIdentityDoesNotChangeTerminalCreationResult() {
        val terminal = FreshInputReadinessProtocol.Creation("TIMED_OUT", "TIMED_OUT", null, null)
        val disposalOnly = parseState()
        assertEquals(session, disposalOnly.sessionId)
        assertEquals("TIMED_OUT", terminal.outcome)
        assertNull(terminal.identity)
    }
}
