package io.github.kiroha.dashcast.satellite.transport

import android.app.Application
import io.github.kiroha.dashcast.satellite.navigation.Guidance
import io.github.kiroha.dashcast.satellite.navigation.Observation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NavigationWireTest {
    private fun valid(at: Long, distance: Int = 200) = Observation.Valid(Guidance("right", distance), at)

    @Test fun `pinned receiver compatibility fixtures are unchanged and executed`() {
        val bytes = javaClass.getResourceAsStream("/v1/navigation-fixtures.json")!!.use { it.readBytes() }
        assertEquals("ccf14b618bfbbe09af7ddbc9f3d1d7e778f9c6102ca5b0932daab9fb027be2bc",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val fixture = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(1, fixture.getInt("version"))
        val accepted = fixture.getJSONArray("accepted")
        repeat(accepted.length()) { NavigationWire.validate(accepted.getJSONObject(it)) }
        val rejected = fixture.getJSONArray("rejected")
        repeat(rejected.length()) { index ->
            try { NavigationWire.validate(rejected.getJSONObject(index)); fail("Rejected fixture accepted: $index") }
            catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
        }
    }

    @Test fun `bounded delivery drops replaced observations and preserves queued source age`() {
        val queue = LatestGuidance()
        repeat(10_000) { queue.offer(valid(1_000, it)) }
        val frame = JSONObject(queue.next(2_000)!!)
        assertEquals(9_999, frame.getInt("distanceMeters"))
        assertEquals(1_000L, frame.getLong("ageMs"))
        assertNull(queue.next(2_000))
        queue.offer(valid(1_000))
        assertNull(queue.next(2_501))
        queue.offer(valid(4_000))
        assertNull(queue.next(3_999))
    }

    @Test fun `stops supersede updates and share monotonic sequence within a socket`() {
        val queue = LatestGuidance()
        queue.offer(valid(1_000))
        assertEquals(0L, JSONObject(queue.next(1_000)!!).getLong("seq"))
        queue.offer(valid(1_100))
        queue.offer(Observation.Stop(1_200))
        val stop = JSONObject(queue.next(1_300)!!)
        assertEquals("navigation.stop", stop.getString("type"))
        assertEquals(1L, stop.getLong("seq"))
        assertEquals(100L, stop.getLong("ageMs"))
    }

    @Test fun `reconnect resets sequence but cannot refresh cached source age`() {
        val queue = LatestGuidance()
        queue.offer(valid(1_000))
        queue.next(1_000)
        queue.newSession()
        val frame = JSONObject(queue.next(2_000)!!)
        assertEquals(0L, frame.getLong("seq"))
        assertEquals(1_000L, frame.getLong("ageMs"))
        queue.newSession()
        assertNull(queue.next(2_501))
    }

    @Test fun `invalid guidance is never serialized into a guessed maneuver`() {
        assertNull(NavigationWire.encode(Observation.Valid(Guidance("unknown", 20), 10), 1, 10))
        assertNull(NavigationWire.encode(Observation.Valid(Guidance("roundabout_cw", 20), 10), 1, 10))
        assertNull(NavigationWire.encode(Observation.Valid(Guidance("right", 20, roadName = "road\nname"), 10), 1, 10))
        assertNull(NavigationWire.encode(valid(0, -1), 1, 0))
        assertNotNull(NavigationWire.encode(valid(0), 1, 1_500))
        assertNull(NavigationWire.encode(valid(0), 1, 1_501))
    }

    @Test fun `generic roundabouts require a strict capability and downgrade to a fresh ordered stop`() {
        for (value in listOf(null, JSONObject.NULL, false, "true", 1, JSONObject())) {
            val welcome = JSONObject().apply { if (value != null) put("genericRoundabouts", value) }
            assertFalse(NavigationWire.supportsGenericRoundabouts(welcome))
        }
        assertTrue(NavigationWire.supportsGenericRoundabouts(JSONObject().put("genericRoundabouts", true)))
        val observation = Observation.Valid(Guidance("roundabout_generic_ccw", 80), 100)
        val update = JSONObject(NavigationWire.encode(observation, 8, 135, genericRoundabouts = true)!!)
        assertEquals("navigation.update", update.getString("type"))
        assertFalse(update.has("exit"))
        val stop = JSONObject(NavigationWire.encode(observation, 8, 135)!!)
        assertEquals("navigation.stop", stop.getString("type"))
        assertEquals(8L, stop.getLong("seq"))
        assertEquals(35L, stop.getLong("ageMs"))
        assertEquals(3, stop.length())
        val queue = LatestGuidance()
        queue.offer(valid(100))
        assertEquals(0L, JSONObject(queue.next(100)!!).getLong("seq"))
        queue.offer(observation)
        assertEquals("navigation.stop", JSONObject(queue.next(135)!!).getString("type"))
        queue.offer(valid(200))
        assertEquals(2L, JSONObject(queue.next(200)!!).getLong("seq"))
        queue.newSession()
        queue.offer(observation)
        assertNull(queue.next(1601, genericRoundabouts = true))
    }

    @Test fun `generic frames with explicit exits remain invalid even for older receivers`() {
        for (exit in listOf(JSONObject.NULL, 0, 1, 10, 11, "1", true)) {
            val json = JSONObject().put("type", "navigation.update").put("seq", 0).put("ageMs", 0)
                .put("maneuver", "roundabout_generic_cw").put("distanceMeters", 50).put("exit", exit)
            try { NavigationWire.validate(json); fail("generic exit accepted") }
            catch (_: IllegalArgumentException) {} catch (_: org.json.JSONException) {}
        }
        for (capable in listOf(false, true)) {
            assertNull(NavigationWire.encode(Observation.Valid(Guidance("roundabout_generic_cw", 50, exit = 1), 0),
                0, 0, capable))
            assertNull(NavigationWire.encode(Observation.Valid(Guidance("roundabout_generic_cw", -1), 0),
                0, 0, capable))
        }
    }

    @Test fun `reconnect backoff increases is bounded and resets after stable session`() {
        val backoff = ReconnectBackoff()
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..7).map { backoff.nextDelayMs(0.5) })
        repeat(100) { assertTrue(backoff.nextDelayMs(1.0) <= 30_000) }
        backoff.reset()
        assertEquals(400L, backoff.nextDelayMs(0.0))
    }
}
