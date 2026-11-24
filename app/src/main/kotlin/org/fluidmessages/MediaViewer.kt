package org.fluidmessages

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** A decoded picture: a still bitmap, or an animated GIF/WebP that plays. */
internal class DecodedMedia(val still: ImageBitmap?, val animated: Drawable?, val width: Int, val height: Int) {
    val ratio: Float get() = if (height <= 0) 1f else width.toFloat() / height
}

internal object MediaDecoding {
    /** Decodes a picture at most [longest] pixels on its longest side. GIFs and animated WebPs stay animated. */
    fun decode(context: Context, uri: Uri, longest: Int): DecodedMedia? = try {
        val resource = if (uri.scheme == "android.resource" && uri.authority == context.packageName) uri.lastPathSegment?.toIntOrNull() else null
        val source = when {
            resource != null -> ImageDecoder.createSource(context.resources, resource)
            uri.scheme == "file" -> ImageDecoder.createSource(File(uri.path!!))
            else -> ImageDecoder.createSource(context.contentResolver, uri)
        }
        var w = 0
        var h = 0
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val scale = (longest.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
            w = (info.size.width * scale).toInt().coerceAtLeast(1)
            h = (info.size.height * scale).toInt().coerceAtLeast(1)
            decoder.setTargetSize(w, h)
        }
        when (drawable) {
            is AnimatedImageDrawable -> DecodedMedia(null, drawable, w, h)
            is BitmapDrawable -> DecodedMedia(drawable.bitmap.asImageBitmap(), null, w, h)
            else -> DecodedMedia(null, drawable, w, h)
        }
    } catch (_: Exception) { null }
}

/** Pictures and GIFs linked in messages, downloaded once into a private cache. HTTPS only, 15 MB at most. */
internal object RemoteMedia {
    private const val LIMIT = 15L * 1024 * 1024

    fun cached(context: Context, url: String): File = File(File(context.cacheDir, "remote-media").apply { mkdirs() }, hash(url))

    fun fetch(context: Context, url: String): File? {
        if (!url.startsWith("https://")) return null
        val file = cached(context, url)
        if (file.isFile && file.length() > 0) return file
        return try {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "FluidMessages")
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode !in 200..299) return null
                val type = connection.contentType.orEmpty()
                if (!type.startsWith("image/") && !type.startsWith("video/")) return null
                if (connection.contentLengthLong > LIMIT) return null
                val partial = File(file.path + ".part")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > LIMIT) { partial.delete(); return null }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (partial.renameTo(file)) file else null
            } finally { connection.disconnect() }
        } catch (_: Exception) { null }
    }

    /** Old downloads are cleared so the cache doesn't grow without end. */
    fun trim(context: Context) {
        val files = File(context.cacheDir, "remote-media").listFiles() ?: return
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total < 80L * 1024 * 1024) break
            total -= file.length()
            file.delete()
        }
    }

    private fun hash(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
}

