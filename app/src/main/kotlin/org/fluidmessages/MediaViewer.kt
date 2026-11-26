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

/** Set by a chat: opens the photo editor on a picture, so any photo in the chat can be edited and sent back. */
internal val LocalEditPicture = androidx.compose.runtime.staticCompositionLocalOf<((Uri) -> Unit)?> { null }

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
/**
 * Links in messages come from other people, so pictures and previews are only fetched from public addresses:
 * never this phone, the home network, or carrier-internal ones a sender could use to probe what's around you.
 */
internal object PublicHosts {
    fun allowed(url: URL): Boolean {
        if (!NetworkGate.internetAllowed()) return false  // Nearby only: no internet at all.
        if (url.protocol != "https" || url.host.isNullOrEmpty()) return false
        // Through Tor the name isn't looked up here (no DNS leak); Tor's exits can't reach your network anyway.
        if (TorRoute.on()) return !TorRoute.localHost(url.host)
        val addresses = runCatching { java.net.InetAddress.getAllByName(url.host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.all { public(it) }
    }

    private fun public(address: java.net.InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0 || first >= 224 || first == 100 && second in 64..127 || first == 192 && second == 0 && (bytes[2].toInt() and 0xFF) == 0 ||
                first == 198 && second in 18..19) return false
        } else if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return false // Unique local (fc00::/7).
        return true
    }
}

internal object RemoteMedia {
    private const val LIMIT = 15L * 1024 * 1024

    fun cached(context: Context, url: String): File = File(File(context.cacheDir, "remote-media").apply { mkdirs() }, hash(url))

    fun fetch(context: Context, url: String): File? {
        if (!url.startsWith("https://")) return null
        val file = cached(context, url)
        if (file.isFile && file.length() > 0) return file
        return try {
            // Redirects are followed by hand so every hop is checked.
            var target = URL(url)
            var connection: HttpsURLConnection
            var hops = 0
            while (true) {
                if (!PublicHosts.allowed(target)) return null
                connection = TorRoute.open(target) as HttpsURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "FluidMessages")
                connection.setRequestProperty("Accept", "image/*, video/*")
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if (code !in 300..399) break
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                if (next == null || ++hops > 4) return null
                target = URL(target, next)
            }
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
                val editPicture = LocalEditPicture.current
                if (editPicture != null && !video) {
                    ViewerButton("Edit this picture", "Edit") { dismiss(); editPicture(uri) }
                    Spacer(Modifier.width(8.dp))
                }
                val saveScope = rememberCoroutineScope()
                ViewerButton("Save to this phone", "Save") {
                    saveScope.launch {
                        val saved = withContext(Dispatchers.IO) { saveToPhone(context, uri, mime, title) }
                        android.widget.Toast.makeText(context, saved ?: "Couldn't save it", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                Spacer(Modifier.width(8.dp))
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

/**
 * Copies a picture, GIF, video, or file into the phone's shared storage (Pictures, Movies, Music, or Downloads, in a
 * FluidMessages folder), where the gallery and file apps see it. No storage permission is needed for this on Android 10+.
 * Returns where it went, for a short message, or null if it couldn't be saved.
 */
internal fun saveToPhone(context: Context, uri: Uri, mime: String, title: String): String? = runCatching {
    val resolver = context.contentResolver
    val type = mime.ifEmpty { resolver.getType(uri) ?: "application/octet-stream" }
    val (collection, folder, place) = when {
        type.startsWith("image/") -> Triple(android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
            android.os.Environment.DIRECTORY_PICTURES, "Pictures")
        type.startsWith("video/") -> Triple(android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
            android.os.Environment.DIRECTORY_MOVIES, "Movies")
        type.startsWith("audio/") -> Triple(android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
            android.os.Environment.DIRECTORY_MUSIC, "Music")
        else -> Triple(android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
            android.os.Environment.DIRECTORY_DOWNLOADS, "Downloads")
    }
    val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
    val base = title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "FluidMessages" }.take(60)
    val name = if (extension != null && !base.lowercase().endsWith(".$extension")) "$base.$extension" else base
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, type)
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "$folder/FluidMessages")
        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val target = resolver.insert(collection, values) ?: return@runCatching null
    try {
        val copied = resolver.openInputStream(uri)?.use { input -> resolver.openOutputStream(target)?.use { input.copyTo(it); true } } == true
        if (!copied) { resolver.delete(target, null, null); return@runCatching null }
        resolver.update(target, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (error: Exception) { resolver.delete(target, null, null); throw error }
    "Saved to $place › FluidMessages"
}.getOrNull()

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
