package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test

class NotificationIdentityExpandTest {
    private val system = "com.android.systemui"
    private data class Ref(val id: Int)
    private fun candidate(id: Int = 1, resource: String? = "android:id/expand_button", pkg: String? = system,
        enabled: Boolean = true, clickable: Boolean = true) =
        NotificationIdentityExpand.Candidate(Ref(id), pkg, resource, enabled, clickable)
    private fun failure(code: NotificationIdentityExpand.Code, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is NotificationIdentityExpand.Failure)
        assertEquals(code, (error as NotificationIdentityExpand.Failure).code)
        assertNull(error.message)
    }

    @Test fun eitherExactResourceIsSelectedThroughTheRuntimeInitialLogic() {
        for (resource in listOf("android:id/expand_button", "$system:id/expand_button")) {
            val value = candidate(resource = resource)
            assertSame(value, NotificationIdentityExpand.initial(listOf(value)))
            assertTrue(value.available)
        }
    }
    @Test fun bothNamespacesTogetherAreAmbiguousInsteadOfPreferringEither() {
        val android = candidate()
        val systemUi = candidate(2, "$system:id/expand_button")
        for (found in listOf(listOf(android, systemUi), listOf(systemUi, android))) {
            failure(NotificationIdentityExpand.Code.AMBIGUOUS) { NotificationIdentityExpand.initial(found) }
            failure(NotificationIdentityExpand.Code.AMBIGUOUS) { NotificationIdentityExpand.fresh(android, found) }
        }
    }
    @Test fun duplicateReturnedWrappersAreNotDeduplicatedIntoAuthorization() {
        val first = candidate()
        val second = candidate()
        assertNotSame(first.node, second.node)
        assertEquals(first.node, second.node)
        failure(NotificationIdentityExpand.Code.AMBIGUOUS) {
            NotificationIdentityExpand.initial(listOf(first, second))
        }
    }
    @Test fun foreignPackagesCannotAuthorizeAnExactResource() {
        for (resource in listOf("android:id/expand_button", "$system:id/expand_button")) {
            for (pkg in listOf("android", "private.app", "$system.extra", null)) {
                failure(NotificationIdentityExpand.Code.CHANGED) {
                    NotificationIdentityExpand.initial(listOf(candidate(resource = resource, pkg = pkg)))
                }
            }
        }
    }
    @Test fun unknownResourcesContainersAndIconsAreNotExpandButtons() {
        for (resource in listOf("android:id/alternate_expand_target", "android:id/expand_button_touch_container",
            "android:id/expand_button_pill", "android:id/expand_button_icon", "android:id/expand_button_extra",
            "android:id/expand_button\n", "prefixandroid:id/expand_button", "android:id/action0",
            "$system:id/expand_button_extra", "private.app:id/expand_button", "android:id/title", "", null)) {
            failure(NotificationIdentityExpand.Code.CHANGED) {
                NotificationIdentityExpand.initial(listOf(candidate(resource = resource)))
            }
        }
    }
    @Test fun initialMissingAndUnavailableAreNotFreshAuthorization() {
        assertNull(NotificationIdentityExpand.initial(emptyList<NotificationIdentityExpand.Candidate<Ref>>()))
        for ((enabled, clickable) in listOf(false to true, true to false, false to false)) {
            val unavailable = candidate(enabled = enabled, clickable = clickable)
            assertSame(unavailable, NotificationIdentityExpand.initial(listOf(unavailable)))
            assertFalse(unavailable.available)
            failure(NotificationIdentityExpand.Code.CHANGED) {
                NotificationIdentityExpand.fresh(unavailable, listOf(candidate()))
            }
        }
    }
    @Test fun freshQueryReturnsItsOwnWrapperForTheSameUnderlyingNode() {
        for (resource in listOf("android:id/expand_button", "$system:id/expand_button")) {
            val before = candidate(resource = resource)
            val rebound = candidate(resource = resource)
            assertNotSame(before.node, rebound.node)
            assertEquals(before.node, rebound.node)
            assertSame(rebound, NotificationIdentityExpand.fresh(before, listOf(rebound)))
        }
    }
    @Test fun missingReplacementOrResourceChangeAfterRebindIsRejected() {
        val before = candidate()
        for (found in listOf(emptyList(), listOf(candidate(id = 2)),
            listOf(candidate(resource = "$system:id/expand_button")))) {
            failure(NotificationIdentityExpand.Code.CHANGED) { NotificationIdentityExpand.fresh(before, found) }
        }
    }
    @Test fun freshUnavailableOrForeignCandidatesCannotReuseInitialAuthorization() {
        for (value in listOf(candidate(enabled = false), candidate(clickable = false),
            candidate(pkg = "private.app"), candidate(resource = "android:id/action0"))) {
            failure(NotificationIdentityExpand.Code.CHANGED) {
                NotificationIdentityExpand.fresh(candidate(), listOf(value))
            }
        }
    }
}
