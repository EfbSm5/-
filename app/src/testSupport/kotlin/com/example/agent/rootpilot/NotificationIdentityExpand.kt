package com.example.agent.rootpilot

import java.util.regex.Pattern

/** Exact expand identity shared by the no-model probe and the gated Type notification test. */
internal object NotificationIdentityExpand {
    val resourcePattern: Pattern = Pattern.compile("^(?:android:id/expand_button|com\\.android\\.systemui:id/expand_button)$")
    enum class Code { AMBIGUOUS, CHANGED }
    class Failure(val code: Code) : RuntimeException()
    data class Candidate<N : Any>(val node: N, val packageName: String?, val resourceName: String?,
        val enabled: Boolean, val clickable: Boolean) {
        val available: Boolean get() = enabled && clickable
    }

    /** The caller retains every returned wrapper before constructing this metadata-only list. */
    fun <N : Any> initial(found: List<Candidate<N>>): Candidate<N>? {
        // Never choose a namespace first, deduplicate, or filter an ambiguous returned list.
        if (found.size > 1) throw Failure(Code.AMBIGUOUS)
        val value = found.singleOrNull() ?: return null
        if (value.packageName != "com.android.systemui" || value.resourceName == null ||
            !resourcePattern.matcher(value.resourceName).matches()) throw Failure(Code.CHANGED)
        return value
    }

    fun <N : Any> fresh(expected: Candidate<N>, found: List<Candidate<N>>): Candidate<N> {
        val value = initial(found) ?: throw Failure(Code.CHANGED)
        if (value.node != expected.node || value.resourceName != expected.resourceName ||
            !expected.available || !value.available) throw Failure(Code.CHANGED)
        return value
    }
}