/** Draws a decoded picture, animating GIFs. */
@Composable
internal fun MediaPicture(media: DecodedMedia, modifier: Modifier, crop: Boolean, description: String) {
    val animated = media.animated
    if (animated != null) {
        AndroidView(factory = { context ->
            ImageView(context).apply {
                scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                contentDescription = description
            }
        }, modifier = modifier, update = { view ->
            if (view.drawable !== animated) {
                view.setImageDrawable(animated)
                (animated as? AnimatedImageDrawable)?.start()
            }
        })
    } else media.still?.let {
        Image(it, description, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
    }
}

/**
 * A photo or GIF in a message, sized to its own shape. Tap it to open the full-screen viewer.
 * [uri] is a local picture; [url] is a picture linked in the message text, downloaded first.
 */
@Composable
internal fun MessagePicture(uri: Uri?, url: String?, description: String, mime: String = "image/*", maxWidth: Int = 230, sticker: Boolean = false) {
    val context = LocalContext.current
    var viewing by remember { mutableStateOf(false) }
    val media by produceState<DecodedMedia?>(null, uri, url) {
        value = withContext(Dispatchers.IO) {
            val local = uri ?: url?.let { RemoteMedia.fetch(context, it)?.let(Uri::fromFile) }
            local?.let { MediaDecoding.decode(context, it, 1000) }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val ratio = (media?.ratio ?: 1.4f).coerceIn(.62f, 1.9f)
    val box = Modifier.padding(bottom = 5.dp).width(if (sticker) 150.dp else maxWidth.dp).aspectRatio(if (sticker) 1f else ratio).clip(shape)
        .background(if (sticker) Color.Transparent else Color.Gray.copy(alpha = .18f))
        .clickable(onClickLabel = "View full screen") { viewing = true }
        .semantics { contentDescription = description }
    Box(box, contentAlignment = Alignment.Center) {
        val decoded = media
        if (decoded != null) MediaPicture(decoded, Modifier.fillMaxSize(), crop = !sticker, description = description)
        else Text("Loading…", color = Color.Gray, fontSize = 12.sp)
        if (decoded?.animated != null && !sticker) Text("GIF", Modifier.align(Alignment.BottomStart).padding(7.dp)
            .background(Color.Black.copy(alpha = .5f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
    if (viewing) {
        val local = uri ?: url?.let { Uri.fromFile(RemoteMedia.cached(context, it)) }
        if (local != null) MediaViewer(local, mime, description, url) { viewing = false }
    }
}

/**
 * The full-screen viewer for photos, GIFs, and videos: pinch or double-tap to zoom, drag to look around,
 * swipe down to close. Share and Open with hand it to another app.
 */
@Composable
internal fun MediaViewer(uri: Uri, mime: String, title: String, link: String? = null, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val video = mime.startsWith("video/")
    val media by produceState<DecodedMedia?>(null, uri) {
        if (!video) value = withContext(Dispatchers.IO) { MediaDecoding.decode(context, uri, 2600) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val entrance = remember { Animatable(.92f) }
    val dismissDrag = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(220)) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val fade = (1f - kotlin.math.abs(dismissDrag.value) / 900f).coerceIn(.2f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade))) {
            val content = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale * entrance.value
                scaleY = scale * entrance.value
                translationX = offset.x
                translationY = offset.y + dismissDrag.value
                alpha = entrance.value
            }
            if (video) {
                AndroidView(factory = { viewContext ->
                    VideoView(viewContext).apply {
                        setVideoURI(uri)
                        setMediaController(MediaController(viewContext).also { it.setAnchorView(this) })
                        setOnPreparedListener { it.isLooping = false; start() }
                    }
                }, modifier = Modifier.fillMaxSize().align(Alignment.Center))
            } else {
                Box(content.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { point ->
                        if (scale > 1.05f) { scale = 1f; offset = Offset.Zero }
                        else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - point) * 1.5f }
                    }, onTap = { if (scale <= 1.05f) dismiss() })
                }.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 6f)
                        if (next <= 1.01f && zoom == 1f) {
                            // Not zoomed in: a vertical drag closes the viewer.
                            scope.launch { dismissDrag.snapTo(dismissDrag.value + pan.y) }
                        } else {
                            scale = next
                            offset = if (next <= 1.01f) Offset.Zero else offset + pan
                        }
                    }
                }.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.all { !it.pressed } && dismissDrag.value != 0f) {
                                if (kotlin.math.abs(dismissDrag.value) > 220f) dismiss()
                                else scope.launch { dismissDrag.animateTo(0f, tween(180)) }
                            }
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                    val decoded = media
                    if (decoded != null) MediaPicture(decoded, Modifier.fillMaxSize(), crop = false, description = title)
                    else Text("Loading…", color = Color.White.copy(alpha = .7f))
                }
            }
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ViewerButton("Close", "✕") { dismiss() }
                Spacer(Modifier.weight(1f))
                ViewerButton("Share", "Share") { shareMedia(context, uri, mime, link) }
                Spacer(Modifier.width(8.dp))
                ViewerButton("Open with another app", "Open") { openMedia(context, uri, mime) }
            }
        }
    }
}

@Composable
private fun ViewerButton(label: String, text: String, click: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .16f)).clickable(onClickLabel = label, onClick = click)
        .padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A content:// address other apps can open; private files are passed through the app's file provider. */
private fun shareable(context: Context, uri: Uri): Uri =
    if (uri.scheme == "file") AttachmentFiles.uri(context, File(uri.path!!)) else uri

private fun shareMedia(context: Context, uri: Uri, mime: String, link: String?) {
    val intent = if (link != null) Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    else Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, shareable(context, uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}

private fun openMedia(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(shareable(context, uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
}
