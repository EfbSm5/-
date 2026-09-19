package com.example.agent.rootpilot

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

/** A notification activity dismisses the shade without bringing the control panel forward. */
class RootPilotNotificationActivity : Activity() {
    private var approvedToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        if (intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN).isNullOrBlank()) finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isFinishing && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            approvedToken = intent.getStringExtra(RootPilotService.EXTRA_APPROVAL_TOKEN)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val token = approvedToken ?: return
        approvedToken = null
        val context = applicationContext
        // ActivityThread removes the decor after onDestroy returns. Dispatch on the next
        // main-loop turn so the pending device action cannot hit this activity's window.
        Handler(Looper.getMainLooper()).post {
            context.startForegroundService(
                Intent(context, RootPilotService::class.java)
                    .setAction(RootPilotService.ACTION_CONFIRM_NOTIFICATION)
                    .putExtra(RootPilotService.EXTRA_APPROVAL_TOKEN, token),
            )
        }
    }
}
