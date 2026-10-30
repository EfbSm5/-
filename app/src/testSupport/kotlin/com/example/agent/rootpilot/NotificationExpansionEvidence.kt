package com.example.agent.rootpilot

/** Pure decisions over the complete visible, bounded own-row snapshot, not a semantic expand API. */
internal object NotificationExpansionEvidence {
    private const val SYSTEM_UI = "com.android.systemui"
    enum class Code {
        NONE, EXPANSION_PHASE_INVALID, EXPANSION_TRUNCATED, EXPANSION_IDENTITY_INVALID,
        EXPANSION_INITIAL_CURRENT_IDENTITY_INVALID, EXPANSION_REBIND_CURRENT_IDENTITY_INVALID,
        EXPANSION_AFTER_EXPAND_IDENTITY_INVALID, EXPANSION_MODAL_PREFIX_IDENTITY_INVALID,
        EXPANSION_ACTION_ALREADY_PRESENT, EXPANSION_ACTION_MISSING,
        EXPANSION_ACTION_AMBIGUOUS, EXPANSION_ACTION_UNAVAILABLE,
        EXPANSION_INITIAL_BIND_ACTIVE_OWN, EXPANSION_INITIAL_BIND_ACTIVE_OTHER, EXPANSION_INITIAL_BIND_PACKAGE_UNAVAILABLE,
        EXPANSION_REBIND_ACTIVE_OWN, EXPANSION_REBIND_ACTIVE_OTHER, EXPANSION_REBIND_PACKAGE_UNAVAILABLE,
        EXPANSION_PRE_GESTURE_ACTIVE_OWN, EXPANSION_PRE_GESTURE_ACTIVE_OTHER, EXPANSION_PRE_GESTURE_PACKAGE_UNAVAILABLE,
        EXPANSION_AFTER_BIND_ACTIVE_OWN, EXPANSION_AFTER_BIND_ACTIVE_OTHER, EXPANSION_AFTER_BIND_PACKAGE_UNAVAILABLE,
        EXPANSION_AFTER_VALIDATE_ACTIVE_OWN, EXPANSION_AFTER_VALIDATE_ACTIVE_OTHER, EXPANSION_AFTER_VALIDATE_PACKAGE_UNAVAILABLE,
    }
    enum class GuardSite { INITIAL_BIND, REBIND, PRE_GESTURE, AFTER_BIND, AFTER_VALIDATE }
    enum class IdentitySite { INITIAL_CURRENT, REBIND_CURRENT, AFTER_EXPAND, MODAL_PREFIX }

    fun identityReason(longPress: Boolean, site: IdentitySite?, code: Code): Code {
        if (!longPress || code != Code.EXPANSION_IDENTITY_INVALID) return code
        return when (site) {
            IdentitySite.INITIAL_CURRENT -> Code.EXPANSION_INITIAL_CURRENT_IDENTITY_INVALID
            IdentitySite.REBIND_CURRENT -> Code.EXPANSION_REBIND_CURRENT_IDENTITY_INVALID
            IdentitySite.AFTER_EXPAND -> Code.EXPANSION_AFTER_EXPAND_IDENTITY_INVALID
            IdentitySite.MODAL_PREFIX -> Code.EXPANSION_MODAL_PREFIX_IDENTITY_INVALID
            null -> code
        }
    }

    /** Categorizes only the active-root package returned by the existing public guard, never window contents. */
    fun activeRootReason(site: GuardSite, packageName: String?): Code {
        if (packageName == SYSTEM_UI) return Code.NONE
        val category = when (packageName) {
            null -> "PACKAGE_UNAVAILABLE"
            "com.example.agent" -> "ACTIVE_OWN"
            else -> "ACTIVE_OTHER"
        }
        return Code.valueOf("EXPANSION_${site.name}_$category")
    }

    fun longPressAvailable(enabled: Boolean, longClickable: Boolean, boundsNonempty: Boolean): Boolean =
        enabled && longClickable && boundsNonempty

    fun failurePublishable(reason: String, cleanupReason: String, baselineRestored: Boolean,
        observerVerified: Boolean, hasBytes: Boolean): Boolean =
        reason == "IDENTITY_ROW_UNRELIABLE" && cleanupReason == "NONE" && baselineRestored && observerVerified && hasBytes

