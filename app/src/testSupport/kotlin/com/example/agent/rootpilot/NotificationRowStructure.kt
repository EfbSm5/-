package com.example.agent.rootpilot

/** Test-only row identity; an unknown/redacted resource name is not evidence of a null ID. */
internal object NotificationRowStructure {
    private const val SYSTEM_UI = "com.android.systemui"

    data class Node(
        val resourceName: String? = null,
        val packageName: String? = null,
        val className: String? = null,
        val enabled: Boolean? = null,
        val clickable: Boolean? = null,
        val scrollable: Boolean? = null,
    )

    /** Independent post-longClick candidate; current's redacted resource is never treated as null. */
    fun isModalRow(current: Node, child: Node?, parent: Node?): Boolean =
        current.packageName == SYSTEM_UI && current.className == "android.widget.FrameLayout" &&
            current.enabled == true && current.clickable == true && current.scrollable == false &&
            child?.packageName == SYSTEM_UI && child.resourceName == "$SYSTEM_UI:id/expanded" &&
            child.className == "android.widget.FrameLayout" && child.enabled == true &&
            child.clickable == false && child.scrollable == false &&
            parent?.packageName == SYSTEM_UI && parent.resourceName == "$SYSTEM_UI:id/modal_window_view" &&
            parent.className == "android.widget.FrameLayout" && parent.enabled == true &&
            parent.clickable == false && parent.scrollable == false

    fun isRow(current: Node, child: Node? = null, parent: Node? = null): Boolean =
        current.resourceName == "$SYSTEM_UI:id/expandableNotificationRow" || (
            current.packageName == SYSTEM_UI && current.className == "android.widget.FrameLayout" &&
                current.enabled == true && current.clickable == true && current.scrollable == false &&
                child?.packageName == SYSTEM_UI && child.resourceName == "$SYSTEM_UI:id/expanded" &&
                parent?.packageName == SYSTEM_UI &&
                parent.resourceName == "$SYSTEM_UI:id/notification_stack_scroller" &&
                parent.className == "android.widget.ScrollView"
            )
}
