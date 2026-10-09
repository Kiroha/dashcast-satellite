package io.github.kiroha.dashcast.satellite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.kiroha.dashcast.satellite.navigation.NavigationObservationBus
import io.github.kiroha.dashcast.satellite.navigation.NavigationListenerRecovery
import io.github.kiroha.dashcast.satellite.pairing.PairingStore
import io.github.kiroha.dashcast.satellite.transport.SatelliteTransport
import java.io.Closeable

class SatelliteService : Service() {
    private var transport: SatelliteTransport? = null
    private var subscription: Closeable? = null
    private var session = 0L
    private var startupFailed = false
    private var listenerRecovery: NavigationListenerRecovery? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = SatelliteSettings(this)
        if (intent?.action == ACTION_STOP || !settings.enabled) {
            settings.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (transport == null) session = SatelliteState.beginSession()
            showForeground()
            if (transport == null) {
                val profile = PairingStore(this).load() ?: error("missing_profile")
                val owner = session
                val sender = SatelliteTransport(this) { SatelliteState.update(owner, it) }
                transport = sender
                subscription = NavigationObservationBus.subscribe { observation, _ -> sender.offer(observation) }
                sender.start(profile)
            }
            NavigationObservationBus.configure(settings.source, transmittingEnabled = true)
            if (listenerRecovery == null) {
                listenerRecovery = NavigationListenerRecovery(this).also { it.start() }
            }
        } catch (_: Exception) {
            startupFailed = true
            settings.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun showForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, SatelliteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_satellite)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_notification))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(1, notification)
    }

    override fun onDestroy() {
        listenerRecovery?.close()
        listenerRecovery = null
        NavigationObservationBus.configure(SatelliteSettings(this).source, transmittingEnabled = false)
        subscription?.close()
        subscription = null
        transport?.stop()
        transport = null
        SatelliteState.endSession(session, startupFailed)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "io.github.kiroha.dashcast.satellite.STOP"
        private const val CHANNEL = "satellite_connection"
    }
}
