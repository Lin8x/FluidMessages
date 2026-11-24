package org.fluidmessages

import java.net.URI

/*
 * Turning what you find on a GIF site into the GIF itself. A site's GIF page (tenor.com/view/…, giphy.com/gifs/…,
 * imgur.com/gallery/…, reddit.com/r/…/comments/…) isn't a picture, and its thumbnails are often small or video,
 * so links and page tags are mapped to the real media address. Pure Kotlin, so it is tested on the JVM.
 *
 * Checked against the sites' public pages (November 2026):
 * - Tenor pages carry og:image = media1.tenor.com/m/<11-char id><5-char format>/<name>.gif and
 *   og:video = media.tenor.com/<id>AAAPo/<name>.mp4. AAAAC is the full GIF and works on media.tenor.com too.
 * - GIPHY pages are giphy.com/gifs/<words>-<id>, and i.giphy.com/<id>.gif (and .mp4) serve the GIF. Use that
 *   rather than media.giphy.com/media/<id>/giphy.gif, which answers with a web page when the request accepts HTML.
 *   Search thumbnails are media*.giphy.com/media/(v1.<token>/)<id>/200w.webp.
 * - Imgur gallery pages carry og:video = i.imgur.com/<id>.gif (with type video/mp4); i.imgur.com/<id>.mp4 is the
 *   same clip as a much smaller video, and .gifv is an HTML page.
 * - Reddit's direct uploads are i.redd.it/<id>.gif; previews are preview.redd.it/<name>.gif?…&format=mp4&s=…
 *   (signed, so the query is kept as is).
 */

internal enum class GifFormat { GIF, WEBP, MP4, PICTURE }

internal data class GifMedia(val url: String, val format: GifFormat)

