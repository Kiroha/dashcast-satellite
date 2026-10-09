package io.github.kiroha.dashcast.satellite.navigation

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import java.io.Closeable

/** Owned by the user-enabled foreground service; requests only rebinds already authorized by Android. */
class NavigationListenerRecovery(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var delayMs = MIN_DELAY_MS
    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            val enabled = NavigationObservationBus.configuration.transmittingEnabled
            val granted = NotificationAccess.isGranted(appContext)
            if (enabled && granted && !NavigationObservationBus.listenerConnected) {
                try {
                    NotificationListenerService.requestRebind(ComponentName(appContext,
                        NavigationNotificationListenerService::class.java))
                } catch (_: RuntimeException) { /* Retry without recording OS exception payloads. */ }
                delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
            } else {
                delayMs = MIN_DELAY_MS
                if (enabled && !granted) {
                    NavigationObservationBus.publish(SourceObservation(
                        Observation.Stop(android.os.SystemClock.elapsedRealtime()), SourceStatus.PERMISSION_MISSING))
                }
            }
            handler.postDelayed(this, delayMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(check)
    }

    override fun close() {
        running = false
        handler.removeCallbacks(check)
    }

    companion object {
        private const val MIN_DELAY_MS = 2_000L
        private const val MAX_DELAY_MS = 60_000L
    }
}
