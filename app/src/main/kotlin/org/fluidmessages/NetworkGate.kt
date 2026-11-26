package org.fluidmessages

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import java.io.IOException

/**
 * "Nearby only (no internet)": one switch that stops FluidMessages from opening any internet connection, for a
 * blackout or for privacy. Every place that connects asks here first, in one line: chat services connected from the
 * phone, FluidMessages Web, backups to the web or a file server, link previews, pictures and GIFs from the web, and
 * update checks. Nearby still works, since it only talks to phones on the same Wi-Fi. Texts over the cell network
 * are a separate choice ([TEXTS], allowed unless turned off), because they don't use the internet.
 *
 * The switch is read from the app's settings each time, so background jobs see it too: [Attach] gives this object the
 * app's context as the process starts, before any job or screen runs.
 */
internal object NetworkGate {
    const val NEARBY_ONLY = "nearbyOnly"
    const val TEXTS = "nearbyOnlyTexts"
    const val REFUSED = "Nearby only is on, so FluidMessages doesn’t use the internet. Turn it off in Settings → Chat services → Nearby."
    const val TEXTS_OFF = "Texts are off while Nearby only is on. Allow them on the Nearby page."

    enum class Use { INTERNET, TEXTS, NEARBY }

    /** The rule itself, kept pure for tests. */
    fun allows(nearbyOnly: Boolean, textsAllowed: Boolean, use: Use): Boolean = when (use) {
        Use.NEARBY -> true
        Use.TEXTS -> !nearbyOnly || textsAllowed
        Use.INTERNET -> !nearbyOnly
    }

    /** The inbox banner while it's on, or "" when it's off. */
    fun banner(nearbyOnly: Boolean): String = if (nearbyOnly) "Nearby only: internet is off in FluidMessages" else ""

    @Volatile private var app: Context? = null

    fun attach(context: Context) { app = context.applicationContext ?: context }

    private fun prefs(): SharedPreferences? = app?.getSharedPreferences("fluid", Context.MODE_PRIVATE)

    @JvmStatic fun nearbyOnly(prefs: SharedPreferences): Boolean = prefs.getBoolean(NEARBY_ONLY, false)

    @JvmStatic fun internetAllowed(prefs: SharedPreferences): Boolean =
        allows(nearbyOnly(prefs), prefs.getBoolean(TEXTS, true), Use.INTERNET)

    @JvmStatic fun internetAllowed(context: Context): Boolean =
        internetAllowed(context.getSharedPreferences("fluid", Context.MODE_PRIVATE))

    /** For code without a context at hand. Before the app context is known nothing is blocked, which never happens in practice. */
    @JvmStatic fun internetAllowed(): Boolean = prefs()?.let(::internetAllowed) ?: true

    @JvmStatic fun textsAllowed(prefs: SharedPreferences): Boolean =
        allows(nearbyOnly(prefs), prefs.getBoolean(TEXTS, true), Use.TEXTS)

    /** Throws before an internet connection while Nearby only is on. An IOException, so queued messages wait. */
    @JvmStatic @Throws(IOException::class)
    fun requireInternet() { if (!internetAllowed()) throw IOException(REFUSED) }

    @JvmStatic @Throws(IOException::class)
    fun requireInternet(context: Context) { if (!internetAllowed(context)) throw IOException(REFUSED) }

    /** Starts as the app's process does (a manifest provider runs before any activity, service, or job). */
    class Attach : ContentProvider() {
        override fun onCreate(): Boolean { context?.let(::attach); return true }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
    }
}
