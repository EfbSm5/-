package com.example.agent.rootpilot

import java.util.regex.Pattern

/** Test-only identity checks for candidates queried inside an already verified notification row. */
internal object NotificationAppIdentity {
    private const val SYSTEM_UI = "com.android.systemui"
    val namedResourcePattern: Pattern = Pattern.compile("^(?:android:id/app_name_text|com\\.android\\.systemui:id/app_name_text)$")

    enum class Result { VERIFIED, MISSING, AMBIGUOUS }

    data class Node(
        val resourceName: String?,
        val packageName: String?,
        val className: String?,
        val descriptionEqualsAppLabel: Boolean = false,
        val textEqualsAppLabel: Boolean = false,
        val clickable: Boolean? = null,
        val longClickable: Boolean? = null,
        val enabled: Boolean? = null,
        val scrollable: Boolean? = null,
    )

    fun <T> verify(
        namedCandidates: List<T>,
        iconCandidates: () -> List<T>,
        describe: (T) -> Node,
        parent: (T) -> T?,
        isBoundRow: (T) -> Boolean,
        title: T,
        sameNode: (T, T) -> Boolean,
        modalBinding: Boolean = false,
    ): Result {
        // Resource candidates are counted before label checks: conflicting identities never fall back.
        if (namedCandidates.size > 1) return Result.AMBIGUOUS
        if (namedCandidates.size == 1) {
            var current = namedCandidates.single()
            val named = describe(current)
            if (named.packageName != SYSTEM_UI || !named.textEqualsAppLabel) return Result.MISSING
            if (named.resourceName == "$SYSTEM_UI:id/app_name_text") return Result.VERIFIED
            if (named.resourceName != "android:id/app_name_text" || named.className != "android.widget.TextView" ||
                !passive(named) || named.longClickable != false) return Result.MISSING
            // The framework label is accepted only in the measured expanded header, ending at
            // the caller's exact title-bound row. A matching label in another subtree is not proof.
            for ((resource, className) in listOf(
                "android:id/notification_top_line" to "android.view.ViewGroup",
                "android:id/notification_header" to "android.widget.RelativeLayout",
                "android:id/status_bar_latest_event_content" to "android.widget.FrameLayout",
                "$SYSTEM_UI:id/expanded" to "android.widget.FrameLayout",
            )) {
                current = parent(current) ?: return Result.MISSING
                val node = describe(current)
                if (node.packageName != SYSTEM_UI || node.resourceName != resource || node.className != className ||
                    !passive(node)) return Result.MISSING
            }
            val row = parent(current) ?: return Result.MISSING
            return if (isBoundRow(row)) Result.VERIFIED else Result.MISSING
        }
        val icons = iconCandidates()
        if (icons.isEmpty()) return Result.MISSING
        if (icons.size != 1) return Result.AMBIGUOUS
        var current = icons.single()
        val icon = describe(current)
        if (!matches(icon, "app_icon", "android.widget.ImageView") || !icon.descriptionEqualsAppLabel ||
            icon.clickable != false || icon.longClickable != false) return Result.MISSING
        var iconAndMain: T? = null
        val chain = listOf("icon_slot" to "android.widget.FrameLayout", "row_icon_and_main" to "android.widget.LinearLayout") +
            (if (modalBinding) listOf("notification_action_list_margin_target" to "android.widget.LinearLayout") else emptyList()) +
            listOf("status_bar_latest_event_content" to "android.widget.FrameLayout", "expanded" to "android.widget.FrameLayout")
        for ((resource, className) in chain) {
            current = parent(current) ?: return Result.MISSING
            val node = describe(current)
            if (!matches(node, resource, className) || (modalBinding && resource != "icon_slot" && !passive(node))) return Result.MISSING
            if (resource == "row_icon_and_main") iconAndMain = current
        }
        val row = parent(current) ?: return Result.MISSING
        if (!isBoundRow(row)) return Result.MISSING
        current = title
        val titleNode = describe(current)
        if (titleNode.packageName != SYSTEM_UI ||
            !NotificationIdentityEvidence.titleResourcePattern.matcher(titleNode.resourceName.orEmpty()).matches() ||
            titleNode.className != "android.widget.TextView" || (modalBinding && !passive(titleNode))) return Result.MISSING
        for ((resource, className) in listOf(
            "line1" to "android.widget.LinearLayout",
            "notification_main_column" to "android.widget.LinearLayout",
            "row_icon_and_main" to "android.widget.LinearLayout",
        )) {
            current = parent(current) ?: return Result.MISSING
            val node = describe(current)
            if (!matches(node, resource, className) || (modalBinding && !passive(node))) return Result.MISSING
        }
        return if (sameNode(current, iconAndMain ?: return Result.MISSING)) Result.VERIFIED else Result.MISSING
    }

    private fun passive(node: Node): Boolean = node.enabled == true && node.clickable == false && node.scrollable == false

    private fun matches(node: Node, resource: String, className: String): Boolean =
        node.packageName == SYSTEM_UI && node.resourceName == "$SYSTEM_UI:id/$resource" && node.className == className
}
