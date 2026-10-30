package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test

class NotificationModalBindingTest {
    private val system = "com.android.systemui"
    private val receipt = "321b21cd-4137-4e24-a14a-56d6a94b94f1"
    private val valid = NotificationExpansionEvidence.Code.NONE
    private val invalid = NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID
    private class Tree(var node: NotificationIdentityEvidence.Node, val children: MutableList<Tree> = mutableListOf())
    private fun node(id: String, clazz: String = "android.widget.FrameLayout", title: Boolean = false,
        icon: Boolean = false, clickable: Boolean = false) = NotificationIdentityEvidence.Node(
        "$system:id/$id", clazz, system, true, clickable, false,
        descriptionEqualsAppLabel = icon, titleMatches = title)
    private fun tree(modal: Boolean, actions: Boolean = true): Tree {
        val common = Tree(node("row_icon_and_main", "android.widget.LinearLayout"), mutableListOf(
            Tree(node("icon_slot"), mutableListOf(Tree(node("app_icon", "android.widget.ImageView", icon = true)))),
            Tree(node("notification_main_column", "android.widget.LinearLayout"), mutableListOf(
                Tree(node("line1", "android.widget.LinearLayout"), mutableListOf(
                    Tree(node("title", "android.widget.TextView", title = true))))))))
        val content = if (modal) Tree(node("notification_action_list_margin_target", "android.widget.LinearLayout"), mutableListOf(common)) else common
        return Tree(node("unresolved_row", clickable = true).copy(longClickable = true), mutableListOf(
            Tree(node("expanded"), mutableListOf(Tree(node("status_bar_latest_event_content"), mutableListOf(content)))))).also {
            if (actions) it.children += listOf(Tree(node("action0", clickable = true).copy(confirmActionMatches = true)),
                Tree(node("action0", clickable = true).copy(stopActionMatches = true)))
        }
    }
    private fun walk(root: Tree): List<Tree> = listOf(root) + root.children.flatMap(::walk)
    private fun entry(root: Tree, id: String) = walk(root).single { it.node.resourceName == "$system:id/$id" }
    private fun snapshot(root: Tree, after: Boolean = true, modal: Boolean = false): NotificationIdentityEvidence.Snapshot<Tree> {
        val snapshot = NotificationIdentityEvidence.capture(root,
            if (after) NotificationIdentityEvidence.Phase.AFTER_EXPAND else NotificationIdentityEvidence.Phase.CURRENT,
            { it.children.size }, { n, index -> n.children[index] }, { it.node })
        return if (modal) snapshot.withModalBinding() else snapshot
    }
    private fun post(root: Tree) = NotificationExpansionEvidence.after(snapshot(root, modal = true))
    private fun before() = snapshot(tree(modal = false, actions = false), after = false)
    private fun serialize(after: NotificationIdentityEvidence.Snapshot<Tree>, longPress: Boolean = true,
        noLaunch: Boolean = true) = NotificationIdentityEvidence.expansionJson(receipt, 1, listOf(before(), after), noLaunch, longPress)
    private fun rowNode(id: String, clazz: String = "android.widget.FrameLayout", clickable: Boolean = false) =
        NotificationRowStructure.Node("$system:id/$id", system, clazz, true, clickable, false)
    private val row get() = rowNode("unresolved_row", clickable = true)
    private val expanded get() = rowNode("expanded")
    private val modalParent get() = rowNode("modal_window_view")

