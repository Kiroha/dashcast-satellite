package io.github.kiroha.dashcast.satellite.navigation

import android.app.Application
import io.github.kiroha.dashcast.satellite.transport.LatestGuidance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NavigationObservationBusTest {
    @Test fun `fresh inactive read retries a stop that expired before dispatch`() {
        var now = 1_000L
        var reads = 0
        var current = listOf(NavigationNotification(
            key = "route", packageName = "com.google.android.apps.maps", ongoing = true,
            navigationCategory = true, title = "100 m", text = "Turn right",
        ))
        val sampler = FreshNavigationSampler({ reads++; current }, { now })
        val queue = LatestGuidance()
        fun sample() = NavigationObservationBus.publish(sampler.sample(NavigationSource.MAPS,
            transmittingEnabled = true, listenerConnected = true, permissionGranted = true))

        NavigationObservationBus.subscribe { observation, _ -> queue.offer(observation) }.use {
            sample()
            assertEquals("navigation.update", JSONObject(queue.next(now)!!).getString("type"))

            current = emptyList()
            now = 1_100
            sample()
            now = 2_601
            assertNull(queue.next(now)) // The original stop must retain its age and expire.
            assertNull(queue.next(now))

            now = 3_100
            sample() // Another actual source read still finds no guidance.
            assertEquals(3, reads)
            val retry = JSONObject(queue.next(now + 100)!!)
            assertEquals("navigation.stop", retry.getString("type"))
            assertEquals(1L, retry.getLong("seq"))
            assertEquals(100L, retry.getLong("ageMs"))
            assertEquals(SourceStatus.INACTIVE, NavigationObservationBus.status)
        }
    }
}
