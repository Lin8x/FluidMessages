package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Communities page: what Messages → Chats | Communities is, with its showcase and tips. It used to be a setup
 * guide step; now it shows once, full page, right after the first IRC, Matrix, or XMPP account is saved (the services
 * with servers, channels, and rooms), and any time from Settings → Help → Communities. FluidMessages Web does the same
 * (server/servers.js communitiesIntro).
 */
internal object CommunitiesIntro {
    /** Set once the page has shown after adding an account. This phone only. */
    const val SEEN_PREF = "communitiesIntroSeen"
    const val TITLE = "Communities"
    const val SUBTITLE = "Chat networks with many rooms, each server with its channels, a tap away from your chats."

    /** The services whose accounts bring servers with channels and rooms (Communities.kt). */
    val services = setOf(MessageProtocol.IRC, MessageProtocol.MATRIX, MessageProtocol.XMPP)

    /** Whether saving an account shows the page: a new account on a community service, and the page not shown yet. */
    fun due(seen: Boolean, protocol: MessageProtocol, newAccount: Boolean) = newAccount && !seen && protocol in services

    @JvmStatic
    fun due(prefs: SharedPreferences, protocol: MessageProtocol, newAccount: Boolean) =
        due(prefs.getBoolean(SEEN_PREF, false), protocol, newAccount)

    @JvmStatic
    fun markSeen(prefs: SharedPreferences) = prefs.edit().putBoolean(SEEN_PREF, true).apply()

    internal val tips = listOf(
        Tip(TipIcon.CHAT, "Chats | Communities", "The switch under the Messages title"),
        Tip(TipIcon.FOLDER, "One section per group", "Each chat service you join gets its own section. Tap its name to open or close it"),
        Tip(TipIcon.PEOPLE, "Browse and join", "A server’s ⋯ menu, or + Add server at the bottom"),
        Tip(TipIcon.OPTIONAL, "In Chats too, if you like", "Settings → Messaging → Show community chats in Chats"))

    /** Settings → Help → Communities: the same page; Continue goes back. */
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, done: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        // The page heading already says "Communities", so the page itself starts with the subtitle.
        view.setContent { AppTheme(Appearance.read(prefs)) { CommunitiesIntroPage(prefs, showTitle = false) { done.run() } } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

/** The Communities page, full page, with one Continue button at the bottom. */
@Composable
internal fun CommunitiesIntroPage(prefs: SharedPreferences, showTitle: Boolean = true, done: () -> Unit) {
    val appearance = remember { Appearance.read(prefs) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (showTitle) Text(CommunitiesIntro.TITLE, fontSize = 32.sp, lineHeight = 36.sp, color = scheme.onSurface, fontWeight = FontWeight.Bold)
                    Text(CommunitiesIntro.SUBTITLE, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
            }
            item { CommunitiesShowcase(appearance) }
            item { Tips(CommunitiesIntro.tips, appearance.speed) }
            item {
                Text("See this again any time in Settings → Help → Communities.", color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        AppButton(onClick = done, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp).padding(bottom = 6.dp)) { Text("Continue") }
    }
}
