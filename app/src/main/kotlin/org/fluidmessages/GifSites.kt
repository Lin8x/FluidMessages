package org.fluidmessages

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.math.abs

/** A website to find GIFs on. [search] has {query} where the words go. */
data class GifSite(val id: String, val name: String, val search: String, val custom: Boolean = false) {
    fun url(query: String): String = if (query.isBlank()) search.substringBefore("{query}").let { start ->
        // With no words, open the site's front page.
        Regex("^https://[^/]+").find(start)?.value ?: start
    } else search.replace("{query}", URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20"))
}

/**
 * GIF sites you can browse right inside the picker, no account or API key needed: open a GIF and tap Send this GIF.
 * The built-in sites can each be turned off, and you can add your own.
 */
object GifSites {
    val defaults = listOf(
        GifSite("tenor", "Tenor", "https://tenor.com/search/{query}-gifs"),
        GifSite("giphy", "GIPHY", "https://giphy.com/search/{query}"),
        GifSite("imgur", "Imgur", "https://imgur.com/search?q={query}"),
        GifSite("reddit", "Reddit r/gifs", "https://www.reddit.com/r/gifs/search/?q={query}&restrict_sr=1"),
    )

    @JvmStatic fun on(prefs: SharedPreferences, site: GifSite) = !prefs.getBoolean("gifSite.off." + site.id, false)

    @JvmStatic fun setOn(prefs: SharedPreferences, id: String, on: Boolean) { prefs.edit().putBoolean("gifSite.off.$id", !on).apply() }

    @JvmStatic fun custom(prefs: SharedPreferences): List<GifSite> = runCatching {
        val array = JSONArray(prefs.getString("gifSites.custom", "[]"))
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { GifSite("custom" + it.optString("name").hashCode(), it.optString("name"), it.optString("url"), true) }
        }
    }.getOrDefault(emptyList())

    @JvmStatic fun all(prefs: SharedPreferences): List<GifSite> = defaults + custom(prefs)

    @JvmStatic fun enabled(prefs: SharedPreferences): List<GifSite> = all(prefs).filter { on(prefs, it) }

    /** Adds a site. Its address must be https and may have {query} where search words go. */
    @JvmStatic fun add(prefs: SharedPreferences, name: String, url: String): Boolean {
        val address = url.trim()
        if (name.isBlank() || !Regex("^https://[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/\\S*)?$").matches(address)) return false
        val array = JSONArray(prefs.getString("gifSites.custom", "[]"))
        array.put(JSONObject().put("name", name.trim().take(30)).put("url", if ("{query}" in address) address else address))
        prefs.edit().putString("gifSites.custom", array.toString()).apply()
        return true
    }

    @JvmStatic fun remove(prefs: SharedPreferences, name: String) {
        val array = JSONArray(prefs.getString("gifSites.custom", "[]"))
        val kept = JSONArray()
        for (index in 0 until array.length()) array.optJSONObject(index)?.takeIf { it.optString("name") != name }?.let(kept::put)
        prefs.edit().putString("gifSites.custom", kept.toString()).apply()
    }

    @JvmStatic fun names(prefs: SharedPreferences) = custom(prefs).map { it.name }
}

/** A GIF ready to send: a picture link that shows as a GIF card, and/or a short video for when the GIF itself is too big. */
internal data class FoundGif(val link: String?, val video: String?, val preview: String?)

/** Checking and fetching what GIF sites point at. Blocking; call off the main thread. */
internal object GifFetch {
    /** Bigger GIFs are sent as their short video instead (FluidMessages shows linked pictures up to 15 MB). */
    private const val LINK_LIMIT = 12L * 1024 * 1024
    private const val PAGE_LIMIT = 768 * 1024
    private const val VIDEO_LIMIT = 15L * 1024 * 1024

    /** The first of [candidates] that really is there, best first: a GIF that isn't too big, else the video. */
    fun choose(candidates: List<GifMedia>): FoundGif? {
        val video = candidates.firstOrNull { it.format == GifFormat.MP4 }?.url
        for (item in candidates) {
            // The GIF was too big, so its preview is the site's small still (never the big GIF itself).
            if (item.format == GifFormat.MP4) return FoundGif(null, item.url, GifLinks.still(item.url))
            if (parsePictureLink(item.url) == null) continue
            val size = probe(item.url) ?: continue
            if (size <= LINK_LIMIT) return FoundGif(item.url, video, item.url)
        }
        return null
    }

