package com.example.agent.rootpilot.information

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.rootpilot.screen.ScreenObservation
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidUiTreeProvider(private val ownPackage: String) : UiTreeProvider {
    override suspend fun query(expected: ScreenObservation): DeviceInfoResult = withContext(Dispatchers.IO) {
        val startedAt = elapsedMillis()
        fun unavailable(reason: DeviceInfoUnavailable) = DeviceInfoResult(
            DeviceInfoSource.UI_SEMANTICS, startedAt, elapsedMillis(), unavailable = reason,
        )
        val targetPackage = expected.foregroundPackage
        if (targetPackage == ownPackage) return@withContext unavailable(DeviceInfoUnavailable.PROTECTED_APP)
        if (targetPackage == null || targetPackage != expected.focusedPackage) {
            return@withContext unavailable(DeviceInfoUnavailable.TARGET_NOT_READY)
        }
        val service = RootPilotAccessibilityService.connectedService
            ?: return@withContext unavailable(DeviceInfoUnavailable.NOT_ENABLED)
        try {
            val result = withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) {
                currentCoroutineContext().ensureActive()
                val root = service.rootInActiveWindow
                currentCoroutineContext().ensureActive()
                if (root == null || root.packageName?.toString() != targetPackage || !root.isVisibleToUser) {
                    return@withTimeoutOrNull unavailable(DeviceInfoUnavailable.TARGET_NOT_READY)
                }
                val window = root.window?.let { UiTreeWindowSnapshot(it.id, it.displayId, it.isFocused) }
                if (window?.accepts(root.windowId) != true) {
                    return@withTimeoutOrNull unavailable(DeviceInfoUnavailable.TARGET_NOT_READY)
                }
                val nodes = mutableListOf<UiNodeSnapshot>()
                val pending = ArrayDeque<PendingNode>()
                pending.add(PendingNode(root, null, 0, false))
                var incomplete = false
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    if (elapsedMillis() - startedAt >= QUERY_TIMEOUT_MILLIS) {
                        return@withTimeoutOrNull unavailable(DeviceInfoUnavailable.TIMEOUT)
                    }
                    if (nodes.size == UiTreeSnapshot.MAX_NODES) {
                        incomplete = true
                        break
                    }
                    val item = pending.removeLast()
                    val node = item.node
                    if (node.windowId != window.id) {
                        return@withTimeoutOrNull unavailable(DeviceInfoUnavailable.TARGET_NOT_READY)
                    }
                    if (item.depth > UiTreeSnapshot.MAX_DEPTH || node.packageName?.toString() != targetPackage || !node.isVisibleToUser) {
                        incomplete = true
                        continue
                    }
                    val bounds = Rect().also(node::getBoundsInScreen)
                    val id = "n${nodes.size}"
                    val sensitive = item.sensitive || node.isPassword || node.isAccessibilityDataSensitive
                    nodes += UiNodeSnapshot(
                        id, item.parentId, targetPackage, node.className?.toString(), node.viewIdResourceName,
                        if (sensitive) null else node.text?.toString(),
                        if (sensitive) null else node.contentDescription?.toString(),
                        if (sensitive) null else node.hintText?.toString(),
                        bounds.left, bounds.top, bounds.right, bounds.bottom,
                        node.isClickable, node.isEditable, node.isEnabled, node.isFocused,
                        node.isScrollable, node.isSelected, node.isCheckable, node.isChecked, sensitive,
                    )
                    val childLimit = minOf(node.childCount, UiTreeSnapshot.MAX_NODES - pending.size)
                    if (childLimit < node.childCount) incomplete = true
                    for (index in childLimit - 1 downTo 0) {
                        currentCoroutineContext().ensureActive()
                        val child = node.getChild(index)
                        if (child == null) incomplete = true
                        else pending.add(PendingNode(child, id, item.depth + 1, sensitive))
                    }
                }
                currentCoroutineContext().ensureActive()
                if (RootPilotAccessibilityService.connectedService !== service) {
                    return@withTimeoutOrNull unavailable(DeviceInfoUnavailable.NOT_ENABLED)
                }
                val currentRoot = service.rootInActiveWindow
                val currentWindow = currentRoot?.window?.let { UiTreeWindowSnapshot(it.id, it.displayId, it.isFocused) }
                currentCoroutineContext().ensureActive()
                if (currentRoot == null || currentRoot.packageName?.toString() != targetPackage ||
                    currentWindow?.accepts(currentRoot.windowId, window.id) != true) {
                    return@withTimeoutOrNull unavailable(DeviceInfoUnavailable.TARGET_NOT_READY)
                }
                if (elapsedMillis() - startedAt >= QUERY_TIMEOUT_MILLIS) {
                    return@withTimeoutOrNull unavailable(DeviceInfoUnavailable.TIMEOUT)
                }
                val formatted = UiTreeSnapshot.format(nodes, targetPackage, incomplete)
                DeviceInfoResult(DeviceInfoSource.UI_SEMANTICS, startedAt, elapsedMillis(),
                    formatted.data, truncated = formatted.truncated)
            }
            result ?: unavailable(DeviceInfoUnavailable.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable(DeviceInfoUnavailable.COLLECTION_FAILED)
        }
    }

    private data class PendingNode(val node: AccessibilityNodeInfo, val parentId: String?, val depth: Int, val sensitive: Boolean)

    private fun elapsedMillis(): Long = System.nanoTime() / 1_000_000

    private companion object {
        const val QUERY_TIMEOUT_MILLIS = 3_000L
    }
}
