package io.github.kiroha.dashcast.satellite.navigation

import android.app.Notification
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.Closeable

/** System-bound, explicitly granted observer. It does not start capture or foreground services. */
class NavigationNotificationListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private var configurationSubscription: Closeable? = null
    private val resourceCache = mutableMapOf<String, Resources>()
    private var excludedKey: String? = null
    private val sampler = FreshNavigationSampler(::readCurrentNotifications, SystemClock::elapsedRealtime)
    private val tick = object : Runnable {
        override fun run() {
            sample()
            scheduleNext()
        }
    }

    override fun onCreate() {
        super.onCreate()
        configurationSubscription = NavigationObservationBus.observeConfiguration {
            // Configuration can come from transport callbacks; all listener reads stay on main.
            handler.post {
                handler.removeCallbacks(tick)
                sample()
                scheduleNext()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        NavigationObservationBus.listenerConnected = true
        handler.removeCallbacks(tick)
        sample()
        scheduleNext()
    }

    override fun onListenerDisconnected() {
        connected = false
        NavigationObservationBus.listenerConnected = false
        handler.removeCallbacks(tick)
        sample()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && acceptsSelectedSource(sbn.packageName) &&
            NavigationObservationBus.configuration.transmittingEnabled) sample()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || !acceptsSelectedSource(sbn.packageName) ||
            !NavigationObservationBus.configuration.transmittingEnabled) return
        // Some implementations briefly include a just-removed notification in the binder snapshot.
        // Never re-observe that key inside its own removal callback.
        excludedKey = sbn.key
        try { sample() } finally { excludedKey = null }
    }

    override fun onDestroy() {
        configurationSubscription?.close()
        configurationSubscription = null
        handler.removeCallbacksAndMessages(null)
        connected = false
        NavigationObservationBus.listenerConnected = false
        sample()
        resourceCache.clear()
        super.onDestroy()
    }

    private fun scheduleNext() {
        if (connected && NavigationObservationBus.configuration.transmittingEnabled) {
            handler.postDelayed(tick, 1_000L)
        }
    }

    private fun sample() {
        val configuration = NavigationObservationBus.configuration
        NavigationObservationBus.publish(sampler.sample(
            configuration.source, configuration.transmittingEnabled, connected,
            NotificationAccess.isGranted(this),
        ))
    }

    private fun readCurrentNotifications(): List<NavigationNotification> {
        // This method is called only after onListenerConnected and while transmission is enabled.
        val current = activeNotifications ?: throw IllegalStateException("Source snapshot unavailable")
        return current.asSequence().filter { acceptsSelectedSource(it.packageName) && it.key != excludedKey }
            .take(MAX_NOTIFICATIONS).map { sbn ->
                val notification = sbn.notification
                val extras = notification.extras
                fun field(key: String): String = extras?.getCharSequence(key)?.take(MAX_TEXT_UNITS)?.toString().orEmpty()
                NavigationNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                    navigationCategory = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        notification.category == Notification.CATEGORY_NAVIGATION,
                    postTime = sbn.postTime,
                    title = field(Notification.EXTRA_TITLE),
                    text = field(Notification.EXTRA_TEXT),
                    bigText = field(Notification.EXTRA_BIG_TEXT),
                    subText = field(Notification.EXTRA_SUB_TEXT),
                    iconResourceName = resourceName(sbn.packageName, notification.smallIcon),
                )
            }.toList()
    }

    private fun acceptsSelectedSource(packageName: String): Boolean = when (NavigationObservationBus.configuration.source) {
        NavigationSource.MAPS -> packageName in MapsAdapter.PACKAGES
        NavigationSource.ABRP -> packageName == AbrpAdapter.PACKAGE
    }

    private fun resourceName(packageName: String, icon: Icon?): String? {
        // Public Icon type/resource accessors arrived in API 28. Older boxes use text parsing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || icon == null ||
            icon.type != Icon.TYPE_RESOURCE || icon.resPackage != packageName ||
            packageName !in MapsAdapter.PACKAGES) return null
        return try {
            val resources = resourceCache.getOrPut(packageName) { createPackageContext(packageName, 0).resources }
            resources.getResourceEntryName(icon.resId).take(MAX_RESOURCE_NAME_UNITS)
        } catch (_: Exception) { null }
    }

    companion object {
        private const val MAX_NOTIFICATIONS = 32
        private const val MAX_TEXT_UNITS = 4_096
        private const val MAX_RESOURCE_NAME_UNITS = 256
    }
}