    /** The size of a picture at [url] (-1 when the server doesn't say), or null when it isn't a reachable picture. */
    private fun probe(url: String): Long? = try {
        var target = URL(url)
        var result: Long? = null
        for (hop in 0..3) {
            if (!PublicHosts.allowed(target)) break
            val connection = target.openConnection() as HttpsURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FluidMessages")
            // Some media hosts answer with a web page when HTML is acceptable.
            connection.setRequestProperty("Accept", "image/*")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    target = URL(target, connection.getHeaderField("Location") ?: break)
                    continue
                }
                if (code in 200..299 && connection.contentType.orEmpty().startsWith("image/")) result = connection.contentLengthLong
            } finally { connection.disconnect() }
            break
        }
        result
    } catch (_: Exception) { null }

    /** The meta tags of a public https page, read from at most its first 768 KB. */
    fun pageMeta(url: String): Map<String, List<String>> = try {
        var target = URL(url)
        var html = ""
        for (hop in 0..3) {
            if (!PublicHosts.allowed(target)) break
            val connection = target.openConnection() as HttpsURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FluidMessages")
            connection.setRequestProperty("Accept", "text/html")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    target = URL(target, connection.getHeaderField("Location") ?: break)
                    continue
                }
                if (code in 200..299 && connection.contentType?.contains("html") == true) {
                    html = connection.inputStream.use { String(it.readNBytes(PAGE_LIMIT), Charsets.UTF_8) }
                }
            } finally { connection.disconnect() }
            break
        }
        GifLinks.parseMeta(html)
    } catch (_: Exception) { emptyMap() }

    /** Everything the page in front of you shows, and failing that its tags as the site serves them. */
    fun resolve(page: String, meta: Map<String, List<String>>, shown: List<String>): FoundGif? {
        choose(GifLinks.candidates(page, meta, shown))?.let { return it }
        if (GifLinks.pageToken(page) == null) return null
        return choose(GifLinks.candidates(page, pageMeta(page), emptyList()))
    }

    /** A long-pressed or tapped GIF: the picture itself, and the page it links to. */
    fun resolvePicked(image: String?, link: String?): FoundGif? {
        val direct = GifLinks.ranked(image?.let(GifLinks::fromMedia).orEmpty() + link?.let(GifLinks::fromPage).orEmpty())
        choose(direct)?.let { return it }
        link?.takeIf { GifLinks.pageToken(it) != null }?.let { page -> choose(GifLinks.candidates(page, pageMeta(page), emptyList()))?.let { return it } }
        return null
    }

    /** Downloads a GIF's short video (15 MB at most) as an attachment. */
    fun video(context: android.content.Context, url: String): Attachment? {
        val copy = AttachmentFiles.scratch(context, "mp4")
        val ok = try {
            var target = URL(url)
            var saved = false
            for (hop in 0..3) {
                if (!PublicHosts.allowed(target)) break
                val connection = target.openConnection() as HttpsURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "FluidMessages")
                connection.setRequestProperty("Accept", "video/*")
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        target = URL(target, connection.getHeaderField("Location") ?: break)
                        continue
                    }
                    if (code !in 200..299 || !connection.contentType.orEmpty().startsWith("video/") || connection.contentLengthLong > VIDEO_LIMIT) break
                    connection.inputStream.use { input ->
                        copy.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > VIDEO_LIMIT) throw java.io.IOException("too big")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    saved = copy.length() > 0
                } finally { connection.disconnect() }
                break
            }
            saved
        } catch (_: Exception) { false }
        if (!ok) { copy.delete(); return null }
        return Attachment(AttachmentKind.VIDEO, AttachmentFiles.uri(context, copy), "GIF.mp4", "video/mp4", copy.length())
    }
}

/** Reads the open page's tags and its main post's media (Reddit's post link) as the page shows them right now. */
private const val PAGE_SCRIPT = """(function(){
var m={};document.querySelectorAll('meta[property],meta[name]').forEach(function(e){var k=(e.getAttribute('property')||e.getAttribute('name')||'').toLowerCase();var v=e.getAttribute('content');if(k&&v){(m[k]=m[k]||[]).push(v);}});
var s=[];var p=document.querySelector('shreddit-post');
if(p){var h=p.getAttribute('content-href');if(h)s.push(h);p.querySelectorAll('img,video,source').forEach(function(e){var u=e.currentSrc||e.src||e.getAttribute('poster');if(u)s.push(u);});}
return JSON.stringify({url:location.href,meta:m,shown:s.slice(0,30)});})()"""

