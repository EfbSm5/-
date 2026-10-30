package com.example.agent.rootpilot

import java.util.UUID

/** Historical failed-only observations; never an expansion proof or the state at the deadline. */
internal object NotificationExpansionTimeoutEvidence {
    private const val SYSTEM_UI = "com.android.systemui"
    private val resource = Regex("(?:android|com\\.android\\.systemui):id/[A-Za-z_][A-Za-z0-9_]*")
    private val javaClass = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

    class Candidate<N : Any> {
        private var snapshot: NotificationIdentityEvidence.Snapshot<N>? = null
        private var decision: NotificationExpansionEvidence.Code? = null
        val available: Boolean get() = snapshot != null

        /** Call immediately before the first query of every new iteration, not before its deadline guard. */
        fun beginQuery() { snapshot = null; decision = null }

        fun observe(value: NotificationIdentityEvidence.Snapshot<N>, observed: NotificationExpansionEvidence.Code) {
            beginQuery()
            if (observed !in listOf(NotificationExpansionEvidence.Code.NONE,
                    NotificationExpansionEvidence.Code.EXPANSION_ACTION_MISSING) || !valid(value) ||
                NotificationExpansionEvidence.after(value) != observed) return
            snapshot = value
            decision = observed
        }

        fun bytes(receipt: String, pid: Int, ownershipRechecked: Boolean, observerVerified: Boolean): ByteArray {
            require(ownershipRechecked && observerVerified && available) { "TIMEOUT_EVIDENCE_GUARDS_INVALID" }
            val value = snapshot!!
            val observed = decision!!
            require(valid(value) && NotificationExpansionEvidence.after(value) == observed) { "TIMEOUT_EVIDENCE_SNAPSHOT_INVALID" }
            require(receipt.length == 36 && runCatching { UUID.fromString(receipt).toString() == receipt }.getOrDefault(false) && pid > 0) {
                "TIMEOUT_EVIDENCE_IDENTITY_INVALID"
            }
            return buildString {
                append("{\"schemaVersion\":1,\"receiptId\":\"").append(receipt).append("\",\"mainPid\":").append(pid)
                append(",\"failureOnly\":true,\"noAcceptance\":true,\"failureSite\":\"POST_WAIT\",\"failureReason\":\"EXPANSION_POST_TIMEOUT\"")
                append(",\"gestureKind\":\"LONG_CLICK\",\"historicalObservation\":true,\"observedDecision\":\"")
                append(if (observed == NotificationExpansionEvidence.Code.NONE) "NONE" else "ACTION_MISSING")
                append("\",\"ownershipRechecked\":true,\"observerVerified\":true,\"phase\":{\"phase\":\"AFTER_EXPAND\",\"truncated\":false")
                value.captureRootPath?.let { append(",\"captureRootPath\":\"").append(it).append('"') }
                append(",\"nodes\":[")
                value.entries.forEachIndexed { index, entry ->
                    if (index != 0) append(',')
                    val n = entry.node
                    append("{\"path\":\"").append(entry.path).append("\",\"depth\":").append(entry.depth)
                    append(",\"resourceName\":\"").append(safeResource(n.resourceName)).append("\",\"className\":\"").append(n.className)
                    append("\",\"packageName\":\"").append(n.packageName).append('"')
                    fun flag(name: String, flag: Boolean) { append(",\"").append(name).append("\":").append(flag) }
                    flag("enabled", n.enabled); flag("clickable", n.clickable); flag("scrollable", n.scrollable)
                    flag("checkable", n.checkable); flag("checked", n.checked); flag("focusable", n.focusable); flag("focused", n.focused)
                    flag("selected", n.selected); flag("longClickable", n.longClickable)
                    flag("textEqualsAppLabel", n.textEqualsAppLabel); flag("descriptionEqualsAppLabel", n.descriptionEqualsAppLabel)
                    flag("textPresent", n.textPresent); flag("descriptionPresent", n.descriptionPresent); flag("titleMatches", n.titleMatches)
                    flag("confirmActionMatches", n.confirmActionMatches); flag("stopActionMatches", n.stopActionMatches)
                    append('}')
                }
                append("]}}")
            }.toByteArray(Charsets.UTF_8).also { require(it.size <= NotificationIdentityEvidence.MAX_BYTES) { "TIMEOUT_EVIDENCE_SIZE_LIMIT" } }
        }
    }

    fun publishable(longPress: Boolean, reason: String, cleanupReason: String, baselineRestored: Boolean,
        ownershipRechecked: Boolean, observerVerified: Boolean, hasBytes: Boolean): Boolean =
        longPress && reason == "EXPANSION_POST_TIMEOUT" && cleanupReason == "NONE" && baselineRestored &&
            ownershipRechecked && observerVerified && hasBytes

    private fun safeResource(value: String?): String =
        value?.takeIf { it.length <= 128 && resource.matches(it) } ?: "REDACTED"

    private fun <N : Any> valid(value: NotificationIdentityEvidence.Snapshot<N>): Boolean {
        if (value.phase != NotificationIdentityEvidence.Phase.AFTER_EXPAND || value.truncated || !value.validContentScope() ||
            value.entries.size !in 1..NotificationIdentityEvidence.MAX_NODES || value.entries.first().path != "0") return false
        val entries = value.entries
        val paths = entries.map { it.path }.toSet()
        if (paths.size != entries.size || entries.any { entry ->
                val parts = entry.path.split('/')
                val n = entry.node
                parts.first() != "0" || parts.any { it.toIntOrNull()?.let { index -> index >= 0 && index.toString() == it } != true } ||
                    entry.depth != parts.size - 1 || (entry.path != "0" && entry.path.substringBeforeLast('/') !in paths) ||
                    n.packageName != SYSTEM_UI || n.className?.let { it.length <= 128 && javaClass.matches(it) } != true
            }) return false
        if (entries.count { NotificationIdentityEvidence.titleResourcePattern.matcher(it.node.resourceName.orEmpty()).matches() } != 1)
            return false
        for (id in listOf("app_name_text", "app_icon")) {
            if (entries.count { it.node.resourceName == "$SYSTEM_UI:id/$id" } > 1) return false
        }
        val confirm = entries.filter { it.node.confirmActionMatches }
        val stop = entries.filter { it.node.stopActionMatches }
        if (confirm.size > 1 || stop.size > 1 || (confirm + stop).any { it.path == "0" || !it.node.enabled || !it.node.clickable } ||
            confirm.isNotEmpty() && stop.isNotEmpty() && confirm.single().path == stop.single().path) return false
        val children = entries.drop(1).groupBy { it.path.substringBeforeLast('/') }
        if (children.values.any { nodes -> nodes.map { it.path.substringAfterLast('/').toInt() }.sorted() != nodes.indices.toList() }) return false
        fun order(path: String): List<String> = listOf(path) + children[path].orEmpty()
            .sortedBy { it.path.substringAfterLast('/').toInt() }.flatMap { order(it.path) }
        return entries.map { it.path } == order("0")
    }
}
