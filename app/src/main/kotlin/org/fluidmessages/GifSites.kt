package org.fluidmessages

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Message
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** A website to find GIFs on. [search] has {query} where the words go. */
data class GifSite(val id: String, val name: String, val search: String, val custom: Boolean = false) {
    fun url(query: String): String = if (query.isBlank()) search.substringBefore("{query}").let { start ->
        // With no words, open the site's front page.
        Regex("^https://[^/]+").find(start)?.value ?: start
    } else search.replace("{query}", URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20"))
}

/**
 * GIF sites you can browse right inside the picker, no account or API key needed: long-press a GIF to send it.
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

/**
 * Browses a GIF site full screen. Long-press any GIF or picture, then Send. Page links (like a GIF's own page)
 * are turned into the picture they show.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GifSiteBrowser(prefs: SharedPreferences, startQuery: String, dismiss: () -> Unit, send: (String) -> Unit) {
    val context = LocalContext.current
    val sites = remember { GifSites.enabled(prefs) }
    var site by remember { mutableStateOf(sites.firstOrNull { it.id == prefs.getString("gifSite.last", "") } ?: sites.firstOrNull()) }
    var query by remember { mutableStateOf(startQuery) }
    var picked by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("Long-press a GIF to send it") }
    var web by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    fun load() { site?.let { web?.loadUrl(it.url(query)) } }
    fun choose(image: String?, link: String?) {
        scope.launch {
            val direct = image?.takeIf { parsePictureLink(it) != null || it.startsWith("https://") && Regex("\\.(gif|webp)(\\?|$)").containsMatchIn(it) }
            val url = direct ?: link?.takeIf { it.startsWith("https://") }?.let { page ->
                note = "Getting that GIF…"
                withContext(Dispatchers.IO) { LinkPreviews.fetch(context, page)?.image?.takeIf { parsePictureLink(it) != null } }
            } ?: image?.takeIf { parsePictureLink(it) != null }
            if (url == null) note = "That isn't a GIF or picture FluidMessages can send. Try long-pressing the GIF itself."
            else { picked = url; note = "" }
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
                if (site == null) {
                    Text("All GIF sites are turned off. Turn one on in Settings → Media, GIFs, and links.", Modifier.padding(20.dp))
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
                        }
                        setOnLongClickListener {
                            val hit = hitTestResult
                            when (hit.type) {
                                WebView.HitTestResult.IMAGE_TYPE -> { choose(hit.extra, null); true }
                                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE, WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                                    val reply = Handler(Looper.getMainLooper()) { message: Message ->
                                        choose(message.data.getString("src") ?: hit.extra, message.data.getString("url"))
                                        true
                                    }
                                    requestFocusNodeHref(reply.obtainMessage())
                                    true
                                }
                                else -> false
                            }
                        }
                        loadUrl(site!!.url(query))
                        web = this
                    }
                }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.destroy() })
                val chosen = picked
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (chosen != null) {
                        Box(Modifier.size(64.dp)) { MessagePicture(null, chosen, "Chosen GIF", "image/gif", maxWidth = 64) }
                        Text("Send this GIF?", Modifier.weight(1f))
                        TextButton(onClick = { picked = null; note = "Long-press a GIF to send it" }) { Text("No") }
                        Button(onClick = { send(chosen); dismiss() }) { Text("Send") }
                    } else Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}