/** What's under a point (in screen pixels): the picture or video there, and the link around it. */
private fun pointScript(x: Float, y: Float) = """(function(){
var r=window.devicePixelRatio||1;var e=document.elementFromPoint($x/r,$y/r);var src=null,href=null;
for(var n=e,i=0;n&&i<8;n=n.parentElement,i++){
if(!src&&n.tagName==='IMG')src=n.currentSrc||n.src;
if(!src&&n.tagName==='VIDEO'){src=n.currentSrc||n.src||n.getAttribute('poster');if(!src){var q=n.querySelector('source');if(q)src=q.src;}}
if(!src&&n.querySelector){var c=n.querySelector('img,video');if(c&&i<3)src=c.currentSrc||c.src||c.getAttribute('poster');}
if(!href&&n.tagName==='A')href=n.href;}
return JSON.stringify({src:src,href:href});})()"""

private suspend fun WebView.run(script: String): JSONObject? = suspendCancellableCoroutine { done ->
    evaluateJavascript(script) { raw ->
        // The result arrives as a JSON string holding our JSON.
        done.resume(runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrNull())
    }
}

private fun JSONObject.metaMap(): Map<String, List<String>> {
    val meta = optJSONObject("meta") ?: return emptyMap()
    return meta.keys().asSequence().associateWith { key ->
        val values = meta.optJSONArray(key) ?: return@associateWith emptyList()
        (0 until values.length()).map { values.optString(it) }
    }
}

