package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test

class NotificationTypeApprovalUiTest {
    private data class Ref(val id: Int)

    @Test fun onlyTotalIdentityAbsenceAndNoActionCanDeferIdentityForExpand() {
        for (identity in NotificationAppIdentity.Result.entries) {
            for (named in 0..2) for (icons in 0..2) for (actions in 0..2) {
                assertEquals("$identity/$named/$icons/$actions",
                    identity == NotificationAppIdentity.Result.MISSING && named == 0 && icons == 0 && actions == 0,
                    NotificationTypeApprovalUi.mayExpandWithoutIdentity(identity, named, icons, actions))
            }
        }
    }

    @Test fun freshBindingUsesUnderlyingTitleAndRowIdentityNotWrapperIdentity() {
        assertTrue(NotificationTypeApprovalUi.sameBinding(Ref(1), Ref(2), Ref(1), Ref(2)))
        assertFalse(NotificationTypeApprovalUi.sameBinding(Ref(1), Ref(2), Ref(3), Ref(2)))
        assertFalse(NotificationTypeApprovalUi.sameBinding(Ref(1), Ref(2), Ref(1), Ref(3)))
        assertFalse(NotificationTypeApprovalUi.sameBinding(Ref(1), Ref(2), Ref(3), Ref(4)))
    }

    @Test fun freshExpandReplacementStillFailsAfterTitleAndRowMatch() {
        val old = NotificationIdentityExpand.Candidate(Ref(3), "com.android.systemui", "android:id/expand_button", true, true)
        assertTrue(NotificationTypeApprovalUi.sameBinding(Ref(1), Ref(2), Ref(1), Ref(2)))
        val failure = runCatching {
            NotificationIdentityExpand.fresh(old, listOf(old.copy(node = Ref(4))))
        }.exceptionOrNull()
        assertTrue(failure is NotificationIdentityExpand.Failure)
        assertEquals(NotificationIdentityExpand.Code.CHANGED, (failure as NotificationIdentityExpand.Failure).code)
    }
}
