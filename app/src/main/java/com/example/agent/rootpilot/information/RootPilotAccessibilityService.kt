package com.example.agent.rootpilot.information

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional connection for on-demand semantics reads; it neither caches events nor executes actions. */
class RootPilotAccessibilityService : AccessibilityService() {
    private val treeQueries = UiTreeQueryScope(
        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS,
        readFlags = { serviceInfo?.flags },
        writeFlags = { flags ->
            val info = serviceInfo ?: error("ui_tree_service_info_unavailable")
            info.flags = flags
            serviceInfo = info
        },
        isConnected = { connectedService === this },
    )

    internal suspend fun <T> queryTree(virtual: Boolean, collect: suspend () -> T): T =
        treeQueries.query(virtual, collect)

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
        mutableConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        clearConnection()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearConnection()
        super.onDestroy()
    }

    private fun clearConnection() {
        if (connectedService === this) {
            connectedService = null
            mutableConnected.value = false
        }
    }

    companion object {
        @Volatile internal var connectedService: RootPilotAccessibilityService? = null
            private set
        private val mutableConnected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    }
}
