package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GIF site pages and media addresses mapped to the GIF to send. The shapes follow the sites' public pages;
 * the ids and names here are made up.
 */
class GifLinksTest {
    private fun first(items: List<GifMedia>) = items.first().url

    @Test fun tenorPagesAndMedia() {
        assertEquals("1234567890123", GifLinks.pageToken("https://tenor.com/view/happy-dance-gif-1234567890123"))
        assertEquals("1234567890123", GifLinks.pageToken("https://tenor.com/en-GB/view/happy-dance-gif-1234567890123?q=x"))
        assertNull(GifLinks.pageToken("https://tenor.com/search/happy-gifs"))
        // Tiny search preview → the full GIF, with the video as a fallback.
        val small = GifLinks.fromMedia("https://media1.tenor.com/m/Ab3dEfGh1jKAAAAM/happy-dance.gif")
        assertEquals("https://media.tenor.com/Ab3dEfGh1jKAAAAC/happy-dance.gif", first(small))
        assertEquals(GifMedia("https://media.tenor.com/Ab3dEfGh1jKAAAPo/happy-dance.mp4", GifFormat.MP4), small[1])
        // The page's own og:image is already the full GIF: same file, on media.tenor.com.
        assertEquals("https://media.tenor.com/Ab3dEfGh1jKAAAAC/happy-dance.gif",
            first(GifLinks.fromMedia("https://media1.tenor.com/m/Ab3dEfGh1jKAAAAC/happy-dance.gif")))
        // A video in the grid → its GIF.
        assertEquals("https://media.tenor.com/Ab3dEfGh1jKAAAAC/happy-dance.gif",
            first(GifLinks.fromMedia("https://media.tenor.com/Ab3dEfGh1jKAAAPs/happy-dance.webm")))
    }

    @Test fun giphyPagesNeedNoDownload() {
        val page = "https://giphy.com/gifs/cat-ice-cream-Zx9Yq8Wp7Vo6"
        assertEquals("Zx9Yq8Wp7Vo6", GifLinks.pageToken(page))
        assertEquals("https://i.giphy.com/Zx9Yq8Wp7Vo6.gif", first(GifLinks.fromPage(page)))
        assertEquals("https://i.giphy.com/Zx9Yq8Wp7Vo6.gif", first(GifLinks.fromPage("https://giphy.com/gifs/Zx9Yq8Wp7Vo6")))
        assertEquals("https://i.giphy.com/Zx9Yq8Wp7Vo6.gif", first(GifLinks.fromPage("https://giphy.com/stickers/wave-hi-Zx9Yq8Wp7Vo6")))
        // Search thumbnails, with or without the long v1 token.
        assertEquals("https://i.giphy.com/Zx9Yq8Wp7Vo6.gif",
            first(GifLinks.fromMedia("https://media4.giphy.com/media/v1.Y2lkPTZjMDliOTUy/Zx9Yq8Wp7Vo6/200w.webp?cid=abc")))
        assertEquals("https://i.giphy.com/Zx9Yq8Wp7Vo6.gif", first(GifLinks.fromMedia("https://i.giphy.com/Zx9Yq8Wp7Vo6.webp")))
        // Avatars and the search page aren't GIFs.
        assertTrue(GifLinks.fromMedia("https://media4.giphy.com/avatars/someone/abc/80h.png").none { "/media/" in it.url })
        assertFalse(GifLinks.isGifPage("https://giphy.com/search/cat"))
    }

    @Test fun imgurGifvBecomesGifThenMp4() {
        val gifv = GifLinks.fromMedia("https://i.imgur.com/AbC12dE.gifv")
        assertEquals(listOf(GifMedia("https://i.imgur.com/AbC12dE.gif", GifFormat.GIF), GifMedia("https://i.imgur.com/AbC12dE.mp4", GifFormat.MP4)), gifv)
        assertEquals("AbC12dE", GifLinks.pageToken("https://imgur.com/gallery/sleepy-cat-AbC12dE"))
        assertEquals("aB3cD", GifLinks.pageToken("https://imgur.com/a/aB3cD"))
        assertEquals("AbC12dE", GifLinks.pageToken("https://imgur.com/AbC12dE"))
        assertNull(GifLinks.pageToken("https://imgur.com/privacy"))
        assertNull(GifLinks.pageToken("https://imgur.com/search?q=cat"))
    }

    @Test fun imgurPageTagsPreferTheClipOverItsStill() {
        val html = """
            <meta property="og:url" data-react-helmet="true" content="https://imgur.com/gallery/sleepy-cat-Qw3Er">
            <meta property="og:video" data-react-helmet="true" content="https://i.imgur.com/AbC12dE.gif">
            <meta property="og:video:type" data-react-helmet="true" content="video/mp4">
            <meta property="og:image" data-react-helmet="true" content="https://i.imgur.com/AbC12dE.jpg?fbplay">
        """.trimIndent()
        val meta = GifLinks.parseMeta(html)
        val page = "https://imgur.com/gallery/Qw3Er"
        assertTrue(GifLinks.metaMatches(meta, page))
        val found = GifLinks.candidates(page, meta, emptyList())
        assertEquals(listOf("https://i.imgur.com/AbC12dE.gif", "https://i.imgur.com/AbC12dE.mp4", "https://i.imgur.com/AbC12dE.jpg"), found.map { it.url })
    }