internal object GifLinks {
    private val tenorPage = Regex("""^https://(?:www\.)?tenor\.com/(?:[a-z]{2}(?:-[A-Za-z]{2})?/)?view/[^/?#]*?-(\d{6,})(?:[/?#]|$)""")
    private val tenorMedia = Regex("""^https://(?:media\d*|c)\.tenor\.com/(?:m/)?([A-Za-z0-9_-]{11})([A-Za-z0-9_-]{5})/([^/?#]+?)(?:\.(gif|mp4|webm|webp|png))?(?:[?#].*)?$""")
    private val giphyPage = Regex("""^https://(?:www\.)?giphy\.com/(?:gifs|stickers|embed)/(?:[^/?#]*/)?(?:[^/?#]*-)?([A-Za-z0-9]{6,40})(?:[/?#]|$)""")
    private val giphyMedia = Regex("""^https://(?:media\d*\.giphy\.com/media/(?:v1\.[^/]+/)?([A-Za-z0-9]{6,40})/[^?#]*|i\.giphy\.com/(?:media/)?([A-Za-z0-9]{6,40})(?:\.(?:gif|webp|mp4))?)(?:[?#].*)?$""")
    private val imgurPage = Regex("""^https://(?:m\.)?imgur\.com/(?:(?:gallery|a|t/[^/?#]+)/(?:[^/?#]*-)?([A-Za-z0-9]{5,8})|([A-Za-z0-9]{7}))(?:[/?#]|$)""")
    private val imgurMedia = Regex("""^https://i\.imgur\.com/([A-Za-z0-9]{5,8})\.(gifv|gif|mp4|webm|webp|png|jpe?g)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val redditPage = Regex("""^https://(?:(?:www|old|new|m|sh)\.)?reddit\.com/r/[^/?#]+/comments/([a-z0-9]{4,12})(?:[/?#]|$)""")
    private val redditShort = Regex("""^https://redd\.it/([a-z0-9]{4,12})(?:[/?#]|$)""")
    private val redditImage = Regex("""^https://i\.redd\.it/([A-Za-z0-9_-]+)\.(gif|png|jpe?g|webp|mp4)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
    private val redditPreview = Regex("""^https://preview\.redd\.it/([A-Za-z0-9_.-]+?)\.(gif|png|jpe?g|webp)(\?[^#]*)?$""", RegexOption.IGNORE_CASE)
    private val extension = Regex("""\.(gif|webp|mp4|png|jpe?g)(?:[?#]|$)""", RegexOption.IGNORE_CASE)

    /** Words that follow imgur.com/ but are site pages, not pictures. */
    private val imgurPages = setOf("search", "upload", "signin", "register", "privacy", "removalrequest", "emerald", "about", "apps")

    /** Whether this address is a page for one GIF (or a direct GIF), as opposed to search results or a front page. */
    fun isGifPage(url: String): Boolean = pageToken(url) != null || fromMedia(url).isNotEmpty()

    /**
     * The part of a GIF page's address that names its GIF. A page that changed its address without reloading
     * (as these sites do) may still carry the previous GIF's tags, so tags only count when one mentions this.
     */
    fun pageToken(url: String): String? {
        tenorPage.find(url)?.let { return it.groupValues[1] }
        giphyPage.find(url)?.let { return it.groupValues[1] }
        redditPage.find(url)?.let { return it.groupValues[1] }
        redditShort.find(url)?.let { return it.groupValues[1] }
        imgurPage.find(url)?.let { match ->
            val id = match.groupValues[1].ifEmpty { match.groupValues[2] }
            // A bare imgur.com/xxxxxxx is a picture only if it looks like an id, not a word.
            if (match.groupValues[1].isEmpty() && (id.lowercase() in imgurPages || id.all { it.isLowerCase() })) return null
            return id
        }
        return null
    }

    /** What the page address alone tells, with no download: GIPHY's id names its GIF. */
    fun fromPage(url: String): List<GifMedia> {
        giphyPage.find(url)?.let { return giphy(it.groupValues[1]) }
        return fromMedia(url)
    }

    /**
     * A media address mapped to the GIF worth sending, best first: thumbnails become the full GIF, videos get
     * their GIF twin when the site has one, and the video stays as a fallback.
     */
    fun fromMedia(raw: String): List<GifMedia> {
        val url = raw.trim()
        if (!url.startsWith("https://") || url.any { it.isWhitespace() }) return emptyList()
        tenorMedia.find(url)?.let { match ->
            val (id, code, name) = match.destructured
            val ext = match.groupValues[4].lowercase()
            val base = name.ifEmpty { "tenor" }
            // Transparent stickers have their own codes, so a GIF keeps its code unless it is the tiny preview.
            // Every form is written on media.tenor.com, so the page's og:image and og:video come out the same.
            val gif = "https://media.tenor.com/$id${if (ext == "gif" && code != "AAAAM") code else "AAAAC"}/$base.gif"
            return listOf(GifMedia(gif, GifFormat.GIF), GifMedia("https://media.tenor.com/${id}AAAPo/$base.mp4", GifFormat.MP4)).distinct()
        }
        giphyMedia.find(url)?.let { match -> return giphy(match.groupValues[1].ifEmpty { match.groupValues[2] }) }
        imgurMedia.find(url)?.let { match ->
            val id = match.groupValues[1]
            return when (match.groupValues[2].lowercase()) {
                "gifv", "gif", "mp4", "webm" -> listOf(GifMedia("https://i.imgur.com/$id.gif", GifFormat.GIF), GifMedia("https://i.imgur.com/$id.mp4", GifFormat.MP4))
                "webp" -> listOf(GifMedia("https://i.imgur.com/$id.webp", GifFormat.WEBP))
                // og:image's "?fbplay" still of a clip: the picture, never ahead of the clip itself.
                else -> listOf(GifMedia(url.substringBefore('?').substringBefore('#'), GifFormat.PICTURE))
            }
        }
        redditImage.find(url)?.let { match ->
            val clean = url.substringBefore('?').substringBefore('#')
            return listOf(GifMedia(clean, format(match.groupValues[2])))
        }
        redditPreview.find(url)?.let { match ->
            val name = match.groupValues[1]
            val ext = match.groupValues[2].lowercase()
            val query = match.groupValues[3]
            val id = name.substringAfterLast("-v0-")
            val out = mutableListOf<GifMedia>()
            if (ext == "gif") out += GifMedia("https://i.redd.it/$id.gif", GifFormat.GIF)
            // Previews are signed: the query stays exactly as Reddit wrote it.
            if ("format=mp4" in query) out += GifMedia(url, GifFormat.MP4)
            else if (ext == "gif") out += GifMedia(url, GifFormat.GIF)
            else out += GifMedia(url, format(ext))
            return out.distinct()
        }
        val ext = extension.find(url.substringBefore('#'))?.groupValues?.get(1) ?: return emptyList()
        return listOf(GifMedia(url, format(ext)))
    }

    /**
     * A small still picture of a clip, to preview it: Imgur's "m" thumbnail (i.imgur.com/<id>m.jpg; plain
     * <id>.jpg serves the whole GIF) and GIPHY's 200_s still.
     */
    fun still(url: String): String? {
        imgurMedia.find(url)?.let { return "https://i.imgur.com/${it.groupValues[1]}m.jpg" }
        giphyMedia.find(url)?.let { match -> return "https://i.giphy.com/media/${match.groupValues[1].ifEmpty { match.groupValues[2] }}/200_s.gif" }
        return null
    }

    private fun giphy(id: String) = listOf(
        GifMedia("https://i.giphy.com/$id.gif", GifFormat.GIF),
        GifMedia("https://i.giphy.com/$id.mp4", GifFormat.MP4),
    )

    private fun format(ext: String) = when (ext.lowercase()) {
        "gif" -> GifFormat.GIF; "webp" -> GifFormat.WEBP; "mp4", "webm" -> GifFormat.MP4; else -> GifFormat.PICTURE
    }

    /** Media tags in the order they're trusted: the clip first, then the picture. */
    private val mediaTags = listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream", "og:image:secure_url",
        "og:image", "og:image:url", "twitter:image", "twitter:image:src")

    /** The GIF a page's own Open Graph and Twitter tags name. Relative addresses are resolved against [page]. */
    fun fromMeta(meta: Map<String, List<String>>, page: String): List<GifMedia> {
        val found = mutableListOf<GifMedia>()
        for (tag in mediaTags) for (value in meta[tag].orEmpty()) {
            val absolute = runCatching { URI(page).resolve(value.trim()).toString() }.getOrNull() ?: continue
            found += fromMedia(absolute)
        }
        return ranked(found)
    }

    /** Whether the page's tags belong to the GIF its address names (see [pageToken]). */
    fun metaMatches(meta: Map<String, List<String>>, page: String): Boolean {
        val token = pageToken(page) ?: return true
        return meta.values.any { values -> values.any { token in it } }
    }

    /**
     * Everything known about the page in front of you, best first: what its address says, its tags if they
     * are its own, and the main post's media as the page shows it (Reddit's post link).
     */
    fun candidates(page: String, meta: Map<String, List<String>>, shown: List<String>): List<GifMedia> {
        val found = fromPage(page).toMutableList()
        if (metaMatches(meta, page)) found += fromMeta(meta, page)
        for (item in shown) found += fromMedia(item).filter { knownHost(it.url) }
        return ranked(found)
    }

    /** GIFs before animated WebP, then video, then still pictures; the first mention wins within each. */
    fun ranked(items: List<GifMedia>): List<GifMedia> = items.distinctBy { it.url }.sortedBy { it.format.ordinal }

    fun knownHost(url: String): Boolean = Regex("""^https://(media\d*\.tenor\.com|c\.tenor\.com|media\d*\.giphy\.com|i\.giphy\.com|i\.imgur\.com|i\.redd\.it|preview\.redd\.it)/""")
        .containsMatchIn(url)

    /** Every meta tag's property or name with its content, from a page's HTML. */
    fun parseMeta(html: String): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        for (tag in Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val text = tag.value
            val key = Regex("""\b(?:property|name)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: continue
            val content = Regex("""\bcontent\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: continue
            out.getOrPut(key.lowercase()) { mutableListOf() } += unescape(content)
        }
        return out
    }

    private fun unescape(text: String) = text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
}
