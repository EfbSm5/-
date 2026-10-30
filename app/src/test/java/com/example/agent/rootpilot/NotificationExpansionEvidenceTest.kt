package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test

class NotificationExpansionEvidenceTest {
    private val system = "com.android.systemui"
    private val receipt = "321b21cd-4137-4e24-a14a-56d6a94b94f1"
    private class Tree(var node: NotificationIdentityEvidence.Node, val children: MutableList<Tree> = mutableListOf())
    private fun node(id: String, clazz: String = "android.widget.FrameLayout", title: Boolean = false,
        icon: Boolean = false, confirm: Boolean = false, stop: Boolean = false, clickable: Boolean = false) =
        NotificationIdentityEvidence.Node("$system:id/$id", clazz, system, true, clickable, false,
            descriptionEqualsAppLabel = icon, titleMatches = title,
            confirmActionMatches = confirm, stopActionMatches = stop)
    private fun tree(expanded: Boolean = false): Tree {
        val icon = Tree(node("app_icon", "android.widget.ImageView", icon = true))
        val title = Tree(node("title", "android.widget.TextView", title = true))
        val common = Tree(node("row_icon_and_main", "android.widget.LinearLayout"), mutableListOf(
            Tree(node("icon_slot"), mutableListOf(icon)),
            Tree(node("notification_main_column", "android.widget.LinearLayout"), mutableListOf(
                Tree(node("line1", "android.widget.LinearLayout"), mutableListOf(title))))))
        return Tree(node("expandableNotificationRow", clickable = true), mutableListOf(
            Tree(node("expanded"), mutableListOf(Tree(node("status_bar_latest_event_content"), mutableListOf(common)))))).also {
            if (expanded) it.children += listOf(Tree(node("action0", confirm = true, clickable = true)),
                Tree(node("action0", stop = true, clickable = true)))
        }
    }
    private fun walk(root: Tree): List<Tree> = listOf(root) + root.children.flatMap(::walk)
    private fun snapshot(root: Tree, after: Boolean = false) = NotificationIdentityEvidence.capture(root,
        if (after) NotificationIdentityEvidence.Phase.AFTER_EXPAND else NotificationIdentityEvidence.Phase.CURRENT,
        { it.children.size }, { n, i -> n.children[i] }, { it.node })
    private fun pre(root: Tree) = NotificationExpansionEvidence.before(snapshot(root))
    private fun post(root: Tree) = NotificationExpansionEvidence.after(snapshot(root, true))
    private fun entry(root: Tree, id: String) = walk(root).single { it.node.resourceName == "$system:id/$id" }

