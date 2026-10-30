package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test

class NotificationCaptureBudgetTest {
    private val system = "com.android.systemui"
    private val receipt = "321b21cd-4137-4e24-a14a-56d6a94b94f1"
    private class Tree(val children: MutableList<Tree> = mutableListOf(), var reported: Int? = null,
        val title: Boolean = false)
    private class Calls {
        val described = mutableListOf<Tree>()
        val counted = mutableListOf<Tree>()
        val listed = mutableListOf<Tree>()
        val retained = mutableListOf<Tree>()
    }
    private fun describe(tree: Tree) = NotificationIdentityEvidence.Node(
        "$system:id/${if (tree.title) "title" else "fixture"}", "android.widget.FrameLayout", system,
        true, true, false, titleMatches = tree.title)
    private fun capture(root: Tree, calls: Calls = Calls(), after: Boolean = false) =
        NotificationIdentityEvidence.capturePublic(root,
            if (after) NotificationIdentityEvidence.Phase.AFTER_EXPAND else NotificationIdentityEvidence.Phase.CURRENT,
            { calls.counted += it; it.reported ?: it.children.size },
            { calls.listed += it; it.children }, { calls.retained += it },
            { calls.described += it; describe(it) })
    private fun generic(root: Tree) = NotificationIdentityEvidence.capture(root, NotificationIdentityEvidence.Phase.CURRENT,
        { it.children.size }, { n, index -> n.children[index] }, ::describe)
    private fun chain(levels: Int): Tree = Tree().also { root ->
        var current = root
        repeat(levels - 1) { Tree().also { current.children += it; current = it } }
    }
    private fun last(root: Tree): Tree = if (root.children.isEmpty()) root else last(root.children.single())
    private fun wide32(withExtraChild: Boolean = false) = Tree(MutableList(31) { Tree() }).also {
        if (withExtraChild) it.children.last().children += Tree()
    }
    private fun metrics(snapshot: NotificationIdentityEvidence.Snapshot<Tree>) = assertNotNull(snapshot.captureBudget).let { snapshot.captureBudget!! }
    private fun report(snapshot: NotificationIdentityEvidence.Snapshot<Tree>, site: NotificationCaptureBudget.Site) =
        NotificationCaptureBudget.report(site, NotificationExpansionEvidence.before(snapshot), snapshot.captureBudget)

