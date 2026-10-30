package com.example.agent.rootpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAppIdentityTest {
    private val verified = NotificationAppIdentity.Result.VERIFIED
    private val missing = NotificationAppIdentity.Result.MISSING
    private val ambiguous = NotificationAppIdentity.Result.AMBIGUOUS
    private val systemUi = "com.android.systemui"
    private val checkedIndices = listOf(0, 1, 2, 3, 4, 6, 7, 8)

    private class Candidate(var node: NotificationAppIdentity.Node, var parent: Candidate? = null) {
        override fun toString(): String = error("candidate contents must not be rendered")
        override fun equals(other: Any?): Boolean = error("candidate equality must be explicit")
        override fun hashCode(): Int = error("candidate hash must not be read")
    }

    private fun chain(): List<Candidate> {
        val nodes = listOf(
            "app_icon" to "android.widget.ImageView",
            "icon_slot" to "android.widget.FrameLayout",
            "row_icon_and_main" to "android.widget.LinearLayout",
            "status_bar_latest_event_content" to "android.widget.FrameLayout",
            "expanded" to "android.widget.FrameLayout",
            "expandableNotificationRow" to "android.widget.FrameLayout",
            "title" to "android.widget.TextView",
            "line1" to "android.widget.LinearLayout",
            "notification_main_column" to "android.widget.LinearLayout",
        ).mapIndexed { index, (resource, className) ->
            Candidate(NotificationAppIdentity.Node("$systemUi:id/$resource", systemUi, className,
                descriptionEqualsAppLabel = index == 0, clickable = false, longClickable = false))
        }
        for (index in 0..4) nodes[index].parent = nodes[index + 1]
        nodes[6].parent = nodes[7]
        nodes[7].parent = nodes[8]
        nodes[8].parent = nodes[2]
        return nodes
    }

    private fun named() = Candidate(NotificationAppIdentity.Node("$systemUi:id/app_name_text", systemUi,
        "android.widget.TextView", textEqualsAppLabel = true))

    private fun verify(nodes: List<Candidate>, names: List<Candidate> = emptyList(),
        icons: List<Candidate> = listOf(nodes[0])) = NotificationAppIdentity.verify(names, { icons }, { it.node },
        { it.parent }, { it === nodes[5] }, nodes[6], { first, second -> first === second })

    @Test fun originalUniqueExactLabelSkipsIconQueryAndEveryParentRead() {
        val label = named()
        assertEquals(verified, NotificationAppIdentity.verify(listOf(label), { error("no icon query") },
            { assertSame(label, it); it.node }, { error("no parent") }, { error("no row check") },
            label, { _, _ -> error("no comparison") }))
    }

    @Test fun originalResourceDuplicateFailsWithoutFallbackBeforeAnyPropertyRead() {
        for (count in 2..3) {
            assertEquals(ambiguous, NotificationAppIdentity.verify(List(count) { Any() },
                { error("no icon query") }, { error("no describe") }, { error("no parent") },
                { error("no row check") }, Any(), { _, _ -> error("no comparison") }))
        }
    }

    @Test fun wrongNamedLabelCannotFallBackToValidIcon() {
        val nodes = chain()
        val label = named().also { it.node = it.node.copy(textEqualsAppLabel = false) }
        assertEquals(missing, NotificationAppIdentity.verify(listOf(label), { error("no icon query") },
            { it.node }, { error("no parent") }, { error("no row check") }, nodes[6],
            { _, _ -> error("no comparison") }))
    }

    @Test fun oneMatchingAndOneWrongNamedLabelStillFailsAmbiguous() {
        val other = named().also { it.node = it.node.copy(textEqualsAppLabel = false) }
        assertEquals(ambiguous, verify(chain(), names = listOf(named(), other)))
    }

    @Test fun namedLabelPackageAndResourceMustMatchExactly() {
        val invalid = listOf(named().node.copy(packageName = "other"), named().node.copy(resourceName = null))
        for (node in invalid) assertEquals(missing, verify(chain(), names = listOf(Candidate(node))))
    }

    @Test fun missingOriginalResourceQueriesIconsOnce() {
        val nodes = chain()
        var queries = 0
        assertEquals(verified, NotificationAppIdentity.verify(emptyList(), { queries++; listOf(nodes[0]) },
            { it.node }, { it.parent }, { it === nodes[5] }, nodes[6], { first, second -> first === second }))
        assertEquals(1, queries)
    }

    @Test fun noIconFailsWithoutAnyAttributeRead() {
        assertEquals(missing, NotificationAppIdentity.verify<Any>(emptyList(), { emptyList() },
            { error("no describe") }, { error("no parent") }, { error("no row check") }, Any(),
            { _, _ -> error("no comparison") }))
    }

    @Test fun duplicateIconResourcesFailBeforeReadingOrFilteringCandidates() {
        for (count in 2..3) {
            assertEquals(ambiguous, NotificationAppIdentity.verify<Any>(emptyList(), { List(count) { Any() } },
                { error("no describe") }, { error("no parent") }, { error("no row check") }, Any(),
                { _, _ -> error("no comparison") }))
        }
    }

    @Test fun oneMatchingAndOneWrongDescriptionIconStillFailsAmbiguous() {
        val nodes = chain()
        val other = Candidate(nodes[0].node.copy(descriptionEqualsAppLabel = false))
        assertEquals(ambiguous, verify(nodes, icons = listOf(nodes[0], other)))
    }

    @Test fun exactObservedIconAndAdjacentParentsBindToOriginalRowAndTitle() {
        assertEquals(verified, verify(chain()))
    }

    @Test fun eitherWhitelistedTitleIdKeepsTheSameExactIconAndHeaderBinding() {
        for (id in listOf("android:id/title", "$systemUi:id/title")) {
            val nodes = chain()
            nodes[6].node = nodes[6].node.copy(resourceName = id)
            assertEquals(verified, verify(nodes))
            nodes[7].node = nodes[7].node.copy(resourceName = "android:id/line1")
            assertEquals(missing, verify(nodes))
        }
    }

    @Test fun titleNamespaceAllowanceNeverChangesOtherIdentityResources() {
        for (index in checkedIndices.filter { it != 6 }) {
            val nodes = chain()
            nodes[6].node = nodes[6].node.copy(resourceName = "android:id/title")
            nodes[index].node = nodes[index].node.copy(resourceName = nodes[index].node.resourceName?.replace(systemUi, "android"))
            assertEquals("index=$index", missing, verify(nodes))
        }
    }

    @Test fun everyIconTitleAndParentPackageMustBeExactlySystemUi() {
        for (index in checkedIndices) for (value in listOf(null, "", "other.package", "$systemUi.extra")) {
            val nodes = chain()
            nodes[index].node = nodes[index].node.copy(packageName = value)
            assertEquals("index=$index", missing, verify(nodes))
        }
    }

    @Test fun everyIconTitleAndParentResourceMustMatchExactly() {
        for (index in checkedIndices) for (value in listOf(null, "", "app_icon", "$systemUi:id/wrong", "other:id/app_icon")) {
            val nodes = chain()
            nodes[index].node = nodes[index].node.copy(resourceName = value)
            assertEquals("index=$index", missing, verify(nodes))
        }
    }

    @Test fun everyIconTitleAndParentClassMustMatchExactly() {
        for (index in checkedIndices) for (value in listOf(null, "", "android.view.View", "wrong.class")) {
            val nodes = chain()
            nodes[index].node = nodes[index].node.copy(className = value)
            assertEquals("index=$index", missing, verify(nodes))
        }
    }

    @Test fun iconRequiresExactDescriptionEqualityNotTextOrPresence() {
        val nodes = chain()
        nodes[0].node = nodes[0].node.copy(descriptionEqualsAppLabel = false, textEqualsAppLabel = true)
        assertEquals(missing, verify(nodes))
    }

    @Test fun clickableOrLongClickableOrUnknownIconFails() {
        for (value in listOf(true, null)) {
            val nodes = chain()
            nodes[0].node = nodes[0].node.copy(clickable = value)
            assertEquals(missing, verify(nodes))
            nodes[0].node = nodes[0].node.copy(clickable = false, longClickable = value)
            assertEquals(missing, verify(nodes))
        }
    }

    @Test fun missingAnyDirectParentIncludingRowAndTitleParentsFails() {
        for (index in checkedIndices) {
            val nodes = chain()
            nodes[index].parent = null
            assertEquals("index=$index", missing, verify(nodes))
        }
    }

    @Test fun skippingAnyObservedIntermediateParentFails() {
        for (index in listOf(0, 1, 2, 3, 6, 7)) {
            val nodes = chain()
            nodes[index].parent = nodes[index].parent?.parent
            assertEquals("index=$index", missing, verify(nodes))
        }
    }

    @Test fun insertingAnyUnobservedIntermediateParentFails() {
        for (index in checkedIndices) {
            val nodes = chain()
            nodes[index].parent = Candidate(NotificationAppIdentity.Node("$systemUi:id/other", systemUi,
                "android.widget.FrameLayout"), nodes[index].parent)
            assertEquals("index=$index", missing, verify(nodes))
        }
    }

    @Test fun identicalLookingChainEndingAtAnotherRowIsRejected() {
        val nodes = chain()
        nodes[4].parent = Candidate(nodes[5].node)
        assertEquals(missing, verify(nodes))
    }

    @Test fun titleAndIconWithDifferentButIdenticalLookingCommonAncestorsFail() {
        val nodes = chain()
        nodes[8].parent = Candidate(nodes[2].node, nodes[3])
        assertEquals(missing, verify(nodes))
    }

    @Test fun invalidIconStopsBeforeParentReadsAndRowBinding() {
        val nodes = chain()
        nodes[0].node = nodes[0].node.copy(descriptionEqualsAppLabel = false)
        assertEquals(missing, NotificationAppIdentity.verify(emptyList(), { listOf(nodes[0]) }, { it.node },
            { error("no parent") }, { error("no row check") }, nodes[6], { _, _ -> error("no comparison") }))
    }

    @Test fun invalidAncestorStopsBeforeNextParentAndRowBinding() {
        for (index in 1..4) {
            val nodes = chain()
            nodes[index].node = nodes[index].node.copy(className = "wrong")
            assertEquals(missing, NotificationAppIdentity.verify(emptyList(), { listOf(nodes[0]) }, { it.node },
                { if (it === nodes[index]) error("no next parent") else it.parent }, { error("no row check") },
                nodes[6], { _, _ -> error("no comparison") }))
        }
    }

    @Test fun exactChainUsesEightParentEdgesAndExplicitCommonAncestorComparison() {
        val nodes = chain()
        var parentReads = 0
        var rowChecks = 0
        var comparisons = 0
        assertEquals(verified, NotificationAppIdentity.verify(emptyList(), { listOf(nodes[0]) }, { it.node },
            { parentReads++; it.parent }, { rowChecks++; it === nodes[5] }, nodes[6],
            { first, second -> comparisons++; assertSame(nodes[2], first); assertSame(nodes[2], second); true }))
        assertEquals(8, parentReads)
        assertEquals(1, rowChecks)
        assertEquals(1, comparisons)
    }

    private fun frameworkHeader(): List<Candidate> {
        val specs = listOf(
            "android:id/app_name_text" to "android.widget.TextView",
            "android:id/notification_top_line" to "android.view.ViewGroup",
            "android:id/notification_header" to "android.widget.RelativeLayout",
            "android:id/status_bar_latest_event_content" to "android.widget.FrameLayout",
            "$systemUi:id/expanded" to "android.widget.FrameLayout",
            "$systemUi:id/notification_row" to "android.widget.FrameLayout",
        )
        val nodes = specs.map { (resource, clazz) -> Candidate(NotificationAppIdentity.Node(resource, systemUi,
            clazz, textEqualsAppLabel = resource == "android:id/app_name_text", clickable = false,
            longClickable = false, enabled = true, scrollable = false)) }
        nodes.zipWithNext().forEach { (node, parent) -> node.parent = parent }
        return nodes
    }

    private fun verifyHeader(nodes: List<Candidate>, boundRow: Candidate = nodes.last()) =
        NotificationAppIdentity.verify(listOf(nodes.first()), { error("no icon fallback") }, { it.node },
            { it.parent }, { it === boundRow }, Candidate(NotificationAppIdentity.Node(null, null, null)),
            { _, _ -> error("no icon comparison") })

    @Test fun exactFrameworkLabelParentChainEndsAtTheBoundRow() {
        assertEquals(verified, verifyHeader(frameworkHeader()))
    }

    @Test fun namedResourceQueryAcceptsExactlyBothNamespaces() {
        for (resource in listOf("android:id/app_name_text", "$systemUi:id/app_name_text"))
            assertTrue(NotificationAppIdentity.namedResourcePattern.matcher(resource).matches())
        for (resource in listOf("android:id/app_name_text_extra", "$systemUi:id/app_name_text\n",
            "other:id/app_name_text", "android:id/app_icon", "android:id/notification_header", ""))
            assertFalse(NotificationAppIdentity.namedResourcePattern.matcher(resource).matches())
    }

    @Test fun frameworkLabelCannotUseWrongTextOrInteractiveSemantics() {
        for (change in listOf<(NotificationAppIdentity.Node) -> NotificationAppIdentity.Node>(
            { it.copy(textEqualsAppLabel = false) }, { it.copy(clickable = true) },
            { it.copy(longClickable = true) }, { it.copy(longClickable = null) },
            { it.copy(enabled = false) }, { it.copy(enabled = null) }, { it.copy(scrollable = true) },
            { it.copy(scrollable = null) }, { it.copy(className = "android.widget.Button") })) {
            val nodes = frameworkHeader()
            nodes.first().node = change(nodes.first().node)
            assertEquals(missing, verifyHeader(nodes))
        }
    }

    @Test fun frameworkLabelAndEveryHeaderParentRequireExactMetadata() {
        for (index in 0..4) {
            for (change in listOf<(NotificationAppIdentity.Node) -> NotificationAppIdentity.Node>(
                { it.copy(resourceName = "other:id/notification_header") }, { it.copy(packageName = "android") },
                { it.copy(packageName = "private.app") }, { it.copy(className = "wrong") },
                { it.copy(enabled = false) }, { it.copy(enabled = null) }, { it.copy(clickable = true) },
                { it.copy(clickable = null) }, { it.copy(scrollable = true) }, { it.copy(scrollable = null) })) {
                val nodes = frameworkHeader()
                nodes[index].node = change(nodes[index].node)
                assertEquals("index=$index", missing, verifyHeader(nodes))
            }
        }
    }

    @Test fun missingSkippingOrInsertingAnyFrameworkHeaderParentIsRejected() {
        for (index in 0..4) {
            for (mutation in 0..2) {
                val nodes = frameworkHeader()
                nodes[index].parent = when (mutation) {
                    0 -> null
                    1 -> nodes[index].parent?.parent
                    else -> Candidate(NotificationAppIdentity.Node("other:id/parent", systemUi, "wrong"), nodes[index].parent)
                }
                assertEquals("index=$index mutation=$mutation", missing, verifyHeader(nodes))
            }
        }
    }

    @Test fun identicalFrameworkHeaderEndingAtAnotherRowIsNotIdentity() {
        val nodes = frameworkHeader()
        assertEquals(missing, verifyHeader(nodes, Candidate(nodes.last().node)))
    }

    @Test fun mixedNamespaceNamedCandidatesAreAmbiguousBeforeReadingEither() {
        val android = frameworkHeader().first()
        val system = Candidate(android.node.copy(resourceName = "$systemUi:id/app_name_text"))
        for (candidates in listOf(listOf(android, system), listOf(system, android), listOf(android, android)))
            assertEquals(NotificationAppIdentity.Result.AMBIGUOUS, NotificationAppIdentity.verify(candidates,
                { error("no icon fallback") }, { error("no describe") }, { error("no parent") },
                { error("no row check") }, android, { _, _ -> error("no comparison") }))
    }

    @Test fun platformFailurePropagatesWithoutBecomingVerifiedOrRenderingContents() {
        val expected = IllegalStateException("fixed")
        try {
            NotificationAppIdentity.verify<Any>(emptyList(), { throw expected },
                { error("no describe") }, { error("no parent") }, { error("no row check") }, Any(),
                { _, _ -> error("no comparison") })
            throw AssertionError("failure expected")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }
    }
}
