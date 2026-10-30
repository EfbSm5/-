package com.example.agent.rootpilot

/** One original BASELINE observation, never a readiness retry or transport acceptance. */
internal class NotificationInputReadinessEvidence {
    enum class Wait { NOT_STARTED, SATISFIED, TIMED_OUT, ERROR }
    enum class Baseline { NOT_REACHED, ACCEPTED, REJECTED, SAMPLE_ERROR }
    enum class ServiceCleanup { NOT_STARTED, UNSAFE }
    enum class ObserverCleanup { NOT_REGISTERED, UNREGISTERED_OWNED, UNSAFE }
    enum class Reason {
        NONE, PREPARATION_REJECTED, WAIT_TIMEOUT, WAIT_ERROR, UI_INITIALIZATION_ERROR,
        BASELINE_SAMPLE_ERROR, BASELINE_REJECTED, CLEANUP_REJECTED,
    }

    var wait = Wait.NOT_STARTED
    var serviceObserverWasRegistered = false
    var activityGuardWasRegistered = false
    var serviceCleanup = ServiceCleanup.UNSAFE
    var serviceObserverCleanup = ObserverCleanup.UNSAFE
    var activityGuardCleanup = ObserverCleanup.UNSAFE
    var sampleStarted = false
    var sample: NotificationServiceTransportEvidence.Sample? = null
    val details = Details()

    class Details {
        var windowsPresent: Boolean? = null
        var rootsComplete: Boolean? = null
        var uniqueFocusedWindow: Boolean? = null
        var focusedWindowFixture: Boolean? = null
        var focusedWindowApplication: Boolean? = null
        var fixtureNodesQueried: Boolean? = null
        var fixtureNodePresent: Boolean? = null
        var anyFixtureNodeVisible: Boolean? = null
        var anyVisibleFixtureNodeEditable: Boolean? = null
        var anyVisibleEditableFixtureNodeFocused: Boolean? = null
        var baselineWindowEstablished: Boolean? = null
        private var visibleExecuted = false
        private var editableExecuted = false
        private var focusedExecuted = false
        private var emptyNodes = false

        // A throwing query never calls this; partially evaluated predicates remain unknown.
        fun nodesReturned(empty: Boolean) {
            fixtureNodesQueried = true
            fixtureNodePresent = !empty
            emptyNodes = empty
        }
        fun visible(value: Boolean) {
            visibleExecuted = true
            if (value) anyFixtureNodeVisible = true
        }
        fun editable(value: Boolean) {
            editableExecuted = true
            if (value) anyVisibleFixtureNodeEditable = true
        }
        fun focused(value: Boolean) {
            focusedExecuted = true
            if (value) anyVisibleEditableFixtureNodeFocused = true
        }
        fun nodesCompleted() {
            if ((visibleExecuted || emptyNodes) && anyFixtureNodeVisible == null) anyFixtureNodeVisible = false
            if ((editableExecuted || emptyNodes) && anyVisibleFixtureNodeEditable == null) anyVisibleFixtureNodeEditable = false
            if ((focusedExecuted || emptyNodes) && anyVisibleEditableFixtureNodeFocused == null) anyVisibleEditableFixtureNodeFocused = false
        }
        fun values(): Map<String, Boolean?> = linkedMapOf(
            "windowsPresent" to windowsPresent, "rootsComplete" to rootsComplete,
            "uniqueFocusedWindow" to uniqueFocusedWindow, "focusedWindowFixture" to focusedWindowFixture,
            "focusedWindowApplication" to focusedWindowApplication, "fixtureNodesQueried" to fixtureNodesQueried,
            "fixtureNodePresent" to fixtureNodePresent, "anyFixtureNodeVisible" to anyFixtureNodeVisible,
            "anyVisibleFixtureNodeEditable" to anyVisibleFixtureNodeEditable,
            "anyVisibleEditableFixtureNodeFocused" to anyVisibleEditableFixtureNodeFocused,
            "baselineWindowEstablished" to baselineWindowEstablished,
        )
    }