    @Test fun independentModalPredicateMatchesOnlyExactMeasuredAdjacentStates() {
        assertTrue(NotificationRowStructure.isModalRow(row, expanded, modalParent))
        for (id in listOf(null, "REDACTED", "$system:id/unknown")) {
            assertTrue(NotificationRowStructure.isModalRow(row.copy(resourceName = id), expanded, modalParent))
        }
        assertFalse(NotificationRowStructure.isModalRow(row, expanded, null))
        assertFalse(NotificationRowStructure.isModalRow(row, null, modalParent))
        for (index in 0..2) {
            val originals = listOf(row, expanded, modalParent)
            val original = originals[index]
            val wrong = listOf(original.copy(packageName = null), original.copy(packageName = "other.app"),
                original.copy(className = null), original.copy(className = "android.view.View"),
                original.copy(enabled = null), original.copy(enabled = false),
                original.copy(clickable = null), original.copy(clickable = index != 0),
                original.copy(scrollable = null), original.copy(scrollable = true)) +
                if (index == 0) emptyList() else listOf(original.copy(resourceName = null), original.copy(resourceName = "$system:id/other"))
            for (changed in wrong) {
                val nodes = originals.toMutableList().also { it[index] = changed }
                assertFalse("index=$index", NotificationRowStructure.isModalRow(nodes[0], nodes[1], nodes[2]))
            }
        }
    }
    @Test fun oldScrollerAndModalPredicatesRemainStrictlySeparateWithoutSkippingWrappers() {
        val scroller = rowNode("notification_stack_scroller", "android.widget.ScrollView")
        assertTrue(NotificationRowStructure.isRow(row, expanded, scroller))
        assertFalse(NotificationRowStructure.isRow(row, expanded, modalParent))
        assertFalse(NotificationRowStructure.isModalRow(row, expanded, scroller))
        assertFalse(NotificationRowStructure.isModalRow(row, row, modalParent))
        assertFalse(NotificationRowStructure.isModalRow(row, expanded, row))
        assertFalse(NotificationRowStructure.isModalRow(modalParent, row, rowNode("other")))
    }
    @Test fun onlyExplicitActualModalSnapshotSelectsMarginChainAndEightLevelsRemainComplete() {
        val root = tree(modal = true)
        val plain = snapshot(root)
        val marked = snapshot(root, modal = true)
        assertFalse(marked.truncated)
        assertEquals(7, marked.entries.maxOf { it.depth })
        assertEquals(invalid, NotificationExpansionEvidence.after(plain))
        assertEquals(valid, NotificationExpansionEvidence.after(marked))
        assertEquals(valid, NotificationExpansionEvidence.after(snapshot(tree(modal = false))))
        assertEquals(invalid, post(tree(modal = false)))
        assertEquals("IDENTITY_PHASES_INVALID", runCatching { before().withModalBinding() }.exceptionOrNull()?.message)
    }
    @Test fun everyModalChainResourceClassPackageAndMeasuredPassiveStateMustMatch() {
        for (id in listOf("row_icon_and_main", "notification_action_list_margin_target", "status_bar_latest_event_content",
            "expanded", "title", "line1", "notification_main_column")) for (change in 0..5) {
            val root = tree(modal = true)
            val target = entry(root, id)
            target.node = when (change) {
                0 -> target.node.copy(resourceName = "$system:id/wrong")
                1 -> target.node.copy(className = "android.view.View")
                2 -> target.node.copy(packageName = "other.app")
                3 -> target.node.copy(enabled = false)
                4 -> target.node.copy(clickable = true)
                else -> target.node.copy(scrollable = true)
            }
            assertEquals("id=$id change=$change", invalid, post(root))
        }
    }
    @Test fun missingMisplacedAndDoubleMarginOrIntermediateWrapperNeverMatches() {
        for (change in 0..3) {
            val root = tree(modal = true)
            val margin = entry(root, "notification_action_list_margin_target")
            val common = margin.children.single()
            val event = entry(root, "status_bar_latest_event_content")
            when (change) {
                0 -> { event.children.clear(); event.children += common }
                1 -> { margin.children.clear(); margin.children += Tree(margin.node, mutableListOf(common)) }
                2 -> { event.children.clear(); event.children += common; common.children += Tree(margin.node) }
                3 -> { margin.children.clear(); margin.children += Tree(node("extra"), mutableListOf(common)) }
            }
            assertNotEquals(valid, post(root))
        }
    }
    @Test fun ancestorEvidenceDoesNotInventMissingIconNameOrActionsAndIconRulesStayExact() {
        for (change in 0..7) {
            val root = tree(modal = true)
            val slot = entry(root, "icon_slot")
            val icon = slot.children.single()
            when (change) {
                0 -> slot.children.clear()
                1 -> slot.children += Tree(icon.node)
                2 -> icon.node = icon.node.copy(descriptionEqualsAppLabel = false)
                3 -> icon.node = icon.node.copy(clickable = true)
                4 -> icon.node = icon.node.copy(longClickable = true)
                5 -> icon.node = icon.node.copy(className = "android.view.View")
                6 -> icon.node = icon.node.copy(packageName = "other.app")
                7 -> slot.node = slot.node.copy(className = "android.view.View")
            }
            assertEquals(invalid, post(root))
        }
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_MISSING, post(tree(modal = true, actions = false)))
    }
    @Test fun identicalLookingIconAndTitleCommonAncestorsCannotBeSubstituted() {
        val root = tree(modal = true)
        val margin = entry(root, "notification_action_list_margin_target")
        val common = margin.children.single()
        val main = entry(root, "notification_main_column")
        common.children.remove(main)
        margin.children += Tree(common.node, mutableListOf(main))
        assertEquals(invalid, post(root))
    }
    @Test fun modalNameCandidatesKeepCountFirstAndWrongOrConflictingNamesNeverFallBack() {
        for (change in 0..2) {
            val root = tree(modal = true)
            val named = node("app_name_text", "android.widget.TextView").copy(textEqualsAppLabel = true)
            root.children += Tree(if (change == 0) named.copy(textEqualsAppLabel = false) else named)
            if (change > 0) root.children += Tree(named.copy(textEqualsAppLabel = change == 2))
            assertEquals(invalid, post(root))
        }
        val namedRoot = tree(modal = true)
        entry(namedRoot, "icon_slot").children.clear()
        namedRoot.children += Tree(node("app_name_text", "android.widget.TextView").copy(textEqualsAppLabel = true))
        assertEquals(valid, post(namedRoot))
    }
    @Test fun onlyLongPressSerializerAcceptsProvenModalAndSchemaDoesNotChange() {
        val after = snapshot(tree(modal = true), modal = true)
        val text = serialize(after).toString(Charsets.UTF_8)
        assertTrue(text.contains("\"expandStatus\":\"LONG_PRESSED\""))
        assertTrue(text.contains("\"gestureKind\":\"LONG_CLICK\""))
        assertFalse(text.contains("modalBinding")); assertFalse(text.contains("confirmActionMatches"))
        assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(after, longPress = false) }.exceptionOrNull()?.message)
        assertEquals("IDENTITY_PHASES_INVALID", runCatching {
            NotificationIdentityEvidence.json(receipt, 1, NotificationIdentityEvidence.ExpandStatus.CLICKED, listOf(before(), after))
        }.exceptionOrNull()?.message)
        assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(after, noLaunch = false) }.exceptionOrNull()?.message)
    }
    @Test fun serializerRevalidatesModalChainAndBothUniqueEnabledClickableActions() {
        for (change in 0..5) {
            val root = tree(modal = true)
            val confirm = walk(root).single { it.node.confirmActionMatches }
            when (change) {
                0 -> root.children.remove(confirm)
                1 -> root.children += Tree(confirm.node)
                2 -> confirm.node = confirm.node.copy(enabled = false)
                3 -> confirm.node = confirm.node.copy(clickable = false)
                4 -> confirm.node = confirm.node.copy(packageName = "other.app")
                5 -> entry(root, "notification_action_list_margin_target").node = node("wrong")
            }
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot(root, modal = true)) }.exceptionOrNull()?.message)
        }
    }
    private fun contentTree(): Tree = tree(modal = true).also { root ->
        val actions = root.children.drop(1)
        root.children.subList(1, root.children.size).clear()
        entry(root, "status_bar_latest_event_content").children += actions
    }
    private fun contentSnapshot(root: Tree) = NotificationIdentityEvidence.captureModalContentPublic(root,
        NotificationIdentityEvidence.Phase.AFTER_EXPAND, { it.children.size }, { it.children }, {}, { it.node })
    private fun scopeCopy(source: NotificationIdentityEvidence.Snapshot<Tree>,
        entries: List<NotificationIdentityEvidence.Entry> = source.entries, rootPath: String? = source.captureRootPath,
        modal: Boolean = source.modalBinding) = NotificationIdentityEvidence.Snapshot<Tree>(source.phase, source.truncated,
        entries, emptyList(), modalBinding = modal, captureBudget = source.captureBudget, captureRootPath = rootPath)

    @Test fun contentScopeRetainsRealRowAncestorsAndExplicitPathWithoutChangingOldSerialization() {
        val snapshot = contentSnapshot(contentTree())
        assertFalse(snapshot.truncated); assertTrue(snapshot.validContentScope())
        assertEquals(listOf("0", "0/0", "0/0/0"), snapshot.entries.take(3).map { it.path })
        assertEquals(listOf(0, 1, 2), snapshot.entries.take(3).map { it.depth })
        assertEquals(valid, NotificationExpansionEvidence.after(snapshot))
        val text = serialize(snapshot).toString(Charsets.UTF_8)
        assertEquals(1, Regex("captureRootPath").findAll(text).count())
        assertTrue(text.contains("\"captureRootPath\":\"0/0/0\""))
        assertFalse(serialize(snapshot(tree(modal = true), modal = true)).toString(Charsets.UTF_8).contains("captureRootPath"))
        assertFalse(serialize(snapshot(tree(modal = false)), longPress = false).toString(Charsets.UTF_8).contains("captureRootPath"))
        assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot, longPress = false) }.exceptionOrNull()?.message)
    }
    @Test fun contentScopeAcceptsEitherTitleIdButNeverTwoAcrossNamespaces() {
        for (id in listOf("android:id/title", "$system:id/title")) {
            val root = contentTree()
            val title = entry(root, "title")
            title.node = title.node.copy(resourceName = id)
            assertEquals(valid, NotificationExpansionEvidence.after(contentSnapshot(root)))
            val otherId = if (id == "android:id/title") "$system:id/title" else "android:id/title"
            entry(root, "status_bar_latest_event_content").children += Tree(title.node.copy(resourceName = otherId))
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID,
                NotificationExpansionEvidence.after(contentSnapshot(root)))
        }
    }
    @Test fun ninthRealRowDepthIsExplicitButAnotherContentLevelStillFailsAndNeverSerializes() {
        for (levels in listOf(7, 8)) {
            val root = contentTree(); var branch = Tree(node("decoration"))
            val first = branch
            repeat(levels - 1) { Tree(node("decoration")).also { branch.children += it; branch = it } }
            entry(root, "status_bar_latest_event_content").children += first
            val snapshot = contentSnapshot(root)
            assertEquals(9, snapshot.entries.maxOf { it.depth })
            assertTrue(snapshot.entries.any { it.path.count { ch -> ch == '/' } == 9 })
            assertTrue(NotificationIdentityEvidence.capturePublic(root, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
                { it.children.size }, { it.children }, {}, { it.node }).truncated)
            if (levels == 7) {
                assertFalse(snapshot.truncated); assertEquals(valid, NotificationExpansionEvidence.after(snapshot))
                assertTrue(serialize(snapshot).toString(Charsets.UTF_8).contains("\"depth\":9"))
                assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching {
                    serialize(scopeCopy(snapshot, rootPath = null))
                }.exceptionOrNull()?.message)
            } else {
                assertTrue(snapshot.truncated)
                assertEquals(NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, NotificationExpansionEvidence.after(snapshot))
                assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot) }.exceptionOrNull()?.message)
            }
        }
    }
    @Test fun completeSideBranchesAreNeverFilteredAndTheirActionsParticipateInTheSameDecision() {
        for (atRow in listOf(true, false)) {
            val root = contentTree()
            val parent = if (atRow) root else entry(root, "expanded")
            parent.children += Tree(node("outside").copy(stopActionMatches = true))
            val snapshot = contentSnapshot(root)
            assertFalse(snapshot.truncated)
            assertEquals(walk(root).size, snapshot.entries.size)
            assertTrue(snapshot.entries.any { it.node.resourceName == "$system:id/outside" })
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_AMBIGUOUS, NotificationExpansionEvidence.after(snapshot))
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot) }.exceptionOrNull()?.message)
        }
        assertEquals(valid, NotificationExpansionEvidence.after(contentSnapshot(tree(modal = true))))
    }
    @Test fun dynamicContentRootKeepsActualIndicesAndEveryVisibleSiblingInDfsOrder() {
        val root = contentTree(); val expanded = entry(root, "expanded")
        root.children.add(0, Tree(node("row_side"), mutableListOf(Tree(node("row_leaf")))))
        expanded.children.add(0, Tree(node("expanded_side"), mutableListOf(Tree(node("expanded_leaf")))))
        val snapshot = contentSnapshot(root)
        fun paths(value: Tree, path: String): List<String> = listOf(path) + value.children.flatMapIndexed { index, child -> paths(child, "$path/$index") }
        assertEquals("0/1/1", snapshot.captureRootPath)
        assertEquals(paths(root, "0"), snapshot.entries.map { it.path })
        assertEquals(walk(root).size, snapshot.captureBudget?.visitedNodes)
        assertTrue(snapshot.validContentScope())
        assertEquals(valid, NotificationExpansionEvidence.after(snapshot))
        assertTrue(serialize(snapshot).toString(Charsets.UTF_8).contains("\"captureRootPath\":\"0/1/1\""))
    }
    @Test fun sideBranchActionCandidatesCountBeforeEnabledOrClickableFiltering() {
        for (atRow in listOf(true, false)) for (confirm in listOf(true, false)) {
            val root = contentTree(); val parent = if (atRow) root else entry(root, "expanded")
            parent.children.add(0, Tree(node("extra_action").copy(enabled = false, clickable = false,
                confirmActionMatches = confirm, stopActionMatches = !confirm)))
            val snapshot = contentSnapshot(root)
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_ACTION_AMBIGUOUS, NotificationExpansionEvidence.after(snapshot))
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot) }.exceptionOrNull()?.message)
        }
    }
    @Test fun sideBranchIdentityCandidatesAndUnknownPackagesCannotBeHiddenByTheChosenContent() {
        val candidates = listOf(
            node("title", "android.widget.TextView"),
            node("title", "android.widget.TextView").copy(resourceName = "android:id/title"),
            node("app_icon", "android.widget.ImageView"),
            node("app_name_text", "android.widget.TextView"),
            node("outside").copy(titleMatches = true),
            node("outside").copy(packageName = "other.app"),
            node("outside").copy(packageName = null),
            node("outside").copy(className = "REDACTED"),
            node("outside").copy(className = null),
            node("expanded", "android.widget.FrameLayout"),
            node("status_bar_latest_event_content", "android.widget.FrameLayout"))
        for ((index, extra) in candidates.withIndex()) {
            val root = contentTree(); entry(root, "expanded").children.add(0, Tree(node("holder"), mutableListOf(Tree(extra))))
            val snapshot = contentSnapshot(root)
            assertFalse(snapshot.truncated)
            assertEquals("candidate=$index", NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, NotificationExpansionEvidence.after(snapshot))
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot) }.exceptionOrNull()?.message)
        }
    }
    @Test fun namedIdentityCannotMaskDuplicateIconsNamesOrTitlesAcrossSideBranches() {
        for (id in listOf("app_icon", "app_name_text", "title")) {
            val root = contentTree(); val content = entry(root, "status_bar_latest_event_content")
            content.children += Tree(node("app_name_text", "android.widget.TextView").copy(textEqualsAppLabel = true))
            assertEquals(valid, NotificationExpansionEvidence.after(contentSnapshot(root)))
            root.children.add(0, Tree(node(id, if (id == "app_icon") "android.widget.ImageView" else "android.widget.TextView")))
            val snapshot = contentSnapshot(root)
            assertEquals(NotificationExpansionEvidence.Code.EXPANSION_IDENTITY_INVALID, NotificationExpansionEvidence.after(snapshot))
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot) }.exceptionOrNull()?.message)
        }
    }
    @Test fun sideBranchesKeepEightAbsoluteLevelsWhileContentKeepsEightRelativeLevels() {
        for (atRow in listOf(true, false)) for (deep in listOf(false, true)) {
            val root = contentTree(); val parent = if (atRow) root else entry(root, "expanded")
            var branch = Tree(node("leaf"))
            repeat((if (atRow) 6 else 5) + if (deep) 1 else 0) { branch = Tree(node("branch"), mutableListOf(branch)) }
            parent.children.add(0, branch)
            val snapshot = contentSnapshot(root)
            assertEquals(deep, snapshot.truncated)
            if (deep) {
                assertEquals(NotificationCaptureBudget.Cause.DEPTH_LIMIT, snapshot.captureBudget?.budgetCause)
                assertEquals(NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, NotificationExpansionEvidence.after(snapshot))
                assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot) }.exceptionOrNull()?.message)
            } else {
                assertEquals(valid, NotificationExpansionEvidence.after(snapshot))
                assertEquals(7, snapshot.entries.filter { !it.path.startsWith("${snapshot.captureRootPath}/") }.maxOf { it.depth })
            }
        }
    }
    @Test fun completeGraphHasOneGlobal32NodeBudgetAndOneReadCache() {
        val root = contentTree()
        while (walk(root).size < 32) root.children.add(0, Tree(node("side")))
        val counts = java.util.IdentityHashMap<Tree, Int>()
        val descriptions = java.util.IdentityHashMap<Tree, Int>()
        val children = java.util.IdentityHashMap<Tree, Int>()
        val retained = mutableListOf<Tree>()
        val snapshot = NotificationIdentityEvidence.captureModalContentPublic(root, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
            { counts[it] = (counts[it] ?: 0) + 1; it.children.size },
            { children[it] = (children[it] ?: 0) + 1; it.children }, { retained += it },
            { descriptions[it] = (descriptions[it] ?: 0) + 1; it.node })
        assertEquals(32, snapshot.entries.size)
        assertEquals(32, counts.size); assertEquals(32, descriptions.size)
        assertTrue(counts.values.all { it == 1 }); assertTrue(descriptions.values.all { it == 1 }); assertTrue(children.values.all { it == 1 })
        assertEquals(walk(root).drop(1).toSet(), retained.toSet()); assertEquals(31, retained.size)
        assertEquals(valid, NotificationExpansionEvidence.after(snapshot))
        root.children.add(0, Tree(node("over_budget")))
        val over = runCatching { contentSnapshot(root) }
        if (over.isSuccess) {
            assertTrue(over.getOrThrow().truncated)
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(over.getOrThrow()) }.exceptionOrNull()?.message)
        } else assertTrue(over.exceptionOrNull() is IllegalStateException)
    }
    @Test fun malformedOrOversizedReturnedListsRetainAllHandlesBeforeFailingWithoutASecondWalk() {
        for (state in listOf("OVERSIZED", "CHANGED", "ALIAS")) {
            val root = contentTree(); val returned = when (state) {
                "OVERSIZED" -> List(33) { Tree(node("extra")) }
                "CHANGED" -> listOf(root.children.single(), Tree(node("extra")))
                else -> listOf(root.children.single(), root)
            }
            val retained = mutableListOf<Tree>(); var childCalls = 0; var describeCalls = 0
            val result = runCatching { NotificationIdentityEvidence.captureModalContentPublic(root, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
                { if (it === root) if (state == "CHANGED") 1 else 2 else it.children.size },
                { childCalls++; if (it === root) returned else it.children }, { retained += it }, { describeCalls++; it.node }) }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(returned, retained); assertEquals(1, childCalls); assertEquals(1, describeCalls)
        }
    }
    @Test fun repeatedWrapperResourcesInANestedSideBranchCannotBecomeAValidScope() {
        for (id in listOf("expanded", "status_bar_latest_event_content")) {
            val root = contentTree(); root.children += Tree(node("side"), mutableListOf(Tree(node(id, "android.widget.FrameLayout"))))
            val snapshot = contentSnapshot(root)
            assertFalse(snapshot.validContentScope())
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot) }.exceptionOrNull()?.message)
        }
    }
    @Test fun removedWrappersMustHaveNoEqualityOrResourceCandidate() {
        val variants: List<(NotificationIdentityEvidence.Node) -> NotificationIdentityEvidence.Node> = listOf(
            { it.copy(titleMatches = true) }, { it.copy(textEqualsAppLabel = true) },
            { it.copy(descriptionEqualsAppLabel = true) }, { it.copy(confirmActionMatches = true) },
            { it.copy(stopActionMatches = true) }, { it.copy(resourceName = "$system:id/title") },
            { it.copy(resourceName = "android:id/title") },
            { it.copy(resourceName = "$system:id/app_name_text") }, { it.copy(resourceName = "$system:id/app_icon") })
        for (id in listOf("unresolved_row", "expanded")) for (change in variants) {
            val root = contentTree(); val target = entry(root, id); target.node = change(target.node)
            assertTrue("id=$id", runCatching { contentSnapshot(root) }.exceptionOrNull() is NotificationIdentityEvidence.ModalContentFailure)
        }
    }
    @Test fun prefixAndContentMetadataRemainStrictAndMissingChildrenFailClosed() {
        for (id in listOf("unresolved_row", "expanded", "status_bar_latest_event_content")) for (change in 0..5) {
            val root = contentTree(); val target = entry(root, id)
            target.node = when (change) {
                0 -> target.node.copy(packageName = "other.app")
                1 -> target.node.copy(className = "android.view.View")
                2 -> target.node.copy(enabled = false)
                3 -> target.node.copy(clickable = !target.node.clickable)
                4 -> target.node.copy(scrollable = true)
                else -> if (id == "unresolved_row") target.node.copy(titleMatches = true) else target.node.copy(longClickable = true)
            }
            assertTrue("id=$id change=$change", runCatching { contentSnapshot(root) }.exceptionOrNull() is NotificationIdentityEvidence.ModalContentFailure)
        }
        for (atRow in listOf(true, false)) {
            val root = contentTree(); (if (atRow) root else entry(root, "expanded")).children.clear()
            assertTrue(runCatching { contentSnapshot(root) }.exceptionOrNull() is NotificationIdentityEvidence.ModalContentFailure)
        }
        assertTrue(runCatching { NotificationIdentityEvidence.captureModalContentPublic(contentTree(),
            NotificationIdentityEvidence.Phase.CURRENT, { it.children.size }, { it.children }, {}, { it.node }) }
            .exceptionOrNull() is NotificationIdentityEvidence.ModalContentFailure)
    }
    private fun prefixFailure(root: Tree, phase: NotificationIdentityEvidence.Phase = NotificationIdentityEvidence.Phase.AFTER_EXPAND):
        Pair<NotificationIdentityEvidence.ModalContentReason, List<String>> {
        val calls = mutableListOf<String>()
        val expanded = root.children.firstOrNull()
        val content = expanded?.children?.firstOrNull()
        fun site(value: Tree) = when {
            value === root -> "ROW"
            value === expanded -> "EXPANDED"
            value === content -> "CONTENT"
            else -> "OTHER"
        }
        val error = runCatching { NotificationIdentityEvidence.captureModalContentPublic(root, phase,
            { calls += "count:${site(it)}"; it.children.size },
            { calls += "children:${site(it)}"; it.children },
            { calls += "retain:${site(it)}" }, { calls += "describe:${site(it)}"; it.node }) }.exceptionOrNull()
        assertTrue(error is NotificationIdentityEvidence.ModalContentFailure)
        return (error as NotificationIdentityEvidence.ModalContentFailure).code to calls
    }
    private val prefixReads get() = listOf("describe:ROW", "count:ROW", "children:ROW", "retain:EXPANDED", "describe:EXPANDED",
        "count:EXPANDED", "children:EXPANDED", "retain:CONTENT", "describe:CONTENT")

    @Test fun everyCachedPrefixConditionReportsExactlyItsFirstFailureWithoutExtraReads() {
        val rowFields = listOf("PACKAGE", "ENABLED", "CLICKABLE", "SCROLLABLE", "CLASS", "CANDIDATE")
        val frameFields = listOf("RESOURCE", "PACKAGE", "CLASS", "ENABLED", "CLICKABLE", "LONG_CLICKABLE", "SCROLLABLE")
        fun changed(node: NotificationIdentityEvidence.Node, field: String, row: Boolean) = when (field) {
            "RESOURCE" -> node.copy(resourceName = "$system:id/wrong")
            "PACKAGE" -> node.copy(packageName = "other.app")
            "CLASS" -> node.copy(className = "android.view.View")
            "ENABLED" -> node.copy(enabled = false)
            "CLICKABLE" -> node.copy(clickable = !row)
            "LONG_CLICKABLE" -> node.copy(longClickable = true)
            "SCROLLABLE" -> node.copy(scrollable = true)
            "CANDIDATE" -> node.copy(titleMatches = true)
            else -> error("unexpected fixture field")
        }
        val codes = mutableSetOf<String>()
        for ((site, id) in listOf("ROW" to "unresolved_row", "EXPANDED" to "expanded", "CONTENT" to "status_bar_latest_event_content")) {
            val fields = if (site == "ROW") rowFields else frameFields + if (site == "EXPANDED") listOf("CANDIDATE") else emptyList()
            for (index in fields.indices) for (invalidateLater in listOf(false, true)) {
                val root = contentTree(); val target = entry(root, id)
                for (field in if (invalidateLater) fields.drop(index) else listOf(fields[index])) {
                    target.node = changed(target.node, field, site == "ROW")
                }
                val suffix = if (fields[index] == "CANDIDATE") "CANDIDATE_PRESENT" else "${fields[index]}_INVALID"
                val expected = if (fields[index] == "RESOURCE") {
                    if (site == "EXPANDED") "EXPANSION_MODAL_PREFIX_ROW_EXPANDED_MISSING" else "EXPANSION_MODAL_PREFIX_EXPANDED_CONTENT_MISSING"
                } else "EXPANSION_MODAL_PREFIX_${site}_$suffix"
                val (code, calls) = prefixFailure(root)
                assertEquals(expected, code.name)
                assertEquals(prefixReads.take(when (site) { "ROW" -> 1; "EXPANDED" -> 5; else -> 9 }), calls)
                codes += code.name
            }
        }
        assertEquals(21, codes.size)
    }
    @Test fun prefixResourceCountsDistinguishEmptyMissingAndAmbiguousBeforeMetadataFiltering() {
        for (atRow in listOf(true, false)) for (state in listOf("EMPTY", "MISSING", "AMBIGUOUS")) {
            val root = contentTree(); val parent = if (atRow) root else entry(root, "expanded")
            when (state) {
                "EMPTY" -> parent.children.clear()
                "MISSING" -> parent.children.single().node = node("wrong")
                else -> parent.children += Tree(parent.children.single().node.copy(enabled = false, className = "android.view.View"))
            }
            val (code, calls) = prefixFailure(root)
            val expected = if (state == "EMPTY") "EXPANSION_MODAL_PREFIX_${if (atRow) "ROW" else "EXPANDED"}_CHILDREN_EMPTY"
                else "EXPANSION_MODAL_PREFIX_${if (atRow) "ROW_EXPANDED" else "EXPANDED_CONTENT"}_$state"
            assertEquals(expected, code.name)
            assertEquals(if (atRow) 1 else 2, calls.count { it.startsWith("count:") })
            assertEquals(if (state == "EMPTY") 0 else if (state == "MISSING") 1 else 2,
                calls.drop(if (atRow) 1 else 5).count { it.startsWith("retain:") })
            assertEquals(if (atRow) "ROW" else "EXPANDED", calls.last { it.startsWith("count:") }.substringAfter(':'))
        }
    }
    @Test fun wrongPhaseReportsFixedReasonBeforeAnyPlatformCallbackAndFormerUnrestrictedStatesRemainAccepted() {
        val (code, calls) = prefixFailure(contentTree(), NotificationIdentityEvidence.Phase.CURRENT)
        assertEquals(NotificationIdentityEvidence.ModalContentReason.EXPANSION_MODAL_PREFIX_PHASE_INVALID, code)
        assertTrue(calls.isEmpty())
        assertEquals(30, NotificationIdentityEvidence.ModalContentReason.entries.size)
        val root = contentTree()
        root.node = root.node.copy(resourceName = "$system:id/other_row", longClickable = false)
        entry(root, "status_bar_latest_event_content").node = entry(root, "status_bar_latest_event_content").node.copy(titleMatches = true)
        assertFalse(contentSnapshot(root).truncated)
    }
    @Test fun contentSerializerRevalidatesIdentityMarginAndEveryActionOverTheCompleteDomain() {
        for (change in 0..8) {
            val root = contentTree(); val event = entry(root, "status_bar_latest_event_content")
            val confirm = walk(root).single { it.node.confirmActionMatches }
            when (change) {
                0 -> event.children.remove(confirm)
                1 -> event.children += Tree(confirm.node)
                2 -> confirm.node = confirm.node.copy(enabled = false)
                3 -> confirm.node = confirm.node.copy(clickable = false)
                4 -> confirm.node = confirm.node.copy(packageName = "other.app")
                5 -> entry(root, "notification_action_list_margin_target").node = node("wrong")
                6 -> entry(root, "app_icon").node = entry(root, "app_icon").node.copy(descriptionEqualsAppLabel = false)
                7 -> event.children += Tree(node("app_name_text", "android.widget.TextView").copy(textEqualsAppLabel = false))
                8 -> entry(root, "notification_main_column").children.clear()
            }
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(contentSnapshot(root)) }.exceptionOrNull()?.message)
        }
    }
    @Test fun serializerRejectsForgedScopePathsMissingAncestorsAndUnmarkedModal() {
        val source = contentSnapshot(contentTree())
        val bad = listOf(
            scopeCopy(source, rootPath = "0/0"), scopeCopy(source, modal = false),
            scopeCopy(source, entries = source.entries.drop(1)),
            scopeCopy(source, entries = listOf(source.entries[0], source.entries[2], source.entries[1]) + source.entries.drop(3)),
            scopeCopy(source, entries = source.entries.map { if (it.path == "0/0") it.copy(depth = 0) else it }),
            scopeCopy(source, entries = source.entries + NotificationIdentityEvidence.Entry("0/2", 1, node("extra"))),
            scopeCopy(source, entries = source.entries.map { if (it.path == "0") it.copy(node = it.node.copy(confirmActionMatches = true)) else it }))
        for (snapshot in bad) {
            assertFalse(snapshot.validContentScope())
            assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot) }.exceptionOrNull()?.message)
        }
        assertEquals("IDENTITY_PHASES_INVALID", runCatching {
            NotificationIdentityEvidence.json(receipt, 1, NotificationIdentityEvidence.ExpandStatus.CLICKED, listOf(before(), source))
        }.exceptionOrNull()?.message)
    }

    @Test fun truncatedModalSnapshotsNeverBecomeExpansionProofs() {
        val root = tree(modal = true)
        repeat(40) { root.children += Tree(node("extra")) }
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, post(root))
        assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching { serialize(snapshot(root, modal = true)) }.exceptionOrNull()?.message)
    }
}
