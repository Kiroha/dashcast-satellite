package io.github.kiroha.dashcast.satellite.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAdaptersTest {
    private val maps = MapsAdapter()
    private fun notification(
        title: String = "300 m",
        text: String = "Turn right onto Example Road",
        icon: String? = null,
    ) = NavigationNotification(
        key = "maps-navigation", packageName = "com.google.android.apps.maps",
        ongoing = true, navigationCategory = true,
        title = title, text = text, iconResourceName = icon,
    )

    @Test fun `explicit Maps instruction produces portable guidance`() {
        val result = maps.parse(notification()) as ParseResult.Valid
        assertEquals("right", result.guidance.maneuver)
        assertEquals(300, result.guidance.distanceMeters)
        assertEquals("Example Road", result.guidance.roadName)
    }

    @Test fun `French instruction with prefix distance parses without generic word inference`() {
        val result = maps.parse(notification(title = "Dans 1,2 km, tournez à gauche", text = "")) as ParseResult.Valid
        assertEquals("left", result.guidance.maneuver)
        assertEquals(1200, result.guidance.distanceMeters)
    }

    @Test fun `resource name resolves maneuver when Maps text contains only a street`() {
        val result = maps.parse(notification(text = "Example Road", icon = "ic_maneuver_turn_left_v2")) as ParseResult.Valid
        assertEquals("left", result.guidance.maneuver)
        assertEquals(300, result.guidance.distanceMeters)
    }

    @Test fun `image-only maneuver and distance remain unsupported`() {
        assertEquals(ParseResult.Unsupported, maps.parse(notification(text = "Example Road")))
    }

    @Test fun `direction without distance remains unsupported`() {
        assertEquals(ParseResult.Unsupported, maps.parse(notification(title = "", text = "Turn right")))
    }

    @Test fun `route summary distance never substitutes for distance to maneuver`() {
        assertEquals(ParseResult.Unsupported, maps.parse(notification(title = "", text = "Turn right")
            .copy(subText = "12 km · 25 min")))
    }

    @Test fun `ambiguous instructions do not acquire a guessed handedness`() {
        listOf("Make a U-turn", "At the roundabout take the 3rd exit", "Take the exit", "Merge", "Continue")
            .forEach { assertEquals(it, ParseResult.Unsupported, maps.parse(notification(text = it))) }
    }

    @Test fun `generic resource names never infer a turn`() {
        listOf("ic_maneuver_u_turn", "ic_maneuver_merge", "ic_maneuver_roundabout", "ic_launcher", "ic_continue")
            .forEach { assertEquals(it, ParseResult.Unsupported, maps.parse(notification(text = "Example Road", icon = it))) }
    }

    @Test fun `roundabout needs both observed circulation and exit`() {
        val instruction = notification(text = "At the roundabout take the 3rd exit", icon = "ic_roundabout_ccw")
        val result = maps.parse(instruction) as ParseResult.Valid
        assertEquals("roundabout_ccw", result.guidance.maneuver)
        assertEquals(3, result.guidance.exit)
        assertEquals(ParseResult.Unsupported, maps.parse(instruction.copy(text = "At the roundabout")))
        assertEquals(ParseResult.Unsupported, maps.parse(instruction.copy(iconResourceName = "ic_roundabout")))
        assertEquals(ParseResult.Unsupported, maps.parse(instruction.copy(text = "At the roundabout take the 11th exit")))
    }

    @Test fun `conflicting icon and text or multiple steps are unsupported`() {
        assertEquals(ParseResult.Unsupported, maps.parse(notification(text = "Turn right", icon = "ic_turn_left")))
        assertEquals(ParseResult.Unsupported, maps.parse(notification(text = "Turn right then turn left")))
        assertEquals(ParseResult.Unsupported, maps.parse(notification(text = "At the roundabout take the 3rd exit", icon = "ic_turn_right")))
    }

    @Test fun `GPS loss cannot reuse an old direction`() {
        assertEquals(ParseResult.Unsupported, maps.parse(notification(text = "GPS signal lost", icon = "ic_turn_right")))
    }

    @Test fun `ABRP accepts explicit text only and never borrows Maps resource mapping`() {
        val abrp = AbrpAdapter()
        val explicit = notification().copy(packageName = AbrpAdapter.PACKAGE)
        assertTrue(abrp.parse(explicit) is ParseResult.Valid)
        assertEquals(ParseResult.Unsupported, abrp.parse(explicit.copy(text = "Example Road", iconResourceName = "ic_turn_right")))
        assertEquals(ParseResult.Unsupported, abrp.parse(explicit.copy(text = "A Better Routeplanner", title = "Planning")))
    }

    @Test fun `package lookalikes and other source notifications are excluded`() {
        assertEquals(ParseResult.Unsupported, maps.parse(notification().copy(packageName = "com.google.android.apps.maps.fake")))
        assertEquals(ParseResult.Unsupported, AbrpAdapter().parse(notification()))
    }

    @Test fun `metric imperial and Arabic distances convert and durations do not become distance`() {
        assertEquals(1200, NavTextParsers.meters("١,٢ كم"))
        assertEquals(1200, NavTextParsers.meters("۱,۲ كم"))
        assertEquals(300, NavTextParsers.meters("300 м"))
        assertEquals(152, NavTextParsers.meters("500 ft"))
        assertEquals(1609, NavTextParsers.meters("1 mi"))
        assertEquals(91, NavTextParsers.meters("100 yd"))
        assertNull(NavTextParsers.meters("25 min"))
        assertNull(NavTextParsers.meters("٢٥ دقيقة"))
    }

    @Test fun `distance bounds and invalid numeric values fail closed`() {
        assertEquals(0, NavTextParsers.meters("0 m"))
        assertEquals(1_000_000, NavTextParsers.meters("1000 km"))
        listOf("1001 km", "9999999999999999999999999999999999 km", "-100 m", "1.2.3 km", "NaN km")
            .forEach { assertNull(it, NavTextParsers.meters(it)) }
    }

    @Test fun `remaining time normalizes Arabic and rejects overflow`() {
        assertEquals(1500, NavTextParsers.remainingSeconds("٢٥ دقيقة"))
        assertEquals(3900, NavTextParsers.remainingSeconds("1 h 05 min"))
        assertNull(NavTextParsers.remainingSeconds("9999999999999999999999999 h"))
        assertNull(NavTextParsers.remainingSeconds("169 h"))
    }

    @Test fun `route and notification contents are not present in diagnostic strings`() {
        val notification = notification()
        val result = maps.parse(notification) as ParseResult.Valid
        assertTrue(!notification.toString().contains("Example Road"))
        assertTrue(!Observation.Valid(result.guidance, 0).toString().contains("Example Road"))
    }
}
