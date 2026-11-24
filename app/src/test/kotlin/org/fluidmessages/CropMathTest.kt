package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo editor's crop mode: zoom and pan, finding handles, resizing the box, and output sizes. */
class CropMathTest {
    // A 2000 by 1000 picture shown 500 by 250 on screen at zoom 1.
    private val pictureW = 2000f
    private val pictureH = 1000f
    private val frameW = 500f
    private val frameH = 250f
    private val fitted = CropView(fit = .25f)

    private fun near(expected: Float, actual: Float, tolerance: Float = .01f) = assertEquals(expected, actual, tolerance)
    private fun inside(box: CropBox) = assertTrue("$box is outside the picture",
        box.left >= -.01f && box.top >= -.01f && box.right <= pictureW + .01f && box.bottom <= pictureH + .01f)

    @Test fun screenMapsToPictureWithZoomAndPan() {
        val view = CropView(fit = .25f, zoom = 2f, panX = -100f, panY = -50f)
        // Screen (0, 0) is picture (200, 100) at this zoom and pan; screen and picture round-trip.
        near(200f, view.toPictureX(0f)); near(100f, view.toPictureY(0f))
        near(123f, view.toScreenX(view.toPictureX(123f))); near(77f, view.toScreenY(view.toPictureY(77f)))
        val box = view.toPicture(0f, 0f, frameW, frameH)
        assertEquals(CropBox(200f, 100f, 1200f, 600f), box)
        assertEquals(CropBox(0f, 0f, frameW, frameH), view.toScreen(box))
    }

    @Test fun zoomStaysUnderTheFingersAndWithinLimits() {
        val zoomed = zoomView(fitted, 3f, 250f, 125f, 0f, 0f, frameW, frameH)
        near(3f, zoomed.zoom)
        // The picture point under the fingers before is still under them after.
        near(fitted.toPictureX(250f), zoomed.toPictureX(250f)); near(fitted.toPictureY(125f), zoomed.toPictureY(125f))
        near(CropView.MAX_ZOOM, zoomView(fitted, 100f, 10f, 10f, 0f, 0f, frameW, frameH).zoom)
        val out = zoomView(zoomed, .01f, 400f, 10f, 0f, 0f, frameW, frameH)
        near(1f, out.zoom); near(0f, out.panX); near(0f, out.panY)
    }

    @Test fun panningCantPushThePhotoOutOfTheFrame() {
        val zoomed = CropView(fit = .25f, zoom = 2f)
        val farLeft = clampView(zoomed.copy(panX = -10_000f, panY = -10_000f), frameW, frameH)
        near(-frameW, farLeft.panX); near(-frameH, farLeft.panY)
        val farRight = clampView(zoomed.copy(panX = 10_000f, panY = 300f), frameW, frameH)
        near(0f, farRight.panX); near(0f, farRight.panY)
        // At zoom 1 it doesn't move at all.
        assertEquals(fitted, clampView(fitted.copy(panX = 40f, panY = -40f), frameW, frameH))
        // Whatever the pan, the frame shows only picture.
        val shown = farLeft.toPicture(0f, 0f, frameW, frameH)
        inside(shown)
    }

    @Test fun doubleTapTogglesFitAndTwice() {
        val in2 = toggleZoom(fitted, 100f, 50f, frameW, frameH)
        near(2f, in2.zoom)
        near(fitted.toPictureX(100f), in2.toPictureX(100f))
        assertEquals(CropView(fit = .25f), toggleZoom(in2, 100f, 50f, frameW, frameH))
    }

    @Test fun handlesHaveFullTouchTargets() {
        val box = CropBox(100f, 50f, 400f, 200f)
        val reach = 24f
        assertEquals(CropHandle.TOP_LEFT, cropHandleAt(box, 80f, 30f, reach))
        assertEquals(CropHandle.TOP_LEFT, cropHandleAt(box, 120f, 70f, reach))
        assertEquals(CropHandle.BOTTOM_RIGHT, cropHandleAt(box, 410f, 210f, reach))
        assertEquals(CropHandle.TOP, cropHandleAt(box, 250f, 40f, reach))
        assertEquals(CropHandle.RIGHT, cropHandleAt(box, 420f, 120f, reach))
        assertEquals(CropHandle.LEFT, cropHandleAt(box, 110f, 120f, reach))
        assertEquals(CropHandle.MOVE, cropHandleAt(box, 250f, 120f, reach))
        assertEquals(CropHandle.NONE, cropHandleAt(box, 20f, 120f, reach))
        assertEquals(CropHandle.NONE, cropHandleAt(box, 250f, 240f, reach))
        // A tiny box still gives the nearer edge, so it can always be made bigger.
        val tiny = CropBox(100f, 100f, 110f, 110f)
        assertEquals(CropHandle.TOP_LEFT, cropHandleAt(tiny, 101f, 101f, reach))
        assertEquals(CropHandle.BOTTOM_RIGHT, cropHandleAt(tiny, 109f, 109f, reach))
    }

