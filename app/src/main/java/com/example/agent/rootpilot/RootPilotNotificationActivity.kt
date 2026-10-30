package com.example.agent.rootpilot

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.example.agent.rootpilot.model.ConfirmationStage

/** A notification activity dismisses the shade without bringing the control panel forward. */
class RootPilotNotificationActivity : Activity() {
    private var approvedToken: String? = null
    private var stage: ConfirmationStage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        stage = if (!intent.hasExtra(RootPilotService.EXTRA_APPROVAL_STAGE)) ConfirmationStage.ACTION else
            ConfirmationStage.entries.singleOrNull { it.name == intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_STAGE) }
        if (stage == null || stage == ConfirmationStage.TYPE_COMMIT ||
            intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN).isNullOrBlank()) finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val unlocked = runCatching {
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        }.getOrDefault(false)
        if (!unlocked && stage == ConfirmationStage.TYPE_PREPARE) {
            intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN)?.let(RootPilotService::cancelTypePreparation)
            approvedToken = null
            finish()
        } else if (hasFocus && !isFinishing && unlocked) {
            approvedToken = intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val token = approvedToken ?: return
        val approvedStage = stage ?: return
        approvedToken = null
        val context = applicationContext
        // ActivityThread removes the decor after onDestroy returns. Dispatch on the next
        // main-loop turn so the pending device action cannot hit this activity's window.
        Handler(Looper.getMainLooper()).post {
            context.startForegroundService(
                Intent(context, RootPilotService::class.java)
                    .setAction(RootPilotService.ACTION_CONFIRM_NOTIFICATION)
                    .putExtra(RootPilotService.EXTRA_APPROVAL_TOKEN, token)
                    .putExtra(RootPilotService.EXTRA_APPROVAL_STAGE, approvedStage.name),
            )
        }
    }
}