    fun <N : Any> before(snapshot: NotificationIdentityEvidence.Snapshot<N>): Code {
        if (snapshot.phase != NotificationIdentityEvidence.Phase.CURRENT || snapshot.modalBinding) return Code.EXPANSION_PHASE_INVALID
        identity(snapshot, requireMeasuredIcon = true).let { if (it != Code.NONE) return it }
        return if (snapshot.entries.any { it.node.confirmActionMatches || it.node.stopActionMatches })
            Code.EXPANSION_ACTION_ALREADY_PRESENT else Code.NONE
    }

    fun <N : Any> after(snapshot: NotificationIdentityEvidence.Snapshot<N>): Code {
        if (snapshot.phase != NotificationIdentityEvidence.Phase.AFTER_EXPAND) return Code.EXPANSION_PHASE_INVALID
        identity(snapshot, requireMeasuredIcon = false).let { if (it != Code.NONE) return it }
        val confirm = snapshot.entries.filter { it.node.confirmActionMatches }
        val stop = snapshot.entries.filter { it.node.stopActionMatches }
        if (confirm.size > 1 || stop.size > 1) return Code.EXPANSION_ACTION_AMBIGUOUS
        if (confirm.isEmpty() || stop.isEmpty()) return Code.EXPANSION_ACTION_MISSING
        return if (confirm.single().path != stop.single().path && (confirm + stop).all { it.path != "0" && it.node.packageName == SYSTEM_UI &&
                it.node.enabled && it.node.clickable }) Code.NONE else Code.EXPANSION_ACTION_UNAVAILABLE
    }

    private fun <N : Any> identity(snapshot: NotificationIdentityEvidence.Snapshot<N>, requireMeasuredIcon: Boolean): Code {
        if (snapshot.truncated) return Code.EXPANSION_TRUNCATED
        if (!snapshot.validContentScope()) return Code.EXPANSION_IDENTITY_INVALID
        val root = snapshot.entries.firstOrNull()?.node ?: return Code.EXPANSION_IDENTITY_INVALID
        if (snapshot.titleCount != 1 || root.packageName != SYSTEM_UI || !root.enabled || !root.clickable ||
            root.scrollable || root.className != "android.widget.FrameLayout") return Code.EXPANSION_IDENTITY_INVALID
        val entries = snapshot.entries
        val named = entries.filter { it.node.resourceName == "$SYSTEM_UI:id/app_name_text" }
        if (snapshot.captureRootPath != null) {
            val titles = entries.filter { NotificationIdentityEvidence.titleResourcePattern.matcher(it.node.resourceName.orEmpty()).matches() }
            val icons = entries.filter { it.node.resourceName == "$SYSTEM_UI:id/app_icon" }
            if (titles.size != 1 || !titles.single().node.titleMatches || named.size > 1 || icons.size > 1)
                return Code.EXPANSION_IDENTITY_INVALID
        }
        // Preflight is the measured icon-only layout. Afterwards use the same supported name/icon rules as normal identity.
        if (requireMeasuredIcon && named.isNotEmpty()) return Code.EXPANSION_IDENTITY_INVALID
        val result = NotificationAppIdentity.verify(named,
            { entries.filter { it.node.resourceName == "$SYSTEM_UI:id/app_icon" } },
            { entry: NotificationIdentityEvidence.Entry -> entry.node.let {
                NotificationAppIdentity.Node(it.resourceName, it.packageName, it.className,
                    it.descriptionEqualsAppLabel, it.textEqualsAppLabel, it.clickable, it.longClickable, it.enabled, it.scrollable)
            } },
            { entry -> entries.singleOrNull { it.path == entry.path.substringBeforeLast('/', "") } },
            { it.path == "0" }, entries.single { it.node.titleMatches }, { a, b -> a.path == b.path }, modalBinding = snapshot.modalBinding)
        return if (result == NotificationAppIdentity.Result.VERIFIED) Code.NONE else Code.EXPANSION_IDENTITY_INVALID
    }
}
