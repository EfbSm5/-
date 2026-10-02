package com.example.agent.rootpilot.information

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class UiNodeSnapshot(
    val id: String,
    val parentId: String?,
    val packageName: String,
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val description: String?,
    val hint: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val sensitive: Boolean,
) {
    override fun toString() = "UiNodeSnapshot(id=$id, sensitive=$sensitive)"
}

internal object UiTreeSnapshot {
    const val MAX_NODES = 200
    const val MAX_DEPTH = 32
    private const val MAX_LABEL_CHARS = 120

    data class Formatted(val data: JsonObject, val truncated: Boolean) {
        override fun toString() = "UiTreeSnapshot(truncated=$truncated)"
    }

    fun format(nodes: List<UiNodeSnapshot>, expectedPackage: String, incomplete: Boolean): Formatted {
        require(nodes.isNotEmpty() && nodes.all { it.packageName == expectedPackage })
        require(nodes.map { it.id }.distinct().size == nodes.size)
        var truncated = incomplete || nodes.size > MAX_NODES
        fun label(value: String?): String? {
            if (value != null && value.length > MAX_LABEL_CHARS) truncated = true
            return value?.take(MAX_LABEL_CHARS)
        }
        val data = buildJsonObject {
            put("package_name", expectedPackage)
            put("bounds_unit", "physical_screen_pixels")
            putJsonArray("nodes") {
                nodes.take(MAX_NODES).forEach { node ->
                    add(buildJsonObject {
                        put("node_id", node.id)
                        put("parent_id", node.parentId)
                        put("class", label(node.className))
                        put("resource_id", label(node.resourceId))
                        put("text", if (node.sensitive) null else label(node.text))
                        put("content_description", if (node.sensitive) null else label(node.description))
                        put("hint", if (node.sensitive) null else label(node.hint))
                        putJsonObject("bounds") {
                            put("left", node.left)
                            put("top", node.top)
                            put("right", node.right)
                            put("bottom", node.bottom)
                        }
                        put("clickable", node.clickable)
                        put("editable", node.editable)
                        put("enabled", node.enabled)
                        put("focused", node.focused)
                        put("scrollable", node.scrollable)
                        put("selected", node.selected)
                        put("checkable", node.checkable)
                        put("checked", node.checked)
                        put("text_redacted", node.sensitive)
                    })
                }
            }
        }
        return Formatted(data, truncated)
    }
}
