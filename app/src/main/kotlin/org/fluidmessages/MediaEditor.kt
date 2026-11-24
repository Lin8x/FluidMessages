package org.fluidmessages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DiscretePathEffect
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * The photo editor: crop, rotate, flip, light and color adjustments, filters, stretching, space around the picture,
 * drawing with a pen, pencil, marker, or highlighter, text in several fonts, and other pictures on top. Everything is drawn by one
 * function, so what you see is exactly what gets sent.
 */

internal enum class EditBrush(val label: String) { PEN("Pen"), PENCIL("Pencil"), MARKER("Marker"), HIGHLIGHTER("Highlighter"), ERASER("Eraser") }

internal data class EditStroke(val points: List<Offset>, val color: Int, val width: Float, val brush: EditBrush)

internal enum class EditFont(val label: String, val family: String, val style: Int) {
    BOLD("Bold", "sans-serif-condensed", Typeface.BOLD), CLASSIC("Classic", "sans-serif", Typeface.BOLD),
    HEAVY("Heavy", "sans-serif-black", Typeface.NORMAL), SERIF("Serif", "serif", Typeface.BOLD),
    TYPEWRITER("Typewriter", "monospace", Typeface.NORMAL), HANDWRITTEN("Handwritten", "cursive", Typeface.BOLD),
    CASUAL("Casual", "casual", Typeface.NORMAL), SMALL_CAPS("Small caps", "sans-serif-smallcaps", Typeface.NORMAL);

    val typeface: Typeface by lazy { Typeface.create(family, style) }
}

internal enum class TextLook(val label: String) { OUTLINE("Outline"), BOX("Box"), PLAIN("Plain") }

internal sealed class EditLayer {
    abstract val id: Long
    abstract val center: Offset
    abstract val scale: Float
    abstract val rotation: Float
}

internal data class TextLayer(override val id: Long, val text: String, val font: EditFont, val color: Int, val size: Float,
                              val look: TextLook, override val center: Offset, override val scale: Float = 1f,
                              override val rotation: Float = 0f) : EditLayer()

internal data class PictureLayer(override val id: Long, val bitmap: Bitmap, override val center: Offset, override val scale: Float,
                                 override val rotation: Float = 0f) : EditLayer()

internal enum class EditFilter(val label: String, val matrix: FloatArray?) {
    NONE("Original", null),
    VIVID("Vivid", ColorMatrix().apply { setSaturation(1.55f) }.array),
    MONO("Mono", ColorMatrix().apply { setSaturation(0f) }.array),
    NOIR("Noir", ColorMatrix().apply { setSaturation(0f); postConcat(contrast(1.5f)) }.array),
    SEPIA("Sepia", floatArrayOf(.393f, .769f, .189f, 0f, 0f, .349f, .686f, .168f, 0f, 0f, .272f, .534f, .131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
    WARM("Warm", floatArrayOf(1.12f, 0f, 0f, 0f, 8f, 0f, 1.02f, 0f, 0f, 0f, 0f, 0f, .88f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
    COOL("Cool", floatArrayOf(.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.15f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)),
    FADE("Fade", floatArrayOf(.8f, 0f, 0f, 0f, 40f, 0f, .8f, 0f, 0f, 40f, 0f, 0f, .8f, 0f, 40f, 0f, 0f, 0f, 1f, 0f)),
    DRAMA("Drama", contrast(1.35f).apply { postConcat(ColorMatrix().apply { setSaturation(1.2f) }) }.array),
    INTENSE("Intense", ColorMatrix().apply { setSaturation(3f); postConcat(contrast(1.9f)) }.array),
    INVERT("Invert", floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f));
}

private fun contrast(amount: Float): ColorMatrix {
    val shift = 128f * (1 - amount)
    return ColorMatrix(floatArrayOf(amount, 0f, 0f, 0f, shift, 0f, amount, 0f, 0f, shift, 0f, 0f, amount, 0f, shift, 0f, 0f, 0f, 1f, 0f))
}

/** Light and color sliders, each 0 when untouched, from -1 to 1. */
internal data class Adjustments(val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
                                val warmth: Float = 0f, val tint: Float = 0f, val fade: Float = 0f) {
    val untouched: Boolean get() = this == Adjustments()
}

/** The filter, then the sliders, as one color matrix (or null when the photo's colors are unchanged). */
internal fun colorMatrix(filter: EditFilter, adjust: Adjustments): ColorMatrix? {
    if (filter.matrix == null && adjust.untouched) return null
    val out = filter.matrix?.let { ColorMatrix(it) } ?: ColorMatrix()
    if (adjust.saturation != 0f) out.postConcat(ColorMatrix().apply { setSaturation(1f + adjust.saturation) })
    if (adjust.contrast != 0f) out.postConcat(contrast(1f + adjust.contrast * .6f))
    if (adjust.warmth != 0f || adjust.tint != 0f || adjust.brightness != 0f) {
        val b = adjust.brightness * 70f
        val r = 1f + adjust.warmth * .14f
        val g = 1f - adjust.tint * .12f
        val bl = 1f - adjust.warmth * .14f
        out.postConcat(ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, b, 0f, g, 0f, 0f, b, 0f, 0f, bl, 0f, b, 0f, 0f, 0f, 1f, 0f)))
    }
    if (adjust.fade > 0f) {
        val keep = 1f - adjust.fade * .3f
        val lift = adjust.fade * 60f
        out.postConcat(ColorMatrix(floatArrayOf(keep, 0f, 0f, 0f, lift, 0f, keep, 0f, 0f, lift, 0f, 0f, keep, 0f, lift, 0f, 0f, 0f, 1f, 0f)))
    }
    return out
}

/**
 * A removed background. [mask] (ALPHA_8, possibly smaller than the photo) says what is kept, and [source] is the
 * photo with the background's color taken out of the soft outline; both are null until removal has run, which
 * means everything is kept. [touches] are Erase strokes (brush ERASER) and Keep strokes (any other brush) on top,
 * in picture coordinates. [taps] are spots you tapped as background, in photo pixels.
 */
internal data class Cutout(
    val mask: Bitmap? = null, val source: Bitmap? = null, val touches: List<EditStroke> = emptyList(),
    val tolerance: Float = .4f, val softness: Float = .35f, val fromEdges: Boolean = true, val taps: List<Pair<Int, Int>> = emptyList(),
    val removed: Float = 0f,
)

/** One version of the picture. Edits make a new one, which is what undo goes back through. */
internal data class EditState(
    val base: Bitmap, val stretchX: Float = 1f, val stretchY: Float = 1f,
    val padTop: Int = 0, val padBottom: Int = 0, val padSides: Int = 0, val padColor: Int = android.graphics.Color.WHITE,
    val filter: EditFilter = EditFilter.NONE, val adjust: Adjustments = Adjustments(), val strokes: List<EditStroke> = emptyList(), val layers: List<EditLayer> = emptyList(),
    val cutout: Cutout? = null,
) {
    val imageWidth: Float get() = base.width * stretchX
    val imageHeight: Float get() = base.height * stretchY
    val width: Int get() = max(1, (imageWidth + padSides * 2).toInt())
    val height: Int get() = max(1, (imageHeight + padTop + padBottom).toInt())
    val imageRect: RectF get() = RectF(padSides.toFloat(), padTop.toFloat(), padSides + imageWidth, padTop + imageHeight)
}

/**
 * Background removal for the editor, on the phone (see BackgroundCut). The background is found at most
 * [ANALYSIS] pixels on a side, which keeps memory and time in check, and the soft outline is applied at full size.
 * Blocking; run it off the main thread.
 */
internal object EditCutouts {
    private const val ANALYSIS = 1280

