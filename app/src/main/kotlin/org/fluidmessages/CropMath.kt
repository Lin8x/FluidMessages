package org.fluidmessages

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The arithmetic behind the photo editor's crop mode, kept free of Android so it can be tested on the JVM:
 * zooming and panning the photo, finding which part of the crop box a finger is on, resizing the box, turning it
 * into bitmap pixels, and picking an output size.
 *
 * "Picture" coordinates are the edited picture's own (0..width, 0..height). "Screen" coordinates are pixels in the
 * frame the picture is shown in, which at zoom 1 is exactly the picture fitted to the space.
 */

/** A crop box in picture coordinates. */
internal data class CropBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun offset(dx: Float, dy: Float) = CropBox(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun whole(width: Float, height: Float) = CropBox(0f, 0f, width, height)
    }
}

/**
 * How the picture is shown: [fit] picture-to-screen at zoom 1, then [zoom] (1 = fitted), then shifted by
 * [panX], [panY] screen pixels. A screen point is `pan + picture * fit * zoom`.
 */
internal data class CropView(val fit: Float, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    val scale: Float get() = fit * zoom
    fun toPictureX(screenX: Float) = (screenX - panX) / scale
    fun toPictureY(screenY: Float) = (screenY - panY) / scale
    fun toScreenX(pictureX: Float) = panX + pictureX * scale
    fun toScreenY(pictureY: Float) = panY + pictureY * scale

    /** A box on screen, in picture coordinates. */
    fun toPicture(left: Float, top: Float, right: Float, bottom: Float) =
        CropBox(toPictureX(left), toPictureY(top), toPictureX(right), toPictureY(bottom))

    fun toScreen(box: CropBox) = CropBox(toScreenX(box.left), toScreenY(box.top), toScreenX(box.right), toScreenY(box.bottom))

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
        const val DOUBLE_TAP_ZOOM = 2f
    }
}

/**
 * Keeps the zoom between fitted and [CropView.MAX_ZOOM], and the pan so the photo always covers the
 * [frameW] by [frameH] frame (the fitted picture's size on screen), so it can't be pushed out of view.
 */
internal fun clampView(view: CropView, frameW: Float, frameH: Float): CropView {
    val zoom = view.zoom.coerceIn(CropView.MIN_ZOOM, CropView.MAX_ZOOM)
    val contentW = frameW * zoom
    val contentH = frameH * zoom
    return view.copy(zoom = zoom, panX = view.panX.coerceIn(min(0f, frameW - contentW), 0f),
        panY = view.panY.coerceIn(min(0f, frameH - contentH), 0f))
}

/**
 * Zooms by [factor] around the screen point ([focusX], [focusY]), so the spot under the fingers stays there, then
 * moves by ([panX], [panY]) screen pixels, and clamps.
 */
internal fun zoomView(view: CropView, factor: Float, focusX: Float, focusY: Float, panX: Float, panY: Float,
                      frameW: Float, frameH: Float): CropView {
    val zoom = (view.zoom * factor).coerceIn(CropView.MIN_ZOOM, CropView.MAX_ZOOM)
    val pictureX = view.toPictureX(focusX)
    val pictureY = view.toPictureY(focusY)
    val scale = view.fit * zoom
    return clampView(view.copy(zoom = zoom, panX = focusX - pictureX * scale + panX, panY = focusY - pictureY * scale + panY), frameW, frameH)
}

/** Double-tap: back to fitted when zoomed in, otherwise to about 2x around the tapped spot. */
internal fun toggleZoom(view: CropView, tapX: Float, tapY: Float, frameW: Float, frameH: Float): CropView =
    if (view.zoom > 1.01f) clampView(view.copy(zoom = 1f, panX = 0f, panY = 0f), frameW, frameH)
    else zoomView(view, CropView.DOUBLE_TAP_ZOOM / view.zoom, tapX, tapY, 0f, 0f, frameW, frameH)

/** Which part of the crop box a finger is on. */
internal enum class CropHandle(val moveLeft: Boolean, val moveTop: Boolean, val moveRight: Boolean, val moveBottom: Boolean) {
    NONE(false, false, false, false), MOVE(true, true, true, true),
    TOP_LEFT(true, true, false, false), TOP(false, true, false, false), TOP_RIGHT(false, true, true, false),
    RIGHT(false, false, true, false), BOTTOM_RIGHT(false, false, true, true), BOTTOM(false, false, false, true),
    BOTTOM_LEFT(true, false, false, true), LEFT(true, false, false, false);

    val corner: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_RIGHT || this == BOTTOM_LEFT
}

