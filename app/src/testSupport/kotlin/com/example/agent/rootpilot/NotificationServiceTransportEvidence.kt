package com.example.agent.rootpilot

/** Test-only evidence; lifecycle order between the next main turn and destruction is unspecified. */
internal object NotificationServiceTransportEvidence {
    enum class Phase { BASELINE, SHADE_OPENED, BEFORE_ACTION, SERVICE_RECEIVED, NEXT_MAIN_TURN, SERVICE_DESTROYED, FINAL_MEASURED }
    enum class Focus { FIXTURE, SYSTEM_UI, OTHER, UNKNOWN }
    enum class Shade { PRESENT, ABSENT, UNKNOWN }
    data class Sample(
        val phase: Phase, val mainThread: Boolean, val editorPresent: Boolean,
        val sameEditor: Boolean, val sameBoundConnection: Boolean, val currentConnectionConsistent: Boolean,
        val fixtureEditorValid: Boolean, val focus: Focus, val fixtureEditorVisible: Boolean,
        val sameFixtureWindow: Boolean, val shade: Shade,
    )

    // Component-wide stop is safe only while this run still owns the observer registration.
    enum class ServiceCleanup { UNOWNED, NOT_STARTED, DESTROYED, STOP_AND_AWAIT }
    fun serviceCleanup(
        registered: Boolean, currentObserverOwned: Boolean, instanceObserved: Boolean,
        dispatchObserved: Boolean, actionClicked: Boolean, destroyed: Boolean,
    ): ServiceCleanup = when {
        !registered || !currentObserverOwned -> ServiceCleanup.UNOWNED
        instanceObserved && destroyed -> ServiceCleanup.DESTROYED
        instanceObserved || dispatchObserved || actionClicked -> ServiceCleanup.STOP_AND_AWAIT
        else -> ServiceCleanup.NOT_STARTED
    }

    fun legalPrefix(phases: List<Phase>): Boolean {
        val seen = mutableSetOf<Phase>()
        return phases.all { phase ->
            val predecessors = when (phase) {
                Phase.BASELINE -> emptySet()
                Phase.SHADE_OPENED -> setOf(Phase.BASELINE)
                Phase.BEFORE_ACTION -> setOf(Phase.SHADE_OPENED)
                Phase.SERVICE_RECEIVED -> setOf(Phase.BEFORE_ACTION)
                Phase.NEXT_MAIN_TURN, Phase.SERVICE_DESTROYED -> setOf(Phase.SERVICE_RECEIVED)
                Phase.FINAL_MEASURED -> setOf(Phase.NEXT_MAIN_TURN, Phase.SERVICE_DESTROYED)
            }
            predecessors.all { it in seen } && seen.add(phase)
        }
    }

    class Session(private val nonce: String, private val pid: Int, private val uid: Int) {
        private var instance: Any? = null
        private val values = mutableListOf<Sample>()
        var failure: String? = null; private set
        var createdCount = 0; private set
        var dispatchCount = 0; private set
        var destroyedCount = 0; private set
        var destroyed = false; private set
        var frozen = false; private set
        var closed = false; private set
        val samples: List<Sample> get() = values.toList()
        private fun reject(code: String): Boolean { if (failure == null) failure = code; return false }
        private fun identity(actualPid: Int, actualUid: Int, main: Boolean) = actualPid == pid && actualUid == uid && main
        fun created(owner: Any, actualPid: Int, actualUid: Int, main: Boolean): Boolean {
            createdCount++
            if (closed || instance != null || !identity(actualPid, actualUid, main)) return reject("BINDING")
            instance = owner
            return true
        }
        fun received(owner: Any, actualNonce: String?, sourceValid: Boolean, actualPid: Int, actualUid: Int, main: Boolean): Boolean {
            dispatchCount++
            if (closed || owner !== instance || createdCount != 1 || destroyed || dispatchCount != 1 ||
                actualNonce != nonce || !sourceValid || !identity(actualPid, actualUid, main)) return reject("BINDING")
            return true
        }
        fun destroyed(owner: Any, actualPid: Int, actualUid: Int, main: Boolean): Boolean {
            destroyedCount++
            if (owner !== instance || dispatchCount != 1 || destroyed || !identity(actualPid, actualUid, main)) return reject("BINDING")
            destroyed = true
            return true
        }
        fun record(sample: Sample): Boolean {
            if (frozen || closed || !sample.mainThread || !legalPrefix(values.map { it.phase } + sample.phase)) return reject("PHASE_ORDER")
            values += sample
            return true
        }
        fun freeze() { frozen = true }
        fun close() { frozen = true; closed = true }
        val complete: Boolean get() = failure == null && createdCount == 1 && dispatchCount == 1 && destroyedCount == 1 &&
            destroyed && values.size == Phase.entries.size && legalPrefix(values.map { it.phase })
        val allOriginalEditor: Boolean get() = values.size == Phase.entries.size && values.all { it.editorPresent && it.sameEditor }
        val allOriginalConnection: Boolean get() = values.size == Phase.entries.size && values.all { it.sameBoundConnection }
        val allConsistent: Boolean get() = values.size == Phase.entries.size && values.all { it.currentConnectionConsistent }
        val allValid: Boolean get() = values.size == Phase.entries.size && values.all { it.fixtureEditorValid }
        val automaticShadeClosed: String get() = when (values.lastOrNull()?.takeIf { it.phase == Phase.FINAL_MEASURED }?.let {
            when {
                it.focus == Focus.FIXTURE && it.fixtureEditorVisible && it.sameFixtureWindow && it.shade == Shade.ABSENT -> true
                it.focus == Focus.SYSTEM_UI && it.shade == Shade.PRESENT -> false
                else -> null
            }
        }) { true -> "true"; false -> "false"; null -> "unknown" }
        fun candidate(diagnosticPassed: Boolean): Boolean = diagnosticPassed && complete && frozen && automaticShadeClosed == "true" &&
            allOriginalEditor && allOriginalConnection && allConsistent && allValid
    }
}
