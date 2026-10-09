package com.example.agent.rootpilot.input

import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.agent.rootpilot.information.RootPilotAccessibilityService
import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Confirmed plain-text edits bound to one owned-display editor, with no IME change or replay. */
internal class VirtualDisplayTextInput(ownPackage: String) {
    private val policy = VirtualDisplayTextInputPolicy(ownPackage, AndroidAccess())

    fun cancel() = policy.cancel()

    suspend fun type(
        text: String,
        session: DisplaySession,
        isCurrent: () -> Boolean,
        validateSession: suspend () -> Boolean,
        observe: suspend () -> ScreenObservation,
        confirm: suspend (String) -> Boolean,
    ): RootExecutionResult = withContext(Dispatchers.IO) {
        policy.type(text, session, isCurrent, validateSession, observe, confirm)
    }

    private class Connection(val service: RootPilotAccessibilityService, val identity: Any)
    private data class NodeIdentity(val node: AccessibilityNodeInfo, val displayId: Int, val uniqueId: String?, val viewId: String?, val className: String?) {
        override fun toString() = "VirtualInputNode"
    }

    private class AndroidAccess : VirtualTextInputAccess {
        override fun connection(): Any? {
            val service = RootPilotAccessibilityService.connectedService ?: return null
            return Connection(service, service.connectionIdentity ?: return null)
        }

        override fun connected(connection: Any): Boolean {
            val owned = connection as Connection
            return RootPilotAccessibilityService.connectedService === owned.service &&
                owned.service.connectionIdentity === owned.identity
        }

        override suspend fun <T> query(connection: Any, collect: suspend () -> T): T =
            (connection as Connection).service.queryTree(virtual = true) {
                check(connected(connection)) { "virtual_input_connection_changed" }
                collect().also { check(connected(connection)) { "virtual_input_connection_changed" } }
            }

        override suspend fun target(connection: Any, session: DisplaySession, packageName: String): VirtualTextTarget? {
            if (!connected(connection)) return null
            val service = (connection as Connection).service
            val window = service.windowsOnAllDisplays[session.displayId]?.singleOrNull {
                it.displayId == session.displayId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
            } ?: return null
            val root = window.root ?: return null
            if (!root.refresh() || root.windowId != window.id || root.packageName?.toString() != packageName || !root.isVisibleToUser) return null
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            if (!node.refresh()) return null
            val path = safePath(node, root, packageName, window.id) ?: return null
            if (!connected(connection)) return null
            val currentWindow = node.window ?: return null
            if (currentWindow.id != window.id || currentWindow.displayId != session.displayId ||
                currentWindow.type != AccessibilityWindowInfo.TYPE_APPLICATION || !currentWindow.isFocused) return null
            val source = node.text
            val boundedSource = source?.takeIf { it.length <= InputText.MAX_LENGTH }?.toString()
            return VirtualTextTarget(
                identity(node, session.displayId), identity(root, session.displayId), node.packageName?.toString(), node.windowId,
                node.isVisibleToUser, node.isEnabled, node.isEditable, node.isFocused,
                ordinaryText(node), node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT },
                source?.isEmpty(), path, true, boundedSource, node.textSelectionStart, node.textSelectionEnd,
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION },
                // A node showing its hint exposes the hint as text, so the visible text is not the real content.
                node.isShowingHintText,
            )
        }

        private suspend fun safePath(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo, packageName: String, windowId: Int): List<VirtualTextAncestor>? {
            val path = mutableListOf<VirtualTextAncestor>()
            val seen = mutableSetOf<AccessibilityNodeInfo>()
            var ancestor: AccessibilityNodeInfo? = node
            for (depth in 0..32) {
                currentCoroutineContext().ensureActive()
                val current = ancestor ?: return null
                if (!seen.add(current) || !current.refresh() || current.packageName?.toString() != packageName ||
                    current.windowId != windowId || current.isPassword || current.isAccessibilityDataSensitive) return null
                path += VirtualTextAncestor(packageName, windowId, false, false)
                if (current == root) return path
                ancestor = current.parent
            }
            return null
        }

        override suspend fun setText(target: VirtualTextTarget, text: String, plan: VirtualTextEditPlan?,
            stillBound: () -> Boolean, validateBinding: suspend () -> Boolean): Boolean {
            val boundNode = target.nodeIdentity as NodeIdentity
            val boundRoot = target.rootIdentity as NodeIdentity
            val node = boundNode.node
            val root = boundRoot.node
            val packageName = target.packageName ?: return false
            suspend fun eligible(): Boolean {
                if (!stillBound() || safePath(node, root, packageName, target.windowId) == null ||
                    identity(root, boundRoot.displayId) != boundRoot || !node.refresh() ||
                    identity(node, boundNode.displayId) != boundNode || node.packageName?.toString() != packageName ||
                    node.windowId != target.windowId || !node.isVisibleToUser || !node.isEnabled || !node.isEditable ||
                    !node.isFocused || node.isPassword || node.isAccessibilityDataSensitive || !ordinaryText(node) ||
                    node.isShowingHintText ||
                    node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return false
                val window = node.window ?: return false
                return window.id == target.windowId && window.displayId == boundNode.displayId &&
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused && stillBound()
            }
            // Observation can suspend; refresh the source and ancestry immediately before submission.
            if (!eligible()) return false
            if (plan == null) {
                if (node.text?.isEmpty() != true) return false
            } else if (!plan.matchesSource(node.text, node.textSelectionStart, node.textSelectionEnd) ||
                node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }) return false
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan?.replacement ?: text)
            }
            if (!stillBound()) return false
            currentCoroutineContext().ensureActive()
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
            if (plan == null) return true
            currentCoroutineContext().ensureActive()
            // A filtered edit or a changed target must not receive a selection action or another SET_TEXT.
            if (!validateBinding() || !eligible() || !plan.matchesResultText(node.text) ||
                node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION }) return false
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, plan.caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, plan.caret)
            }
            if (!stillBound()) return false
            currentCoroutineContext().ensureActive()
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)) return false
            currentCoroutineContext().ensureActive()
            return eligible() && plan.matchesResult(node.text, node.textSelectionStart, node.textSelectionEnd)
        }

        private fun ordinaryText(node: AccessibilityNodeInfo) =
            node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && !InputConnectionBridge.isPassword(node.inputType)

        private fun identity(node: AccessibilityNodeInfo, displayId: Int) =
            NodeIdentity(node, displayId, node.uniqueId, node.viewIdResourceName, node.className?.toString())
    }
}
