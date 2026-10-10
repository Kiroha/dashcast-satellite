package io.github.kiroha.dashcast.satellite

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import io.github.kiroha.dashcast.satellite.navigation.NavigationNotificationListenerService
import io.github.kiroha.dashcast.satellite.navigation.NotificationAccess
import io.github.kiroha.dashcast.satellite.transport.TransportState
import io.github.kiroha.dashcast.satellite.transport.TransportStatus
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainActivityStatusTest {
    @Test fun `restricted access help opens this app info without granting notification access`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertFalse(NotificationAccess.isGranted(activity))
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.restricted_access_help).visibility)
            activity.findViewById<View>(R.id.open_app_info).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals("package:${activity.packageName}", intent.data.toString())
            assertFalse(NotificationAccess.isGranted(activity))
            activity.findViewById<View>(R.id.grant_access).performClick()
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `returning after the user grants access hides restricted settings help`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            controller.pause()
            shadowOf(activity.getSystemService(NotificationManager::class.java))
                .setNotificationListenerAccessGranted(ComponentName(activity,
                    NavigationNotificationListenerService::class.java), true)
            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.restricted_access_help).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.open_app_info).visibility)
            assertEquals(activity.getString(R.string.access_on), activity.findViewById<TextView>(R.id.access_state).text)
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [32])
    fun `older Android does not show instructions for unavailable restricted settings`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertEquals(View.GONE, controller.get().findViewById<View>(R.id.restricted_access_help).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `retry details identify the attempted address without displaying arbitrary diagnostic text`() {
        val owner = SatelliteState.beginSession()
        SatelliteState.update(owner, TransportStatus(TransportState.CONNECTING,
            detail = "connection_timeout", endpointHost = "192.168.49.1"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val details = activity.findViewById<TextView>(R.id.connection_detail)
            assertTrue(details.text.contains("192.168.49.1"))
            assertTrue(details.text.contains(activity.getString(R.string.connection_timeout)))
            SatelliteState.update(owner, TransportStatus(TransportState.RECONNECTING,
                detail = "private server response must not reach the screen"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals(View.GONE, details.visibility)
            assertEquals("", details.text.toString())
        } finally { controller.pause().stop().destroy(); SatelliteState.endSession(owner) }
    }

    @Test fun `a connected receiver with guidance disabled is distinct from an unconfirmed connection`() {
        val owner = SatelliteState.beginSession()
        SatelliteState.update(owner, TransportStatus(TransportState.CONNECTED, remoteGuidance = false))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val receiver = activity.findViewById<TextView>(R.id.receiver_state)
            assertTrue(receiver.text.contains(activity.getString(R.string.receiver_guidance_off)))
            SatelliteState.update(owner, TransportStatus(TransportState.RECONNECTING))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertTrue(receiver.text.contains(activity.getString(R.string.receiver_disabled)))
        } finally { controller.pause().stop().destroy(); SatelliteState.endSession(owner) }
    }
}
