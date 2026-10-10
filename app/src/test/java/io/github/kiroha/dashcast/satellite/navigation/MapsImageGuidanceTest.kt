package io.github.kiroha.dashcast.satellite.navigation

import io.github.kiroha.dashcast.satellite.transport.NavigationWire
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises portable semantics after the independently tested bitmap classifier. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MapsImageGuidanceTest {
    private val maps = MapsAdapter()
    private fun notification(maneuver: String? = "left", title: String = "300 m", text: String = "Example Road") =
        NavigationNotification("maps", MapsAdapter.PACKAGES.first(), true, true,
            title = title, text = text, imageManeuver = maneuver)

    @Test fun `recognized image with distance-only title produces portable v1 update`() {
        for (maneuver in listOf("left", "right", "slight_left", "slight_right", "sharp_left", "sharp_right",
                "uturn_left", "uturn_right", "straight", "destination")) {
            val guidance = (maps.parse(notification(maneuver)) as ParseResult.Valid).guidance
            val wire = JSONObject(requireNotNull(NavigationWire.encode(Observation.Valid(guidance, 100), 5, 135)))
            assertEquals(maneuver, wire.getString("maneuver"))
            assertEquals(300, wire.getInt("distanceMeters"))
            assertEquals(35, wire.getInt("ageMs"))
            assertFalse(wire.has("exit"))
            assertFalse(wire.has("iconId"))
        }
    }

    @Test fun `image cannot borrow a summary or road-name distance`() {
        for (title in listOf("", "Route 300 m", "Route 300")) {
            assertEquals(ParseResult.Unsupported, maps.parse(notification(title = title).copy(subText = "12 km · 25 min")))
        }
    }

    @Test fun `image text and resource directions must agree`() {
        assertEquals(ParseResult.Unsupported, maps.parse(notification(text = "Turn right")))
        assertEquals(ParseResult.Unsupported, maps.parse(notification().copy(iconResourceName = "ic_turn_right")))
        assertEquals(ParseResult.Unsupported, maps.parse(notification("roundabout_ccw", text = "Turn left")))
        assertTrue(maps.parse(notification(text = "Turn left").copy(iconResourceName = "ic_turn_left")) is ParseResult.Valid)
        assertTrue(maps.parse(notification(null, text = "Turn right")) is ParseResult.Valid)
    }

    @Test fun `roundabout image supplies only circulation while the text supplies the exit`() {
        for (maneuver in listOf("roundabout_ccw", "roundabout_cw")) {
            for (text in listOf("3rd exit", "Exit 3", "3e sortie", "Sortie 3", "At the roundabout take the 3rd exit")) {
                val guidance = (maps.parse(notification(maneuver, text = text)) as ParseResult.Valid).guidance
                val wire = JSONObject(requireNotNull(NavigationWire.encode(Observation.Valid(guidance, 0), 0, 0)))
                assertEquals(maneuver, wire.getString("maneuver"))
                assertEquals(3, wire.getInt("exit"))
            }
        }
        val inline = maps.parse(notification("roundabout_ccw", title = "", text = "Exit 3 in 300 m")) as ParseResult.Valid
        assertEquals(300, inline.guidance.distanceMeters)
    }

    @Test fun `absent invalid fractional or contradictory exits never acquire a guessed number`() {
        for (text in listOf("Example Road", "At the roundabout", "11th exit", "0th exit", "exit -1",
                "exit 999999999999999999999", "1.5th exit", "exit 1.5", "exit 1A", "3rd exit then 4th exit",
                "11th exit then 3rd exit", "exit 1.5 then exit 3", "exit 1 003", "1 003rd exit",
                "1\u202f003rd exit", "exit 1\u00a0003", "exit 1\t003")) {
            assertEquals(text, ParseResult.Unsupported, maps.parse(notification("roundabout_ccw", text = text)))
        }
        assertEquals(ParseResult.Unsupported, maps.parse(notification("roundabout_cw", text = "3rd exit")
            .copy(bigText = "4th exit")))
        assertTrue(maps.parse(notification("roundabout_cw", text = "3rd exit")
            .copy(bigText = "3rd exit")) is ParseResult.Valid)
        assertEquals(ParseResult.Unsupported, maps.parse(notification("left", text = "Exit 3")))
    }

    @Test fun `source loss and unsupported semantics override image evidence`() {
        for (text in listOf("GPS signal lost", "Searching for GPS", "Recalculating", "Navigation paused",
                "Merge", "Take the exit", "Tunnel", "Turn left then turn right")) {
            assertEquals(text, ParseResult.Unsupported, maps.parse(notification(text = text)))
        }
        assertEquals(ParseResult.Unsupported, maps.parse(notification("merge_right")))
        assertEquals(ParseResult.Unsupported, maps.parse(notification(null)))
    }

    @Test fun `ABRP ignores Maps image and resource evidence`() {
        val source = notification().copy(packageName = AbrpAdapter.PACKAGE, iconResourceName = "ic_turn_left")
        assertEquals(ParseResult.Unsupported, AbrpAdapter().parse(source))
        val explicit = AbrpAdapter().parse(source.copy(text = "Turn right")) as ParseResult.Valid
        assertEquals("right", explicit.guidance.maneuver)
    }

    @Test fun `unrecognized image clears prior guidance on a fresh sample`() {
        var current = notification()
        var now = 100L
        val sampler = FreshNavigationSampler({ listOf(current) }, { now })
        assertEquals(SourceStatus.ACTIVE, sampler.sample(NavigationSource.MAPS, true, true, true).status)
        current = current.copy(imageManeuver = null)
        now = 250
        val lost = sampler.sample(NavigationSource.MAPS, true, true, true)
        assertEquals(SourceStatus.UNSUPPORTED, lost.status)
        assertEquals(Observation.Stop(250), lost.observation)
        assertEquals("navigation.stop", JSONObject(requireNotNull(NavigationWire.encode(lost.observation, 1, now)))
            .getString("type"))
    }
}