    fun compute(base: Bitmap, cut: Cutout, progress: (Float) -> Unit, stop: () -> Boolean): Cutout {
        val scale = min(1f, ANALYSIS.toFloat() / max(base.width, base.height))
        val w = max(1, (base.width * scale).toInt())
        val h = max(1, (base.height * scale).toInt())
        val small = if (scale < 1f) Bitmap.createScaledBitmap(base, w, h, true) else base
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== base) small.recycle()
        val taps = cut.taps.map { (x, y) -> (x * scale).toInt() to (y * scale).toInt() }
        val found = BackgroundCut.find(pixels, w, h, cut.tolerance, cut.softness, cut.fromEdges, taps, { progress(it * .8f) }, stop)
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val rows = java.nio.ByteBuffer.allocate(mask.rowBytes * h)
        for (y in 0 until h) { rows.position(y * mask.rowBytes); rows.put(found.alpha, y * w, w) }
        rows.rewind()
        mask.copyPixelsFromBuffer(rows)
        if (stop()) throw BackgroundCut.CancellationException()
        val full = IntArray(base.width * base.height)
        base.getPixels(full, 0, base.width, 0, 0, base.width, base.height)
        BackgroundCut.decontaminate(full, base.width, base.height, found)
        val source = Bitmap.createBitmap(full, base.width, base.height, Bitmap.Config.ARGB_8888)
        progress(1f)
        return cut.copy(mask = mask, source = source, removed = found.removed)
    }

    /** A sticker trimmed to what's left of the picture, with a little room around it. */
    fun trimmed(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val box = BackgroundCut.opaqueBounds(pixels, bitmap.width, bitmap.height) ?: return bitmap
        val room = (max(box[2] - box[0], box[3] - box[1]) * .03f).toInt()
        val left = (box[0] - room).coerceAtLeast(0); val top = (box[1] - room).coerceAtLeast(0)
        val right = (box[2] + room).coerceAtMost(bitmap.width); val bottom = (box[3] + room).coerceAtMost(bitmap.height)
        if (left == 0 && top == 0 && right == bitmap.width && bottom == bitmap.height) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }
}

private val shared = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

private fun textPaint(layer: TextLayer) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = layer.font.typeface
    textSize = layer.size * layer.scale
    textAlign = Paint.Align.CENTER
    color = layer.color
}

/** The lines of a text layer and the box around them, in picture coordinates before rotation. */
private fun textBounds(layer: TextLayer): RectF {
    val paint = textPaint(layer)
    val lines = layer.text.split('\n')
    val width = lines.maxOf { paint.measureText(it) }
    val lineHeight = paint.fontSpacing
    val height = lineHeight * lines.size
    return RectF(layer.center.x - width / 2 - 12, layer.center.y - height / 2 - 8, layer.center.x + width / 2 + 12, layer.center.y + height / 2 + 8)
}

private fun pictureBounds(layer: PictureLayer): RectF {
    val w = layer.bitmap.width * layer.scale / 2
    val h = layer.bitmap.height * layer.scale / 2
    return RectF(layer.center.x - w, layer.center.y - h, layer.center.x + w, layer.center.y + h)
}

private fun bounds(layer: EditLayer) = when (layer) { is TextLayer -> textBounds(layer); is PictureLayer -> pictureBounds(layer) }

/** Draws the whole picture onto a canvas in picture coordinates. Used for the preview and for the file that's sent. */
private fun strokePath(stroke: EditStroke) = Path().apply {
    moveTo(stroke.points[0].x, stroke.points[0].y)
    if (stroke.points.size == 1) lineTo(stroke.points[0].x + .1f, stroke.points[0].y)
    for (index in 1 until stroke.points.size) {
        val previous = stroke.points[index - 1]
        val point = stroke.points[index]
        quadTo(previous.x, previous.y, (previous.x + point.x) / 2, (previous.y + point.y) / 2)
    }
}