    @Test fun exactEightLevelsWithLeafIsCompleteAndHasNoCut() {
        val root = chain(8)
        val calls = Calls()
        val snapshot = capture(root, calls)
        assertFalse(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.NONE, 8, 7, -1, -1, -1), metrics(snapshot))
        assertEquals(8, calls.described.size); assertEquals(8, calls.counted.size)
        assertEquals(7, calls.listed.size); assertEquals(7, calls.retained.size)
        assertEquals(NotificationCaptureBudget.NONE, report(snapshot, NotificationCaptureBudget.Site.INITIAL_CURRENT))
    }
    @Test fun childCountAtDepthSevenRecordsDepthWithoutListingOrVisitingTheChild() {
        val root = chain(9)
        val unseen = last(root)
        val calls = Calls()
        val snapshot = capture(root, calls)
        assertTrue(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.DEPTH_LIMIT, 8, 7, 0, 8, 7), metrics(snapshot))
        assertFalse(calls.described.contains(unseen)); assertFalse(calls.counted.contains(unseen))
        assertEquals(7, calls.listed.size); assertEquals(8, calls.described.size)
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, NotificationExpansionEvidence.before(snapshot))
    }
    @Test fun exactThirtyTwoLeafNodesAreCompleteWithoutGuessingCutFromCount() {
        val snapshot = capture(wide32())
        assertFalse(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.NONE, 32, 1, -1, -1, -1), metrics(snapshot))
    }
    @Test fun thirtySecondVisitedNodeWithChildCountRecordsNodeCutWithoutAnotherList() {
        val root = wide32(withExtraChild = true)
        val calls = Calls()
        val snapshot = capture(root, calls)
        assertTrue(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.NODE_LIMIT, 32, 1, 0, 32, 1), metrics(snapshot))
        assertEquals(listOf(root), calls.listed)
        assertEquals(32, calls.described.size); assertEquals(32, calls.counted.size)
        assertEquals(31, calls.retained.size)
    }
    @Test fun depthBranchWinsWhenLastVisitedNodeAlsoReachesNodeCapacity() {
        val deep = chain(7)
        last(deep).children += Tree()
        val root = Tree((MutableList(24) { Tree() } + deep).toMutableList())
        val snapshot = capture(root)
        assertTrue(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.DEPTH_LIMIT, 32, 7, 0, 32, 7), metrics(snapshot))
    }
    @Test fun bothActualCutBranchesAccumulateButFirstCutKeepsItsPendingReservation() {
        val deep = chain(7)
        last(deep).children += Tree()
        val siblings = MutableList(24) { Tree() }
        siblings.last().children += Tree()
        val root = Tree((listOf(deep) + siblings).toMutableList())
        val snapshot = capture(root)
        assertTrue(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.BOTH, 32, 7, 24, 8, 7), metrics(snapshot))
    }
    @Test fun multipleDepthCutsKeepTheFirstCutCountersRatherThanFinalCounters() {
        val root = Tree(MutableList(2) { chain(8) })
        val snapshot = capture(root)
        assertTrue(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.DEPTH_LIMIT, 15, 7, 1, 8, 7), metrics(snapshot))
    }
    @Test fun widthAdmissionFailureStillThrowsOriginalFixedExceptionBeforeChildrenQuery() {
        val root = Tree(MutableList(32) { Tree() })
        val calls = Calls()
        assertEquals("IDENTITY_CHILDREN_LIMIT", runCatching { capture(root, calls) }.exceptionOrNull()?.message)
        assertEquals(listOf(root), calls.described); assertTrue(calls.listed.isEmpty())
        assertEquals(NotificationCaptureBudget.NONE, NotificationCaptureBudget.report(
            NotificationCaptureBudget.Site.INITIAL_CURRENT, NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, null))
    }
    @Test fun alreadyPendingSiblingsStillReserveWidthAndDoNotBecomeTruncated() {
        val branch = Tree(mutableListOf(Tree(), Tree()))
        val root = Tree((listOf(branch) + MutableList(29) { Tree() }).toMutableList())
        val calls = Calls()
        assertEquals("IDENTITY_CHILDREN_LIMIT", runCatching { capture(root, calls) }.exceptionOrNull()?.message)
        assertEquals(listOf(root, branch), calls.described); assertEquals(listOf(root), calls.listed)
    }
    @Test fun rawChildCountAtBoundaryDoesNotClaimAnotherVisibleChildExists() {
        val deep = chain(8)
        last(deep).reported = 1
        val depthCalls = Calls()
        assertEquals(NotificationCaptureBudget.Cause.DEPTH_LIMIT, metrics(capture(deep, depthCalls)).budgetCause)
        assertFalse(depthCalls.listed.contains(last(deep)))
        val wide = wide32()
        wide.children.last().reported = 1
        val nodeCalls = Calls()
        assertEquals(NotificationCaptureBudget.Cause.NODE_LIMIT, metrics(capture(wide, nodeCalls)).budgetCause)
        assertFalse(nodeCalls.listed.contains(wide.children.last()))
        val shallow = Tree(reported = 1)
        assertFalse(capture(shallow).truncated)
    }
    @Test fun everyCaptureSiteStaysDistinctAndOtherDecisionsPublishOnlySentinels() {
        val snapshot = capture(chain(9))
        for (site in NotificationCaptureBudget.Site.entries.filter { it != NotificationCaptureBudget.Site.NONE }) {
            assertEquals(site, report(snapshot, site).captureSite)
            assertSame(snapshot.captureBudget, report(snapshot, site).summary)
            for (code in NotificationExpansionEvidence.Code.entries.filter { it != NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED }) {
                assertEquals(NotificationCaptureBudget.NONE, NotificationCaptureBudget.report(site, code, snapshot.captureBudget))
            }
        }
        assertEquals(NotificationCaptureBudget.NONE, report(snapshot, NotificationCaptureBudget.Site.NONE))
        assertEquals(NotificationCaptureBudget.NONE, NotificationCaptureBudget.report(NotificationCaptureBudget.Site.REBIND_CURRENT,
            NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, metrics(capture(chain(8)))))
        with(NotificationCaptureBudget.NONE) {
            assertEquals(NotificationCaptureBudget.Site.NONE, captureSite)
            assertEquals(NotificationCaptureBudget.Cause.NONE, summary.budgetCause)
            assertEquals(listOf(-1, -1, -1, -1, -1), listOf(summary.visitedNodes, summary.maxVisitedDepth,
                summary.pendingAtFirstCut, summary.firstCutVisitedNodes, summary.firstCutDepth))
        }
    }
    @Test fun snapshotMetadataIsImmutableAndModalCopyRetainsItWithoutChangingDecision() {
        val snapshot = capture(chain(9), after = true)
        val modal = snapshot.withModalBinding()
        assertSame(snapshot.captureBudget, modal.captureBudget)
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, NotificationExpansionEvidence.after(snapshot))
        assertEquals(NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED, NotificationExpansionEvidence.after(modal))
        val recorder = NotificationCaptureBudget.Recorder { 0 }
        recorder.visited(0)
        val first = recorder.snapshot()
        recorder.cut(NotificationCaptureBudget.Limit.NODE_LIMIT, 0)
        assertEquals(NotificationCaptureBudget.Cause.NONE, first.budgetCause)
        assertEquals(NotificationCaptureBudget.Cause.NODE_LIMIT, recorder.snapshot().budgetCause)
    }
    private fun modalContent(content: Tree, calls: Calls = Calls()): NotificationIdentityEvidence.Snapshot<Tree> {
        val expanded = Tree(mutableListOf(content)); val root = Tree(mutableListOf(expanded))
        return NotificationIdentityEvidence.captureModalContentPublic(root, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
            { calls.counted += it; it.reported ?: it.children.size },
            { calls.listed += it; it.children }, { calls.retained += it }, { value ->
                calls.described += value
                val id = when (value) { root -> "unresolved_row"; expanded -> "expanded"; content -> "status_bar_latest_event_content"; else -> "fixture" }
                NotificationIdentityEvidence.Node("$system:id/$id", "android.widget.FrameLayout", system,
                    true, value === root, false)
            })
    }
    @Test fun modalContentEightLevelsRetainRealDepthAndDescribeEveryPrefixOnlyOnce() {
        val calls = Calls(); val snapshot = modalContent(chain(8), calls)
        assertFalse(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.NONE, 10, 9, -1, -1, -1), metrics(snapshot))
        assertEquals(10, calls.described.size); assertEquals(10, calls.described.distinct().size)
        assertEquals(10, calls.counted.size); assertEquals(10, calls.counted.distinct().size)
        assertEquals(9, calls.listed.size); assertEquals(9, calls.listed.distinct().size)
        assertEquals(9, calls.retained.size); assertEquals(9, calls.retained.distinct().size)
        assertEquals("0/0/0", snapshot.captureRootPath)
        assertEquals(snapshot.captureRootPath, snapshot.withModalBinding().captureRootPath)
        assertTrue(snapshot.validContentScope())
    }
    @Test fun modalNinthContentLevelCutsAtRealDepthNineWithoutReadingTheUnseenChild() {
        val content = chain(9); val unseen = last(content); val calls = Calls()
        val snapshot = modalContent(content, calls)
        assertTrue(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.DEPTH_LIMIT, 10, 9, 0, 10, 9), metrics(snapshot))
        assertFalse(calls.described.contains(unseen)); assertFalse(calls.counted.contains(unseen))
        assertEquals(9, calls.listed.size)
        val report = NotificationCaptureBudget.report(NotificationCaptureBudget.Site.AFTER_EXPAND,
            NotificationExpansionEvidence.after(snapshot), snapshot.captureBudget)
        assertEquals(NotificationCaptureBudget.Site.AFTER_EXPAND, report.captureSite)
        assertEquals(9, report.summary.firstCutDepth)
    }
    @Test fun modalNodeBudgetIncludesTwoAncestorsAndDoesNotOpenANewThirtyTwoNodePool() {
        val content = Tree(MutableList(29) { Tree() }); val calls = Calls()
        val snapshot = modalContent(content, calls)
        assertFalse(snapshot.truncated)
        assertEquals(32, snapshot.entries.size); assertEquals(32, calls.described.size)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.NONE, 32, 3, -1, -1, -1), metrics(snapshot))
        content.children.last().children += Tree()
        val cut = modalContent(content)
        assertTrue(cut.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.NODE_LIMIT, 32, 3, 0, 32, 3), metrics(cut))
        val tooWide = Calls()
        assertEquals("IDENTITY_CHILDREN_LIMIT", runCatching { modalContent(Tree(MutableList(30) { Tree() }), tooWide) }.exceptionOrNull()?.message)
        assertEquals(3, tooWide.described.size); assertEquals(2, tooWide.listed.size)
    }
    @Test fun modalPendingReservationsAndBothCutBranchesKeepRealFirstCutCounters() {
        val deep = chain(7); last(deep).children += Tree()
        val siblings = MutableList(22) { Tree() }; siblings.last().children += Tree()
        val snapshot = modalContent(Tree((listOf(deep) + siblings).toMutableList()))
        assertTrue(snapshot.truncated)
        assertEquals(NotificationCaptureBudget.Summary(NotificationCaptureBudget.Cause.BOTH, 32, 9, 22, 10, 9), metrics(snapshot))
        val wideBranch = Tree(mutableListOf(Tree(), Tree()))
        val calls = Calls()
        assertEquals("IDENTITY_CHILDREN_LIMIT", runCatching {
            modalContent(Tree((listOf(wideBranch) + MutableList(27) { Tree() }).toMutableList()), calls)
        }.exceptionOrNull()?.message)
        assertEquals(4, calls.described.size)
        assertFalse(calls.listed.contains(wideBranch))
    }

    @Test fun metadataDoesNotEnterAnyJsonAndCannotRelaxExpansionProofs() {
        val root = Tree(mutableListOf(Tree(title = true)))
        val observed = capture(root)
        val original = generic(root)
        assertNotNull(observed.captureBudget); assertNull(original.captureBudget)
        assertArrayEquals(NotificationIdentityEvidence.json(receipt, 1, NotificationIdentityEvidence.ExpandStatus.MISSING, listOf(original)),
            NotificationIdentityEvidence.json(receipt, 1, NotificationIdentityEvidence.ExpandStatus.MISSING, listOf(observed)))
        val truncated = capture(Tree(mutableListOf(Tree(title = true), chain(8))))
        val json = NotificationIdentityEvidence.json(receipt, 1, NotificationIdentityEvidence.ExpandStatus.BOUNDED_INCOMPLETE,
            listOf(truncated)).toString(Charsets.UTF_8)
        for (name in listOf("captureSite", "budgetCause", "visitedNodes", "maxVisitedDepth", "pendingAtFirstCut",
            "firstCutVisitedNodes", "firstCutDepth")) assertFalse(json.contains(name))
        val after = capture(chain(9), after = true).withModalBinding()
        assertEquals("IDENTITY_EXPANSION_PROOFS_INVALID", runCatching {
            NotificationIdentityEvidence.expansionJson(receipt, 1, listOf(observed, after), true, longPress = true)
        }.exceptionOrNull()?.message)
    }
}
