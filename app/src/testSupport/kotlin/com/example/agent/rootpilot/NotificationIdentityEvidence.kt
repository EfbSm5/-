package com.example.agent.rootpilot

import java.util.UUID
import java.util.regex.Pattern

/** Row-only test evidence. Text/description enter only as booleans, never as strings. */
internal object NotificationIdentityEvidence {
    const val MAX_NODES = 32
    const val MAX_LEVELS = 8
    const val MAX_BYTES = 32_768
    private const val SYSTEM_UI = "com.android.systemui"
    private val resource = Regex("(?:android|com\\.android\\.systemui):id/[A-Za-z_][A-Za-z0-9_]*")
    private val javaClass = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
    /** Per-run known title binds SystemUI identity without reading an unowned row's contents. */
    fun title(receipt: String): String {
        require(receipt.length == 36 && runCatching { UUID.fromString(receipt).toString() == receipt }.getOrDefault(false)) {
            "IDENTITY_IDENTITY_INVALID"
        }
        return "RootPilot · 第 1 步 · $receipt"
    }
    enum class Phase { CURRENT, AFTER_EXPAND }
    enum class ExpandStatus { MISSING, UNAVAILABLE, BOUNDED_INCOMPLETE, CLICKED, SWIPED, LONG_PRESSED }
    data class Node(
        val resourceName: String?, val className: String?, val packageName: String?,
        val enabled: Boolean, val clickable: Boolean, val scrollable: Boolean,
        val checkable: Boolean = false, val checked: Boolean = false,
        val focusable: Boolean = false, val focused: Boolean = false,
        val selected: Boolean = false, val longClickable: Boolean = false,
        val textEqualsAppLabel: Boolean = false, val descriptionEqualsAppLabel: Boolean = false,
        val textPresent: Boolean = false, val descriptionPresent: Boolean = false,
        val titleMatches: Boolean = false,
        // Equality-only expansion decisions; the original identity JSON remains byte-compatible.
        val confirmActionMatches: Boolean = false, val stopActionMatches: Boolean = false,
    )
    data class OwnIdentity(
        val packageName: String?, val tag: String?, val id: Int, val channel: String?,
        val titleMatches: Boolean, val intentsMatch: Boolean,
    )
    fun owns(active: List<OwnIdentity>, expectedTag: String, expectedId: Int, expectedChannel: String): Boolean =
        active.singleOrNull()?.let { it.packageName == "com.example.agent" && it.tag == expectedTag &&
            it.id == expectedId && it.channel == expectedChannel && it.titleMatches && it.intentsMatch } == true

    const val MAX_WINDOWS = 8
    const val MAX_ROOTS = 9
    const val MAX_MATCHES_PER_ROOT = 8
    const val MAX_TOTAL_MATCHES = 32
    enum class BindingPhase { INITIAL, REBIND, AFTER_EXPAND }
    enum class BindingFailureCode { WINDOW_LIMIT, ROOT_LIMIT, MATCH_LIMIT, TITLE_MISSING, TITLE_AMBIGUOUS }
    class BindingFailure(val code: BindingFailureCode) : RuntimeException()
    fun bindingReason(phase: BindingPhase, code: BindingFailureCode): String = when (code) {
        BindingFailureCode.TITLE_MISSING, BindingFailureCode.TITLE_AMBIGUOUS -> "IDENTITY_${phase.name}_${code.name}"
        else -> "IDENTITY_${code.name}"
    }

