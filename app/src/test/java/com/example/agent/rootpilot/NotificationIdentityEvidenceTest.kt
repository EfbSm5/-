package com.example.agent.rootpilot

import org.junit.Assert.*
import org.junit.Test

class NotificationIdentityEvidenceTest {
    private val receipt = "321b21cd-4137-4e24-a14a-56d6a94b94f1"
    private val system = "com.android.systemui"
    private fun node(title: Boolean = true, resource: String? = "$system:id/title", clazz: String? = "android.widget.TextView",
        pkg: String? = system) = NotificationIdentityEvidence.Node(resource, clazz, pkg, true, false, false,
        textEqualsAppLabel = true, descriptionEqualsAppLabel = false, textPresent = true, titleMatches = title)
    private fun single(value: NotificationIdentityEvidence.Node = node(), phase: NotificationIdentityEvidence.Phase = NotificationIdentityEvidence.Phase.CURRENT) =
        NotificationIdentityEvidence.capture(0, phase, { 0 }, { _, _ -> error("NO_CHILD") }, { value })
    private fun json(value: NotificationIdentityEvidence.Node = node()) = NotificationIdentityEvidence.json(receipt, 123,
        NotificationIdentityEvidence.ExpandStatus.MISSING, listOf(single(value))).toString(Charsets.UTF_8)

