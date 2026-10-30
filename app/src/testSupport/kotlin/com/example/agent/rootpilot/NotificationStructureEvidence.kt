package com.example.agent.rootpilot

import java.util.UUID

/** Test-only schema: no UI text/description, arbitrary package names, or unbounded fields. */
internal object NotificationStructureEvidence {
    const val MAX_NODES = 12
    const val MAX_BYTES = 16_384
    const val REDACTED = "REDACTED"
    private val resource = Regex("(?:android|com\\.android\\.systemui):id/[A-Za-z_][A-Za-z0-9_]*")
    private val javaClass = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

    data class Node(
        val resourceName: String?,
        val className: String?,
        val packageName: String?,
        val enabled: Boolean,
        val clickable: Boolean,
        val scrollable: Boolean,
    )

    class Snapshot internal constructor(private val nodes: List<Pair<Node, Boolean>>) {
        fun json(receipt: String, pid: Int): ByteArray = encode(receipt, pid, expansionFailure = false)

        fun expansionFailureJson(receipt: String, pid: Int): ByteArray = encode(receipt, pid, expansionFailure = true)

        private fun encode(receipt: String, pid: Int, expansionFailure: Boolean): ByteArray {
            require(receipt.length == 36 &&
                runCatching { UUID.fromString(receipt).toString() == receipt }.getOrDefault(false) && pid > 0) {
                "STRUCTURE_IDENTITY_INVALID"
            }
            val json = buildString {
                append("{\"schemaVersion\":1,\"receiptId\":\"").append(receipt)
                append("\",\"mainPid\":").append(pid)
                if (expansionFailure) append(",\"failureOnly\":true,\"failureSite\":\"AFTER_BIND\",\"failureReason\":\"IDENTITY_ROW_UNRELIABLE\",\"gestureKind\":\"LONG_CLICK\"")
                append(",\"structureOnlyNotAcceptance\":true,\"truncated\":")
                append(nodes.last().second).append(",\"nodes\":[")
                nodes.forEachIndexed { index, (node, hasParent) ->
                    if (index != 0) append(',')
                    append("{\"depth\":").append(index)
                    append(",\"resourceName\":\"").append(node.resourceName)
                    append("\",\"className\":\"").append(node.className)
                    append("\",\"packageName\":\"").append(node.packageName)
                    append("\",\"enabled\":").append(node.enabled)
                    append(",\"clickable\":").append(node.clickable)
                    append(",\"scrollable\":").append(node.scrollable)
                    append(",\"hasParent\":").append(hasParent).append('}')
                }
                append("]}")
            }.toByteArray(Charsets.UTF_8)
            check(json.size <= MAX_BYTES) { "STRUCTURE_SIZE_LIMIT" }
            return json
        }
    }

    /** Title is depth zero. A twelfth parent is only checked for truncation, never described. */
    fun <N : Any> capture(start: N, parent: (N) -> N?, describe: (N) -> Node): Snapshot {
        val nodes = mutableListOf<Pair<Node, Boolean>>()
        var current: N? = start
        repeat(MAX_NODES) {
            val value = current ?: return Snapshot(nodes)
            val raw = describe(value)
            val safe = raw.copy(
                resourceName = safe(raw.resourceName, resource),
                className = safe(raw.className, javaClass),
                packageName = raw.packageName.takeIf { it == "android" || it == "com.android.systemui" } ?: REDACTED,
            )
            current = parent(value)
            nodes += safe to (current != null)
        }
        return Snapshot(nodes)
    }

    private fun safe(value: String?, format: Regex): String =
        value?.takeIf { it.length <= 128 && format.matches(it) } ?: REDACTED
}
