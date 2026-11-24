package org.fluidmessages

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** One search result: a small preview to show and the link that gets sent. */
internal data class GifResult(val preview: String, val send: String, val width: Int, val height: Int)

/**
 * GIF and sticker settings and search. Searching is optional: with no service chosen, GIFs come from your
 * phone or keyboard. Tenor and GIPHY need your own free API key; an API address can point at a compatible
 * server or proxy instead of theirs.
 */
internal object GifSettings {
    const val NONE = "none"; const val TENOR = "tenor"; const val GIPHY = "giphy"

    @JvmStatic fun gifsOn(prefs: SharedPreferences) = prefs.getBoolean("gifs", true)
    @JvmStatic fun stickersOn(prefs: SharedPreferences) = prefs.getBoolean("stickers", true)
    @JvmStatic fun source(prefs: SharedPreferences): String = prefs.getString("gifSource", NONE) ?: NONE
    @JvmStatic fun key(prefs: SharedPreferences): String = prefs.getString("gifKey", "").orEmpty()
    @JvmStatic fun apiBase(prefs: SharedPreferences): String = prefs.getString("gifApi", "").orEmpty()
    @JvmStatic fun autoPictures(prefs: SharedPreferences) = prefs.getBoolean("autoPictures", true)
    @JvmStatic fun linkPreviews(prefs: SharedPreferences) = prefs.getBoolean("linkPreviews", true)

    @JvmStatic fun label(source: String) = when (source) { TENOR -> "Tenor"; GIPHY -> "GIPHY"; else -> "Your phone and keyboard" }

    fun searchable(prefs: SharedPreferences) = source(prefs) != NONE && key(prefs).isNotBlank()

    /** Searches, or shows what's popular for an empty query. Blocking; call off the main thread. */
    fun search(prefs: SharedPreferences, query: String, stickers: Boolean): List<GifResult> {
        val key = URLEncoder.encode(key(prefs), "UTF-8")
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val custom = apiBase(prefs).trim().trimEnd('/').takeIf { it.startsWith("https://") }
        return when (source(prefs)) {
            TENOR -> {
                val base = custom ?: "https://tenor.googleapis.com"
                val filter = if (stickers) "&searchfilter=sticker&media_filter=tinygif_transparent,gif_transparent" else "&media_filter=tinygif,gif"
                val path = if (query.isBlank()) "/v2/featured?" else "/v2/search?q=$q&"
                val json = get("$base${path}key=$key&client_key=fluidmessages&limit=30&contentfilter=medium$filter") ?: return emptyList()
                val results = json.optJSONArray("results") ?: return emptyList()
                (0 until results.length()).mapNotNull { index ->
                    val formats = results.optJSONObject(index)?.optJSONObject("media_formats") ?: return@mapNotNull null
                    val small = formats.optJSONObject(if (stickers) "tinygif_transparent" else "tinygif") ?: return@mapNotNull null
                    val full = formats.optJSONObject(if (stickers) "gif_transparent" else "gif") ?: small
                    val dims = small.optJSONArray("dims")
                    GifResult(small.optString("url"), full.optString("url"), dims?.optInt(0) ?: 1, dims?.optInt(1) ?: 1)
                }
            }
            GIPHY -> {
                val base = custom ?: "https://api.giphy.com"
                val kind = if (stickers) "stickers" else "gifs"
                val path = if (query.isBlank()) "/v1/$kind/trending?" else "/v1/$kind/search?q=$q&"
                val json = get("$base${path}api_key=$key&limit=30&rating=pg-13") ?: return emptyList()
                val data = json.optJSONArray("data") ?: return emptyList()
                (0 until data.length()).mapNotNull { index ->
                    val images = data.optJSONObject(index)?.optJSONObject("images") ?: return@mapNotNull null
                    val small = images.optJSONObject("fixed_width_small") ?: images.optJSONObject("fixed_width") ?: return@mapNotNull null
                    val full = images.optJSONObject("downsized") ?: images.optJSONObject("original") ?: small
                    GifResult(small.optString("url"), full.optString("url").substringBefore("?"),
                        small.optString("width").toIntOrNull() ?: 1, small.optString("height").toIntOrNull() ?: 1)
                }
            }
            else -> emptyList()
        }.filter { it.preview.startsWith("https://") && it.send.startsWith("https://") }
    }