/**
 * Browses a GIF site full screen. Open any GIF and a Send this GIF bar appears at the bottom; a tap on the GIF
 * there, or a long-press on any GIF (even in search results), offers it too. Page links and thumbnails are turned
 * into the real GIF; one too big to send as a GIF goes as its short video when [sendFile] is given.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
internal fun GifSiteBrowser(prefs: SharedPreferences, startQuery: String, dismiss: () -> Unit, send: (String) -> Unit,
                            sendFile: ((Attachment) -> Unit)? = null) {
    val context = LocalContext.current
    val sites = remember { GifSites.enabled(prefs) }
    var site by remember { mutableStateOf(sites.firstOrNull { it.id == prefs.getString("gifSite.last", "") } ?: sites.firstOrNull()) }
    var query by remember { mutableStateOf(startQuery) }
    var tip by remember { mutableStateOf(!prefs.getBoolean("gifSite.tipSeen", false)) }
    // The GIF the open page is about, and one you picked by long-pressing or tapping.
    var page by remember { mutableStateOf("") }
    var onPage by remember { mutableStateOf<FoundGif?>(null) }
    var picked by remember { mutableStateOf<FoundGif?>(null) }
    var finding by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    var pageJob by remember { mutableStateOf<Job?>(null) }
    fun load() { site?.let { web?.loadUrl(it.url(query)) } }

    /** Looks for the open page's GIF. These sites change pages without reloading, so the tags are read until they're the new page's. */
    fun lookAt(view: WebView, url: String) {
        if (url == page && (onPage != null || pageJob?.isActive == true)) return
        if (url != page) picked = null
        page = url
        onPage = null
        pageJob?.cancel()
        if (!GifLinks.isGifPage(url)) { finding = false; return }
        finding = true
        pageJob = scope.launch {
            var found: FoundGif? = null
            for (attempt in 0 until 4) {
                delay(if (attempt == 0) 500 else 900)
                val seen = view.run(PAGE_SCRIPT) ?: continue
                if (seen.optString("url") != url) continue
                val meta = seen.metaMap()
                val shown = seen.optJSONArray("shown")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
                if (!GifLinks.metaMatches(meta, url) && shown.isEmpty() && attempt < 3) continue
                found = withContext(Dispatchers.IO) { GifFetch.resolve(url, meta, shown) }
                break
            }
            if (page == url) { onPage = found; finding = false }
        }
    }

    fun pick(image: String?, link: String?) {
        if (image == null && link == null) return
        scope.launch {
            finding = true
            note = ""
            val found = withContext(Dispatchers.IO) { GifFetch.resolvePicked(image?.takeIf { it.startsWith("https://") }, link?.takeIf { it.startsWith("https://") }) }
            finding = false
            if (found == null) note = "That isn't a GIF FluidMessages can send. Open the GIF, then tap Send this GIF."
            else picked = found
        }
    }

    fun deliver(gif: FoundGif) {
        val link = gif.link
        val video = gif.video
        when {
            link != null -> { send(link); dismiss() }
            // Without files, a GIF that's only available as video goes as its page, which previews as a link.
            video != null && sendFile == null -> { send(page.takeIf { GifLinks.pageToken(it) != null } ?: video); dismiss() }
            video != null -> scope.launch {
                sending = true
                val attachment = withContext(Dispatchers.IO) { GifFetch.video(context, video) }
                sending = false
                if (attachment == null) note = "That GIF couldn't be downloaded. Try another, or check your connection."
                else { sendFile?.invoke(attachment); dismiss() }
            }
        }
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = dismiss) { Text("Close") }
                    OutlinedTextField(query, { query = it.take(60) }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search GIFs") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { load() }))
                    TextButton(onClick = { load() }) { Text("Go") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in sites) FilterChip(site == option, {
                        site = option
                        prefs.edit().putString("gifSite.last", option.id).apply()
                        web?.loadUrl(option.url(query))
                    }, label = { Text(option.name) })
                }
                if (tip && site != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Tap any GIF to open it, then tap Send this GIF at the bottom. You can also long-press a GIF right in the results.",
                        Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    TextButton(onClick = { tip = false; prefs.edit().putBoolean("gifSite.tipSeen", true).apply() }) { Text("Got it") }
                }
                if (site == null) {
                    Text("All GIF sites are turned off. Turn one on in Settings → Media, GIFs, and fun.", Modifier.padding(20.dp))
                } else AndroidView(factory = { viewContext ->
                    WebView(viewContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            // Only web pages open here; app links and anything else stay out.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                request.url.scheme != "https"

                            // Also called when a site changes pages without reloading.
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                url?.let { lookAt(view, it) }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                url?.let { lookAt(view, it) }
                            }
                        }
                        // A quick tap on the GIF of a GIF's page offers it; taps elsewhere work as usual.
                        var downAt = 0L; var downX = 0f; var downY = 0f
                        setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> { downAt = event.eventTime; downX = event.x; downY = event.y }
                                MotionEvent.ACTION_UP -> if (event.eventTime - downAt < 300 && abs(event.x - downX) < 24 && abs(event.y - downY) < 24 &&
                                    GifLinks.isGifPage(page)) {
                                    view.postDelayed({
                                        val hit = (view as WebView).hitTestResult
                                        if (hit.type == WebView.HitTestResult.IMAGE_TYPE) pick(hit.extra, null)
                                    }, 60)
                                }
                            }
                            false
                        }
                        setOnLongClickListener { view ->
                            val webView = view as WebView
                            val hit = webView.hitTestResult
                            when (hit.type) {
                                WebView.HitTestResult.IMAGE_TYPE -> pick(hit.extra, null)
                                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                                    val reply = Handler(Looper.getMainLooper()) { message: Message ->
                                        pick(message.data.getString("src") ?: hit.extra, message.data.getString("url"))
                                        true
                                    }
                                    webView.requestFocusNodeHref(reply.obtainMessage())
                                }
                                // Videos (some sites show GIFs as video) and anything else: ask the page what's there.
                                else -> scope.launch {
                                    val there = webView.run(pointScript(downX, downY)) ?: return@launch
                                    pick(there.optString("src").takeIf { it.isNotEmpty() && it != "null" },
                                        there.optString("href").takeIf { it.isNotEmpty() && it != "null" })
                                }
                            }
                            true
                        }
                        loadUrl(site!!.url(query))
                        web = this
                    }
                }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.destroy() })
                val offer = picked ?: onPage
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        offer != null -> {
                            val preview = offer.preview
                            if (preview != null) Box(Modifier.size(56.dp)) { MessagePicture(null, preview, "Chosen GIF", "image/gif", maxWidth = 56) }
                            Column(Modifier.weight(1f)) {
                                Text(if (picked != null) "Send this GIF?" else "This GIF", fontWeight = FontWeight.SemiBold)
                                Text(when {
                                    sending -> "Getting it ready…"
                                    note.isNotEmpty() -> note
                                    offer.link == null && sendFile != null -> "Sends as a short video, since the GIF is big."
                                    else -> "Sends as a GIF."
                                }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (picked != null) TextButton(onClick = { picked = null; note = "" }) { Text("Cancel") }
                            Button(enabled = !sending, onClick = { deliver(offer) }) { Text("Send this GIF") }
                        }
                        finding -> {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Finding this GIF…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        else -> Text(note.ifEmpty { "Open a GIF to send it, or long-press one." }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