internal fun renderEdit(canvas: android.graphics.Canvas, state: EditState) {
    val image = state.imageRect
    if (state.padTop > 0 || state.padBottom > 0 || state.padSides > 0) {
        // Only around the photo, so a removed background stays see-through.
        canvas.save()
        canvas.clipOutRect(image)
        canvas.drawColor(state.padColor)
        canvas.restore()
    }
    val photo = Paint(shared).apply { colorFilter = colorMatrix(state.filter, state.adjust)?.let { ColorMatrixColorFilter(it) } }
    val cut = state.cutout
    if (cut == null) canvas.drawBitmap(state.base, null, image, photo)
    else {
        // What's kept is drawn as alpha first (the found mask, then Erase and Keep strokes), and the photo goes in through it.
        val layer = canvas.saveLayer(image, null)
        canvas.clipRect(image)
        if (cut.mask != null) canvas.drawBitmap(cut.mask, null, image, shared) else canvas.drawColor(android.graphics.Color.BLACK)
        for (touch in cut.touches) {
            if (touch.points.isEmpty()) continue
            canvas.drawPath(strokePath(touch), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                strokeWidth = touch.width; color = android.graphics.Color.BLACK
                if (touch.brush == EditBrush.ERASER) blendMode = BlendMode.CLEAR
            })
        }
        canvas.drawBitmap(cut.source ?: state.base, null, image, Paint(photo).apply { blendMode = BlendMode.SRC_IN })
        canvas.restoreToCount(layer)
    }
    // Strokes go on their own layer, so the eraser and highlighter behave like real ones.
    val saved = canvas.saveLayer(0f, 0f, state.width.toFloat(), state.height.toFloat(), null)
    for (stroke in state.strokes) {
        if (stroke.points.isEmpty()) continue
        val path = strokePath(stroke)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = stroke.color
            strokeWidth = stroke.width
            when (stroke.brush) {
                EditBrush.PENCIL -> { alpha = 210; strokeWidth = stroke.width * .55f; pathEffect = DiscretePathEffect(3f, stroke.width * .12f) }
                EditBrush.MARKER -> { strokeWidth = stroke.width * 1.8f }
                EditBrush.HIGHLIGHTER -> { alpha = 110; strokeWidth = stroke.width * 3.2f; strokeCap = Paint.Cap.SQUARE; blendMode = BlendMode.MULTIPLY }
                EditBrush.ERASER -> { strokeWidth = stroke.width * 2.4f; blendMode = BlendMode.CLEAR }
                EditBrush.PEN -> {}
            }
        }
        canvas.drawPath(path, paint)
    }
    canvas.restoreToCount(saved)
    for (layer in state.layers) {
        canvas.save()
        canvas.rotate(layer.rotation, layer.center.x, layer.center.y)
        when (layer) {
            is PictureLayer -> canvas.drawBitmap(layer.bitmap, null, pictureBounds(layer), shared)
            is TextLayer -> {
                val paint = textPaint(layer)
                val lines = layer.text.split('\n')
                val lineHeight = paint.fontSpacing
                val box = textBounds(layer)
                if (layer.look == TextLook.BOX) {
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (Color(layer.color).luminance() > .5f) 0xCC000000.toInt() else 0xEEFFFFFF.toInt()
                    }
                    canvas.drawRoundRect(box, paint.textSize * .25f, paint.textSize * .25f, fill)
                }
                var y = layer.center.y - lineHeight * lines.size / 2 - paint.fontMetrics.ascent
                for (line in lines) {
                    if (layer.look == TextLook.OUTLINE) {
                        val outline = Paint(paint).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = paint.textSize * .13f
                            strokeJoin = Paint.Join.ROUND
                            color = if (Color(layer.color).luminance() > .5f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        }
                        canvas.drawText(line, layer.center.x, y, outline)
                    }
                    canvas.drawText(line, layer.center.x, y, paint)
                    y += lineHeight
                }
            }
        }
        canvas.restore()
    }
}

/** The finished picture, at full size. */
/**
 * The finished picture. Stretching and added space can make it far bigger than the photo, so it's scaled to stay
 * within [maxPixels] (16 million is about 64 MB in memory) and 4096 on the longest side.
 */
internal fun flatten(state: EditState, maxPixels: Long = 16_000_000L): Bitmap {
    val pixels = state.width.toLong() * state.height
    val shrink = minOf(1f, kotlin.math.sqrt(maxPixels.toDouble() / pixels).toFloat(), 4096f / max(state.width, state.height))
    val width = max(1, (state.width * shrink).toInt())
    val height = max(1, (state.height * shrink).toInt())
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    if (shrink < 1f) canvas.scale(width / state.width.toFloat(), height / state.height.toFloat())
    renderEdit(canvas, state)
    return result
}

internal object EditFiles {
    fun open(context: Context, uri: Uri, longest: Int = 2048): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = (longest.toFloat() / max(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }.getOrNull()

    fun save(context: Context, bitmap: Bitmap): Attachment {
        // Any see-through pixel (a removed background, or a transparent picture) keeps the picture a PNG.
        val alpha = bitmap.hasAlpha() && run {
            val row = IntArray(bitmap.width)
            (0 until bitmap.height).any { y ->
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                row.any { (it ushr 24) < 255 }
            }
        }
        val file = AttachmentFiles.scratch(context, if (alpha) "png" else "jpg")
        FileOutputStream(file).use { bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
        return Attachment(AttachmentKind.PHOTO, AttachmentFiles.uri(context, file), if (alpha) "Edited photo.png" else "Edited photo.jpg",
            if (alpha) "image/png" else "image/jpeg", file.length())
    }
}

private enum class EditTool(val label: String) {
    CROP("Crop"), ADJUST("Adjust"), FILTERS("Filters"), BACKGROUND("Background"), DRAW("Draw"), TEXT("Text"), IMAGES("Images"), SIZE("Size")
}

private val swatches = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF007AFF, 0xFF5856D6,
    0xFFAF52DE, 0xFFFF2D55, 0xFFA2845E).map { it.toInt() }

/** How Background touches the picture: tap a spot of background, or brush parts away or back. */
private enum class CutMode(val label: String) { TAP("Tap to remove"), ERASE("Erase"), KEEP("Keep") }

/**
 * The editor, full screen. [done] gets the edited picture and whether to send it right away. With [sticker], it
 * opens on Background with the background already being removed, and its one button saves the sticker.
 */
@Composable
internal fun MediaEditor(source: Uri, dismiss: () -> Unit, sticker: Boolean = false, done: (Attachment, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Null while loading, then the picture or a failure (an unsupported file, or one that's gone).
    val loaded by produceState<Result<Bitmap>?>(null, source) {
        value = withContext(Dispatchers.IO) { EditFiles.open(context, source)?.let { Result.success(it) } ?: Result.failure(IllegalStateException()) }
    }
    val first = loaded?.getOrNull()
    var failed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0E0E10), contentColor = Color.White) {
            if (loaded?.isFailure == true || failed) Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (failed) "The edited picture couldn't be saved. Your phone may be out of space." else "This picture can't be opened for editing.",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = dismiss) { Text("Close", color = Color.White) }
            }
            else if (first == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else EditorBody(first, sticker, dismiss) { bitmap, send ->
                scope.launch {
                    val attachment = withContext(Dispatchers.IO) { runCatching { EditFiles.save(context, bitmap) }.getOrNull() }
                    if (attachment == null) { failed = true; return@launch }
                    done(attachment, send)
                }
            }
        }
    }
}

