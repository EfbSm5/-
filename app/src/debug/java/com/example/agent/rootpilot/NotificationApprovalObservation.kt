package com.example.agent.rootpilot

import java.security.MessageDigest

/** Read-only success evidence; never approves, waits, or retains a plaintext approval token. */
internal object NotificationApprovalObservation {
    data class Success(val runId: String, val approvalTokenDigest: String)

    @Volatile var lastSuccess: Success? = null
        private set

    // Called only after approve(token) won, while the controller still holds its state lock.
    fun succeeded(runId: String?, token: String) {
        if (runId == null) return
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            lastSuccess = Success(runId, digest)
        }
    }
}
