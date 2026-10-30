package com.example.agent.rootpilot

import java.util.UUID

/** Test-only, fixed FRESH handoff wire validation; never supplies a replacement editor. */
internal object FreshInputReadinessProtocol {
    data class Identity(
        val sessionId: String, val instanceId: String, val pid: Int,
        val taskId: Int, val displayId: Int, val processStartTicks: Long,
    )
    data class Creation(val outcome: String, val reason: String, val identity: Identity?, val selfFinish: Boolean?)
    private val identityKeys = setOf("freshSessionId", "instanceId", "processId", "fixtureTaskId", "fixtureDisplayId", "fixtureProcessStartTicks")
    private val resultKeys = setOf("creationOutcome", "creationReason", "rejectedAdmissionSelfFinishAttempted")
    private val stateKeys = identityKeys + setOf("ready", "empty", "matches", "fieldId", "commitCalls")
    private val rejectionReasons = setOf("CONFLICT", "DESTROYED", "INVALID_REQUEST", "PROCESS_IDENTITY_UNAVAILABLE")
    fun canonicalId(value: String): Boolean = value.length == 36 &&
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun identity(values: Map<String, Any?>, session: String, oldInstance: String?, mainPid: Int): Identity {
        require(canonicalId(session) && values["freshSessionId"] == session)
        val instance = values["instanceId"] as? String ?: error("fresh_identity")
        val pid = values["processId"] as? Int ?: error("fresh_identity")
        val task = values["fixtureTaskId"] as? Int ?: error("fresh_identity")
        val display = values["fixtureDisplayId"] as? Int ?: error("fresh_identity")
        val ticks = values["fixtureProcessStartTicks"] as? Long ?: error("fresh_identity")
        require(canonicalId(instance) && instance != oldInstance && pid > 0 && pid != mainPid && task > 0 && display == 0 && ticks > 0)
        return Identity(session, instance, pid, task, display, ticks)
    }

    fun state(values: Map<String, Any?>, session: String, oldInstance: String, mainPid: Int): Identity =
        stateWithExclusion(values, session, oldInstance, mainPid)

    fun independentState(values: Map<String, Any?>, session: String, mainPid: Int): Identity =
        stateWithExclusion(values, session, null, mainPid)

    private fun stateWithExclusion(values: Map<String, Any?>, session: String, oldInstance: String?, mainPid: Int): Identity {
        require(values.keys == stateKeys)
        require(values["ready"] == true && values["empty"] == true && values["matches"] == false &&
            values["fieldId"] is Int && values["fieldId"] == 16908291 && values["commitCalls"] is Int && values["commitCalls"] == 0)
        return identity(values, session, oldInstance, mainPid)
    }

    fun callback(code: Int, values: Map<String, Any?>, session: String, oldInstance: String, mainPid: Int): Creation =
        callbackWithExclusion(code, values, session, oldInstance, mainPid)

    fun independentCallback(code: Int, values: Map<String, Any?>, session: String, mainPid: Int): Creation =
        callbackWithExclusion(code, values, session, null, mainPid)

    private fun callbackWithExclusion(code: Int, values: Map<String, Any?>, session: String, oldInstance: String?, mainPid: Int): Creation {
        require(values["freshSessionId"] == session && canonicalId(session))
        return when (values["creationOutcome"]) {
            "READY" -> {
                require(code == 1 && values.keys == identityKeys + resultKeys && values["creationReason"] == "NONE" &&
                    values["rejectedAdmissionSelfFinishAttempted"] == false)
                Creation("READY", "NONE", identity(values, session, oldInstance, mainPid), false)
            }
            "REJECTED" -> {
                require(code == 0 && values.keys == resultKeys + "freshSessionId" && values["creationReason"] in rejectionReasons &&
                    values["rejectedAdmissionSelfFinishAttempted"] is Boolean)
                val selfFinish = values["rejectedAdmissionSelfFinishAttempted"] as Boolean
                require(selfFinish == (values["creationReason"] != "DESTROYED"))
                Creation("REJECTED", values["creationReason"] as String, null, selfFinish)
            }
            else -> error("fresh_callback")
        }
    }

    /** Fixed diagnostic-only telemetry, not a readiness callback or identity. */
    fun lifecycle(code: Int, values: Map<String, Any?>, session: String): String {
        require(code == 2 && canonicalId(session) && values.keys == setOf("freshSessionId", "event", "state") &&
            values["freshSessionId"] == session)
        val event = values["event"] as? String ?: error("fresh_lifecycle")
        val state = values["state"] as? String ?: error("fresh_lifecycle")
        require(event in setOf("CREATE", "RESUME", "FOCUS_GAINED", "FOCUS_LOST", "LAYOUT") &&
            state.matches(Regex("[01u]{6}")))
        return "$event:$state"
    }

    /** The first result or timeout closes the channel; later callbacks cannot resurrect it. */
    class Handoff {
        private var closed = false
        @Synchronized fun offer(result: () -> Creation): Creation? {
            if (closed) return null
            closed = true
            return try { result() } catch (_: Exception) { Creation("ERROR", "INVALID_CALLBACK", null, null) }
        }
        @Synchronized fun close() { closed = true }
    }
}
