package io.github.kiroha.dashcast.satellite.navigation

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import io.github.kiroha.dashcast.satellite.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.Closeable
import java.time.Duration

/** Native pixels exercise acquisition through the real listener and fresh OS notification snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationListenerImageTest {
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

    @Test fun `native large Maps bitmap supplies guidance when notification text contains no turn`() {
        addRoute(fixture("ic_turn_right"))
        val beforeRead = SystemClock.elapsedRealtime()
        connect()
        val current = valid()
        assertEquals("right", current.guidance.maneuver)
        assertEquals(100, current.guidance.distanceMeters)
        assertEquals(beforeRead, current.observedAtElapsedMs)
    }

    @Test fun `image-only changes with identical text key and post time are freshly observed`() {
        val key = addRoute(fixture("ic_turn_right"))
        connect()
        assertEquals("right", valid().guidance.maneuver)
        val previousTime = valid().observedAtElapsedMs
        listener.cancelNotification(key)
        val replacement = addRoute(fixture("ic_straight"))
        assertEquals(key, replacement)
        listener.onNotificationPosted(listener.activeNotifications.single { it.key == replacement })
        assertEquals("straight", valid().guidance.maneuver)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("straight", valid().guidance.maneuver)
        assertTrue(valid().observedAtElapsedMs > previousTime)
    }

    @Test fun `unknown replacement bitmap clears old guidance and removal remains inactive`() {
        val key = addRoute(fixture("ic_turn_right"))
        connect()
        assertEquals(SourceStatus.ACTIVE, observations.last().status)
        listener.cancelNotification(key)
        addRoute(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        val replacement = listener.activeNotifications.single { it.key == key }
        listener.onNotificationPosted(replacement)
        assertEquals(SourceStatus.UNSUPPORTED, observations.last().status)
        assertTrue(observations.last().observation is Observation.Stop)
        listener.onNotificationRemoved(replacement)
        assertEquals(SourceStatus.INACTIVE, observations.last().status)
        assertTrue(observations.last().observation is Observation.Stop)
        listener.cancelNotification(key)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(SourceStatus.INACTIVE, observations.last().status)
    }

    @Test fun `ABRP never borrows Maps raster maneuvers and retains explicit text guidance`() {
        val key = addRoute(fixture("ic_turn_right"), packageName = AbrpAdapter.PACKAGE)
        connect(NavigationSource.ABRP)
        assertEquals(SourceStatus.UNSUPPORTED, observations.last().status)
        assertTrue(observations.last().observation is Observation.Stop)
        listener.cancelNotification(key)
        val updated = addRoute(fixture("ic_turn_right"), packageName = AbrpAdapter.PACKAGE, text = "Turn left")
        listener.onNotificationPosted(listener.activeNotifications.single { it.key == updated })
        assertEquals("left", valid().guidance.maneuver)
    }

    @Test fun `large icon follows navigation category then posting time of the selected candidate`() {
        addRoute(fixture("ic_turn_right"), id = 1, navigationCategory = true, postTime = 1)
        addRoute(fixture("ic_straight"), id = 2, navigationCategory = false, postTime = 200)
        connect()
        assertEquals("right", valid().guidance.maneuver)
        val newest = addRoute(fixture("ic_straight"), id = 3, navigationCategory = true, postTime = 300)
        val notification = listener.activeNotifications.single { it.key == newest }
        listener.onNotificationPosted(notification)
        assertEquals("straight", valid().guidance.maneuver)
        listener.onNotificationRemoved(notification)
        assertEquals("right", valid().guidance.maneuver)
        listener.cancelNotification(newest)
    }

    @Test fun `non-navigation non-ongoing Maps images cannot activate guidance`() {
        addRoute(fixture("ic_turn_right"), navigationCategory = false, ongoing = false)
        connect()
        assertEquals(SourceStatus.INACTIVE, observations.last().status)
        assertTrue(observations.last().observation is Observation.Stop)
    }

    @Test fun `permission revocation and listener disconnect clear image-derived guidance`() {
        addRoute(fixture("ic_turn_right"))
        connect()
        assertEquals(SourceStatus.ACTIVE, observations.last().status)
        grant(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(SourceStatus.PERMISSION_MISSING, observations.last().status)
        assertTrue(observations.last().observation is Observation.Stop)
        grant(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(SourceStatus.ACTIVE, observations.last().status)
        listener.onListenerDisconnected()
        assertEquals(SourceStatus.SOURCE_UNAVAILABLE, observations.last().status)
        assertTrue(observations.last().observation is Observation.Stop)
    }

    @Test
    @Config(sdk = [26])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `Android 8 remains text-only even when a conflicting Maps bitmap is present`() {
        addRoute(fixture("ic_turn_right"), text = "Turn left")
        connect()
        assertEquals("left", valid().guidance.maneuver)
    }

    private fun valid(): Observation.Valid {
        assertEquals(SourceStatus.ACTIVE, observations.last().status)
        return observations.last().observation as Observation.Valid
    }

    private fun connect(source: NavigationSource = NavigationSource.MAPS) {
        NavigationObservationBus.configure(source, true)
        listener.onListenerConnected()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun addRoute(bitmap: Bitmap, packageName: String = "com.google.android.apps.maps",
        id: Int = 1, text: String = "Main Street", navigationCategory: Boolean = true,
        ongoing: Boolean = true, postTime: Long = 100): String {
        val notification = Notification.Builder(listener, "navigation")
            .setSmallIcon(R.drawable.ic_satellite)
            .setLargeIcon(bitmap)
            .setContentTitle("100 m")
            .setContentText(text)
            .setCategory(if (navigationCategory) Notification.CATEGORY_NAVIGATION else Notification.CATEGORY_STATUS)
            .setOngoing(ongoing)
            .setWhen(postTime)
            .build()
        return shadowOf(listener).addActiveNotification(packageName, id, notification)
    }

    private fun fixture(name: String): Bitmap = requireNotNull(javaClass
        .getResourceAsStream("/navigation/maps-26.33/$name.png")).use {
            requireNotNull(BitmapFactory.decodeStream(it))
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
