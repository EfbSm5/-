package com.example.agent.rootpilot

/** Test-only lifecycle evidence. It observes cooperation, not the physical source of a touch. */
internal class NotificationManualConfirmationEvidence(private val waitMillis: Long = 120_000L) {
    init { require(waitMillis > 0) }

    enum class Failure { STATE, EARLY_ACTIVITY, ACTIVITY, TIMEOUT, AUTOMATIC_APPROVAL }

    data class Snapshot(
        val armed: Boolean,
        val waiting: Boolean,
        val complete: Boolean,
        val closed: Boolean,
        val automaticApprovalAttempts: Int,
        val failure: Failure?,
    ) {
        val noAutomaticApproval get() = automaticApprovalAttempts == 0
    }

    private var armedAt: Long? = null
    private var deadline: Long? = null
    private var instance: Any? = null
    private var created = 0
    private var resumed = 0
    private var destroyed = 0
    private var completedAt: Long? = null
    private var closed = false
    private var attempts = 0
    private var failure: Failure? = null

    @Synchronized fun arm(now: Long): Snapshot {
        if (closed || armedAt != null || now < 0) reject(Failure.STATE)
        if (failure == null) armedAt = now
        return snapshot(now)
    }

    /** Start the full window after openNotification returns; callbacks during opening are retained. */
    @Synchronized fun beginWait(now: Long): Snapshot {
        val armed = armedAt
        if (closed || armed == null || deadline != null || now < armed || now > Long.MAX_VALUE - waitMillis) {
            reject(Failure.STATE)
        }
        if (failure == null) deadline = now + waitMillis
        return snapshot(now)
    }

    @Synchronized fun onCreated(activity: Any, target: Boolean, now: Long) {
        if (!accept(now)) return
        if (!target || instance != null || created != 0) {
            reject(Failure.ACTIVITY)
            return
        }
        instance = activity
        created = 1
    }

    @Synchronized fun onResumed(activity: Any, target: Boolean, now: Long) {
        if (!accept(now)) return
        if (!target || activity !== instance || created != 1 || resumed != 0 || destroyed != 0) {
            reject(Failure.ACTIVITY)
            return
        }
        resumed = 1
    }

    @Synchronized fun onOtherActivityStarted(now: Long) {
        if (accept(now)) reject(Failure.ACTIVITY)
    }

    @Synchronized fun onDestroyed(activity: Any, target: Boolean, now: Long) {
        // Stopping/destroying a pre-existing main Activity is not a new notification entry.
        if (!target) return
        if (!accept(now)) return
        if (activity !== instance || created != 1 || resumed != 1 || destroyed != 0) {
            reject(Failure.ACTIVITY)
            return
        }
        destroyed = 1
        completedAt = now
    }

    @Synchronized fun automaticApprovalAttempt(): Snapshot {
        if (!closed) {
            attempts++
            reject(Failure.AUTOMATIC_APPROVAL)
        }
        return current()
    }

    @Synchronized fun snapshot(now: Long): Snapshot {
        if (!closed && failure == null) {
            val until = deadline
            if (until != null && !lifecycleComplete() && now >= until) reject(Failure.TIMEOUT)
        }
        return current()
    }

    @Synchronized fun finish(now: Long, requireComplete: Boolean): Snapshot {
        snapshot(now)
        if (requireComplete && !current().complete) reject(Failure.STATE)
        closed = true
        return current()
    }

    private fun accept(now: Long): Boolean {
        if (closed || failure != null) return false
        val armed = armedAt
        if (armed == null) reject(Failure.EARLY_ACTIVITY)
        else if (now < armed) reject(Failure.STATE)
        else if (deadline?.let { now >= it } == true) reject(Failure.TIMEOUT)
        return failure == null
    }

    private fun lifecycleComplete() = created == 1 && resumed == 1 && destroyed == 1

    private fun current(): Snapshot {
        val completed = completedAt
        val until = deadline
        return Snapshot(armedAt != null, until != null,
            failure == null && armedAt != null && until != null && lifecycleComplete() &&
                completed != null && completed < until,
            closed, attempts, failure)
    }

    private fun reject(value: Failure) { if (failure == null) failure = value }
}
