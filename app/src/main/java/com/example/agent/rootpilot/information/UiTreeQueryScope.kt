package com.example.agent.rootpilot.information

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes this service's tree reads while a virtual query temporarily includes layout nodes. */
internal class UiTreeQueryScope(
    private val layoutFlag: Int,
    private val readFlags: () -> Int?,
    private val writeFlags: (Int) -> Unit,
    private val isConnected: () -> Boolean,
) {
    private val mutex = Mutex()
    private var usable = true

    suspend fun <T> query(virtual: Boolean, collect: suspend () -> T): T = mutex.withLock {
        check(usable && isConnected()) { "ui_tree_scope_unavailable" }
        if (!virtual) return@withLock collect()
        val original = readFlags() ?: error("ui_tree_service_info_unavailable")
        val expanded = original or layoutFlag
        if (expanded == original) return@withLock collect()
        var failure: Throwable? = null
        try {
            writeFlags(expanded)
            check(readFlags() == expanded && isConnected()) { "ui_tree_layout_flag_unavailable" }
            collect()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val restored = withContext(NonCancellable) {
                runCatching {
                    if (!isConnected()) false else {
                        writeFlags(original)
                        val flags = readFlags()
                        isConnected() && flags == original
                    }
                }.getOrDefault(false)
            }
            if (!restored) {
                // A failed restoration must not let later main-screen reads inherit expanded flags.
                usable = false
                val error = IllegalStateException("ui_tree_layout_flag_restore_failed")
                if (failure == null) throw error else failure.addSuppressed(error)
            }
        }
    }
}