/**
 * Finds the handle under a finger at ([x], [y]), all in screen pixels. [reach] is half the touch target (24dp for a
 * 48dp target). Corners win over edges, and edges over the inside. When the box is too small on screen for its
 * edge targets to leave room inside, the nearer edge still wins, so a small box can always be made bigger.
 */
internal fun cropHandleAt(box: CropBox, x: Float, y: Float, reach: Float): CropHandle {
    val nearLeft = abs(x - box.left) <= reach
    val nearRight = abs(x - box.right) <= reach
    val nearTop = abs(y - box.top) <= reach
    val nearBottom = abs(y - box.bottom) <= reach
    val withinX = x >= box.left - reach && x <= box.right + reach
    val withinY = y >= box.top - reach && y <= box.bottom + reach
    if (!withinX || !withinY) return CropHandle.NONE
    // When both opposite edges are in reach (a small box), take the nearer one.
    val left = nearLeft && (!nearRight || abs(x - box.left) <= abs(x - box.right))
    val right = nearRight && !left
    val top = nearTop && (!nearBottom || abs(y - box.top) <= abs(y - box.bottom))
    val bottom = nearBottom && !top
    return when {
        left && top -> CropHandle.TOP_LEFT
        right && top -> CropHandle.TOP_RIGHT
        left && bottom -> CropHandle.BOTTOM_LEFT
        right && bottom -> CropHandle.BOTTOM_RIGHT
        left -> CropHandle.LEFT
        right -> CropHandle.RIGHT
        top -> CropHandle.TOP
        bottom -> CropHandle.BOTTOM
        box.contains(x, y) -> CropHandle.MOVE
        else -> CropHandle.NONE
    }
}

/** The smallest crop side for a [width] by [height] picture: 8% of the short side, but never more than the picture. */
internal fun leastCropSide(width: Float, height: Float) = max(1f, min(width, height) * .08f)

