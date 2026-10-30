package com.example.agent.rootpilot

/** Type notification tests may defer identity only for one non-approval expand click. */
internal object NotificationTypeApprovalUi {
    fun mayExpandWithoutIdentity(identity: NotificationAppIdentity.Result,
        namedCount: Int, iconCount: Int, actionCount: Int): Boolean =
        identity == NotificationAppIdentity.Result.MISSING && namedCount == 0 && iconCount == 0 && actionCount == 0

    fun <N> sameBinding(oldTitle: N, oldRow: N, freshTitle: N, freshRow: N): Boolean =
        oldTitle == freshTitle && oldRow == freshRow
}