    @Test fun tenorPageTagsAndStaleTags() {
        val html = """
            <meta class="dynamic" name="twitter:app:url:googleplay" content="riffsy://gifs/1234567890123">
            <meta class="dynamic" property="og:image" content="https://media1.tenor.com/m/Ab3dEfGh1jKAAAAC/happy-dance.gif">
            <meta class="dynamic" property="og:video" content="https://media.tenor.com/Ab3dEfGh1jKAAAPo/happy-dance.mp4">
        """.trimIndent()
        val meta = GifLinks.parseMeta(html)
        val found = GifLinks.candidates("https://tenor.com/view/happy-dance-gif-1234567890123", meta, emptyList())
        assertEquals("https://media.tenor.com/Ab3dEfGh1jKAAAAC/happy-dance.gif", found.first().url)
        assertEquals(GifFormat.GIF, found.first().format)
        // After moving to another GIF without a reload, the old tags don't count.
        assertTrue(GifLinks.candidates("https://tenor.com/view/other-gif-9999999999999", meta, emptyList()).isEmpty())
    }

    @Test fun giphyTagsPutTheGifAheadOfWebp() {
        val meta = GifLinks.parseMeta("""
            <meta property="og:url" content="https://giphy.com/gifs/wave-Zx9Yq8Wp7Vo6"/>
            <meta property="og:image" content="https://media4.giphy.com/media/v1.abc/Zx9Yq8Wp7Vo6/giphy.webp"/>
            <meta property="og:image" content="https://media4.giphy.com/media/v1.abc/Zx9Yq8Wp7Vo6/giphy.gif"/>
        """.trimIndent())
        assertEquals("https://i.giphy.com/Zx9Yq8Wp7Vo6.gif", GifLinks.fromMeta(meta, "https://giphy.com/gifs/wave-Zx9Yq8Wp7Vo6").first().url)
    }

    @Test fun redditMedia() {
        assertEquals("q7w8e9", GifLinks.pageToken("https://www.reddit.com/r/gifs/comments/q7w8e9/a_cat_waves/"))
        assertEquals(listOf(GifMedia("https://i.redd.it/ab12cd34ef.gif", GifFormat.GIF)), GifLinks.fromMedia("https://i.redd.it/ab12cd34ef.gif"))
        val preview = "https://preview.redd.it/a-cat-waves-v0-ab12cd34ef.gif?width=640&format=mp4&s=0123abcd"
        val found = GifLinks.fromMedia(preview)
        assertEquals(GifMedia("https://i.redd.it/ab12cd34ef.gif", GifFormat.GIF), found[0])
        assertEquals(GifMedia(preview, GifFormat.MP4), found[1])
        // The post's link, as the page shows it, counts; other sites' pictures on the page don't.
        val shown = GifLinks.candidates("https://www.reddit.com/r/gifs/comments/q7w8e9/a_cat_waves/", emptyMap(),
            listOf("https://i.redd.it/ab12cd34ef.gif", "https://www.redditstatic.com/avatar.png"))
        assertEquals(listOf("https://i.redd.it/ab12cd34ef.gif"), shown.map { it.url })
    }

    @Test fun clipsPreviewAsSmallStills() {
        assertEquals("https://i.imgur.com/AbC12dEm.jpg", GifLinks.still("https://i.imgur.com/AbC12dE.mp4"))
        assertEquals("https://i.giphy.com/media/Zx9Yq8Wp7Vo6/200_s.gif", GifLinks.still("https://i.giphy.com/Zx9Yq8Wp7Vo6.mp4"))
        assertNull(GifLinks.still("https://media.tenor.com/Ab3dEfGh1jKAAAPo/happy-dance.mp4"))
    }

    @Test fun onlyHttps() {
        assertTrue(GifLinks.fromMedia("http://i.imgur.com/AbC12dE.gif").isEmpty())
        assertTrue(GifLinks.fromMedia("javascript:alert(1).gif").isEmpty())
        assertTrue(GifLinks.fromMedia("https://example.com/a b.gif").isEmpty())
    }

    @Test fun whatIsSentShowsAsAGifCard() {
        for (url in listOf(
            "https://media.tenor.com/Ab3dEfGh1jKAAAAC/happy-dance.gif",
            "https://media1.tenor.com/m/Ab3dEfGh1jKAAAAC/happy-dance.gif",
            "https://i.giphy.com/Zx9Yq8Wp7Vo6.gif",
            "https://i.imgur.com/AbC12dE.gif",
            "https://i.redd.it/ab12cd34ef.gif",
        )) assertNotNull(url, parsePictureLink(url))
    }
}