    @Test fun longPressIdentityFailureUsesTheFourExactStageCodes() {
        val expected = listOf("EXPANSION_INITIAL_CURRENT_IDENTITY_INVALID", "EXPANSION_REBIND_CURRENT_IDENTITY_INVALID",
            "EXPANSION_AFTER_EXPAND_IDENTITY_INVALID", "EXPANSION_MODAL_PREFIX_IDENTITY_INVALID")
        val sites = NotificationExpansionEvidence.IdentitySite.entries
        assertEquals(4, sites.size)
        for ((index, site) in sites.withIndex()) {
            assertEquals(expected[index], NotificationExpansionEvidence.identityReason(true, site,
                NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID).name)
        }
    }
    @Test fun identityReasonPreservesNonLongPressFailuresAndSuccess() {
        for (site in NotificationExpansionEvidence.IdentitySite.entries + listOf(null)) {
            for (code in NotificationExpansionEvidence.Code.entries) {
                assertEquals(code, NotificationExpansionEvidence.identityReason(false, site, code))
                if (code != NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID || site == null) {
                    assertEquals(code, NotificationExpansionEvidence.identityReason(true, site, code))
                }
            }
        }
    }
    @Test fun activeRootPackageGuardsUseAllFifteenFixedFailureCodes() {
        val expected = listOf(
            listOf("EXPANSION_INITIAL_BIND_ACTIVE_OWN", "EXPANSION_INITIAL_BIND_ACTIVE_OTHER", "EXPANSION_INITIAL_BIND_PACKAGE_UNAVAILABLE"),
            listOf("EXPANSION_REBIND_ACTIVE_OWN", "EXPANSION_REBIND_ACTIVE_OTHER", "EXPANSION_REBIND_PACKAGE_UNAVAILABLE"),
            listOf("EXPANSION_PRE_GESTURE_ACTIVE_OWN", "EXPANSION_PRE_GESTURE_ACTIVE_OTHER", "EXPANSION_PRE_GESTURE_PACKAGE_UNAVAILABLE"),
            listOf("EXPANSION_AFTER_BIND_ACTIVE_OWN", "EXPANSION_AFTER_BIND_ACTIVE_OTHER", "EXPANSION_AFTER_BIND_PACKAGE_UNAVAILABLE"),
            listOf("EXPANSION_AFTER_VALIDATE_ACTIVE_OWN", "EXPANSION_AFTER_VALIDATE_ACTIVE_OTHER", "EXPANSION_AFTER_VALIDATE_PACKAGE_UNAVAILABLE"),
        )
        val sites = NotificationExpansionEvidence.GuardSite.entries
        assertEquals(5, sites.size)
        for ((index, site) in sites.withIndex()) {
            for ((category, pkg) in listOf("com.example.agent", "unrelated.app", null).withIndex()) {
                assertEquals(expected[index][category], NotificationExpansionEvidence.activeRootReason(site, pkg).name)
            }
            assertEquals(NotificationExpansionEvidence.Code.NONE, NotificationExpansionEvidence.activeRootReason(site, system))
        }
    }
    @Test fun onlyNullMeansPackageUnavailableAndOnlyExactPackagesPassOrMeanOwn() {
        for (site in NotificationExpansionEvidence.GuardSite.entries) {
            for (pkg in listOf("", "com.example.agent.other", "com.android.systemui.other", " com.android.systemui")) {
                assertEquals("EXPANSION_${site.name}_ACTIVE_OTHER", NotificationExpansionEvidence.activeRootReason(site, pkg).name)
            }
        }
    }
    @Test fun completeMeasuredIconAndTitleChainAdmitsOnlyAbsentActionsBeforeGesture() {
        assertEquals(NotificationExpansionEvidence.Code.NONE, pre(tree()))
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_ALREADY_PRESENT, pre(tree(true)))
    }
    @Test fun eitherWhitelistedTitleIdPreservesIdentityAndCrossNamespaceDuplicatesFailBothPhases() {
        for (id in listOf("android:id/title", "$system:id/title")) for (after in listOf(false, true)) {
            val root = tree(after)
            val title = entry(root, "title")
            title.node = title.node.copy(resourceName = id)
            assertEquals(NotificationExpansionEvidence.Code.NONE, if (after) post(root) else pre(root))
            val otherId = if (id == "android:id/title") "$system:id/title" else "android:id/title"
            root.children += Tree(title.node.copy(resourceName = otherId))
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, if (after) post(root) else pre(root))
        }
    }
    @Test fun eitherExpectedActionAlreadyPresentRejectsWithoutGesture() {
        for (confirm in listOf(true, false)) {
            val root = tree()
            root.children += Tree(node("action0", confirm = confirm, stop = !confirm))
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_ALREADY_PRESENT, pre(root))
        }
    }
    @Test fun expansionRequiresBothUniqueEnabledClickableSystemUiActions() {
        assertEquals(NotificationExpansionEvidence.Code.NONE, post(tree(true)))
        for (confirm in listOf(true, false)) {
            val root = tree(true)
            root.children.removeAll { if (confirm) it.node.confirmActionMatches else it.node.stopActionMatches }
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_MISSING, post(root))
        }
    }
    @Test fun duplicateActionIsAmbiguousEvenIfOneIsUnavailable() {
        for (confirm in listOf(true, false)) {
            val root = tree(true)
            root.children += Tree(node("action0", confirm = confirm, stop = !confirm))
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_AMBIGUOUS, post(root))
        }
    }
    @Test fun disabledNonclickableAndForeignActionsNeverProveExpansion() {
        for (confirm in listOf(true, false)) for (change in 0..2) {
            val root = tree(true)
            val action = walk(root).single { if (confirm) it.node.confirmActionMatches else it.node.stopActionMatches }
            action.node = when (change) {
                0 -> action.node.copy(enabled = false)
                1 -> action.node.copy(clickable = false)
                else -> action.node.copy(packageName = "other.app")
            }
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_UNAVAILABLE, post(root))
        }
    }
    @Test fun titleOrHeightAloneCannotProveExpandedState() {
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_MISSING, post(tree()))
    }
    @Test fun iconMissingDuplicateWrongLabelOrClickableFailsBothPhases() {
        for (change in 0..4) {
            val root = tree(true)
            val slot = entry(root, "icon_slot")
            val icon = slot.children.single()
            when (change) {
                0 -> slot.children.clear()
                1 -> slot.children += Tree(icon.node)
                2 -> icon.node = icon.node.copy(descriptionEqualsAppLabel = false)
                3 -> icon.node = icon.node.copy(clickable = true)
                4 -> icon.node = icon.node.copy(longClickable = true)
            }
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, pre(root))
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, post(root))
        }
    }
    @Test fun everyMeasuredIconAndTitleParentMustKeepExactResourceClassAndPackage() {
        for (id in listOf("icon_slot", "row_icon_and_main", "status_bar_latest_event_content", "expanded",
            "line1", "notification_main_column", "title")) for (change in 0..2) {
            val root = tree(true)
            val target = entry(root, id)
            target.node = when (change) {
                0 -> target.node.copy(resourceName = "android:id/other")
                1 -> target.node.copy(className = "android.view.View")
                else -> target.node.copy(packageName = "other.app")
            }
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, post(root))
        }
    }
    @Test fun missingDuplicateOrForeignRootTitleRejectsBothPhases() {
        for (change in 0..2) {
            val root = tree(true)
            when (change) {
                0 -> entry(root, "title").node = entry(root, "title").node.copy(titleMatches = false)
                1 -> root.children += Tree(node("title", "android.widget.TextView", title = true))
                else -> root.node = root.node.copy(packageName = "other.app")
            }
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, post(root))
        }
    }
    @Test fun preflightRejectsEveryRealNameRatherThanHidingItFromTheIconGuard() {
        for (valid in listOf(true, false)) {
            val root = tree()
            root.children += Tree(node("app_name_text", "android.widget.TextView").copy(textEqualsAppLabel = valid))
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, pre(root))
        }
    }
    @Test fun afterSupportsExactUniqueNameButNeverWrongOrMultipleNamesWithValidIcon() {
        val named = tree(true)
        named.children += Tree(node("app_name_text", "android.widget.TextView").copy(textEqualsAppLabel = true))
        entry(named, "app_icon").node = entry(named, "app_icon").node.copy(descriptionEqualsAppLabel = false)
        assertEquals(NotificationExpansionEvidence.Code.NONE, post(named))
        for (change in 0..2) {
            val root = tree(true)
            root.children += Tree(node("app_name_text", "android.widget.TextView").copy(textEqualsAppLabel = true))
            when (change) {
                0 -> entry(root, "app_name_text").node = entry(root, "app_name_text").node.copy(textEqualsAppLabel = false)
                1 -> entry(root, "app_name_text").node = entry(root, "app_name_text").node.copy(packageName = "other.app")
                else -> root.children += Tree(node("app_name_text", "android.widget.TextView"))
            }
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, post(root))
        }
    }
    @Test fun rootDisabledNonclickableScrollableOrWrongClassRejectsBothPhases() {
        for (change in 0..3) {
            val root = tree(true)
            root.node = when (change) {
                0 -> root.node.copy(enabled = false)
                1 -> root.node.copy(clickable = false)
                2 -> root.node.copy(scrollable = true)
                else -> root.node.copy(className = "android.view.View")
            }
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, pre(root))
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, post(root))
        }
    }
    @Test fun twoActionProofsCannotReferToTheSameVisibleNode() {
        val root = tree()
        root.children += Tree(node("action0", confirm = true, stop = true, clickable = true))
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_UNAVAILABLE, post(root))
    }
    @Test fun truncatedDepthAndWidthCannotBecomeValidProofs() {
        for (depth in listOf(true, false)) {
            val root = tree(true)
            if (depth) {
                var current = root
                repeat(9) { Tree(node("extra")).also { current.children += it; current = it } }
            } else repeat(40) { root.children += Tree(node("extra")) }
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, pre(root))
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, post(root))
        }
    }
    @Test fun swappedPhasesCannotBeAdmitted() {
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_PHASE_INVALID,
            NotificationExpansionEvidence.before(snapshot(tree(), true)))
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_PHASE_INVALID,
            NotificationExpansionEvidence.after(snapshot(tree(true))))
    }
    @Test fun swipeStatusRequiresDedicatedEntryAndFourVerifiedProofs() {
        val phases = listOf(snapshot(tree()), snapshot(tree(true), true))
        assertEquals("IDENTITY_PHASES_INVALID", runCatching { NotificationIdentityEvidence.json(receipt, 1,
            NotificationIdentityEvidence.ExpandStatus.SWIPED, phases) }.exceptionOrNull()?.message)
        val text = NotificationIdentityEvidence.expansionJson(receipt, 1, phases, true).toString(Charsets.UTF_8)
        assertTrue(text.contains("\"expandStatus\":\"SWIPED\""))
        assertTrue(text.contains("\"expansionProofs\":{\"ownRowExpanded\":true,\"noOwnActivityLaunch\":true,\"confirmActionUnique\":true,\"stopActionUnique\":true}"))
        assertTrue(text.toByteArray().size <= 32768)
        assertFalse(text.contains("确认当前动作")); assertFalse(text.contains("停止"))
        assertFalse(text.contains("confirmActionMatches")); assertFalse(text.contains("stopActionMatches"))
    }
    @Test fun lifecycleFailureMissingActionOrInvalidPreconditionCannotSerializeValidArtifact() {
        val before = snapshot(tree())
        val after = snapshot(tree(true), true)
        for ((phases, noLaunch) in listOf(listOf(before, after) to false,
            listOf(before, snapshot(tree(), true)) to true,
            listOf(snapshot(tree(true)), after) to true, listOf(before) to true,
            listOf(after, before) to true)) {
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching {
                NotificationIdentityEvidence.expansionJson(receipt, 1, phases, noLaunch)
            }.exceptionOrNull()?.message)
        }
    }
    @Test fun longPressRequiresAllThreeLiveGuards() {
        for (enabled in listOf(false, true)) for (longClickable in listOf(false, true))
            for (boundsNonempty in listOf(false, true)) {
                assertEquals(enabled && longClickable && boundsNonempty,
                    NotificationExpansionEvidence.longPressAvailable(enabled, longClickable, boundsNonempty))
            }
    }
    @Test fun failurePublicationRequiresExactOriginalFailureAndEveryCleanupObserverGuard() {
        assertTrue(NotificationExpansionEvidence.failurePublishable("IDENTITY_ROW_UNRELIABLE", "NONE", true, true, true))
        for (reason in listOf("NONE", "IDENTITY_AFTER_EXPAND_TITLE_MISSING", "IDENTITY_AFTER_EXPAND_TITLE_AMBIGUOUS",
            "EXPANSION_LEFT_SYSTEM_UI", "EXPANSION_OWN_ACTIVITY_LAUNCHED", "EXPANSION_POST_TIMEOUT")) {
            assertFalse(NotificationExpansionEvidence.failurePublishable(reason, "NONE", true, true, true))
        }
        for (baseline in listOf(false, true)) for (observer in listOf(false, true)) for (bytes in listOf(false, true))
            for (cleanup in listOf("NONE", "VERIFY_OTHER")) {
                assertEquals(baseline && observer && bytes && cleanup == "NONE",
                    NotificationExpansionEvidence.failurePublishable("IDENTITY_ROW_UNRELIABLE", cleanup, baseline, observer, bytes))
            }
    }
    @Test fun longPressSuccessHasDistinctStatusAndGestureWhileSwipeBytesStaySeparate() {
        val before = tree().also { it.node = it.node.copy(longClickable = true) }
        val phases = listOf(snapshot(before), snapshot(tree(true), true))
        val longPress = NotificationIdentityEvidence.expansionJson(receipt, 1, phases, true, longPress = true).toString(Charsets.UTF_8)
        assertTrue(longPress.contains("\"expandStatus\":\"LONG_PRESSED\""))
        assertTrue(longPress.contains("\"gestureKind\":\"LONG_CLICK\""))
        assertFalse(longPress.contains("SWIPED"))
        val swipe = NotificationIdentityEvidence.expansionJson(receipt, 1, phases, true).toString(Charsets.UTF_8)
        assertTrue(swipe.contains("\"expandStatus\":\"SWIPED\""))
        assertFalse(swipe.contains("gestureKind")); assertFalse(swipe.contains("LONG_PRESSED"))
        assertTrue(longPress.toByteArray().size <= NotificationIdentityEvidence.MAX_BYTES)
    }
    @Test fun longPressCannotSerializeWithoutLongClickableBeforeRowOrThroughIdentityMode() {
        assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching {
            NotificationIdentityEvidence.expansionJson(receipt, 1,
                listOf(snapshot(tree()), snapshot(tree(true), true)), true, longPress = true)
        }.exceptionOrNull()?.message)
        assertEquals("IDENTITY_PHASES_INVALID", runCatching {
            NotificationIdentityEvidence.json(receipt, 1, NotificationIdentityEvidence.ExpandStatus.LONG_PRESSED,
                listOf(snapshot(tree()), snapshot(tree(true), true)))
        }.exceptionOrNull()?.message)
    }
    @Test fun originalIdentityJsonNeverIncludesExpansionProofsOrActionEquality() {
        val text = NotificationIdentityEvidence.json(receipt, 1, NotificationIdentityEvidence.ExpandStatus.MISSING,
            listOf(snapshot(tree()))).toString(Charsets.UTF_8)
        assertFalse(text.contains("expansionProofs")); assertFalse(text.contains("ActionMatches"))
    }
}
