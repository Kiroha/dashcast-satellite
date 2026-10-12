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

    @Test fun `absent exit retains only observed circulation and does not invent an exit`() {
        for (direction in listOf("cw", "ccw")) for (text in listOf("Example Road", "At the roundabout", "N59 Example Road")) {
            val guidance = (maps.parse(notification("roundabout_$direction", text = text)) as ParseResult.Valid).guidance
            assertEquals("roundabout_generic_$direction", guidance.maneuver)
            assertNull(guidance.exit)
            val wire = JSONObject(requireNotNull(NavigationWire.encode(Observation.Valid(guidance, 0), 0, 0,
                genericRoundabouts = true)))
            assertFalse(wire.has("exit"))
            assertEquals(guidance.maneuver, wire.getString("maneuver"))
        }
        assertEquals(ParseResult.Unsupported, maps.parse(notification(null, text = "At the roundabout")))
        assertEquals(ParseResult.Unsupported, maps.parse(notification("roundabout_ccw", title = "", text = "Example Road")))
    }

    @Test fun `French numeric and spelled ordinals retain the correct observed exit`() {
        for ((text, exit) in listOf("1ère sortie" to 1, "1ere sortie" to 1, "première sortie" to 1,
            "premiere sortie" to 1, "deuxième sortie" to 2, "2ème sortie" to 2,
            "Au rond-point, prendre la troisième sortie" to 3, "dixième sortie" to 10)) {
            val guidance = (maps.parse(notification("roundabout_ccw", text = text)) as ParseResult.Valid).guidance
            assertEquals(text, exit, guidance.exit)
            assertEquals("roundabout_ccw", guidance.maneuver)
        }
        val evaluator = NavigationSnapshotEvaluator()
        val generic = evaluator.evaluate(NavigationSource.MAPS, listOf(notification("roundabout_cw")), 100)
        assertEquals(SourceStatus.ACTIVE, generic.status)
        assertTrue(generic.observation is Observation.Valid)
        val following = evaluator.evaluate(NavigationSource.MAPS, listOf(notification("left")), 200)
        assertEquals("left", (following.observation as Observation.Valid).guidance.maneuver)
        assertEquals(200L, following.observation.observedAtElapsedMs)
    }

    @Test fun `route connectors after a known ordinal never invalidate the numbered maneuver`() {
        for (text in listOf("At the roundabout take the 3rd exit toward Paris",
            "At the roundabout take the 3rd exit north", "At the roundabout take the 3rd exit with signs for Paris",
            "Au rond-point prendre la troisième sortie direction Paris",
            "Au rond-point prendre la 3e sortie rue Example")) {
            val guidance = (maps.parse(notification("roundabout_ccw", text = text)) as ParseResult.Valid).guidance
            assertEquals(text, 3, guidance.exit)
            assertEquals("roundabout_ccw", guidance.maneuver)
        }
    }

    @Test fun `invalid fractional or contradictory exits never become generic or acquire a guessed number`() {
        for (text in listOf("11th exit", "eleventh exit", "onzième sortie", "0th exit", "exit -1",
                "twelfth exit", "exit twelfth", "douzième sortie", "sortie douzième",
                "3e sortie puis douzième sortie", "exit - 1", "sortie + 2", "exit nonsense",
                "3e sortie douzième", "twelfth exit 3",
                "twenty first exit", "twenty-first exit", "twenty third exit", "one hundred and first exit",
                "vingt première sortie", "vingt et première sortie", "- 1st exit",
                "twenty exit 3", "vingt sortie 3",
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
