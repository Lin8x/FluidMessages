package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Background removal on small made-up pictures. */
class BackgroundCutTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** A shaded, slightly noisy wall with shapes painted by [paint] (return null to leave the wall). */
    private fun picture(width: Int, height: Int, paint: (Int, Int) -> Int?): IntArray {
        val random = Random(7)
        return IntArray(width * height) { i ->
            val x = i % width; val y = i / width
            paint(x, y) ?: run {
                // Light gray on the left fading to a darker gray on the right, like a lit wall.
                val shade = 238 - x * 45 / width + random.nextInt(-3, 4)
                rgb(shade, shade, shade + 4)
            }
        }
    }

    private fun alphaAt(mask: BackgroundCut.Mask, x: Int, y: Int) = mask.alpha[y * mask.width + x].toInt() and 0xFF

    @Test fun removesAShadedWallAndKeepsTheSubject() {
        val w = 80; val h = 60
        val red = rgb(200, 40, 40)
        val pixels = picture(w, h) { x, y -> if ((x - 40) * (x - 40) + (y - 30) * (y - 30) < 15 * 15) red else null }
        val mask = BackgroundCut.find(pixels, w, h, tolerance = .4f)
        assertEquals(255, alphaAt(mask, 40, 30))
        assertEquals(255, alphaAt(mask, 32, 30))
        for ((x, y) in listOf(0 to 0, 79 to 0, 0 to 59, 79 to 59, 5 to 30, 75 to 30)) assertEquals("$x,$y", 0, alphaAt(mask, x, y))
        // The circle is about 15% of the picture.
        val kept = 1 - mask.removed
        assertTrue("kept $kept", kept in .12f..0.2f)
    }

    @Test fun aSubjectTouchingTheBottomEdgeIsNotTakenForBackground() {
        val w = 80; val h = 60
        val blue = rgb(30, 60, 190)
        // Shoulders and head: a wide block along the bottom and a circle above it.
        val pixels = picture(w, h) { x, y -> if (y >= 40 && x in 15..65 || (x - 40) * (x - 40) + (y - 28) * (y - 28) < 100) blue else null }
        val mask = BackgroundCut.find(pixels, w, h, tolerance = .4f)
        assertEquals(255, alphaAt(mask, 40, 55))
        assertEquals(255, alphaAt(mask, 16, 59))
        assertEquals(255, alphaAt(mask, 40, 28))
        assertEquals(0, alphaAt(mask, 5, 55))
        assertEquals(0, alphaAt(mask, 40, 3))
    }

    @Test fun enclosedBackgroundGoesWhenTapped() {
        val w = 60; val h = 60
        val green = rgb(20, 150, 60)
        // A ring: the wall shows through its middle, which the edge can't reach.
        val pixels = picture(w, h) { x, y ->
            val d = (x - 30) * (x - 30) + (y - 30) * (y - 30)
            if (d in 100..400) green else null
        }
        val edgesOnly = BackgroundCut.find(pixels, w, h, tolerance = .4f)
        assertEquals(255, alphaAt(edgesOnly, 30, 30))
        val tapped = BackgroundCut.find(pixels, w, h, tolerance = .4f, taps = listOf(30 to 30))
        assertEquals(0, alphaAt(tapped, 30, 30))
        assertEquals(255, alphaAt(tapped, 30, 15))
        assertEquals(0, alphaAt(tapped, 2, 2))
    }

    @Test fun lowToleranceKeepsMoreThanHighTolerance() {
        val w = 60; val h = 40
        // A pale subject on a pale wall.
        val pale = rgb(205, 200, 185)
        val pixels = picture(w, h) { x, y -> if (x in 20..40 && y in 10..30) pale else null }
        val strict = BackgroundCut.find(pixels, w, h, tolerance = 0f, softness = 0f)
        val loose = BackgroundCut.find(pixels, w, h, tolerance = 1f, softness = 0f)
        assertTrue(strict.removed <= loose.removed)
        assertEquals(255, alphaAt(strict, 30, 20))
    }

    @Test fun transparentPixelsStayRemovedAndSpecksAreCleared() {
        val w = 40; val h = 40
        val pixels = picture(w, h) { x, y ->
            when {
                x in 10..30 && y in 10..30 -> rgb(240, 120, 0)
                x == 3 && y == 3 -> rgb(0, 0, 0) // A speck of dust.
                y == 0 -> 0 // A transparent top row.
                else -> null
            }
        }
        val mask = BackgroundCut.find(pixels, w, h, tolerance = .3f, softness = 0f)
        assertEquals(0, alphaAt(mask, 3, 3))
        assertEquals(0, alphaAt(mask, 20, 0))
        assertEquals(255, alphaAt(mask, 20, 20))
    }

    @Test fun softEdgesAreFeatheredButTheMiddleStaysSolid() {
        val w = 60; val h = 60
        val pixels = picture(w, h) { x, y -> if (x in 20..40 && y in 20..40) rgb(20, 20, 160) else null }
        val mask = BackgroundCut.find(pixels, w, h, tolerance = .4f, softness = 1f)
        assertEquals(255, alphaAt(mask, 30, 30))
        assertEquals(0, alphaAt(mask, 5, 5))
        val edge = (17..23).map { alphaAt(mask, it, 30) }
        assertTrue("edge $edge", edge.any { it in 1..254 })
        assertTrue("edge $edge", edge.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun decontaminationTakesTheWallOutOfAnEdgePixel() {
        // One soft edge pixel: half red, half white wall.
        val mask = BackgroundCut.Mask(byteArrayOf(128.toByte()), 1, 1, intArrayOf(rgb(255, 255, 255)))
        val pixels = intArrayOf(rgb(228, 128, 128))
        BackgroundCut.decontaminate(pixels, 1, 1, mask)
        val r = (pixels[0] shr 16) and 0xFF; val g = (pixels[0] shr 8) and 0xFF
        assertTrue("r $r", r > 190)
        assertTrue("g $g", g < 20)
    }

    @Test fun oklabRoundTrips() {
        val out = FloatArray(3)
        for (color in listOf(rgb(255, 255, 255), rgb(0, 0, 0), rgb(200, 40, 40), rgb(30, 60, 190), rgb(128, 200, 90))) {
            BackgroundCut.oklab(color, out, 0)
            val back = BackgroundCut.fromOklab(out)
            for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs(((back shr shift) and 0xFF) - ((color shr shift) and 0xFF)) <= 1)
        }
    }

    @Test fun opaqueBoundsFindsTheSubject() {
        val w = 10; val h = 8
        val pixels = IntArray(w * h)
        pixels[2 * w + 3] = rgb(1, 2, 3); pixels[5 * w + 7] = rgb(1, 2, 3)
        assertEquals(listOf(3, 2, 8, 6), BackgroundCut.opaqueBounds(pixels, w, h)!!.toList())
        assertNull(BackgroundCut.opaqueBounds(IntArray(4), 2, 2))
    }
}
