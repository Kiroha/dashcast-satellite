package io.github.kiroha.dashcast.satellite.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.Icon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native pixels exercise the actual field PNG, rather than Robolectric's legacy bitmap stub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapsManeuverImageTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()

    @Test fun `original field capture and its horizontal reflection resolve distinct directions`() {
        val left = capture()
        val right = Bitmap.createBitmap(left, 0, 0, left.width, left.height,
            Matrix().apply { setScale(-1f, 1f) }, false)
        try {
            val a = MapsManeuverImage.read(ctx, Icon.createWithBitmap(left))
            val b = MapsManeuverImage.read(ctx, Icon.createWithBitmap(right))
            assertEquals("left", a)
            assertEquals("right", b)
            assertNotEquals(a, b)
            assertFalse(left.isRecycled)
            assertFalse(right.isRecycled)
        } finally { left.recycle(); right.recycle() }
    }

    @Test fun `blank solid coloured and URI icons cannot become a turn`() {
        val bitmap = Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888)
        try {
            for (colour in listOf(Color.TRANSPARENT, Color.WHITE, Color.BLUE)) {
                bitmap.eraseColor(colour)
                assertNull(MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)))
            }
            assertNull(MapsManeuverImage.read(ctx, Icon.createWithContentUri("content://private/nav-image")))
            assertNull(MapsManeuverImage.read(ctx, null))
        } finally { bitmap.recycle() }
    }

    @Test fun `oversized and undersized icons are rejected without recycling their bitmap`() {
        for ((width, height) in listOf(7 to 54, 54 to 7, 257 to 54, 54 to 257)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                assertNull(MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)))
                assertFalse(bitmap.isRecycled)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `coloured glyph nonuniform corners and encoded data icons are unsupported`() {
        val original = capture()
        val coloured = requireNotNull(original.copy(Bitmap.Config.ARGB_8888, true))
        val nonuniform = requireNotNull(original.copy(Bitmap.Config.ARGB_8888, true))
        try {
            for (y in 0 until coloured.height) for (x in 0 until coloured.width) {
                val alpha = Color.alpha(coloured.getPixel(x, y))
                coloured.setPixel(x, y, Color.argb(alpha, 0, 0, 255))
            }
            nonuniform.setPixel(0, 0, Color.WHITE)
            assertNull(MapsManeuverImage.read(ctx, Icon.createWithBitmap(coloured)))
            assertNull(MapsManeuverImage.read(ctx, Icon.createWithBitmap(nonuniform)))
            val png = requireNotNull(javaClass.getResourceAsStream(
                "/navigation/maps-left-seal-20261009.png")).use { it.readBytes() }
            assertNull(MapsManeuverImage.read(ctx, Icon.createWithData(png, 0, png.size)))
        } finally { original.recycle(); coloured.recycle(); nonuniform.recycle() }
    }

    @Test
    @Config(sdk = [26, 27])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `older Android versions do not access the unavailable public icon type API`() {
        val bitmap = Bitmap.createBitmap(54, 54, Bitmap.Config.ARGB_8888)
        try {
            assertNull(MapsManeuverImage.read(ctx, Icon.createWithBitmap(bitmap)))
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    private fun capture(): Bitmap = requireNotNull(BitmapFactory.decodeStream(
        javaClass.getResourceAsStream("/navigation/maps-left-seal-20261009.png")))
}