    @Test fun titleIsBoundToOnlyOneCanonicalReceiptWithoutSerializingItAsText() {
        val title = NotificationIdentityEvidence.title(receipt)
        assertEquals("RootPilot · 第 1 步 · $receipt", title)
        assertNotEquals(title, NotificationIdentityEvidence.title("421b21cd-4137-4e24-a14a-56d6a94b94f1"))
        assertFalse(json().contains(title))
        for (invalid in listOf(receipt.uppercase(), "private title", "../path")) {
            assertEquals("IDENTITY_IDENTITY_INVALID", runCatching { NotificationIdentityEvidence.title(invalid) }.exceptionOrNull()?.message)
        }
    }
    @Test fun fixedSchemaAndBooleanOnlyTextEvidence() {
        val value = json()
        assertTrue(value.contains("\"onlyDiagnosticNotAcceptance\":true"))
        assertTrue(value.contains("\"identityProofs\":{\"ownNotificationVerified\":true"))
        assertTrue(value.contains("\"path\":\"0\",\"depth\":0"))
        assertTrue(value.contains("\"textEqualsAppLabel\":true"))
        assertTrue(value.contains("\"descriptionEqualsAppLabel\":false"))
        assertFalse(value.contains("\"text\":"))
        assertFalse(value.contains("\"description\":"))
    }
    @Test fun unknownMetadataAndPrivateStringsAreRedacted() {
        for (secret in listOf("private\"message", "private\nmessage", "https://private/path", "x".repeat(129), "")) {
            val result = json(node(resource = secret, clazz = secret, pkg = secret))
            assertEquals(3, Regex("REDACTED").findAll(result).count())
            if (secret.isNotEmpty()) assertFalse(result.contains(secret))
        }
    }
    @Test fun nullMetadataIsNotAnUnknownIdGuess() {
        assertEquals(3, Regex("REDACTED").findAll(json(node(resource = null, clazz = null, pkg = null))).count())
    }
    @Test fun depthStopsAtEightLevelsWithoutRequestingNinth() {
        var described = 0
        var requested = 0
        val snapshot = NotificationIdentityEvidence.capture(0, NotificationIdentityEvidence.Phase.CURRENT, { 1 }, { n, _ ->
            requested++; check(n < 7); n + 1
        }) { n -> described++; node(title = n == 0) }
        assertEquals(8, described); assertEquals(7, requested); assertTrue(snapshot.truncated)
    }
    @Test fun nodeLimitIsAppliedBeforeChildReadNotAfterCollection() {
        var described = 0
        var requested = 0
        val snapshot = NotificationIdentityEvidence.capture(-1, NotificationIdentityEvidence.Phase.CURRENT,
            { if (it == -1) Int.MAX_VALUE else 0 }, { _, i -> requested++; check(i < 31); i }) {
            described++; node(title = it == -1)
        }
        assertEquals(32, described); assertEquals(31, requested); assertTrue(snapshot.truncated)
    }
    @Test fun exactThirtyTwoNodesAreCompleteNotTruncated() {
        val snapshot = NotificationIdentityEvidence.capture(-1, NotificationIdentityEvidence.Phase.CURRENT,
            { if (it == -1) 31 else 0 }, { _, i -> i }, { node(title = it == -1) })
        assertFalse(snapshot.truncated)
    }
    @Test fun rootOnlyTraversalCannotReadParentSiblingsOrAnotherNotification() {
        val requested = mutableListOf<Pair<Int, Int>>()
        val snapshot = NotificationIdentityEvidence.capture(7, NotificationIdentityEvidence.Phase.CURRENT,
            { if (it == 7) 1 else 0 }, { n, i -> requested += n to i; check(n == 7 && i == 0); 8 }, { node(title = it == 8) })
        val output = NotificationIdentityEvidence.json(receipt, 123, NotificationIdentityEvidence.ExpandStatus.MISSING,
            listOf(snapshot)).toString(Charsets.UTF_8)
        assertEquals(listOf(7 to 0), requested)
        assertTrue(output.contains("\"path\":\"0/0\",\"depth\":1"))
    }
    @Test fun missingChildAndNegativeCountAreFixedFailures() {
        assertEquals("IDENTITY_CHILD_MISSING", runCatching {
            NotificationIdentityEvidence.capture(0, NotificationIdentityEvidence.Phase.CURRENT, { 1 }, { _, _ -> null as Int? }, { node() })
        }.exceptionOrNull()?.message)
        assertEquals("IDENTITY_CHILDREN_INVALID", runCatching {
            NotificationIdentityEvidence.capture(0, NotificationIdentityEvidence.Phase.CURRENT, { -1 }, { _, _ -> 1 }, { node() })
        }.exceptionOrNull()?.message)
    }
    @Test fun onlySystemUiExpandButtonCanBeAnExpansionCandidate() {
        for ((resource, pkg, expected) in listOf(Triple("$system:id/expand_button", system, 1),
            Triple("android:id/expand_button", system, 0), Triple("$system:id/expand_button", "private.app", 0),
            Triple("$system:id/action0", system, 0))) {
            assertEquals(expected, single(node(resource = resource, pkg = pkg)).expandNodes.size)
        }
    }
    @Test fun invalidReceiptOrPidCannotProduceArtifact() {
        for ((id, pid) in listOf(receipt.uppercase() to 123, "../private" to 123, receipt to 0, receipt to -1)) {
            assertEquals("IDENTITY_IDENTITY_INVALID", runCatching {
                NotificationIdentityEvidence.json(id, pid, NotificationIdentityEvidence.ExpandStatus.MISSING, listOf(single()))
            }.exceptionOrNull()?.message)
        }
    }
    @Test fun twoPhasesOnlyWhenExactlyOneExpandClickWasRecorded() {
        val phases = listOf(single(), single(phase = NotificationIdentityEvidence.Phase.AFTER_EXPAND))
        val bytes = NotificationIdentityEvidence.json(receipt, 123, NotificationIdentityEvidence.ExpandStatus.CLICKED, phases)
        assertTrue(bytes.size <= 32768)
        assertEquals("IDENTITY_PHASES_INVALID", runCatching {
            NotificationIdentityEvidence.json(receipt, 123, NotificationIdentityEvidence.ExpandStatus.MISSING, phases)
        }.exceptionOrNull()?.message)
        assertEquals("IDENTITY_PHASES_INVALID", runCatching {
            NotificationIdentityEvidence.json(receipt, 123, NotificationIdentityEvidence.ExpandStatus.CLICKED, listOf(single()))
        }.exceptionOrNull()?.message)
    }
    @Test fun absentOrDuplicateRowTitleIsNotSuccessfulIdentityEvidence() {
        val absent = single(node(title = false))
        val duplicate = NotificationIdentityEvidence.capture(0, NotificationIdentityEvidence.Phase.CURRENT,
            { if (it == 0) 1 else 0 }, { _, _ -> 1 }, { node() })
        for (snapshot in listOf(absent, duplicate)) assertEquals("IDENTITY_PHASES_INVALID", runCatching {
            NotificationIdentityEvidence.json(receipt, 123, NotificationIdentityEvidence.ExpandStatus.MISSING, listOf(snapshot))
        }.exceptionOrNull()?.message)
    }
    private data class Ref(val id: Int)
    private fun bindingFailure(expected: NotificationIdentityEvidence.BindingFailureCode, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is NotificationIdentityEvidence.BindingFailure)
        assertEquals(expected, (error as NotificationIdentityEvidence.BindingFailure).code)
        assertNull(error.message)
    }
    @Test fun windowLimitRetainsWholeListBeforeRefusingAnyRootRead() {
        val released = mutableListOf<Ref>()
        val windows = List(9) { Ref(it) }
        NotificationIdentityEvidence.Handles<Ref> { released += it }.use { handles ->
            bindingFailure(NotificationIdentityEvidence.BindingFailureCode.WINDOW_LIMIT) {
                NotificationIdentityEvidence.windowRoots(windows, { handles.retain(it) },
                    { error("ACTIVE_ROOT_MUST_NOT_BE_READ") }, { error("WINDOW_ROOT_MUST_NOT_BE_READ") }, { _: Ref -> error("NO_NODE") })
            }
        }
        assertEquals(windows, released)
    }
    @Test fun eightWindowsAndActiveAreReadOnceAndAdmitNineRoots() {
        var activeReads = 0
        val windowReads = mutableListOf<Int>()
        val retained = mutableListOf<Ref>()
        val roots = NotificationIdentityEvidence.windowRoots(List(8) { it }, {},
            { activeReads++; Ref(8) }, { windowReads += it; Ref(it) }, { retained += it })
        assertEquals(1, activeReads); assertEquals((0..7).toList(), windowReads)
        assertEquals(9, roots.size); assertEquals(9, retained.size)
    }
    @Test fun absentActiveRootStillUsesWindowRootWithoutFallback() {
        val roots = NotificationIdentityEvidence.windowRoots(listOf(1), {}, { null as Ref? }, { Ref(it) }, {})
        assertEquals(listOf(Ref(1)), roots)
        assertEquals(Ref(7), NotificationIdentityEvidence.uniqueTitle(roots, { listOf(Ref(7)) }, {}, { true }))
    }
    @Test fun equalActiveAndWindowRootsAreQueriedOnceButBothWrappersReleased() {
        val active = Ref(1); val window = Ref(1)
        val released = mutableListOf<Ref>()
        var queries = 0
        NotificationIdentityEvidence.Handles<Ref> { released += it }.use { handles ->
            val roots = NotificationIdentityEvidence.windowRoots(listOf(0), {}, { active }, { window }, { handles.retain(it) })
            assertEquals(1, roots.size)
            NotificationIdentityEvidence.uniqueTitle(roots, { queries++; listOf(Ref(7)) }, { handles.retain(it) }, { true })
        }
        assertEquals(1, queries); assertEquals(3, released.size)
        assertSame(active, released[0]); assertSame(window, released[1])
    }
    @Test fun sameTitleNodeReturnedByDistinctRootsIsOneMatchNotTwo() {
        val first = Ref(7); val second = Ref(7)
        val retained = mutableListOf<Ref>()
        val selected = NotificationIdentityEvidence.uniqueTitle(listOf(Ref(1), Ref(2)),
            { if (it.id == 1) listOf(first) else listOf(second) }, { retained += it }, { true })
        assertSame(first, selected); assertEquals(2, retained.size); assertSame(second, retained[1])
    }
    @Test fun zeroRootsOrZeroMatchesHaveMissingNotAmbiguousCode() {
        bindingFailure(NotificationIdentityEvidence.BindingFailureCode.TITLE_MISSING) {
            NotificationIdentityEvidence.uniqueTitle(emptyList<Ref>(), { error("NO_QUERY") }, {}, { error("NO_METADATA") })
        }
        bindingFailure(NotificationIdentityEvidence.BindingFailureCode.TITLE_MISSING) {
            NotificationIdentityEvidence.uniqueTitle(listOf(Ref(1)), { listOf(Ref(7)) }, {}, { false })
        }
    }
    @Test fun distinctTitleNodesAreAmbiguousWithinOrAcrossRoots() {
        for (across in listOf(false, true)) bindingFailure(NotificationIdentityEvidence.BindingFailureCode.TITLE_AMBIGUOUS) {
            NotificationIdentityEvidence.uniqueTitle(if (across) listOf(Ref(1), Ref(2)) else listOf(Ref(1)),
                { if (across) listOf(Ref(it.id + 7)) else listOf(Ref(7), Ref(8)) }, {}, { true })
        }
    }
    @Test fun rawRootLimitPrecedesDedupAndAnyQuery() {
        bindingFailure(NotificationIdentityEvidence.BindingFailureCode.ROOT_LIMIT) {
            NotificationIdentityEvidence.uniqueTitle(List(10) { Ref(1) }, { error("NO_QUERY") }, {}, { error("NO_METADATA") })
        }
    }
    @Test fun oversizedMatchListIsAllRetainedAndReleasedWithoutMetadataRead() {
        val released = mutableListOf<Ref>()
        NotificationIdentityEvidence.Handles<Ref> { released += it }.use { handles ->
            bindingFailure(NotificationIdentityEvidence.BindingFailureCode.MATCH_LIMIT) {
                NotificationIdentityEvidence.uniqueTitle(listOf(Ref(1)), { List(9) { Ref(it) } },
                    { handles.retain(it) }, { error("NO_METADATA") })
            }
        }
        assertEquals(9, released.size)
    }
    @Test fun exhaustedTotalBudgetRejectsBeforeNextRootQuery() {
        var queries = 0; var descriptions = 0
        bindingFailure(NotificationIdentityEvidence.BindingFailureCode.MATCH_LIMIT) {
            NotificationIdentityEvidence.uniqueTitle(List(5) { Ref(it) }, { queries++; List(8) { Ref(it) } }, {},
                { descriptions++; false })
        }
        assertEquals(4, queries); assertEquals(32, descriptions)
    }
    @Test fun cumulativeMatchLimitRejectsWholeReturnedListBeforeMetadata() {
        var queries = 0; var retained = 0; var descriptions = 0
        bindingFailure(NotificationIdentityEvidence.BindingFailureCode.MATCH_LIMIT) {
            NotificationIdentityEvidence.uniqueTitle(List(5) { Ref(it) }, { queries++; List(7) { Ref(it) } },
                { retained++ }, { descriptions++; false })
        }
        assertEquals(5, queries); assertEquals(35, retained); assertEquals(28, descriptions)
    }
    @Test fun exactTotalBudgetCanProveUniqueIfNoRootRemains() {
        assertEquals(Ref(7), NotificationIdentityEvidence.uniqueTitle(List(4) { Ref(it) },
            { List(8) { Ref(7) } }, {}, { true }))
    }
    @Test fun titleFilterRequiresExactSystemUiPackageWhitelistedIdAndKnownNonceText() {
        val title = NotificationIdentityEvidence.title(receipt)
        for (id in listOf("android:id/title", "$system:id/title")) {
            assertTrue(NotificationIdentityEvidence.titleResourcePattern.matcher(id).matches())
            assertTrue(NotificationIdentityEvidence.matchesTitle(system, id, title, title))
            for (pkg in listOf(null, "android", "other.app", "$system.extra"))
                assertFalse(NotificationIdentityEvidence.matchesTitle(pkg, id, title, title))
            for (text in listOf(null, "$title extra", "other notification"))
                assertFalse(NotificationIdentityEvidence.matchesTitle(system, id, text, title))
        }
        for (id in listOf(null, "", "title", "other.app:id/title", "android:id/title_extra", "android:id/other",
            "android:id/title\n", "prefixandroid:id/title", "comXandroidXsystemui:id/title", "$system:id/other", "$system:id/title/child")) {
            assertFalse(NotificationIdentityEvidence.titleResourcePattern.matcher(id.orEmpty()).matches())
            assertFalse(NotificationIdentityEvidence.matchesTitle(system, id, title, title))
        }
    }
    @Test fun publicTitleRejectsDuplicateExactTitlesAcrossAllowedNamespacesWithoutPreference() {
        val title = NotificationIdentityEvidence.title(receipt)
        for (ids in listOf(listOf("android:id/title", "$system:id/title"), listOf("$system:id/title", "android:id/title"))) {
            val found = ids.filter { NotificationIdentityEvidence.matchesTitle(system, it, title, title) }
            val retained = mutableListOf<String>()
            bindingFailure(NotificationIdentityEvidence.BindingFailureCode.TITLE_AMBIGUOUS) {
                NotificationIdentityEvidence.publicTitle(found, { retained += it })
            }
            assertEquals(ids, retained)
        }
    }
    @Test fun titleFailuresAreFixedAndDistinguishAllThreeBindingPhases() {
        for (phase in NotificationIdentityEvidence.BindingPhase.values()) {
            for (code in listOf(NotificationIdentityEvidence.BindingFailureCode.TITLE_MISSING,
                NotificationIdentityEvidence.BindingFailureCode.TITLE_AMBIGUOUS))
                assertEquals("IDENTITY_${phase.name}_${code.name}", NotificationIdentityEvidence.bindingReason(phase, code))
            for (code in listOf(NotificationIdentityEvidence.BindingFailureCode.WINDOW_LIMIT,
                NotificationIdentityEvidence.BindingFailureCode.ROOT_LIMIT, NotificationIdentityEvidence.BindingFailureCode.MATCH_LIMIT))
                assertEquals("IDENTITY_${code.name}", NotificationIdentityEvidence.bindingReason(phase, code))
        }
    }
    @Test fun queryExceptionStillReleasesAllAcquiredWindowAndNodeWrappers() {
        val releasedWindows = mutableListOf<Ref>(); val releasedNodes = mutableListOf<Ref>()
        val failure = IllegalStateException("PRIVATE_EXCEPTION_NOT_FOR_EVIDENCE")
        val error = runCatching {
            NotificationIdentityEvidence.Handles<Ref> { releasedWindows += it }.use { windows ->
                NotificationIdentityEvidence.Handles<Ref> { releasedNodes += it }.use { nodes ->
                    val roots = NotificationIdentityEvidence.windowRoots(listOf(Ref(1), Ref(2)), { windows.retain(it) },
                        { Ref(0) }, { Ref(it.id) }, { nodes.retain(it) })
                    NotificationIdentityEvidence.uniqueTitle(roots, { if (it.id == 0) listOf(Ref(7)) else throw failure },
                        { nodes.retain(it) }, { true })
                }
            }
        }.exceptionOrNull()
        assertSame(failure, error); assertEquals(2, releasedWindows.size); assertEquals(4, releasedNodes.size)
    }
    @Test fun resourceReleaseUsesObjectIdentityAndContinuesAfterReleaseFailure() {
        val first = Ref(1); val equalWrapper = Ref(1)
        val released = mutableListOf<Ref>(); val failure = IllegalStateException("RELEASE_FAILURE")
        val handles = NotificationIdentityEvidence.Handles<Ref> {
            released += it
            if (it === first) throw failure
        }
        handles.retain(first); handles.retain(first); handles.retain(equalWrapper)
        assertSame(failure, runCatching { handles.close() }.exceptionOrNull())
        assertEquals(2, released.size); assertSame(first, released[0]); assertSame(equalWrapper, released[1])
        handles.close(); assertEquals(2, released.size)
    }
    @Test fun publicTitleRetainsEveryWrapperBeforeLimitsAndRequiresStrictUniqueness() {
        for ((count, code) in listOf(0 to NotificationIdentityEvidence.BindingFailureCode.TITLE_MISSING,
            2 to NotificationIdentityEvidence.BindingFailureCode.TITLE_AMBIGUOUS,
            33 to NotificationIdentityEvidence.BindingFailureCode.MATCH_LIMIT)) {
            val wrappers = List(count) { Ref(1) }
            val released = mutableListOf<Ref>()
            NotificationIdentityEvidence.Handles<Ref> { released += it }.use { handles ->
                bindingFailure(code) { NotificationIdentityEvidence.publicTitle(wrappers, { handles.retain(it) }) }
            }
            assertEquals(count, released.size)
            wrappers.forEachIndexed { i, wrapper -> assertSame(wrapper, released[i]) }
        }
        val only = Ref(7)
        assertSame(only, NotificationIdentityEvidence.publicTitle(listOf(only), {}))
    }
    @Test fun publicChildrenListIsFetchedOnceForAllIndexedChildren() {
        val root = Ref(-1); val leaves = List(3) { Ref(it) }
        var queries = 0
        val described = mutableListOf<Ref>()
        val retained = mutableListOf<Ref>()
        val snapshot = NotificationIdentityEvidence.capturePublic(root, NotificationIdentityEvidence.Phase.CURRENT,
            { if (it === root) 3 else 0 }, { assertSame(root, it); queries++; leaves }, { retained += it }) {
            described += it; node(title = it === root)
        }
        assertEquals(1, queries); assertEquals(leaves, retained)
        assertEquals(listOf(root) + leaves, described); assertFalse(snapshot.truncated)
    }
    @Test fun publicWideCountRejectsBeforeQueryOrChildPropertiesAndCannotProduceArtifact() {
        var artifact: ByteArray? = null; var described = 0
        val failure = runCatching {
            val snapshot = NotificationIdentityEvidence.capturePublic(Ref(0), NotificationIdentityEvidence.Phase.CURRENT,
                { 32 }, { error("NO_CHILD_QUERY") }, { error("NO_RETAIN") }) {
                described++; node()
            }
            artifact = NotificationIdentityEvidence.json(receipt, 123, NotificationIdentityEvidence.ExpandStatus.MISSING, listOf(snapshot))
        }.exceptionOrNull()
        assertEquals("IDENTITY_CHILDREN_LIMIT", failure?.message)
        assertEquals(1, described); assertNull(artifact)
    }
    @Test fun publicOversizedReturnedListRetainsAndReleasesAllWithoutChildProperties() {
        val root = Ref(-1); val wrappers = List(40) { Ref(it) }
        val released = mutableListOf<Ref>(); val described = mutableListOf<Ref>()
        NotificationIdentityEvidence.Handles<Ref> { released += it }.use { handles ->
            handles.retain(root)
            assertEquals("IDENTITY_CHILDREN_LIMIT", runCatching {
                NotificationIdentityEvidence.capturePublic(root, NotificationIdentityEvidence.Phase.CURRENT,
                    { 1 }, { wrappers }, { handles.retain(it) }) { described += it; node() }
            }.exceptionOrNull()?.message)
        }
        assertEquals(listOf(root), described); assertEquals(41, released.size)
        wrappers.forEachIndexed { i, wrapper -> assertSame(wrapper, released[i + 1]) }
    }
    @Test fun publicPendingSiblingsReserveBudgetBeforeDescendantQueryAndAttributes() {
        val root = Ref(-1); val first = Ref(0); val sibling = Ref(1)
        val queried = mutableListOf<Ref>(); val described = mutableListOf<Ref>()
        assertEquals("IDENTITY_CHILDREN_LIMIT", runCatching {
            NotificationIdentityEvidence.capturePublic(root, NotificationIdentityEvidence.Phase.CURRENT,
                { if (it === root) 2 else 30 }, { queried += it; listOf(first, sibling) }, {}) {
                described += it; node(title = it === root)
            }
        }.exceptionOrNull()?.message)
        assertEquals(listOf(root), queried); assertEquals(listOf(root, first), described)
    }
    @Test fun publicDepthBoundaryReadsCountButNeverRequestsChildren() {
        val refs = List(9) { Ref(it) }; val queries = mutableListOf<Int>(); val described = mutableListOf<Int>()
        val snapshot = NotificationIdentityEvidence.capturePublic(refs[0], NotificationIdentityEvidence.Phase.CURRENT,
            { 1 }, { queries += it.id; check(it.id < 7); listOf(refs[it.id + 1]) }, {}) {
            described += it.id; node(title = it.id == 0)
        }
        assertEquals((0..6).toList(), queries); assertEquals((0..7).toList(), described)
        assertTrue(snapshot.truncated)
    }
    @Test fun publicThirtySecondNodeWithChildrenIsTruncatedWithoutChildrenQuery() {
        val root = Ref(-1); val leaves = List(31) { Ref(it) }; var queries = 0; var described = 0
        val snapshot = NotificationIdentityEvidence.capturePublic(root, NotificationIdentityEvidence.Phase.CURRENT,
            { when { it === root -> 31; it.id == 30 -> 1; else -> 0 } },
            { assertSame(root, it); queries++; leaves }, {}) { described++; node(title = it === root) }
        assertEquals(1, queries); assertEquals(32, described); assertTrue(snapshot.truncated)
    }
    @Test fun publicVisibleChildrenCanOmitStableHiddenNodesAndStillDescribeVisibleTitle() {
        val root = Ref(-1); val visibleTitle = Ref(0)
        val described = mutableListOf<Ref>(); val retained = mutableListOf<Ref>()
        val snapshot = NotificationIdentityEvidence.capturePublic(root, NotificationIdentityEvidence.Phase.CURRENT,
            { if (it === root) 2 else 0 }, { assertSame(root, it); listOf(visibleTitle) }, { retained += it }) {
            described += it; node(title = it === visibleTitle)
        }
        assertEquals(listOf(root, visibleTitle), described); assertEquals(listOf(visibleTitle), retained)
        assertEquals(1, snapshot.titleCount); assertFalse(snapshot.truncated)
        val output = NotificationIdentityEvidence.json(receipt, 123, NotificationIdentityEvidence.ExpandStatus.MISSING,
            listOf(snapshot)).toString(Charsets.UTF_8)
        assertTrue(output.contains("\"path\":\"0/0\",\"depth\":1"))
    }
    @Test fun publicChildListGrowthFailsWithoutChildPropertiesAndReleasesEveryWrapper() {
        val root = Ref(-1); val children = listOf(Ref(0), Ref(1))
        val described = mutableListOf<Ref>(); val released = mutableListOf<Ref>()
        var artifact: ByteArray? = null
        NotificationIdentityEvidence.Handles<Ref> { released += it }.use { handles ->
            handles.retain(root)
            assertEquals("IDENTITY_CHILDREN_CHANGED", runCatching {
                val snapshot = NotificationIdentityEvidence.capturePublic(root, NotificationIdentityEvidence.Phase.CURRENT,
                    { 1 }, { children }, { handles.retain(it) }) { described += it; node() }
                artifact = NotificationIdentityEvidence.json(receipt, 123,
                    NotificationIdentityEvidence.ExpandStatus.MISSING, listOf(snapshot))
            }.exceptionOrNull()?.message)
        }
        assertEquals(listOf(root), described); assertNull(artifact); assertEquals(3, released.size)
        assertSame(root, released[0]); children.forEachIndexed { i, child -> assertSame(child, released[i + 1]) }
    }
    private fun identity() = NotificationIdentityEvidence.OwnIdentity("com.example.agent", "owned", 123, "channel", true, true)
    private fun owns(values: List<NotificationIdentityEvidence.OwnIdentity>) = NotificationIdentityEvidence.owns(values, "owned", 123, "channel")
    @Test fun exactOwnActiveNotificationIdentityIsRequired() {
        val own = identity()
        assertTrue(owns(listOf(own)))
        for (changed in listOf(own.copy(packageName = "other.app"), own.copy(tag = "other"), own.copy(id = 124),
            own.copy(channel = "other"), own.copy(titleMatches = false), own.copy(intentsMatch = false))) assertFalse(owns(listOf(changed)))
    }
    @Test fun absentAndMultipleActiveNotificationsAreRejected() {
        assertFalse(owns(emptyList())); assertFalse(owns(listOf(identity(), identity())))
    }
    @Test fun worstCaseArtifactNeverExceedsBound() {
        val snapshot = NotificationIdentityEvidence.capture(-1, NotificationIdentityEvidence.Phase.CURRENT,
            { if (it == -1) 31 else 0 }, { _, i -> i }, { n -> node(n == -1,
                "android:id/" + "a".repeat(117), "android." + "C".repeat(120)) })
        val result = runCatching { NotificationIdentityEvidence.json(receipt, 123,
            NotificationIdentityEvidence.ExpandStatus.CLICKED, listOf(snapshot,
                NotificationIdentityEvidence.capture(-1, NotificationIdentityEvidence.Phase.AFTER_EXPAND,
                    { if (it == -1) 31 else 0 }, { _, i -> i }, { n -> node(n == -1,
                        "android:id/" + "a".repeat(117), "android." + "C".repeat(120)) }))) }
        if (result.isSuccess) assertTrue(result.getOrThrow().size <= 32768)
        else assertEquals("IDENTITY_SIZE_LIMIT", result.exceptionOrNull()?.message)
    }
}
