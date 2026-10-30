package com.example.agent.rootpilot

/** A dry comparison of the exact ready/connection guard; never authorizes an input. */
internal object NotificationImeIdentityEvidence {
    enum class Phase {
        BASELINE, UI_READY, BEFORE_SHADE, SHADE, BEFORE_CLICK, ACTIVITY_POST_CREATED, ACTIVITY_RESUMED,
        ACTIVITY_FOCUS, ACTIVITY_DESTROYED, NEXT_MAIN_TURN, FIXTURE_RETURNED,
    }
    data class Sample(val phase: Phase, val editorPresent: Boolean, val sameEditor: Boolean,
        val sameBoundConnection: Boolean, val currentConnectionValid: Boolean, val targetAllowed: Boolean) {
        val boundGuardRejects get() = !sameEditor || !sameBoundConnection
    }
    enum class Outcome { UI_INITIALIZATION_DRIFT, BEFORE_SHADE_DRIFT, SHADE_DRIFT, ACTIVITY_DRIFT, NO_DRIFT_OBSERVED }

    fun firstBoundDrift(samples: List<Sample>): Phase? {
        require(samples.firstOrNull()?.let { it.phase == Phase.BASELINE && !it.boundGuardRejects &&
            it.currentConnectionValid && it.targetAllowed } == true)
        return samples.drop(1).firstOrNull { it.boundGuardRejects }?.phase
    }

    fun outcome(samples: List<Sample>): Outcome = when (firstBoundDrift(samples)) {
        null -> Outcome.NO_DRIFT_OBSERVED
        Phase.UI_READY -> Outcome.UI_INITIALIZATION_DRIFT
        Phase.BEFORE_SHADE -> Outcome.BEFORE_SHADE_DRIFT
        Phase.SHADE, Phase.BEFORE_CLICK -> Outcome.SHADE_DRIFT
        else -> Outcome.ACTIVITY_DRIFT
    }
}