/** The biggest box of [ratio] (width / height) centered in the picture, or the whole picture for Free. */
internal fun centeredCrop(width: Float, height: Float, ratio: Float?): CropBox {
    if (ratio == null) return CropBox.whole(width, height)
    val w = min(width, height * ratio)
    val h = w / ratio
    return CropBox((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

/**
 * The box after dragging [handle] by ([dx], [dy]) picture units from where it was at the start of the drag
 * ([start]). It stays inside the [width] by [height] picture, at least [least] on each side, and at [ratio]
 * (width / height) when one is chosen. Edges never cross over each other.
 */
internal fun resizeCrop(start: CropBox, handle: CropHandle, dx: Float, dy: Float, width: Float, height: Float,
                        ratio: Float?, least: Float = leastCropSide(width, height)): CropBox {
    val minW = min(least, width)
    val minH = min(least, height)
    if (handle == CropHandle.NONE) return start
    if (handle == CropHandle.MOVE) {
        val ox = dx.coerceIn(-start.left, width - start.right)
        val oy = dy.coerceIn(-start.top, height - start.bottom)
        return start.offset(ox, oy)
    }
    if (ratio == null) {
        val left = if (handle.moveLeft) (start.left + dx).coerceIn(0f, start.right - minW) else start.left
        val right = if (handle.moveRight) (start.right + dx).coerceIn(start.left + minW, width) else start.right
        val top = if (handle.moveTop) (start.top + dy).coerceIn(0f, start.bottom - minH) else start.top
        val bottom = if (handle.moveBottom) (start.bottom + dy).coerceIn(start.top + minH, height) else start.bottom
        return CropBox(left, top, right, bottom)
    }
    // With a fixed shape the width decides the height. The smallest width keeps both sides at least [least].
    val leastW = min(max(minW, minH * ratio), min(width, height * ratio))
    if (handle.corner) {
        // The opposite corner stays put; the box follows whichever way the finger went further.
        val anchorX = if (handle.moveLeft) start.right else start.left
        val anchorY = if (handle.moveTop) start.bottom else start.top
        val x = (if (handle.moveLeft) start.left else start.right) + dx
        val y = (if (handle.moveTop) start.top else start.bottom) + dy
        val wantW = max(if (handle.moveLeft) anchorX - x else x - anchorX, 0f)
        val wantH = max(if (handle.moveTop) anchorY - y else y - anchorY, 0f)
        val roomW = if (handle.moveLeft) anchorX else width - anchorX
        val roomH = if (handle.moveTop) anchorY else height - anchorY
        val w = max(wantW, wantH * ratio).coerceAtMost(min(roomW, roomH * ratio)).coerceAtLeast(min(leastW, min(roomW, roomH * ratio)))
        val h = w / ratio
        val left = if (handle.moveLeft) anchorX - w else anchorX
        val top = if (handle.moveTop) anchorY - h else anchorY
        return CropBox(left, top, left + w, top + h)
    }
    // An edge: it moves, the opposite edge stays, and the box grows or shrinks evenly the other way, sliding
    // back inside the picture if it reaches an edge.
    val horizontal = handle == CropHandle.LEFT || handle == CropHandle.RIGHT
    val w = if (horizontal) {
        val want = if (handle.moveLeft) start.width - dx else start.width + dx
        val room = if (handle.moveLeft) start.right else width - start.left
        want.coerceAtMost(min(room, height * ratio)).coerceAtLeast(min(leastW, min(room, height * ratio)))
    } else {
        val want = (if (handle.moveTop) start.height - dy else start.height + dy) * ratio
        val room = (if (handle.moveTop) start.bottom else height - start.top) * ratio
        want.coerceAtMost(min(room, width)).coerceAtLeast(min(leastW, min(room, width)))
    }
    val h = w / ratio
    return if (horizontal) {
        val left = if (handle.moveLeft) start.right - w else start.left
        val top = (start.centerY - h / 2).coerceIn(0f, max(0f, height - h))
        CropBox(left, top, left + w, top + h)
    } else {
        val top = if (handle.moveTop) start.bottom - h else start.top
        val left = (start.centerX - w / 2).coerceIn(0f, max(0f, width - w))
        CropBox(left, top, left + w, top + h)
    }
}

/** A crop box in whole bitmap pixels: left, top, width, height. */
internal data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * The pixels of a [bitmapW] by [bitmapH] bitmap that a [box] in a [pictureW] by [pictureH] picture covers. The
 * bitmap can be smaller than the picture (flatten caps its size), so the box is scaled to it.
 */
internal fun cropPixels(box: CropBox, pictureW: Float, pictureH: Float, bitmapW: Int, bitmapH: Int): PixelRect {
    val sx = bitmapW / pictureW
    val sy = bitmapH / pictureH
    val left = (box.left * sx).roundToInt().coerceIn(0, bitmapW - 1)
    val top = (box.top * sy).roundToInt().coerceIn(0, bitmapH - 1)
    val right = (box.right * sx).roundToInt().coerceIn(left + 1, bitmapW)
    val bottom = (box.bottom * sy).roundToInt().coerceIn(top + 1, bitmapH)
    return PixelRect(left, top, right - left, bottom - top)
}

/** The largest output picture: the same caps as flatten. */
internal const val MAX_OUTPUT_SIDE = 4096
internal const val MAX_OUTPUT_PIXELS = 16_000_000L

/**
 * An output size kept within [MAX_OUTPUT_SIDE] on each side and [MAX_OUTPUT_PIXELS] in all, and at least 1 by 1.
 * When it has to shrink, both sides shrink together so the shape stays.
 */
internal fun clampOutputSize(width: Int, height: Int): Pair<Int, Int> {
    val w = max(1, width)
    val h = max(1, height)
    val shrink = minOf(1.0, MAX_OUTPUT_SIDE.toDouble() / w, MAX_OUTPUT_SIDE.toDouble() / h, sqrt(MAX_OUTPUT_PIXELS.toDouble() / (w.toLong() * h)))
    if (shrink >= 1.0) return w to h
    return max(1, (w * shrink).toInt()).coerceAtMost(MAX_OUTPUT_SIDE) to max(1, (h * shrink).toInt()).coerceAtMost(MAX_OUTPUT_SIDE)
}

/** The other side of a size with the shape of [fromW] by [fromH] kept: the height for [width]. */
internal fun lockedHeight(width: Int, fromW: Int, fromH: Int) = max(1, (width.toLong() * fromH / max(1, fromW).toDouble()).roundToInt())
internal fun lockedWidth(height: Int, fromW: Int, fromH: Int) = max(1, (height.toLong() * fromW / max(1, fromH).toDouble()).roundToInt())

/** Quick output sizes. The video-style ones fit the picture inside 1920 by 1080 or 1280 by 720, turned to match it. */
internal enum class ResizePreset(val label: String) {
    ORIGINAL("Original"), HALF("50%"), HD1080("1080p"), HD720("720p");

    fun size(width: Int, height: Int): Pair<Int, Int> = when (this) {
        ORIGINAL -> width to height
        HALF -> max(1, (width / 2.0).roundToInt()) to max(1, (height / 2.0).roundToInt())
        HD1080 -> fitInside(width, height, 1920, 1080)
        HD720 -> fitInside(width, height, 1280, 720)
    }

    private fun fitInside(width: Int, height: Int, long: Int, short: Int): Pair<Int, Int> {
        val (boxW, boxH) = if (width >= height) long to short else short to long
        val scale = min(boxW.toDouble() / width, boxH.toDouble() / height)
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}
