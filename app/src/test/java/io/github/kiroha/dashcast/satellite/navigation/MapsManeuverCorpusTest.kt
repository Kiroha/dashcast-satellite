package io.github.kiroha.dashcast.satellite.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest

/** Fixtures come from the APK's notification SVGs, independently rasterized with Cairo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsManeuverCorpusTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val manifest get() = JSONObject(requireNotNull(javaClass.getResourceAsStream(
        "/navigation/maps-26.33/manifest.json")).bufferedReader().use { it.readText() })

    @Test fun `notification SVG corpus obeys portable labels and explicit unsupported expectations`() {
        val entries = manifest.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val name = entry.getString("name")
            val bitmap = fixture(name)
            try {
                val result = MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap))
                assertEquals(name, expected(entry), result)
                assertFalse(bitmap.isRecycled)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `fixture bytes and retained reference masks match their reviewed provenance`() {
        assertEquals("3565221625ef0b3b0e039d7662abff0f5f39aca6", manifest.getString("receiverCommit"))
        assertEquals(586, manifest.getInt("referenceCount"))
        assertEquals(586, MapsManeuverReferences.entries.size)
        assertEquals(84, MapsManeuverReferences.entries.count { it.maneuver == null })
        for (group in listOf("entries", "negative")) {
            val entries = manifest.getJSONArray(group)
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val name = entry.getString("name")
                val bytes = requireNotNull(javaClass.getResourceAsStream(
                    "/navigation/maps-26.33/$name.png")).use { it.readBytes() }
                assertEquals(name, entry.getString("pngSha256"), sha256(bytes))
                if (entry.has("unsupportedReason")) {
                    assertTrue(entry.isNull("expectedManeuver"))
                    assertTrue(entry.getString("unsupportedReason").isNotBlank())
                }
            }
        }
        val field = manifest.getJSONObject("fieldCapture")
        assertEquals("a26fe1ac20b8fd8be272332e7a1f4f92e11f80f9", field.getString("receiverCommit"))
        val bytes = requireNotNull(javaClass.getResourceAsStream(
            "/navigation/maps-left-seal-20261009.png")).use { it.readBytes() }
        assertEquals(field.getString("pngSha256"), sha256(bytes))
    }

    @Test fun `merge glyphs remain rejecting competitors at all common bitmap sizes`() {
        for (name in listOf("ic_merge", "ic_merge_right", "ic_merge_right_mirrored",
            "ic_merge_slight_right", "ic_merge_slight_right_mirrored")) {
            val original = fixture(name)
            try {
                for (edge in listOf(48, 54, 64, 72, 96, 108)) {
                    val scaled = Bitmap.createScaledBitmap(original, edge, edge, true)
                    try {
                        assertNull("$name at $edge", MapsManeuverImage.read(ctx, Icon.createWithBitmap(scaled)))
                    } finally { if (scaled !== original) scaled.recycle() }
                }
            } finally { original.recycle() }
        }
    }

    @Test fun `resampled notifications retain direction across common bitmap sizes`() {
        val entries = manifest.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val name = entry.getString("name")
            val original = fixture(name)
            try {
                for (edge in listOf(48, 64, 72, 96, 108)) {
                    val scaled = Bitmap.createScaledBitmap(original, edge, edge, true)
                    try {
                        assertEquals("$name at $edge", expected(entry),
                            MapsManeuverImage.read(ctx, Icon.createWithBitmap(scaled)))
                    } finally { scaled.recycle() }
                }
            } finally { original.recycle() }
        }
    }

    @Test fun `opaque black and white notification backgrounds retain roundabout direction`() {
        for (name in listOf("ic_roundabout_left", "ic_roundabout_straight",
                "ic_roundabout_right_mirrored", "ic_u_turn", "ic_straight")) {
            val source = fixture(name)
            try {
                val expected = MapsManeuverImage.read(ctx, Icon.createWithBitmap(source))
                for (background in listOf(Color.BLACK, Color.WHITE)) {
                    val bitmap = Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(background)
                        val paint = Paint().apply {
                            colorFilter = android.graphics.PorterDuffColorFilter(
                                if (background == Color.WHITE) Color.BLACK else Color.WHITE,
                                android.graphics.PorterDuff.Mode.SRC_IN)
                        }
                        Canvas(bitmap).drawBitmap(source, 0f, 0f, paint)
                        assertNotNull(name, expected)
                        assertEquals("$name on $background", expected,
                            MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)))
                    } finally { bitmap.recycle() }
                }
            } finally { source.recycle() }
        }
    }

    @Test fun `unknown and transport pictograms never become a driving maneuver`() {
        for (name in listOf("da_turn_unknown", "da_turn_ferry", "ferry_train")) {
            val bitmap = fixture(name)
            try {
                assertEquals(name, null, MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `cropped or combined guidance glyphs cannot silently become a different turn`() {
        val source = fixture("ic_roundabout_left")
        val composite = Bitmap.createBitmap(108, 54, Bitmap.Config.ARGB_8888)
        try {
            Canvas(composite).apply {
                drawBitmap(source, 0f, 0f, null)
                drawBitmap(source, 54f, 0f, null)
            }
            assertNull(MapsManeuverImage.read(ctx, Icon.createWithBitmap(composite)))
            val cropped = Bitmap.createBitmap(source, 0, 18, source.width, source.height - 18)
            try {
                assertNull(MapsManeuverImage.read(ctx, Icon.createWithBitmap(cropped)))
            } finally { cropped.recycle() }
        } finally { source.recycle(); composite.recycle() }
    }

    @Test fun `flattening a roundabout highlighted path removes reliable circulation evidence`() {
        val source = fixture("ic_roundabout_left")
        val bitmap = requireNotNull(source.copy(Bitmap.Config.ARGB_8888, true))
        source.recycle()
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) >= 128) bitmap.setPixel(x, y, Color.WHITE)
                else bitmap.setPixel(x, y, Color.TRANSPARENT)
            }
            assertNull(MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)))
        } finally { bitmap.recycle() }
    }

    @Test fun `canonical directions retain the explicit domain mapping`() {
        val cases = listOf("ic_straight" to "straight",
            "ic_u_turn" to "uturn_left",
            "ic_u_turn_mirrored" to "uturn_right",
            "ic_roundabout_left" to "roundabout_ccw",
            "ic_roundabout_right" to "roundabout_ccw",
            "ic_roundabout_straight" to "roundabout_ccw",
            "ic_roundabout_straight_mirrored" to "roundabout_cw")
        for ((name, expected) in cases) {
            val bitmap = fixture(name)
            try {
                assertEquals(name, expected, MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)))
            } finally { bitmap.recycle() }
        }
    }

    private fun expected(entry: JSONObject): String? =
        if (entry.isNull("expectedManeuver")) null else entry.getString("expectedManeuver")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun fixture(name: String): Bitmap = requireNotNull(BitmapFactory.decodeStream(
        javaClass.getResourceAsStream("/navigation/maps-26.33/$name.png")))
}
