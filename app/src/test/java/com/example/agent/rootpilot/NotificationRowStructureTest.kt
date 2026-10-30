package com.example.agent.rootpilot

import com.example.agent.rootpilot.NotificationRowStructure.Node
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRowStructureTest {
    private val systemUi = "com.android.systemui"
    // The real evidence redacts this value; neither its original ID nor null is inferred.
    private val row = Node("REDACTED", systemUi, "android.widget.FrameLayout", true, true, false)
    private val expanded = Node("$systemUi:id/expanded", systemUi, "android.widget.FrameLayout", true, false, false)
    private val scroller = Node("$systemUi:id/notification_stack_scroller", systemUi, "android.widget.ScrollView", true, false, false)
    private val container = Node("$systemUi:id/shared_notification_container", systemUi, "android.view.ViewGroup", true, false, false)

    @Test fun observedRowBetweenExpandedAndDirectScrollerMatches() {
        assertTrue(NotificationRowStructure.isRow(row, expanded, scroller))
    }

    @Test fun candidateResourceNameIsNotUsedToInferTheRedactedId() {
        for (resource in listOf(null, "REDACTED", "$systemUi:id/synthetic_unknown")) {
            assertTrue(NotificationRowStructure.isRow(row.copy(resourceName = resource), expanded, scroller))
        }
    }

    @Test fun legacyNamedRowStillMatchesWithoutStructuralFields() {
        assertTrue(NotificationRowStructure.isRow(Node(resourceName = "$systemUi:id/expandableNotificationRow")))
        assertFalse(NotificationRowStructure.isRow(Node(resourceName = "other:id/expandableNotificationRow")))
    }

    @Test fun everyRequiredCandidateFieldRejectsMissingAndMismatchedValues() {
        val invalid = listOf(
            row.copy(packageName = null), row.copy(packageName = "other"),
            row.copy(className = null), row.copy(className = "android.view.ViewGroup"),
            row.copy(enabled = null), row.copy(enabled = false),
            row.copy(clickable = null), row.copy(clickable = false),
            row.copy(scrollable = null), row.copy(scrollable = true),
        )
        for (candidate in invalid) assertFalse(NotificationRowStructure.isRow(candidate, expanded, scroller))
    }

    @Test fun pathChildMustBeExpandedInSystemUi() {
        val invalid = listOf(null, expanded.copy(resourceName = null), expanded.copy(resourceName = "$systemUi:id/line1"),
            expanded.copy(packageName = null), expanded.copy(packageName = "other"))
        for (child in invalid) assertFalse(NotificationRowStructure.isRow(row, child, scroller))
    }

    @Test fun directParentRequiresEveryScrollerIdentityField() {
        val invalid = listOf(null, scroller.copy(resourceName = null), scroller.copy(resourceName = "$systemUi:id/shared_notification_container"),
            scroller.copy(packageName = null), scroller.copy(packageName = "other"),
            scroller.copy(className = null), scroller.copy(className = "android.widget.FrameLayout"))
        for (parent in invalid) assertFalse(NotificationRowStructure.isRow(row, expanded, parent))
    }

    @Test fun scrollerAndHigherContainersAreNotRows() {
        assertFalse(NotificationRowStructure.isRow(scroller, row, container))
        assertFalse(NotificationRowStructure.isRow(container, scroller,
            Node("$systemUi:id/legacy_window_root", systemUi, "android.widget.FrameLayout", true, false, false)))
        assertFalse(NotificationRowStructure.isRow(container.copy(className = "android.widget.FrameLayout", clickable = true), expanded, null))
    }

    @Test fun anInterposedParentCannotBeSkippedToReachTheScroller() {
        assertFalse(NotificationRowStructure.isRow(row, expanded, Node(packageName = systemUi,
            className = "android.widget.FrameLayout", enabled = true, clickable = true, scrollable = false)))
        assertFalse(NotificationRowStructure.isRow(row, container, scroller))
    }
}