    val failureFields: List<String> get() = sample?.let { s -> buildList {
        if (!s.sameEditor) add("sameEditor")
        if (!s.sameBoundConnection) add("sameBoundConnection")
        if (!s.currentConnectionConsistent) add("currentConnectionConsistent")
        if (!s.fixtureEditorValid) add("fixtureEditorValid")
        if (s.focus != NotificationServiceTransportEvidence.Focus.FIXTURE) add("focus")
        if (!s.fixtureEditorVisible) add("fixtureEditorVisible")
        if (!s.sameFixtureWindow) add("sameFixtureWindow")
    } } ?: emptyList()

    fun baseline(sourceReason: String): Baseline = when {
        sample == null -> if (sampleStarted) Baseline.SAMPLE_ERROR else Baseline.NOT_REACHED
        sourceReason != "NONE" && sourceReason != "EDITOR_READY" -> Baseline.SAMPLE_ERROR
        failureFields.isEmpty() -> Baseline.ACCEPTED
        else -> Baseline.REJECTED
    }

    fun reason(sourceReason: String, uiPhase: Boolean, cleanupFailed: Boolean): Reason = when {
        wait == Wait.TIMED_OUT -> Reason.WAIT_TIMEOUT
        wait == Wait.ERROR -> Reason.WAIT_ERROR
        sourceReason == "EDITOR_READY" && sample != null && failureFields.isNotEmpty() -> Reason.BASELINE_REJECTED
        sourceReason != "NONE" && wait == Wait.NOT_STARTED -> Reason.PREPARATION_REJECTED
        sourceReason != "NONE" && !sampleStarted && uiPhase -> Reason.UI_INITIALIZATION_ERROR
        sourceReason != "NONE" -> Reason.BASELINE_SAMPLE_ERROR
        cleanupFailed -> Reason.CLEANUP_REJECTED
        baseline(sourceReason) == Baseline.REJECTED -> Reason.BASELINE_REJECTED
        baseline(sourceReason) != Baseline.ACCEPTED -> Reason.BASELINE_SAMPLE_ERROR
        else -> Reason.NONE
    }

    fun passed(
        completed: Boolean, sourceReason: String, cleanupFailed: Boolean, closed: Boolean,
        forbiddenEffect: Boolean, serviceEvents: Int, activityEvents: Int,
    ): Boolean = completed && sourceReason == "NONE" && !cleanupFailed && closed && !forbiddenEffect &&
        wait == Wait.SATISFIED && baseline(sourceReason) == Baseline.ACCEPTED &&
        serviceObserverWasRegistered && activityGuardWasRegistered && serviceEvents == 0 && activityEvents == 0 &&
        serviceCleanup == ServiceCleanup.NOT_STARTED &&
        serviceObserverCleanup == ObserverCleanup.UNREGISTERED_OWNED &&
        activityGuardCleanup == ObserverCleanup.UNREGISTERED_OWNED

    companion object {
        fun observedCount(wasRegistered: Boolean, cleanupSafe: Boolean, value: Int): Int? =
            if (!wasRegistered || (!cleanupSafe && value == 0)) null else value
        fun serviceCleanup(registered: Boolean, currentOwned: Boolean, instance: Boolean, dispatch: Boolean, action: Boolean): ServiceCleanup =
            if (instance || dispatch || action || (registered && !currentOwned)) ServiceCleanup.UNSAFE else ServiceCleanup.NOT_STARTED
        fun observerCleanup(wasRegistered: Boolean, unregisteredOwned: Boolean): ObserverCleanup = when {
            !wasRegistered -> ObserverCleanup.NOT_REGISTERED
            unregisteredOwned -> ObserverCleanup.UNREGISTERED_OWNED
            else -> ObserverCleanup.UNSAFE
        }
    }
}
