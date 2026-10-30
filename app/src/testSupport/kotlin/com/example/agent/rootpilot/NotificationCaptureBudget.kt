package com.example.agent.rootpilot

/** Scalar observations of existing cut branches, never estimates of the unseen tree. */
internal object NotificationCaptureBudget {
    enum class Site { NONE, INITIAL_CURRENT, REBIND_CURRENT, AFTER_EXPAND }
    enum class Cause { NONE, DEPTH_LIMIT, NODE_LIMIT, BOTH }
    enum class Limit { DEPTH_LIMIT, NODE_LIMIT }

    data class Summary(
        val budgetCause: Cause,
        val visitedNodes: Int,
        val maxVisitedDepth: Int,
        val pendingAtFirstCut: Int,
        val firstCutVisitedNodes: Int,
        val firstCutDepth: Int,
    )
    data class Report(val captureSite: Site, val summary: Summary)
    val NONE = Report(Site.NONE, Summary(Cause.NONE, -1, -1, -1, -1, -1))

    /** Only the original TRUNCATED decision publishes its own capture, not a later/earlier phase. */
    fun report(site: Site, code: NotificationExpansionEvidence.Code, summary: Summary?): Report =
        if (code == NotificationExpansionEvidence.Code.EXPANSION_TRUNCATED && site != Site.NONE &&
            summary != null && summary.budgetCause != Cause.NONE) Report(site, summary) else NONE

    class Recorder(private val pending: () -> Int) {
        private var visitedNodes = 0
        private var maxVisitedDepth = -1
        private var depthLimited = false
        private var nodeLimited = false
        private var pendingAtFirstCut = -1
        private var firstCutVisitedNodes = -1
        private var firstCutDepth = -1

        fun visited(depth: Int) {
            visitedNodes++
            maxVisitedDepth = maxOf(maxVisitedDepth, depth)
        }
        fun cut(limit: Limit, depth: Int) {
            if (firstCutVisitedNodes == -1) {
                // Only already admitted wrappers awaiting description; not omitted/unseen children.
                pendingAtFirstCut = pending()
                firstCutVisitedNodes = visitedNodes
                firstCutDepth = depth
            }
            when (limit) {
                Limit.DEPTH_LIMIT -> depthLimited = true
                Limit.NODE_LIMIT -> nodeLimited = true
            }
        }
        fun snapshot(): Summary = Summary(when {
            depthLimited && nodeLimited -> Cause.BOTH
            depthLimited -> Cause.DEPTH_LIMIT
            nodeLimited -> Cause.NODE_LIMIT
            else -> Cause.NONE
        }, visitedNodes, maxVisitedDepth, pendingAtFirstCut, firstCutVisitedNodes, firstCutDepth)
    }
}