    @Test fun freeResizeStaysInsideAndAboveTheMinimum() {
        val start = CropBox(500f, 200f, 1500f, 800f)
        assertEquals(CropBox(400f, 200f, 1500f, 800f), resizeCrop(start, CropHandle.LEFT, -100f, 30f, pictureW, pictureH, null))
        assertEquals(CropBox(500f, 0f, 2000f, 800f), resizeCrop(start, CropHandle.TOP_RIGHT, 5000f, -5000f, pictureW, pictureH, null))
        val least = leastCropSide(pictureW, pictureH)
        val crushed = resizeCrop(start, CropHandle.BOTTOM_RIGHT, -5000f, -5000f, pictureW, pictureH, null)
        near(least, crushed.width); near(least, crushed.height)
        assertEquals(500f, crushed.left); assertEquals(200f, crushed.top)
        // Moving is limited by the picture's edges and keeps the size.
        val moved = resizeCrop(start, CropHandle.MOVE, 9000f, -9000f, pictureW, pictureH, null)
        assertEquals(CropBox(1000f, 0f, 2000f, 600f), moved)
    }

    @Test fun aspectPresetsHoldWhileResizing() {
        val square = centeredCrop(pictureW, pictureH, 1f)
        assertEquals(CropBox(500f, 0f, 1500f, 1000f), square)
        for (handle in CropHandle.entries.filter { it != CropHandle.NONE }) {
            for ((dx, dy) in listOf(-300f to -200f, 300f to 200f, 5000f to 5000f, -5000f to 5000f, -900f to -900f)) {
                for (ratio in listOf(1f, 4f / 3, 16f / 9, 9f / 16)) {
                    val start = centeredCrop(pictureW, pictureH, ratio).let { resizeCrop(it, CropHandle.BOTTOM_RIGHT, -200f, -200f, pictureW, pictureH, ratio) }
                    val box = resizeCrop(start, handle, dx, dy, pictureW, pictureH, ratio)
                    inside(box)
                    near(ratio, box.width / box.height, .001f)
                    assertTrue("$handle $dx,$dy at $ratio is too small: $box", box.height >= leastCropSide(pictureW, pictureH) - .01f)
                }
            }
        }
        // A corner keeps the opposite corner where it was.
        val start = CropBox(200f, 100f, 600f, 400f)
        val grown = resizeCrop(start, CropHandle.TOP_LEFT, -100f, -10f, pictureW, pictureH, 4f / 3)
        near(600f, grown.right); near(400f, grown.bottom); near(500f, grown.width)
    }

    @Test fun cropBoxBecomesBitmapPixelsAtAnySize() {
        val box = CropBox(500f, 250f, 1500f, 750f)
        assertEquals(PixelRect(500, 250, 1000, 500), cropPixels(box, pictureW, pictureH, 2000, 1000))
        // Flatten capped the bitmap at half size: the same part of the picture is cropped.
        assertEquals(PixelRect(250, 125, 500, 250), cropPixels(box, pictureW, pictureH, 1000, 500))
        // A box set while zoomed, from the frame shown on screen, is the same box.
        val zoomed = zoomView(fitted, 4f, 250f, 125f, 0f, 0f, frameW, frameH)
        val shown = zoomed.toPicture(0f, 0f, frameW, frameH)
        assertEquals(PixelRect(750, 375, 500, 250), cropPixels(shown, pictureW, pictureH, 2000, 1000))
        // Never empty, never past the bitmap.
        val edge = cropPixels(CropBox(1999.9f, 999.9f, 2000f, 1000f), pictureW, pictureH, 2000, 1000)
        assertTrue(edge.width >= 1 && edge.height >= 1 && edge.left + edge.width <= 2000 && edge.top + edge.height <= 1000)
    }

    @Test fun outputSizesRespectTheCaps() {
        assertEquals(1200 to 800, clampOutputSize(1200, 800))
        val (w, h) = clampOutputSize(8000, 2000)
        assertEquals(MAX_OUTPUT_SIDE, w); assertEquals(1024, h)
        val (bw, bh) = clampOutputSize(4096, 4096)
        assertTrue(bw.toLong() * bh <= MAX_OUTPUT_PIXELS && bw == bh)
        assertEquals(1 to 1, clampOutputSize(0, -5))
        assertEquals(750, lockedHeight(1000, 2000, 1500))
        assertEquals(1000, lockedWidth(750, 2000, 1500))
    }

    @Test fun resizePresets() {
        assertEquals(2000 to 1500, ResizePreset.ORIGINAL.size(2000, 1500))
        assertEquals(1000 to 750, ResizePreset.HALF.size(2000, 1500))
        assertEquals(1440 to 1080, ResizePreset.HD1080.size(2000, 1500))
        assertEquals(1080 to 1440, ResizePreset.HD1080.size(1500, 2000))
        assertEquals(1280 to 720, ResizePreset.HD720.size(1920, 1080))
        assertEquals(720 to 1280, ResizePreset.HD720.size(1080, 1920))
    }
}
