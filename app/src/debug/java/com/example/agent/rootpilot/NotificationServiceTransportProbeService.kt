package com.example.agent.rootpilot

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Debug-only, no-input endpoint for a synthetic notification transport experiment. */
class NotificationServiceTransportProbeService : Service() {
    override fun onCreate() {
        super.onCreate()
        NotificationServiceTransportProbe.observer?.created(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            NotificationServiceTransportProbe.observer?.received(this, intent, startId)
        } finally {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { NotificationServiceTransportProbe.observer?.destroyed(this) } finally { super.onDestroy() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** One in-memory registration, scoped to this debug probe Service only. */
object NotificationServiceTransportProbe {
    interface Observer {
        fun created(service: NotificationServiceTransportProbeService)
        fun received(service: NotificationServiceTransportProbeService, intent: Intent?, startId: Int)
        fun destroyed(service: NotificationServiceTransportProbeService)
    }
    @Volatile var observer: Observer? = null
        private set
    @Synchronized fun register(value: Observer): Boolean {
        if (observer != null) return false
        observer = value
        return true
    }
    @Synchronized fun unregister(value: Observer): Boolean {
        if (observer !== value) return false
        observer = null
        return true
    }
}
