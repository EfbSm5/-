package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.root.RootExecutionResult
import com.example.agent.rootpilot.screen.DisplaySession
import com.example.agent.rootpilot.screen.ScreenObservation
import com.example.agent.rootpilot.screen.sameTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull

internal data class VirtualTextAncestor(val packageName: String?, val windowId: Int, val password: Boolean, val sensitive: Boolean)

/** Opaque identities with a bounded runtime-only source for nonempty plain-text edits. */
internal data class VirtualTextTarget(
    val nodeIdentity: Any,
    val rootIdentity: Any,
    val packageName: String?,
    val windowId: Int,
    val visible: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val ordinaryText: Boolean,
    val setTextSupported: Boolean,
    val empty: Boolean?,
    val ancestors: List<VirtualTextAncestor>,
    val rootReached: Boolean,
    val sourceText: String? = null,
    val selectionStart: Int = -1,
    val selectionEnd: Int = -1,
    val setSelectionSupported: Boolean = false,
    val hintShown: Boolean = false,
) {
    fun accepts(packageName: String): Boolean = this.packageName == packageName && windowId >= 0 &&
        visible && enabled && editable && focused && ordinaryText && setTextSupported && !hintShown &&
        (empty == true || empty == false && !sourceText.isNullOrEmpty() && sourceText.length <= InputText.MAX_LENGTH &&
            setSelectionSupported) &&
        rootReached && ancestors.size in 1..33 && ancestors.all {
            it.packageName == packageName && it.windowId == windowId && !it.password && !it.sensitive
        }

    fun sameBinding(other: VirtualTextTarget): Boolean = packageName == other.packageName &&
        windowId == other.windowId && nodeIdentity == other.nodeIdentity && rootIdentity == other.rootIdentity

    override fun toString() = "VirtualTextTarget"
}

/** The Android adapter only exposes an owned-display root inside the service's shared query scope. */
internal interface VirtualTextInputAccess {
    fun connection(): Any?
    fun connected(connection: Any): Boolean
    suspend fun <T> query(connection: Any, collect: suspend () -> T): T
    suspend fun target(connection: Any, session: DisplaySession, packageName: String): VirtualTextTarget?
    suspend fun setText(target: VirtualTextTarget, text: String, plan: VirtualTextEditPlan?,
        stillBound: () -> Boolean, validateBinding: suspend () -> Boolean): Boolean
}

internal class VirtualDisplayTextInputPolicy(
    private val ownPackage: String,
    private val access: VirtualTextInputAccess,
    private val timeoutMillis: Long = 3_000,
) {
    init { require(timeoutMillis > 0) }
    private val lock = Any()
    private val operations = mutableSetOf<Job>()

    fun cancel() {
        synchronized(lock) { operations.toList() }.forEach { it.cancel(CancellationException("Virtual input cancelled")) }
    }

    suspend fun type(
        text: String,
        session: DisplaySession,
        isCurrent: () -> Boolean,
        validateSession: suspend () -> Boolean,
        observe: suspend () -> ScreenObservation,
        confirm: suspend (String) -> Boolean,
    ): RootExecutionResult = coroutineScope {
        val operation = currentCoroutineContext().job
        synchronized(lock) { operations.add(operation) }
        var attempted = false
        var rejected: String? = null
        fun failed(reason: String? = null) = RootExecutionResult.Failure(reason ?: if (attempted)
            "副屏输入可能已生效，但提交或目标／服务状态未确认；请核对结果，勿直接重放"
            else "副屏输入目标不可用或已变化，未提交文本")
        try {
            currentCoroutineContext().ensureActive()
            if (!InputText.isValid(text)) return@coroutineScope RootExecutionResult.Failure("输入文本不合法")
            val connection = access.connection() ?: return@coroutineScope failed()
            fun bound() = isCurrent() && access.connected(connection)
            suspend fun observation(expected: ScreenObservation? = null): ScreenObservation? {
                if (!bound() || !validateSession() || !bound()) return null
                val current = observe()
                currentCoroutineContext().ensureActive()
                if (!bound() || !validateSession() || !bound() ||
                    current.displayId != session.displayId || current.sessionId != session.sessionId ||
                    !current.sameTarget(current) || current.foregroundPackage != current.focusedPackage ||
                    current.foregroundPackage == ownPackage || current.keyboardVisible == null) return null
                if (expected != null && (!expected.sameTarget(current) || expected.keyboardVisible != current.keyboardVisible)) return null
                return current
            }

            val prepared = withTimeoutOrNull(timeoutMillis) {
                val expected = observation() ?: return@withTimeoutOrNull null
                val packageName = expected.foregroundPackage ?: return@withTimeoutOrNull null
                val target = access.query(connection) {
                    access.target(connection, session, packageName)?.takeIf { it.accepts(packageName) }
                } ?: return@withTimeoutOrNull null
                val plan = if (target.empty == false) {
                    val created = VirtualTextEditPlan.create(target.sourceText,
                        target.selectionStart, target.selectionEnd, text)
                    if (created == null) {
                        rejected = "副屏输入旧文／选区或结果文本不符合规则，未提交文本"
                        return@withTimeoutOrNull null
                    }
                    created
                } else null
                if (observation(expected) == null) return@withTimeoutOrNull null
                Triple(expected, target, plan)
            } ?: return@coroutineScope failed(rejected)
            val (expected, original, plan) = prepared
            currentCoroutineContext().ensureActive()
            if (!bound()) return@coroutineScope failed()
            // The query scope has restored its flags and released its mutex before approval.
            if (!confirm(requireNotNull(original.packageName))) return@coroutineScope RootExecutionResult.Failure("用户取消输入")
            currentCoroutineContext().ensureActive()
            val accepted = withTimeoutOrNull(timeoutMillis) {
                access.query(connection) {
                    if (observation(expected) == null) return@query false
                    val packageName = requireNotNull(original.packageName)
                    val fresh = access.target(connection, session, packageName) ?: return@query false
                    if (!fresh.accepts(packageName) || !original.sameBinding(fresh) || !bound()) return@query false
                    if (plan == null) {
                        if (fresh.empty != true) return@query false
                    } else if (fresh.empty != false ||
                        !plan.matchesSource(fresh.sourceText, fresh.selectionStart, fresh.selectionEnd)) return@query false
                    if (observation(expected) == null) return@query false
                    currentCoroutineContext().ensureActive()
                    attempted = true
                    access.setText(fresh, text, plan, ::bound) { observation(expected) != null }
                }
            } ?: return@coroutineScope failed()
            currentCoroutineContext().ensureActive()
            if (!accepted || withTimeoutOrNull(timeoutMillis) { observation(expected) } == null) return@coroutineScope failed()
            RootExecutionResult.Success(if (plan == null) "已向空副屏输入框提交文本，仍需观察确认实际内容"
                else "已向副屏输入框提交文本并核对光标，仍需观察确认实际内容")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed()
        } finally {
            synchronized(lock) { operations.remove(operation) }
        }
    }
}
