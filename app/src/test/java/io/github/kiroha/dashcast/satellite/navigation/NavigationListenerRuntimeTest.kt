package io.github.kiroha.dashcast.satellite.navigation

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Build
import android.os.Looper
import android.provider.Settings
import io.github.kiroha.dashcast.satellite.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNotificationListenerService
import java.io.Closeable
import java.time.Duration

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationListenerRuntimeTest {
    private lateinit var controller: ServiceController<NavigationNotificationListenerService>
    private lateinit var listener: NavigationNotificationListenerService
    private lateinit var subscription: Closeable
    private val observations = mutableListOf<SourceObservation>()

    @Before fun create() {
        controller = Robolectric.buildService(NavigationNotificationListenerService::class.java).create()
        listener = controller.get()
        grant(true)
        NavigationObservationBus.configure(NavigationSource.MAPS, false)
        subscription = NavigationObservationBus.subscribe { observation, status ->
            observations.add(SourceObservation(observation, status))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun destroy() {
        NavigationObservationBus.configure(NavigationSource.MAPS, false)
        subscription.close()
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `Android 15 observes current notification and refreshes from live OS snapshot`() {
        addRoute()
        NavigationObservationBus.configure(NavigationSource.MAPS, true)
        listener.onListenerConnected()
        shadowOf(Looper.getMainLooper()).idle()
        val first = observations.last { it.observation is Observation.Valid }.observation
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val refreshed = observations.last().observation as Observation.Valid
        assertTrue(refreshed.observedAtElapsedMs > first.observedAtElapsedMs)
        assertEquals("right", refreshed.guidance.maneuver)
    }

    @Test fun `Android 15 permission revocation clears guidance without another navigation post`() {
        addRoute()
        NavigationObservationBus.configure(NavigationSource.MAPS, true)
        listener.onListenerConnected()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SourceStatus.ACTIVE, observations.last().status)
        grant(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(SourceStatus.PERMISSION_MISSING, observations.last().status)
        assertTrue(observations.last().observation is Observation.Stop)
    }

    @Test fun `Android 15 removal clears even if callback snapshot briefly retains the removed key`() {
        val key = addRoute()
        NavigationObservationBus.configure(NavigationSource.MAPS, true)
        listener.onListenerConnected()
        shadowOf(Looper.getMainLooper()).idle()
        val notification = listener.activeNotifications.single { it.key == key }
        listener.onNotificationRemoved(notification)
        assertEquals(SourceStatus.INACTIVE, observations.last().status)
        assertTrue(observations.last().observation is Observation.Stop)
        listener.cancelNotification(key) // Update the fake OS state before the next periodic read.
    }

    @Test fun `Android 15 recovery requests only already granted listener access`() {
        NavigationObservationBus.configure(NavigationSource.MAPS, true)
        val initial = ShadowNotificationListenerService.getRebindRequestCount()
        val recovery = NavigationListenerRecovery(listener)
        try {
            recovery.start()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(initial + 1, ShadowNotificationListenerService.getRebindRequestCount())
            grant(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(initial + 1, ShadowNotificationListenerService.getRebindRequestCount())
            assertEquals(SourceStatus.PERMISSION_MISSING, NavigationObservationBus.status)
        } finally { recovery.close() }
    }

    @Test
    @Config(sdk = [26])
    fun `Android 8 text-only observation avoids Icon API 28 calls`() {
        addRoute()
        NavigationObservationBus.configure(NavigationSource.MAPS, true)
        listener.onListenerConnected()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SourceStatus.ACTIVE, observations.last().status)
        assertTrue(observations.last().observation is Observation.Valid)
    }

    private fun addRoute(): String {
        val notification = Notification.Builder(listener, "navigation")
            .setSmallIcon(R.drawable.ic_satellite)
            .setContentTitle("100 m")
            .setContentText("Turn right")
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setOngoing(true).build()
        return shadowOf(listener).addActiveNotification("com.google.android.apps.maps", 1, notification)
    }

    private fun grant(granted: Boolean) {
        val component = ComponentName(listener, NavigationNotificationListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            shadowOf(listener.getSystemService(NotificationManager::class.java))
                .setNotificationListenerAccessGranted(component, granted)
        } else {
            Settings.Secure.putString(listener.contentResolver, "enabled_notification_listeners",
                if (granted) component.flattenToString() else "")
        }
    }
}