    private fun get(url: String): JSONObject? = try {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode !in 200..299) null
            else JSONObject(connection.inputStream.use { String(it.readNBytes(2_000_000), Charsets.UTF_8) })
        } finally { connection.disconnect() }
    } catch (_: Exception) { null }
}

/** Stickers you've saved, kept as small PNG/WebP files on this phone only. */
internal object SavedStickers {
    private fun folder(context: Context) = File(context.filesDir, "stickers").apply { mkdirs() }

    fun list(context: Context): List<File> = folder(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Saves a picture as a sticker, at most 512 pixels, keeping transparency. Animated GIFs are kept as they are. */
    fun add(context: Context, source: Uri): File? = runCatching {
        val type = context.contentResolver.getType(source).orEmpty()
        val folder = folder(context)
        if (type == "image/gif" || type == "image/webp" && isAnimated(context, source)) {
            val file = File(folder, "sticker-${System.currentTimeMillis()}.${if (type == "image/gif") "gif" else "webp"}")
            context.contentResolver.openInputStream(source)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            if (file.length() > 2_000_000) { file.delete(); null } else file
        } else {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                val scale = (512f / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val file = File(folder, "sticker-${System.currentTimeMillis()}.webp")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
            file
        }
    }.getOrNull()

    /** Animated GIFs and WebPs are saved as they are; other pictures open in the editor first, to cut them out. */
    fun animated(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri).orEmpty()
        return type == "image/gif" || type == "image/webp" && isAnimated(context, uri)
    }

    private fun isAnimated(context: Context, uri: Uri) = runCatching {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) is android.graphics.drawable.AnimatedImageDrawable
    }.getOrDefault(false)

    /** A sticker as an attachment. The "sticker" name tells FluidMessages to show it without a bubble. */
    fun attachment(context: Context, file: File): Attachment {
        val copy = AttachmentFiles.scratch(context, file.extension)
        file.copyTo(copy, overwrite = true)
        val mime = when (file.extension) { "gif" -> "image/gif"; "png" -> "image/png"; else -> "image/webp" }
        return Attachment(AttachmentKind.PHOTO, AttachmentFiles.uri(context, copy), "sticker.${file.extension}", mime, copy.length())
    }
}

internal fun Attachment.isSticker() = kind == AttachmentKind.PHOTO && name.startsWith("sticker")

