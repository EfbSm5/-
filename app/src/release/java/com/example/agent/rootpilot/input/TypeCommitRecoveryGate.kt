package com.example.agent.rootpilot.input

import com.example.agent.rootpilot.root.RootExecutionResult

/** Release has no test control state or instrumentation entry points. */
internal object TypeCommitRecoveryGate {
    @Suppress("UNUSED_PARAMETER")
    suspend fun aroundSubmission(targetPackage: String, submit: suspend () -> RootExecutionResult): RootExecutionResult = submit()
}
