package com.example.agent.rootpilot.root

import com.example.agent.rootpilot.screen.ScreenObservation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Task records are navigation hints. Intent extras, paths and all unrelated tasks are discarded. */
internal object ActivityStackParser {
    data class Stack(val data: JsonObject, val truncated: Boolean) {
        override fun toString() = "ActivityStack(truncated=$truncated)"
    }

    private val record = Regex("ActivityRecord\\{([0-9a-fA-F]{1,16}) u([0-9]+) ([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)/([A-Za-z0-9_.$]+) t([0-9]+)\\}")
    private val resumed = Regex("^\\s*(?:topResumedActivity\\s*=|mResumedActivity\\s*[:=]|ResumedActivity\\s*:|Resumed\\s*:)\\s*(ActivityRecord\\{[^}]+\\})\\s*$")
    private val history = Regex("^\\s*\\* Hist\\s+#([0-9]+):\\s*(ActivityRecord\\{[^}]+\\})\\s*$")
    private const val MAX_ENTRIES = 32

    fun parse(raw: String, expected: ScreenObservation): Stack? {
        val lines = raw.lineSequence().toList()
        val display = lines.indexOfFirst { it.trim() == "Display #0 (activities from top to bottom):" }
        if (display < 0) return null
        val body = lines.drop(display + 1).takeWhile {
            !it.trimStart().startsWith("Display #") && !it.startsWith("ActivityTaskSupervisor state:")
        }
        val tops = body.mapNotNull { resumed.matchEntire(it)?.groupValues?.get(1)?.let(record::matchEntire) }
            .distinctBy { it.groupValues[1] }
        val top = tops.singleOrNull() ?: return null
        val topPackage = top.groupValues[3]
        val topActivity = top.groupValues[4].let { if (it.startsWith('.')) topPackage + it else it }
        if (topPackage != expected.foregroundPackage || topActivity != expected.foregroundActivity) return null
        val taskId = top.groupValues[5]
        val userId = top.groupValues[2]
        val entries = body.mapNotNull { line ->
            val hist = history.matchEntire(line) ?: return@mapNotNull null
            val activity = record.matchEntire(hist.groupValues[2]) ?: return@mapNotNull null
            if (activity.groupValues[5] != taskId || activity.groupValues[2] != userId) return@mapNotNull null
            val position = hist.groupValues[1].toIntOrNull() ?: return null
            position to activity
        }.distinctBy { it.second.groupValues[1] }.sortedByDescending { it.first }
        if (entries.isEmpty() || entries.first().second.groupValues[1] != top.groupValues[1]) return null
        if (entries.map { it.first }.distinct().size != entries.size) return null
        return Stack(buildJsonObject {
            put("task_id", taskId)
            put("order", "top_to_bottom")
            putJsonArray("activities") {
                entries.take(MAX_ENTRIES).forEach { (_, entry) ->
                    val packageName = entry.groupValues[3]
                    val activityName = entry.groupValues[4].let { if (it.startsWith('.')) packageName + it else it }
                    add(buildJsonObject {
                        put("package_name", packageName)
                        put("activity", activityName)
                        put("top_resumed", entry.groupValues[1] == top.groupValues[1])
                    })
                }
            }
        }, entries.size > MAX_ENTRIES)
    }
}