/**
 * The GIF and sticker picker. [link] sends a GIF/sticker link (light, and works on every service);
 * [file] attaches a picture from the phone or a saved sticker.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun GifSheet(startWithStickers: Boolean, filesAllowed: Boolean, dismiss: () -> Unit, link: (String) -> Unit, file: (Attachment) -> Unit,
                      openSettings: (() -> Unit)?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fluid", Context.MODE_PRIVATE) }
    val gifs = GifSettings.gifsOn(prefs)
    val stickersOn = GifSettings.stickersOn(prefs)
    var stickers by remember { mutableStateOf(startWithStickers && stickersOn || !gifs) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GifResult>?>(null) }
    var saved by remember { mutableStateOf(SavedStickers.list(context)) }
    val searchable = GifSettings.searchable(prefs)
    var browsing by remember { mutableStateOf(false) }
    val sites = remember { GifSites.enabled(prefs) }
    // A photo being made into a sticker: it opens in the editor with its background being removed.
    var stickerPhoto by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    if (browsing) {
        GifSiteBrowser(prefs, query, { browsing = false }, { url -> link(url); dismiss() },
            if (filesAllowed) { video -> file(video); dismiss() } else null)
        return
    }
    stickerPhoto?.let { photo ->
        MediaEditor(photo, { stickerPhoto = null }, sticker = true) { edited, _ ->
            scope.launch {
                withContext(Dispatchers.IO) { edited.uri?.let { SavedStickers.add(context, it) } }
                saved = SavedStickers.list(context)
                stickers = true
                stickerPhoto = null
            }
        }
        return
    }
    LaunchedEffect(query, stickers) {
        if (!searchable) return@LaunchedEffect
        results = null
        delay(if (query.isEmpty()) 0 else 350)
        results = withContext(Dispatchers.IO) { GifSettings.search(prefs, query, stickers) }
    }
    val phoneGif = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) runCatching { Attachment.describe(context, uri, AttachmentKind.PHOTO) }.getOrNull()?.let { file(it); dismiss() }
    }
    val addSticker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (SavedStickers.animated(context, uri)) { if (SavedStickers.add(context, uri) != null) saved = SavedStickers.list(context) }
        else stickerPhoto = uri
    }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.82f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (gifs && stickersOn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!stickers, onClick = { stickers = false }, label = { Text("GIFs") })
                FilterChip(stickers, onClick = { stickers = true }, label = { Text("Stickers") })
            }
            if (searchable) OutlinedTextField(query, { query = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search ${GifSettings.label(GifSettings.source(prefs))}") })
            // GIF sites to browse in the app, no key needed. Change which ones show in Settings.
            if (!stickers && sites.isNotEmpty()) Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Browse", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (site in sites) AssistChip(onClick = { prefs.edit().putString("gifSite.last", site.id).apply(); browsing = true },
                    label = { Text(site.name) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!stickers && filesAllowed) OutlinedButton(onClick = {
                    phoneGif.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif")))
                }) { Text("GIF from your phone") }
                if (stickers) OutlinedButton(onClick = {
                    addSticker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("Make a sticker") }
            }
            val cells = if (stickers && (!searchable || query.isEmpty())) saved.map { GifResult(Uri.fromFile(it).toString(), it.path, 1, 1) } + (results.orEmpty())
                else results.orEmpty()
            when {
                searchable && results == null && !(stickers && saved.isNotEmpty() && query.isEmpty()) ->
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                cells.isEmpty() -> Text(when {
                    stickers && !searchable -> "Tap Make a sticker to turn any photo into a sticker: its background is removed, and you can touch it up before saving. Your keyboard's stickers work too."
                    !searchable -> "Tap a site above to browse GIFs, or pick one from your phone or keyboard. For search results right here, add a Tenor or GIPHY key in Settings → Media, GIFs, and links."
                    else -> "Nothing found."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(cells, key = { it.send }) { item ->
                        val local = item.preview.startsWith("file:")
                        val media by produceState<DecodedMedia?>(null, item.preview) {
                            value = withContext(Dispatchers.IO) {
                                val uri = if (local) Uri.parse(item.preview) else RemoteMedia.fetch(context, item.preview)?.let(Uri::fromFile)
                                uri?.let { MediaDecoding.decode(context, it, 320) }
                            }
                        }
                        var remove by remember { mutableStateOf(false) }
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (stickers) Color.Transparent else Color.Gray.copy(alpha = .15f))
                            .combinedClickable(onLongClick = if (local) ({ remove = true }) else null) {
                                if (local) { if (filesAllowed) file(SavedStickers.attachment(context, File(item.send))) else return@combinedClickable }
                                else link(item.send + if (stickers) "#sticker" else "")
                                dismiss()
                            }, contentAlignment = Alignment.Center) {
                            media?.let { MediaPicture(it, Modifier.fillMaxSize(), crop = !stickers, description = if (stickers) "Sticker" else "GIF") }
                            DropdownMenu(remove, { remove = false }) {
                                DropdownMenuItem(text = { Text("Remove sticker") }, onClick = {
                                    File(item.send).delete(); saved = SavedStickers.list(context); remove = false
                                })
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (searchable) "Search by ${GifSettings.label(GifSettings.source(prefs))}" else "", Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (openSettings != null) TextButton(onClick = { dismiss(); openSettings() }) { Text("Settings") }
            }
        }
    }
}
