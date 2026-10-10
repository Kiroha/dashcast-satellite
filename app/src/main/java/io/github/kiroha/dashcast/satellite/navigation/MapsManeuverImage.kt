package io.github.kiroha.dashcast.satellite.navigation

// Adapted from DashCast, Copyright (c) 2026 Cedric Carre, MIT.
// Full upstream license: docs/PARSER_UPSTREAM_LICENSE.txt.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build

/** Bounded Maps notification-glyph matching ported from the reviewed DashCast receiver.
 * Provenance and fixture hashes are pinned in the test corpus manifest. Only portable maneuvers
 * leave this matcher; roundabout circulation never supplies an exit number.
 */
internal object MapsManeuverImage {
    private const val EDGE = 32
    private const val MAX_INPUT_EDGE = 256
    private const val MAX_ERROR = 0.22
    private const val MIN_MARGIN = 0.15

    /** Reads one selected source icon. Never opens URI icons or recycles a source bitmap. */
    fun read(context: Context, icon: Icon?): String? {
        if (Build.VERSION.SDK_INT < 28 || icon == null ||
            icon.type !in intArrayOf(Icon.TYPE_BITMAP, Icon.TYPE_RESOURCE)) {
            return null
        }
        var owned: Bitmap? = null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else {
                val width = drawable.intrinsicWidth
                val height = drawable.intrinsicHeight
                if (width !in 8..MAX_INPUT_EDGE || height !in 8..MAX_INPUT_EDGE) {
                    return null
                }
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    owned = it
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(Canvas(it))
                }
            }
            val width = bitmap.width
            val height = bitmap.height
            if (width !in 8..MAX_INPUT_EDGE || height !in 8..MAX_INPUT_EDGE) {
                return null
            }
            val readable = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { owned = it }
                    ?: return null
            } else bitmap
            val pixels = IntArray(width * height)
            readable.getPixels(pixels, 0, width, 0, 0, width, height)
            recognize(width, height, pixels)
        } catch (_: Exception) {
            null
        } finally { owned?.recycle() }
    }

    private fun recognize(width: Int, height: Int, pixels: IntArray): String? {
        // The capture has a transparent background. Also accept a uniform opaque background,
        // without accepting coloured logos, gradients, photos, or adaptive-icon clipping.
        val corners = intArrayOf(pixels[0], pixels[width - 1], pixels[width * (height - 1)], pixels.last())
        val transparent = corners.all { Color.alpha(it) < 32 }
        val background = corners.first()
        if (!transparent && !corners.all { colourDistance(it, background) <= 12 }) {
            return null
        }
        val foreground = BooleanArray(pixels.size)
        val strongForeground = BooleanArray(pixels.size)
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            val colour = pixels[y * width + x]
            val visible = Color.alpha(colour) >= 128 &&
                (transparent || colourDistance(colour, background) >= 100)
            if (!visible) continue
            val r = Color.red(colour); val g = Color.green(colour); val b = Color.blue(colour)
            if (maxOf(r, g, b) - minOf(r, g, b) > 20) return null
            foreground[y * width + x] = true
            strongForeground[y * width + x] = if (transparent) Color.alpha(colour) >= 192
                else colourDistance(colour, background) >= 192
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val glyphWidth = maxX - minX + 1
        val glyphHeight = maxY - minY + 1
        if (glyphWidth < 8 || glyphHeight < 8) return null
        val rows = IntArray(EDGE)
        val strongRows = IntArray(EDGE)
        for (y in 0 until EDGE) for (x in 0 until EDGE) {
            val sx = minX + minOf(glyphWidth - 1, (2 * x + 1) * glyphWidth / (2 * EDGE))
            val sy = minY + minOf(glyphHeight - 1, (2 * y + 1) * glyphHeight / (2 * EDGE))
            if (foreground[sy * width + sx]) rows[y] = rows[y] or (1 shl x)
            if (strongForeground[sy * width + sx]) strongRows[y] = strongRows[y] or (1 shl x)
        }
        val aspect = glyphWidth.toDouble() / glyphHeight
        // Preserve the complete field-validated turn matcher. Notification.Builder may resample
        // its thin boundary, so adding an emphasis plane must not narrow the established path.
        if (kotlin.math.abs(aspect - 45.0 / 39.0) <= 0.08) {
            val leftError = error(rows, leftRows)
            val rightError = error(rows, rightRows)
            if (minOf(leftError, rightError) <= MAX_ERROR &&
                    kotlin.math.abs(leftError - rightError) >= MIN_MARGIN) {
                return if (leftError < rightError) "left" else "right"
            }
        }
        var best = 1.0
        var next = 1.0
        var winner: MapsManeuverReferences.Reference? = null
        for (reference in MapsManeuverReferences.entries) {
            // Bounding-box normalization must preserve aspect, especially for narrow straight arrows.
            if (kotlin.math.abs(aspect - reference.aspect) > 0.08) continue
            // A single silhouette loses the highlighted path through a dim roundabout ring.
            // Match both coverage and emphasis, retaining the existing narrow topology halo.
            val score = maxOf(error(rows, reference.rows), error(strongRows, reference.strongRows))
            val sameLabel = winner?.label == reference.label
            if (score < best) {
                if (!sameLabel) next = best
                best = score
                winner = reference
            } else if (!sameLabel) next = minOf(next, score)
        }
        val match = winner
        if (match == null || best > MAX_ERROR || next - best < MIN_MARGIN) return null
        return match.maneuver
    }

    private fun colourDistance(a: Int, b: Int): Int = maxOf(
        kotlin.math.abs(Color.red(a) - Color.red(b)),
        kotlin.math.abs(Color.green(a) - Color.green(b)),
        kotlin.math.abs(Color.blue(a) - Color.blue(b)))

    private fun error(actual: IntArray, reference: IntArray): Double {
        var difference = 0; var union = 0; var outside = 0
        for (i in actual.indices) {
            difference += Integer.bitCount(actual[i] xor reference[i])
            union += Integer.bitCount(actual[i] or reference[i])
            outside += Integer.bitCount(actual[i] and halo(reference, i).inv())
            outside += Integer.bitCount(reference[i] and halo(actual, i).inv())
        }
        // Notification.Builder may resample the original bitmap. Permit one boundary pixel,
        // but require the foreground topology to remain inside that narrow symmetric halo.
        return if (union == 0 || outside.toDouble() / union > 0.02) 1.0
            else difference.toDouble() / union
    }

    private fun halo(rows: IntArray, y: Int): Int {
        var row = rows[y]
        if (y > 0) row = row or rows[y - 1]
        if (y < EDGE - 1) row = row or rows[y + 1]
        return row or (row shl 1) or (row ushr 1)
    }

    // Derived from the original non-location PNG in the driver's Oct 9 export (message 105).
    // This is our own capture-based mask, not an OpenBYD signature or asset registry.
    // Keep the field reference alongside the APK corpus to preserve the established turn behavior.
    private val leftRows = arrayOf(
        ".........##.....................", "........###.....................",
        "......######....................", "......#####.....................",
        ".....######.....................", "....######......................",
        "....#####.......................", "..######........................",
        ".#######........................", ".##########################.....",
        "############################....", ".#############################..",
        ".##############################.", "..######.................######.",
        "...######.................#####.", "....#####..................####.",
        ".....######.................###.", "......#####.................####",
        "......######................####", ".......#####................####",
        "........####................####", ".........##.................####",
        "............................####", "............................####",
        "............................####", "............................####",
        "............................####", "............................####",
        "............................####", "............................####",
        "............................####", "............................####",
    ).map { row -> row.foldIndexed(0) { x, bits, c -> if (c == '#') bits or (1 shl x) else bits } }.toIntArray()
    private val rightRows = leftRows.map { Integer.reverse(it) }.toIntArray()
}