@Composable
private fun EditorBody(original: Bitmap, sticker: Boolean, dismiss: () -> Unit, finish: (Bitmap, Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(EditState(original)) }
    val undo = remember { mutableStateListOf<EditState>() }
    val redo = remember { mutableStateListOf<EditState>() }
    // Crops and turns make a new full-size photo, so undo keeps at most a few of those (and 30 steps in all).
    fun trim() {
        while (undo.size > 30 || undo.flatMap { listOfNotNull(it.base, it.cutout?.source) }.distinct().size > 6) undo.removeAt(0)
    }
    fun commit(next: EditState) { undo.add(state); trim(); redo.clear(); state = next }
    var tool by remember { mutableStateOf(if (sticker) EditTool.BACKGROUND else EditTool.CROP) }
    var brush by remember { mutableStateOf(EditBrush.PEN) }
    var ink by remember { mutableIntStateOf(0xFFFF3B30.toInt()) }
    var thickness by remember { mutableFloatStateOf(12f) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf<TextLayer?>(null) }
    var crop by remember { mutableStateOf<RectF?>(null) }
    var cropRatio by remember { mutableStateOf<Float?>(null) }
    var working by remember { mutableStateOf(false) }
    val nextId = remember { java.util.concurrent.atomic.AtomicLong(1) }
    val scale = max(state.width, state.height) / 1000f
    fun addText(text: String, at: Offset, font: EditFont = EditFont.BOLD, look: TextLook = TextLook.OUTLINE, color: Int = android.graphics.Color.WHITE) {
        val layer = TextLayer(nextId.getAndIncrement(), text, font, color, 72f * scale.coerceAtLeast(.4f), look, at)
        if (state.layers.size < 60) commit(state.copy(layers = state.layers + layer))
        selected = layer.id
    }
    // Sliders change the picture live; the step for undo is taken once, when a slide starts.
    var sliding by remember { mutableStateOf(false) }
    fun slide(next: EditState) { if (!sliding) { undo.add(state); trim(); redo.clear(); sliding = true }; state = next }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val bitmap = uri?.let { EditFiles.open(context, it, 1024) } ?: return@rememberLauncherForActivityResult
        val fit = min(state.width, state.height) * .4f / max(bitmap.width, bitmap.height)
        val layer = PictureLayer(nextId.getAndIncrement(), bitmap, Offset(state.width / 2f, state.height / 2f), fit)
        if (state.layers.size < 60) commit(state.copy(layers = state.layers + layer))
        selected = layer.id
    }
    fun updateLayer(id: Long, change: (EditLayer) -> EditLayer, record: Boolean = true) {
        val next = state.copy(layers = state.layers.map { if (it.id == id) change(it) else it })
        if (record) commit(next) else state = next
    }
    val chosen = state.layers.firstOrNull { it.id == selected }

    // Background removal runs off the main thread; a newer request replaces one still running.
    var cutMode by remember { mutableStateOf(CutMode.TAP) }
    var cutJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var cutProgress by remember { mutableStateOf<Float?>(null) }
    var cutNote by remember { mutableStateOf("") }
    var strength by remember { mutableFloatStateOf(.4f) }
    var softness by remember { mutableFloatStateOf(.35f) }
    fun runCut(params: Cutout) {
        val base = state.base
        cutJob?.cancel()
        cutProgress = 0f
        cutNote = ""
        cutJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                try { Result.success(EditCutouts.compute(base, params, { cutProgress = it }, { !isActive })) }
                catch (_: BackgroundCut.CancellationException) { null }
                catch (_: OutOfMemoryError) { Result.failure(IllegalStateException("memory")) }
            } ?: return@launch
            cutProgress = null
            val made = result.getOrNull()
            when {
                made == null -> cutNote = "There isn't enough memory to remove this picture's background. Try cropping it first."
                state.base !== base -> {}
                else -> {
                    // Erase and Keep strokes made meanwhile stay.
                    commit(state.copy(cutout = made.copy(touches = state.cutout?.touches.orEmpty())))
                    cutNote = when {
                        made.removed < .01f -> "No clear background found. Tap the background, or use Erase."
                        made.removed > .97f -> "That took almost everything. Lower Strength, or use Keep to bring parts back."
                        else -> ""
                    }
                }
            }
        }
    }
    fun cutParams() = (state.cutout ?: Cutout()).copy(tolerance = strength, softness = softness)
    LaunchedEffect(Unit) { if (sticker) runCut(Cutout(tolerance = strength, softness = softness)) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = dismiss) { Text("Cancel", color = Color.White) }
            Spacer(Modifier.weight(1f))
            TextButton(enabled = undo.isNotEmpty(), onClick = { redo.add(state); state = undo.removeAt(undo.lastIndex); selected = null }) {
                Text("↶ Undo", color = if (undo.isNotEmpty()) Color.White else Color.Gray)
            }
            TextButton(enabled = redo.isNotEmpty(), onClick = { undo.add(state); state = redo.removeAt(redo.lastIndex) }) {
                Text("↷", color = if (redo.isNotEmpty()) Color.White else Color.Gray, fontSize = 18.sp)
            }
            Spacer(Modifier.weight(1f))
            if (sticker) Button(enabled = !working && cutProgress == null, onClick = {
                working = true; selected = null; finish(EditCutouts.trimmed(flatten(state)), false)
            }) { Text("Save sticker") }
            else {
                TextButton(enabled = !working, onClick = { working = true; selected = null; finish(flatten(state), false) }) { Text("Done", color = Color.White) }
                Button(enabled = !working, onClick = { working = true; selected = null; finish(flatten(state), true) }) { Text("Send") }
            }
        }
        // The picture, fitted to the space, with every gesture mapped back to picture coordinates.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val fit = min(boxW / state.width, boxH / state.height)
            val viewW = state.width * fit
            val viewH = state.height * fit
            fun toPicture(point: Offset) = Offset(point.x / fit, point.y / fit)
            var drawing by remember { mutableStateOf<List<Offset>?>(null) }
            val live = state
            val checkerCell = with(density) { 8.dp.toPx() }
            val checker = remember {
                Paint().apply {
                    val tile = Bitmap.createBitmap(intArrayOf(0xFF9A9A9E.toInt(), 0xFF6B6B70.toInt(), 0xFF6B6B70.toInt(), 0xFF9A9A9E.toInt()), 2, 2, Bitmap.Config.ARGB_8888)
                    shader = android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
                }
            }
            Canvas(Modifier.size(with(density) { viewW.toDp() }, with(density) { viewH.toDp() })
                .pointerInput(tool, brush, ink, thickness, fit, scale, cropRatio, cutMode) {
                    when (tool) {
                        EditTool.BACKGROUND -> if (cutMode == CutMode.TAP) detectTapGestures { point ->
                            // Picture coordinates to photo pixels.
                            val p = toPicture(point)
                            val x = ((p.x - state.padSides) / state.stretchX).toInt()
                            val y = ((p.y - state.padTop) / state.stretchY).toInt()
                            if (x in 0 until state.base.width && y in 0 until state.base.height) {
                                val current = cutParams()
                                runCut(current.copy(taps = (current.taps + (x to y)).takeLast(40)))
                            }
                        } else detectDragGestures(
                            onDragStart = { drawing = listOf(toPicture(it)) },
                            onDragEnd = {
                                drawing?.let { points ->
                                    val cut = state.cutout ?: Cutout(fromEdges = false)
                                    commit(state.copy(cutout = cut.copy(touches = cut.touches + cutTouch(points, cutMode, thickness * scale))))
                                }
                                drawing = null
                            },
                            onDragCancel = { drawing = null },
                        ) { change, _ -> drawing = drawing.orEmpty() + toPicture(change.position) }
                        EditTool.DRAW -> detectDragGestures(
                            onDragStart = { drawing = listOf(toPicture(it)) },
                            onDragEnd = {
                                drawing?.let { points -> commit(state.copy(strokes = state.strokes + EditStroke(points, ink, thickness * scale, brush))) }
                                drawing = null
                            },
                            onDragCancel = { drawing = null },
                        ) { change, _ -> drawing = drawing.orEmpty() + toPicture(change.position) }
                        EditTool.CROP -> detectDragGestures(onDragStart = { start ->
                            val point = toPicture(start)
                            val rect = crop ?: RectF(0f, 0f, state.width.toFloat(), state.height.toFloat())
                            crop = RectF(rect)
                            // Which corner, or the middle to move the whole box.
                            dragCorner = listOf(Offset(rect.left, rect.top), Offset(rect.right, rect.top), Offset(rect.left, rect.bottom),
                                Offset(rect.right, rect.bottom)).withIndex().minByOrNull { (it.value - point).getDistance() }
                                ?.takeIf { (it.value - point).getDistance() < 64f / fit }?.index ?: -1
                        }) { change, amount ->
                            val delta = Offset(amount.x / fit, amount.y / fit)
                            val rect = RectF(crop ?: return@detectDragGestures)
                            when (dragCorner) {
                                0 -> { rect.left += delta.x; rect.top += delta.y }
                                1 -> { rect.right += delta.x; rect.top += delta.y }
                                2 -> { rect.left += delta.x; rect.bottom += delta.y }
                                3 -> { rect.right += delta.x; rect.bottom += delta.y }
                                else -> rect.offset(delta.x, delta.y)
                            }
                            change.consume()
                            crop = constrainCrop(rect, state.width.toFloat(), state.height.toFloat(), cropRatio, dragCorner)
                        }
                        EditTool.TEXT, EditTool.IMAGES -> coroutineScope {
                            launch {
                                detectTapGestures(onDoubleTap = { point ->
                                    val hit = state.layers.lastOrNull { bounds(it).contains(toPicture(point).x, toPicture(point).y) }
                                    if (hit is TextLayer) editingText = hit
                                }) { point ->
                                    val p = toPicture(point)
                                    val hit = state.layers.lastOrNull { bounds(it).contains(p.x, p.y) }
                                    selected = hit?.id
                                    if (hit == null && tool != EditTool.IMAGES) editingText = TextLayer(-1, "", EditFont.BOLD, android.graphics.Color.WHITE,
                                        72f * scale.coerceAtLeast(.4f), TextLook.OUTLINE, p)
                                }
                            }
                            launch {
                                // Move, pinch, and turn the chosen layer. Each whole gesture is one step for undo.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var recorded = false
                                    val start = toPicture(down.position)
                                    val target = state.layers.lastOrNull { it.id == selected && bounds(it).contains(start.x, start.y) }
                                        ?: state.layers.lastOrNull { bounds(it).contains(start.x, start.y) }
                                    do {
                                        val event = awaitPointerEvent()
                                        val id = target?.id ?: break
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        val rotation = event.calculateRotation()
                                        if (pan == Offset.Zero && zoom == 1f && rotation == 0f) continue
                                        if (!recorded) { undo.add(state); trim(); redo.clear(); recorded = true; selected = id }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        updateLayer(id, { layer ->
                                        val moved = layer.center + Offset(pan.x / fit, pan.y / fit)
                                        val size = (layer.scale * zoom).coerceIn(.1f, 12f)
                                        when (layer) {
                                            is TextLayer -> layer.copy(center = moved, scale = size, rotation = layer.rotation + rotation)
                                            is PictureLayer -> layer.copy(center = moved, scale = size, rotation = layer.rotation + rotation)
                                        }
                                    }, record = false)
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                        }
                        else -> {}
                    }
                }) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.scale(fit, fit)
                    checker.shader.setLocalMatrix(Matrix().apply { setScale(checkerCell / fit, checkerCell / fit) })
                    val preview = drawing?.let { points ->
                        if (tool == EditTool.BACKGROUND) {
                            val cut = live.cutout ?: Cutout(fromEdges = false)
                            live.copy(cutout = cut.copy(touches = cut.touches + cutTouch(points, cutMode, thickness * scale)))
                        } else live.copy(strokes = live.strokes + EditStroke(points, ink, thickness * scale, brush))
                    } ?: live
                    // A checkerboard behind see-through parts, as photo apps show it.
                    if (preview.cutout != null || preview.base.hasAlpha()) native.drawRect(0f, 0f, preview.width.toFloat(), preview.height.toFloat(), checker)
                    renderEdit(native, preview)
                    native.restore()
                }
                chosen?.let { layer ->
                    val box = bounds(layer)
                    rotateAround(layer.rotation, Offset(layer.center.x * fit, layer.center.y * fit)) {
                        drawRect(Color.White, Offset(box.left * fit, box.top * fit), androidx.compose.ui.geometry.Size(box.width() * fit, box.height() * fit),
                            style = Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                    }
                }
                if (tool == EditTool.CROP) {
                    val rect = crop ?: RectF(0f, 0f, live.width.toFloat(), live.height.toFloat())
                    val shade = Color.Black.copy(alpha = .55f)
                    drawRect(shade, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, rect.top * fit))
                    drawRect(shade, Offset(0f, rect.bottom * fit), androidx.compose.ui.geometry.Size(size.width, size.height - rect.bottom * fit))
                    drawRect(shade, Offset(0f, rect.top * fit), androidx.compose.ui.geometry.Size(rect.left * fit, rect.height() * fit))
                    drawRect(shade, Offset(rect.right * fit, rect.top * fit), androidx.compose.ui.geometry.Size(size.width - rect.right * fit, rect.height() * fit))
                    drawRect(Color.White, Offset(rect.left * fit, rect.top * fit), androidx.compose.ui.geometry.Size(rect.width() * fit, rect.height() * fit),
                        style = Stroke(2.dp.toPx()))
                    for (third in 1..2) {
                        val x = (rect.left + rect.width() * third / 3) * fit
                        val y = (rect.top + rect.height() * third / 3) * fit
                        drawLine(Color.White.copy(alpha = .5f), Offset(x, rect.top * fit), Offset(x, rect.bottom * fit), 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = .5f), Offset(rect.left * fit, y), Offset(rect.right * fit, y), 1.dp.toPx())
                    }
                    for (corner in listOf(Offset(rect.left, rect.top), Offset(rect.right, rect.top), Offset(rect.left, rect.bottom), Offset(rect.right, rect.bottom))) {
                        drawCircle(Color.White, 9.dp.toPx(), Offset(corner.x * fit, corner.y * fit))
                    }
                }
            }
        }
        // Controls for the tool in use.
        Column(Modifier.fillMaxWidth().background(Color(0xFF1C1C1F)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tool) {
                EditTool.BACKGROUND -> {
                    val cut = state.cutout
                    val busy = cutProgress
                    if (busy != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Removing the background…", fontSize = 13.sp)
                        LinearProgressIndicator(progress = { busy }, Modifier.weight(1f))
                        TextButton(onClick = { cutJob?.cancel(); cutProgress = null }) { Text("Stop", color = Color.White) }
                    }
                    if (cut?.mask == null && busy == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { runCut(cutParams().copy(fromEdges = true)) }) { Text("Remove background") }
                        Text("Works best when the background is plain and runs along the picture's edges.", Modifier.weight(1f), fontSize = 12.sp, color = Color.LightGray)
                    }
                    ChipRow {
                        for (mode in CutMode.entries) FilterChip(cutMode == mode, { cutMode = mode }, label = { Text(mode.label) })
                        if (cut != null) EditChip("Bring back all") { commit(state.copy(cutout = null)); cutNote = "" }
                    }
                    Text(when (cutMode) {
                        CutMode.TAP -> "Tap any background still showing, like a gap between arms, to remove it too."
                        CutMode.ERASE -> "Brush over anything else to remove."
                        CutMode.KEEP -> "Brush over parts that should stay, to bring them back."
                    }, fontSize = 12.sp, color = Color.LightGray)
                    if (cutMode == CutMode.TAP) {
                        LabeledSlider("Strength", strength, 0f..1f, { if (cut?.mask != null) runCut(cutParams()) }) { strength = it }
                        LabeledSlider("Soft edges", softness, 0f..1f, { if (cut?.mask != null) runCut(cutParams()) }) { softness = it }
                    } else LabeledSlider("Brush size", thickness, 3f..60f) { thickness = it }
                    if (cutNote.isNotEmpty()) Text(cutNote, fontSize = 12.sp, color = Color(0xFFFFD60A))
                }
                EditTool.ADJUST -> {
                    val a = state.adjust
                    LabeledSlider("Brightness", a.brightness, -1f..1f, { sliding = false }) { slide(state.copy(adjust = a.copy(brightness = it))) }
                    LabeledSlider("Contrast", a.contrast, -1f..1f, { sliding = false }) { slide(state.copy(adjust = a.copy(contrast = it))) }
                    LabeledSlider("Saturation", a.saturation, -1f..1f, { sliding = false }) { slide(state.copy(adjust = a.copy(saturation = it))) }
                    LabeledSlider("Warmth", a.warmth, -1f..1f, { sliding = false }) { slide(state.copy(adjust = a.copy(warmth = it))) }
                    LabeledSlider("Tint", a.tint, -1f..1f, { sliding = false }) { slide(state.copy(adjust = a.copy(tint = it))) }
                    LabeledSlider("Fade", a.fade, 0f..1f, { sliding = false }) { slide(state.copy(adjust = a.copy(fade = it))) }
                    TextButton(enabled = !a.untouched, onClick = { commit(state.copy(adjust = Adjustments())) }) {
                        Text("Reset adjustments", color = if (a.untouched) Color.Gray else Color.White)
                    }
                }
                EditTool.CROP -> {
                    ChipRow {
                        for ((label, ratio) in listOf("Free" to null, "Square" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16)) {
                            FilterChip(cropRatio == ratio, {
                                cropRatio = ratio
                                crop = constrainCrop(RectF(0f, 0f, state.width.toFloat(), state.height.toFloat()), state.width.toFloat(), state.height.toFloat(), ratio, 3, center = true)
                            }, label = { Text(label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { commit(EditState(rotated(flatten(state), 90f))); crop = null }) { Text("⟳ Rotate") }
                        OutlinedButton(onClick = { commit(EditState(flipped(flatten(state)))); crop = null }) { Text("⇋ Flip") }
                        Spacer(Modifier.weight(1f))
                        Button(enabled = crop != null, onClick = {
                            val rect = crop ?: return@Button
                            val whole = flatten(state)
                            val left = rect.left.toInt().coerceIn(0, whole.width - 1)
                            val top = rect.top.toInt().coerceIn(0, whole.height - 1)
                            val cropped = Bitmap.createBitmap(whole, left, top, rect.width().toInt().coerceIn(1, whole.width - left),
                                rect.height().toInt().coerceIn(1, whole.height - top))
                            commit(EditState(cropped)); crop = null; selected = null
                        }) { Text("Crop") }
                    }
                }
                EditTool.SIZE -> {
                    LabeledSlider("Stretch wide", state.stretchX, .4f..2.5f, { sliding = false }) { slide(state.copy(stretchX = it)) }
                    LabeledSlider("Stretch tall", state.stretchY, .4f..2.5f, { sliding = false }) { slide(state.copy(stretchY = it)) }
                    val unit = max(state.base.width * state.stretchX, state.base.height * state.stretchY)
                    LabeledSlider("Space on top", state.padTop / unit, 0f..1f, { sliding = false }) { slide(state.copy(padTop = (it * unit).toInt())) }
                    LabeledSlider("Space below", state.padBottom / unit, 0f..1f, { sliding = false }) { slide(state.copy(padBottom = (it * unit).toInt())) }
                    LabeledSlider("Space on the sides", state.padSides / unit, 0f..1f, { sliding = false }) { slide(state.copy(padSides = (it * unit).toInt())) }
                    SwatchRow(state.padColor) { commit(state.copy(padColor = it)) }
                    TextButton(onClick = { commit(state.copy(stretchX = 1f, stretchY = 1f, padTop = 0, padBottom = 0, padSides = 0)) }) {
                        Text("Reset size", color = Color.White)
                    }
                }
                EditTool.FILTERS -> {
                    val thumb = remember(state.base) {
                        val ratio = 96f / max(state.base.width, state.base.height)
                        Bitmap.createScaledBitmap(state.base, (state.base.width * ratio).toInt().coerceAtLeast(1), (state.base.height * ratio).toInt().coerceAtLeast(1), true)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (filter in EditFilter.entries) Column(Modifier.clip(RoundedCornerShape(10.dp))
                            .clickable { commit(state.copy(filter = filter)) }.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val image = remember(thumb, filter) {
                                Bitmap.createBitmap(thumb.width, thumb.height, Bitmap.Config.ARGB_8888).also { out ->
                                    android.graphics.Canvas(out).drawBitmap(thumb, 0f, 0f, Paint().apply {
                                        colorFilter = filter.matrix?.let { ColorMatrixColorFilter(ColorMatrix(it)) } })
                                }.asImageBitmap()
                            }
                            androidx.compose.foundation.Image(image, filter.label, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                                .then(if (state.filter == filter) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            Text(filter.label, fontSize = 11.sp)
                        }
                    }
                }
                EditTool.DRAW -> {
                    ChipRow { for (option in EditBrush.entries) FilterChip(brush == option, { brush = option }, label = { Text(option.label) }) }
                    if (brush != EditBrush.ERASER) SwatchRow(ink) { ink = it }
                    LabeledSlider("Size", thickness, 3f..60f) { thickness = it }
                }
                EditTool.TEXT -> {
                    if (chosen is TextLayer) {
                        ChipRow {
                            for (font in EditFont.entries) FilterChip(chosen.font == font, { updateLayer(chosen.id, { (it as TextLayer).copy(font = font) }) },
                                label = { Text(font.label, fontFamily = androidx.compose.ui.text.font.FontFamily(font.typeface)) })
                        }
                        ChipRow {
                            for (look in TextLook.entries) FilterChip(chosen.look == look, { updateLayer(chosen.id, { (it as TextLayer).copy(look = look) }) },
                                label = { Text(look.label) })
                            EditChip("Edit words") { editingText = chosen }
                            EditChip("Delete") { commit(state.copy(layers = state.layers - chosen)); selected = null }
                        }
                        SwatchRow(chosen.color) { color -> updateLayer(chosen.id, { (it as TextLayer).copy(color = color) }) }
                        Text("Drag to move. Pinch to resize or turn.", fontSize = 12.sp, color = Color.LightGray)
                    } else {
                        Text("Tap the picture where you want words, or start from a layout.", fontSize = 13.sp, color = Color.LightGray)
                        ChipRow {
                            EditChip("Add text") { editingText = TextLayer(-1, "", EditFont.CLASSIC, android.graphics.Color.WHITE, 72f * scale.coerceAtLeast(.4f),
                                TextLook.OUTLINE, Offset(state.width / 2f, state.height / 2f)) }
                            EditChip("Top and bottom") {
                                addText("Top text", Offset(state.width / 2f, state.height * .1f))
                                addText("Bottom text", Offset(state.width / 2f, state.height * .9f))
                            }
                            EditChip("Title bar above") {
                                val space = (state.height * .22f).toInt()
                                val shift = Offset(0f, space.toFloat())
                                val next = state.copy(padTop = state.padTop + space, padColor = android.graphics.Color.WHITE,
                                    layers = state.layers.map { layer -> when (layer) {
                                        is TextLayer -> layer.copy(center = layer.center + shift)
                                        is PictureLayer -> layer.copy(center = layer.center + shift)
                                    } }, strokes = state.strokes.map { stroke -> stroke.copy(points = stroke.points.map { it + shift }) })
                                commit(next)
                                addText("Title", Offset(next.width / 2f, space / 2f), EditFont.CLASSIC, TextLook.PLAIN, android.graphics.Color.BLACK)
                            }
                            EditChip("Caption bar below") {
                                val space = (state.height * .22f).toInt()
                                commit(state.copy(padBottom = state.padBottom + space, padColor = android.graphics.Color.WHITE))
                                addText("Caption", Offset(state.width / 2f, state.height - space / 2f), EditFont.CLASSIC, TextLook.PLAIN, android.graphics.Color.BLACK)
                            }
                            EditChip("Label") { addText("Label", Offset(state.width / 2f, state.height * .5f), EditFont.CLASSIC, TextLook.BOX, android.graphics.Color.WHITE) }
                        }
                    }
                }
                EditTool.IMAGES -> {
                    ChipRow {
                        EditChip("Add a photo") { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        for (file in remember { SavedStickers.list(context).take(12) }) {
                            val sticker = remember(file) { EditFiles.open(context, Uri.fromFile(file), 512) }
                            if (sticker != null) Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable {
                                val fit = min(state.width, state.height) * .3f / max(sticker.width, sticker.height)
                                val layer = PictureLayer(nextId.getAndIncrement(), sticker, Offset(state.width / 2f, state.height / 2f), fit)
                                if (state.layers.size < 60) commit(state.copy(layers = state.layers + layer)); selected = layer.id
                            }) { androidx.compose.foundation.Image(sticker.asImageBitmap(), "Sticker", Modifier.fillMaxSize()) }
                        }
                    }
                    if (chosen is PictureLayer) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Drag to move. Pinch to resize or turn.", Modifier.weight(1f), fontSize = 12.sp, color = Color.LightGray)
                        EditChip("Delete") { commit(state.copy(layers = state.layers - chosen)); selected = null }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (option in EditTool.entries) {
                    val on = tool == option
                    Text(option.label, Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) Color.White else Color.Transparent)
                        .clickable { tool = option; if (option != EditTool.CROP) crop = null }.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = if (on) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
    editingText?.let { layer ->
        var words by remember(layer) { mutableStateOf(layer.text) }
        FormPage(onDismissRequest = { editingText = null }, title = { Text(if (layer.id < 0) "Add text" else "Edit text") }, text = {
            OutlinedTextField(words, { words = it.take(200) }, minLines = 1, maxLines = 4, placeholder = { Text("Type your text") })
        }, confirmButton = {
            TextButton(onClick = {
                if (words.isNotBlank()) {
                    if (layer.id < 0) addText(words, layer.center, layer.font, layer.look, layer.color)
                    else updateLayer(layer.id, { (it as TextLayer).copy(text = words) })
                }
                editingText = null
            }) { Text("OK") }
        }, dismissButton = { TextButton(onClick = { editingText = null }) { Text("Cancel") } })
    }
}

private var dragCorner = -1

/** An Erase or Keep stroke for Background. Erase is marked with the eraser brush. */
private fun cutTouch(points: List<Offset>, mode: CutMode, width: Float) =
    EditStroke(points, android.graphics.Color.BLACK, width * 2.4f, if (mode == CutMode.ERASE) EditBrush.ERASER else EditBrush.PEN)

/** Keeps the crop box inside the picture, at least a little bigger than a fingertip, and at the chosen shape. */
private fun constrainCrop(rect: RectF, width: Float, height: Float, ratio: Float?, corner: Int, center: Boolean = false): RectF {
    val least = min(width, height) * .08f
    val out = RectF(rect)
    if (ratio != null) {
        if (center) {
            val w = min(width, height * ratio)
            val h = w / ratio
            out.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        } else {
            val h = out.width() / ratio
            if (corner == 0 || corner == 1) out.top = out.bottom - h else out.bottom = out.top + h
        }
    }
    if (out.width() < least) out.right = out.left + least
    if (out.height() < least) out.bottom = out.top + least
    if (out.width() > width) { out.left = 0f; out.right = width }
    if (out.height() > height) { out.top = 0f; out.bottom = height }
    if (out.left < 0) out.offset(-out.left, 0f)
    if (out.top < 0) out.offset(0f, -out.top)
    if (out.right > width) out.offset(width - out.right, 0f)
    if (out.bottom > height) out.offset(0f, height - out.bottom)
    return out
}

private fun rotated(bitmap: Bitmap, degrees: Float): Bitmap =
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)

private fun flipped(bitmap: Bitmap): Bitmap =
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postScale(-1f, 1f) }, true)

private suspend fun coroutineScope(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = kotlinx.coroutines.coroutineScope(block)

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun EditChip(label: String, run: () -> Unit) {
    AssistChip(onClick = run, label = { Text(label) }, colors = AssistChipDefaults.assistChipColors(labelColor = Color.White))
}

@Composable
private fun SwatchRow(current: Int, pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (color in swatches) Box(Modifier.size(30.dp).clip(CircleShape).background(Color(color))
            .border(if (color == current) 3.dp else 1.dp, if (color == current) Color.White else Color.Gray, CircleShape)
            .clickable { pick(color) })
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>,
                          finished: (() -> Unit)? = null, change: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(132.dp), fontSize = 13.sp)
        Slider(value.coerceIn(range), change, Modifier.weight(1f), valueRange = range, onValueChangeFinished = { finished?.invoke() })
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateAround(degrees: Float, pivot: Offset,
                                                                            block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    if (degrees == 0f) block() else rotate(degrees, pivot) { block() }
}