    /** Equal platform nodes can have distinct wrappers; release each Java object identity once. */
    class Handles<T : Any>(private val release: (T) -> Unit) : AutoCloseable {
        private val identities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())
        private val values = mutableListOf<T>()
        fun retain(value: T): T {
            if (identities.add(value)) values += value
            return value
        }
        override fun close() {
            var failure: Throwable? = null
            values.forEach { value ->
                try { release(value) } catch (error: Throwable) { if (failure == null) failure = error }
            }
            values.clear()
            identities.clear()
            failure?.let { throw it }
        }
    }

    /** Limits admit returned lists; they do not bound platform query internals or IPC work. */
    fun <W : Any, N : Any> windowRoots(windows: List<W>, retainWindow: (W) -> Unit,
        activeRoot: () -> N?, windowRoot: (W) -> N?, retainNode: (N) -> Unit): List<N> {
        windows.forEach(retainWindow)
        if (windows.size > MAX_WINDOWS) throw BindingFailure(BindingFailureCode.WINDOW_LIMIT)
        val roots = mutableListOf<N>()
        fun accept(value: N?) {
            if (value != null) { retainNode(value); roots += value }
        }
        accept(activeRoot())
        windows.forEach { accept(windowRoot(it)) }
        if (roots.size > MAX_ROOTS) throw BindingFailure(BindingFailureCode.ROOT_LIMIT)
        return roots.distinct()
    }

    // Framework notification templates keep android's resource namespace inside SystemUI.
    // A single selector must collect both allowed IDs before checking title uniqueness.
    val titleResourcePattern: Pattern = Pattern.compile("^(?:android:id/title|com\\.android\\.systemui:id/title)$")

    fun matchesTitle(packageName: String?, resourceName: String?, text: String?, expected: String): Boolean =
        packageName == SYSTEM_UI && resourceName != null && titleResourcePattern.matcher(resourceName).matches() && text == expected

    fun <N : Any> uniqueTitle(roots: List<N>, query: (N) -> List<N>,
        retainNode: (N) -> Unit, matches: (N) -> Boolean): N {
        if (roots.size > MAX_ROOTS) throw BindingFailure(BindingFailureCode.ROOT_LIMIT)
        var total = 0
        val titles = mutableListOf<N>()
        roots.distinct().forEach { root ->
            if (total >= MAX_TOTAL_MATCHES) throw BindingFailure(BindingFailureCode.MATCH_LIMIT)
            val found = query(root)
            found.forEach(retainNode)
            if (found.size > MAX_MATCHES_PER_ROOT || found.size > MAX_TOTAL_MATCHES - total)
                throw BindingFailure(BindingFailureCode.MATCH_LIMIT)
            total += found.size
            found.forEach { node ->
                if (matches(node) && node !in titles) {
                    titles += node
                    if (titles.size > 1) throw BindingFailure(BindingFailureCode.TITLE_AMBIGUOUS)
                }
            }
        }
        return titles.singleOrNull() ?: throw BindingFailure(BindingFailureCode.TITLE_MISSING)
    }

    /** Public UiDevice already merges its query roots. Admit every wrapper before checking uniqueness. */
    fun <N : Any> publicTitle(found: List<N>, retainNode: (N) -> Unit): N {
        found.forEach(retainNode)
        if (found.size > MAX_TOTAL_MATCHES) throw BindingFailure(BindingFailureCode.MATCH_LIMIT)
        if (found.isEmpty()) throw BindingFailure(BindingFailureCode.TITLE_MISSING)
        if (found.size != 1) throw BindingFailure(BindingFailureCode.TITLE_AMBIGUOUS)
        return found.single()
    }

    /**
     * Adapts public children lists to capture's indexed walk. Budgets cover our admission,
     * description and export, not UiObject2's internal subtree queries/refresh/watchers or IPC.
     * Pending siblings reserve capacity before any descendant list can be admitted.
     * Public children include only visible matches; childCount can include hidden nodes.
     * This visible set proves neither the absence of hidden nodes nor original-tree consistency.
     */
    fun <N : Any> capturePublic(root: N, phase: Phase, childCount: (N) -> Int,
        children: (N) -> List<N>, retainNode: (N) -> Unit, describe: (N) -> Node): Snapshot<N> {
        val lists = java.util.IdentityHashMap<N, List<N>>()
        val depths = java.util.IdentityHashMap<N, Int>()
        depths[root] = 0
        var described = 0
        var pending = 1
        val budget = NotificationCaptureBudget.Recorder { pending }
        return captureBounded(root, phase, { value ->
            val count = childCount(value)
            check(count >= 0) { "IDENTITY_CHILDREN_INVALID" }
            if (count == 0 || depths.getValue(value) + 1 >= MAX_LEVELS || described >= MAX_NODES) count
            else {
                val admitted = lists[value] ?: run {
                    val remaining = MAX_NODES - described - pending
                    check(count <= remaining) { "IDENTITY_CHILDREN_LIMIT" }
                    val returned = children(value)
                    returned.forEach(retainNode)
                    check(returned.size <= remaining) { "IDENTITY_CHILDREN_LIMIT" }
                    check(returned.size <= count) { "IDENTITY_CHILDREN_CHANGED" }
                    returned.forEach { depths[it] = depths.getValue(value) + 1 }
                    pending += returned.size
                    lists[value] = returned
                    returned
                }
                admitted.size
            }
        }, { value, index -> lists.getValue(value)[index] }, { value ->
            check(pending > 0 && described < MAX_NODES) { "IDENTITY_CHILDREN_LIMIT" }
            pending--
            described++
            describe(value)
        }, budget)
    }

    /** Fixed first-failing conditions from the one cached modal capture. */
    enum class ModalContentReason {
        EXPANSION_MODAL_PREFIX_PHASE_INVALID,
        EXPANSION_MODAL_PREFIX_ROW_CHILDREN_EMPTY, EXPANSION_MODAL_PREFIX_ROW_CHILDREN_MULTIPLE,
        EXPANSION_MODAL_PREFIX_EXPANDED_CHILDREN_EMPTY, EXPANSION_MODAL_PREFIX_EXPANDED_CHILDREN_MULTIPLE,
        EXPANSION_MODAL_PREFIX_ROW_EXPANDED_MISSING, EXPANSION_MODAL_PREFIX_ROW_EXPANDED_AMBIGUOUS,
        EXPANSION_MODAL_PREFIX_EXPANDED_CONTENT_MISSING, EXPANSION_MODAL_PREFIX_EXPANDED_CONTENT_AMBIGUOUS,
        EXPANSION_MODAL_PREFIX_ROW_PACKAGE_INVALID, EXPANSION_MODAL_PREFIX_ROW_ENABLED_INVALID,
        EXPANSION_MODAL_PREFIX_ROW_CLICKABLE_INVALID, EXPANSION_MODAL_PREFIX_ROW_SCROLLABLE_INVALID,
        EXPANSION_MODAL_PREFIX_ROW_CLASS_INVALID, EXPANSION_MODAL_PREFIX_ROW_CANDIDATE_PRESENT,
        EXPANSION_MODAL_PREFIX_EXPANDED_RESOURCE_INVALID, EXPANSION_MODAL_PREFIX_EXPANDED_PACKAGE_INVALID,
        EXPANSION_MODAL_PREFIX_EXPANDED_CLASS_INVALID, EXPANSION_MODAL_PREFIX_EXPANDED_ENABLED_INVALID,
        EXPANSION_MODAL_PREFIX_EXPANDED_CLICKABLE_INVALID, EXPANSION_MODAL_PREFIX_EXPANDED_LONG_CLICKABLE_INVALID,
        EXPANSION_MODAL_PREFIX_EXPANDED_SCROLLABLE_INVALID, EXPANSION_MODAL_PREFIX_EXPANDED_CANDIDATE_PRESENT,
        EXPANSION_MODAL_PREFIX_CONTENT_RESOURCE_INVALID, EXPANSION_MODAL_PREFIX_CONTENT_PACKAGE_INVALID,
        EXPANSION_MODAL_PREFIX_CONTENT_CLASS_INVALID, EXPANSION_MODAL_PREFIX_CONTENT_ENABLED_INVALID,
        EXPANSION_MODAL_PREFIX_CONTENT_CLICKABLE_INVALID, EXPANSION_MODAL_PREFIX_CONTENT_LONG_CLICKABLE_INVALID,
        EXPANSION_MODAL_PREFIX_CONTENT_SCROLLABLE_INVALID,
    }
    class ModalContentFailure(val code: ModalContentReason) : RuntimeException()
    private fun requireModalContent(value: Boolean, code: ModalContentReason) {
        if (!value) throw ModalContentFailure(code)
    }

    /** Reads only the cached description, in the same short-circuit order as validOwnRow/candidate. */
    private fun requireModalRow(node: Node) {
        val code = when {
            node.packageName != SYSTEM_UI -> ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_PACKAGE_INVALID
            !node.enabled -> ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_ENABLED_INVALID
            !node.clickable -> ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_CLICKABLE_INVALID
            node.scrollable -> ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_SCROLLABLE_INVALID
            node.className != "android.widget.FrameLayout" -> ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_CLASS_INVALID
            candidate(node) -> ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_CANDIDATE_PRESENT
            else -> return
        }
        throw ModalContentFailure(code)
    }

    /** Expanded/content keep passiveFrame's exact condition order and accepted states. */
    private fun requireModalFrame(node: Node, id: String, site: String) {
        val suffix = when {
            node.resourceName != "$SYSTEM_UI:id/$id" -> "RESOURCE_INVALID"
            node.packageName != SYSTEM_UI -> "PACKAGE_INVALID"
            node.className != "android.widget.FrameLayout" -> "CLASS_INVALID"
            !node.enabled -> "ENABLED_INVALID"
            node.clickable -> "CLICKABLE_INVALID"
            node.longClickable -> "LONG_CLICKABLE_INVALID"
            node.scrollable -> "SCROLLABLE_INVALID"
            else -> return
        }
        throw ModalContentFailure(ModalContentReason.valueOf("EXPANSION_MODAL_PREFIX_${site}_$suffix"))
    }

    /**
     * Captures the entire already-bound modal row, including all visible sibling branches.
     * Only the measured content chain gets eight relative levels; other branches retain
     * the original eight row levels. One cache/admission budget covers recognition and DFS.
     */
    fun <N : Any> captureModalContentPublic(root: N, phase: Phase, childCount: (N) -> Int,
        children: (N) -> List<N>, retainNode: (N) -> Unit, describe: (N) -> Node): Snapshot<N> {
        requireModalContent(phase == Phase.AFTER_EXPAND, ModalContentReason.EXPANSION_MODAL_PREFIX_PHASE_INVALID)
        val lists = java.util.IdentityHashMap<N, List<N>>()
        val counts = java.util.IdentityHashMap<N, Int>()
        val paths = java.util.IdentityHashMap<N, String>()
        val descriptions = java.util.IdentityHashMap<N, Node>()
        paths[root] = "0"
        var described = 0
        var pending = 1
        val budget = NotificationCaptureBudget.Recorder { pending }
        fun description(value: N): Node = descriptions[value] ?: run {
            check(pending > 0 && described < MAX_NODES) { "IDENTITY_CHILDREN_LIMIT" }
            pending--
            described++
            describe(value).also { descriptions[value] = it }
        }
        fun count(value: N): Int = counts[value] ?: childCount(value).also {
            check(it >= 0) { "IDENTITY_CHILDREN_INVALID" }; counts[value] = it
        }
        fun admitted(value: N): List<N> = lists[value] ?: run {
            val count = count(value)
            val remaining = MAX_NODES - described - pending
            check(count <= remaining) { "IDENTITY_CHILDREN_LIMIT" }
            val returned = if (count == 0) emptyList() else children(value)
            returned.forEach(retainNode)
            check(returned.size <= remaining) { "IDENTITY_CHILDREN_LIMIT" }
            check(returned.size <= count) { "IDENTITY_CHILDREN_CHANGED" }
            returned.forEachIndexed { index, child ->
                check(!paths.containsKey(child)) { "IDENTITY_CHILD_PATH_INVALID" }
                paths[child] = "${paths.getValue(value)}/$index"
            }
            pending += returned.size
            lists[value] = returned
            returned
        }
        fun uniqueChild(value: N, resource: String, empty: ModalContentReason,
            missing: ModalContentReason, ambiguous: ModalContentReason): N {
            val returned = admitted(value)
            requireModalContent(returned.isNotEmpty(), empty)
            // Count resource candidates before validating metadata; never hide a disabled duplicate.
            val matches = returned.filter { description(it).resourceName == "$SYSTEM_UI:id/$resource" }
            requireModalContent(matches.isNotEmpty(), missing)
            requireModalContent(matches.size == 1, ambiguous)
            return matches.single()
        }
        requireModalRow(sanitized(description(root)))
        val expanded = uniqueChild(root, "expanded", ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_CHILDREN_EMPTY,
            ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_EXPANDED_MISSING,
            ModalContentReason.EXPANSION_MODAL_PREFIX_ROW_EXPANDED_AMBIGUOUS)
        val expandedNode = sanitized(description(expanded))
        requireModalFrame(expandedNode, "expanded", "EXPANDED")
        requireModalContent(!candidate(expandedNode), ModalContentReason.EXPANSION_MODAL_PREFIX_EXPANDED_CANDIDATE_PRESENT)
        val content = uniqueChild(expanded, "status_bar_latest_event_content", ModalContentReason.EXPANSION_MODAL_PREFIX_EXPANDED_CHILDREN_EMPTY,
            ModalContentReason.EXPANSION_MODAL_PREFIX_EXPANDED_CONTENT_MISSING,
            ModalContentReason.EXPANSION_MODAL_PREFIX_EXPANDED_CONTENT_AMBIGUOUS)
        requireModalFrame(description(content), "status_bar_latest_event_content", "CONTENT")
        val contentPath = paths.getValue(content)
        val entries = mutableListOf<Entry>()
        val expand = mutableListOf<N>()
        var truncated = false
        fun visit(value: N, depth: Int) {
            val path = paths.getValue(value)
            val raw = description(value)
            entries += Entry(path, depth, sanitized(raw))
            budget.visited(depth)
            if (raw.packageName == SYSTEM_UI && raw.resourceName == "$SYSTEM_UI:id/expand_button") expand += value
            if (count(value) == 0) return
            val inContent = path == contentPath || path.startsWith("$contentPath/")
            if (depth - (if (inContent) 2 else 0) + 1 >= MAX_LEVELS) {
                truncated = true; budget.cut(NotificationCaptureBudget.Limit.DEPTH_LIMIT, depth); return
            }
            if (described >= MAX_NODES && !lists.containsKey(value)) {
                truncated = true; budget.cut(NotificationCaptureBudget.Limit.NODE_LIMIT, depth); return
            }
            for (child in admitted(value)) {
                if (entries.size >= MAX_NODES) {
                    truncated = true; budget.cut(NotificationCaptureBudget.Limit.NODE_LIMIT, depth); return
                }
                visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return Snapshot(phase, truncated, entries, expand, modalBinding = true,
            captureBudget = budget.snapshot(), captureRootPath = contentPath)
    }

    private fun sanitized(raw: Node): Node = raw.copy(resourceName = safe(raw.resourceName, resource),
        className = safe(raw.className, javaClass),
        packageName = raw.packageName.takeIf { it == "android" || it == SYSTEM_UI } ?: "REDACTED")
    private fun validOwnRow(node: Node): Boolean = node.packageName == SYSTEM_UI && node.enabled && node.clickable &&
        !node.scrollable && node.className == "android.widget.FrameLayout"
    private fun passiveFrame(node: Node, id: String): Boolean = node.resourceName == "$SYSTEM_UI:id/$id" &&
        node.packageName == SYSTEM_UI && node.className == "android.widget.FrameLayout" && node.enabled &&
        !node.clickable && !node.longClickable && !node.scrollable
    private fun candidate(node: Node): Boolean = node.titleMatches || node.confirmActionMatches || node.stopActionMatches ||
        node.textEqualsAppLabel || node.descriptionEqualsAppLabel || titleResourcePattern.matcher(node.resourceName.orEmpty()).matches() ||
        node.resourceName in listOf("$SYSTEM_UI:id/app_name_text", "$SYSTEM_UI:id/app_icon")

    internal data class Entry(val path: String, val depth: Int, val node: Node)
    class Snapshot<N : Any> internal constructor(
        val phase: Phase, val truncated: Boolean,
        internal val entries: List<Entry>, internal val expandNodes: List<N>,
        internal val modalBinding: Boolean = false,
        internal val captureBudget: NotificationCaptureBudget.Summary? = null,
        internal val captureRootPath: String? = null,
    ) {
        internal fun withModalBinding(): Snapshot<N> {
            require(phase == Phase.AFTER_EXPAND) { "IDENTITY_PHASES_INVALID" }
            return Snapshot(phase, truncated, entries, expandNodes, modalBinding = true, captureBudget = captureBudget,
                captureRootPath = captureRootPath)
        }
        /** Rechecks the complete real row graph, including side branches and actual sibling indices. */
        internal fun validContentScope(): Boolean {
            if (captureRootPath == null) return entries.size <= MAX_NODES && entries.all { it.depth in 0 until MAX_LEVELS }
            if (phase != Phase.AFTER_EXPAND || !modalBinding || entries.size !in 3..MAX_NODES ||
                captureRootPath.split('/').size != 3 || entries.first().path != "0") return false
            val byPath = entries.associateBy { it.path }
            if (byPath.size != entries.size) return false
            val expandedPath = captureRootPath.substringBeforeLast('/')
            val row = byPath["0"]?.node ?: return false
            val expanded = byPath[expandedPath]?.node ?: return false
            val content = byPath[captureRootPath]?.node ?: return false
            if (!validOwnRow(row) || candidate(row) || !passiveFrame(expanded, "expanded") || candidate(expanded) ||
                !passiveFrame(content, "status_bar_latest_event_content")) return false
            if (entries.filter { it.node.resourceName == "$SYSTEM_UI:id/expanded" }.map { it.path } != listOf(expandedPath) ||
                entries.filter { it.node.resourceName == "$SYSTEM_UI:id/status_bar_latest_event_content" }.map { it.path } != listOf(captureRootPath)) return false
            if (!entries.all { entry ->
                val parts = entry.path.split('/')
                val contentEntry = entry.path == captureRootPath || entry.path.startsWith("$captureRootPath/")
                parts.first() == "0" && parts.all { part -> part.toIntOrNull()?.let { index -> index >= 0 && index.toString() == part } == true } &&
                    entry.depth == parts.size - 1 && entry.node.packageName == SYSTEM_UI &&
                    entry.node.className?.let { NotificationIdentityEvidence.javaClass.matches(it) } == true &&
                    (if (contentEntry) entry.depth in 2 until MAX_LEVELS + 2 else entry.depth in 0 until MAX_LEVELS) &&
                    (entry.path == "0" || byPath.containsKey(entry.path.substringBeforeLast('/')))
            }) return false
            val children = entries.drop(1).groupBy { it.path.substringBeforeLast('/') }
            if (children.values.any { values -> values.map { it.path.substringAfterLast('/').toInt() }.sorted() != values.indices.toList() }) return false
            fun order(path: String): List<String> = listOf(path) + children[path].orEmpty()
                .sortedBy { it.path.substringAfterLast('/').toInt() }.flatMap { order(it.path) }
            return entries.map { it.path } == order("0")
        }
        val titleCount: Int get() = entries.count { it.node.titleMatches }
        internal fun appendTo(out: StringBuilder) = with(out) {
            append("{\"phase\":\"").append(phase.name).append("\",\"truncated\":").append(truncated)
            if (captureRootPath != null) append(",\"captureRootPath\":\"").append(captureRootPath).append('"')
            append(",\"nodes\":[")
            entries.forEachIndexed { i, entry ->
                if (i != 0) append(',')
                val n = entry.node
                append("{\"path\":\"").append(entry.path).append("\",\"depth\":").append(entry.depth)
                append(",\"resourceName\":\"").append(n.resourceName)
                append("\",\"className\":\"").append(n.className)
                append("\",\"packageName\":\"").append(n.packageName).append('"')
                fun flag(name: String, value: Boolean) { append(",\"").append(name).append("\":").append(value) }
                flag("enabled", n.enabled); flag("clickable", n.clickable); flag("scrollable", n.scrollable)
                flag("checkable", n.checkable); flag("checked", n.checked); flag("focusable", n.focusable)
                flag("focused", n.focused); flag("selected", n.selected); flag("longClickable", n.longClickable)
                flag("textEqualsAppLabel", n.textEqualsAppLabel); flag("descriptionEqualsAppLabel", n.descriptionEqualsAppLabel)
                flag("textPresent", n.textPresent); flag("descriptionPresent", n.descriptionPresent)
                flag("titleMatches", n.titleMatches)
                append('}')
            }
            append("]}")
        }
    }

    /** Never calls parent, never requests a child outside the budget, never materializes child lists. */
    fun <N : Any> capture(root: N, phase: Phase, childCount: (N) -> Int,
        childAt: (N, Int) -> N?, describe: (N) -> Node): Snapshot<N> =
        captureBounded(root, phase, childCount, childAt, describe, budget = null)

    private fun <N : Any> captureBounded(root: N, phase: Phase, childCount: (N) -> Int,
        childAt: (N, Int) -> N?, describe: (N) -> Node, budget: NotificationCaptureBudget.Recorder?,
        rootPath: String = "0", rootDepth: Int = 0, prefix: List<Entry> = emptyList(),
        captureRootPath: String? = null): Snapshot<N> {
        val entries = prefix.toMutableList()
        val expand = mutableListOf<N>()
        var truncated = false
        fun visit(value: N, path: String, depth: Int) {
            val raw = describe(value)
            entries += Entry(path, depth, sanitized(raw))
            budget?.visited(depth)
            if (raw.packageName == SYSTEM_UI && raw.resourceName == "$SYSTEM_UI:id/expand_button") expand += value
            val count = childCount(value)
            require(count >= 0) { "IDENTITY_CHILDREN_INVALID" }
            if (count == 0) return
            if (depth - rootDepth + 1 >= MAX_LEVELS) {
                truncated = true
                budget?.cut(NotificationCaptureBudget.Limit.DEPTH_LIMIT, depth)
                return
            }
            var index = 0
            while (index < count) {
                if (entries.size >= MAX_NODES) {
                    truncated = true
                    budget?.cut(NotificationCaptureBudget.Limit.NODE_LIMIT, depth)
                    return
                }
                val child = childAt(value, index) ?: throw IllegalStateException("IDENTITY_CHILD_MISSING")
                visit(child, "$path/$index", depth + 1)
                index++
            }
        }
        visit(root, rootPath, rootDepth)
        return Snapshot(phase, truncated, entries, expand, modalBinding = captureRootPath != null,
            captureBudget = budget?.snapshot(), captureRootPath = captureRootPath)
    }

    fun <N : Any> json(receipt: String, pid: Int, expand: ExpandStatus, phases: List<Snapshot<N>>): ByteArray {
        require(expand != ExpandStatus.SWIPED && expand != ExpandStatus.LONG_PRESSED && phases.none { it.modalBinding || it.captureRootPath != null }) { "IDENTITY_PHASES_INVALID" }
        return encode(receipt, pid, expand, phases, expansion = false)
    }

    /** Gesture outcomes are admissible only through the separately validated exploration contract. */
    fun <N : Any> expansionJson(receipt: String, pid: Int, phases: List<Snapshot<N>>,
        noOwnActivityLaunch: Boolean, longPress: Boolean = false): ByteArray {
        require(phases.size == 2 && noOwnActivityLaunch && (longPress || phases.none { it.modalBinding }) &&
            NotificationExpansionEvidence.before(phases[0]) == NotificationExpansionEvidence.Code.NONE &&
            (!longPress || phases[0].entries.first().node.longClickable) &&
            NotificationExpansionEvidence.after(phases[1]) == NotificationExpansionEvidence.Code.NONE) {
            "IDENTITY_EXPANSION_PROOFS_INVALID"
        }
        return encode(receipt, pid, if (longPress) ExpandStatus.LONG_PRESSED else ExpandStatus.SWIPED,
            phases, expansion = true)
    }

    private fun <N : Any> encode(receipt: String, pid: Int, expand: ExpandStatus, phases: List<Snapshot<N>>,
        expansion: Boolean): ByteArray {
        require(receipt.length == 36 && runCatching { UUID.fromString(receipt).toString() == receipt }.getOrDefault(false) && pid > 0) {
            "IDENTITY_IDENTITY_INVALID"
        }
        require(phases.size in 1..2 && phases[0].phase == Phase.CURRENT &&
            phases.all { it.titleCount == 1 } &&
            (((expand == ExpandStatus.CLICKED || expansion) && phases.size == 2 && phases[1].phase == Phase.AFTER_EXPAND) ||
                (expand != ExpandStatus.CLICKED && !expansion && phases.size == 1))) { "IDENTITY_PHASES_INVALID" }
        return buildString {
            append("{\"schemaVersion\":1,\"receiptId\":\"").append(receipt).append("\",\"mainPid\":").append(pid)
            append(",\"onlyDiagnosticNotAcceptance\":true,\"identityProofs\":{\"ownNotificationVerified\":true,\"uniqueSystemUiTitle\":true,\"strictRowVerified\":true,\"rowTitleUnique\":true},\"expandStatus\":\"").append(expand.name).append("\",\"phases\":[")
            phases.forEachIndexed { index, phase -> if (index != 0) append(','); phase.appendTo(this) }
            append(']')
            if (expansion) append(",\"expansionProofs\":{\"ownRowExpanded\":true,\"noOwnActivityLaunch\":true,\"confirmActionUnique\":true,\"stopActionUnique\":true}")
            if (expand == ExpandStatus.LONG_PRESSED) append(",\"gestureKind\":\"LONG_CLICK\"")
            append('}')
        }.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "IDENTITY_SIZE_LIMIT" } }
    }
    private fun safe(value: String?, format: Regex): String =
        value?.takeIf { it.length <= 128 && format.matches(it) } ?: "REDACTED"
}
