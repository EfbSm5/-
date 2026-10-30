package com.example.agent.rootpilot

import java.util.UUID

/** Snapshot evidence only: no Activity ownership, readiness, deadline or cleanup conclusion. */
internal object FreshFixtureArrivalProtocol {
    private data class Observation(val epoch: String, val phase: String, val session: String?, val instance: String?)

    private fun canonical(value: Any?): String {
        require(value is String && value.length == 36 && UUID.fromString(value).toString() == value)
        return value
    }

    private fun decode(values: Map<String, Any?>): Observation {
        require(values.keys == setOf("processEpoch", "arrival"))
        val epoch = canonical(values["processEpoch"])
        val arrival = values["arrival"] as? Map<*, *> ?: error("arrival_shape")
        require(arrival.keys == setOf("phase", "sessionId", "instanceId"))
        val phase = arrival["phase"]
        require(phase == "EMPTY" || phase == "SEEN" || phase == "AMBIGUOUS")
        if (phase != "SEEN") {
            require(arrival["sessionId"] == null && arrival["instanceId"] == null)
            return Observation(epoch, phase as String, null, null)
        }
        return Observation(epoch, "SEEN", canonical(arrival["sessionId"]), canonical(arrival["instanceId"]))
    }

    fun baseline(read: () -> Map<String, Any?>): String? = try {
        decode(read()).takeIf { it.phase == "EMPTY" }?.epoch
    } catch (_: Exception) { null }

    fun classify(epoch: String?, values: Map<String, Any?>, session: String, oldInstance: String): String = try {
        val observed = decode(values)
        if (epoch != null && canonical(epoch) == observed.epoch && observed.phase == "SEEN" &&
            observed.session == canonical(session) && observed.instance != canonical(oldInstance)) "SEEN" else "UNKNOWN"
    } catch (_: Exception) { "UNKNOWN" }

    class Once(private val emit: (String) -> Unit) {
        private var sent = false
        fun report(value: String) {
            if (sent) return
            sent = true
            try { emit(if (value == "SEEN" || value == "UNKNOWN") value else "UNKNOWN") }
            catch (_: Exception) { }
        }
    }

    /** Diagnostic decode/send exceptions cannot skip or replace the original strict state path. */
    fun <T> observeThenRead(diagnostic: () -> String, emit: (String) -> Unit, state: () -> T): T {
        val result = try { diagnostic().takeIf { it == "SEEN" || it == "UNKNOWN" } ?: "UNKNOWN" }
            catch (_: Exception) { "UNKNOWN" }
        try { emit(result) } catch (_: Exception) { }
        return state()
    }
}
