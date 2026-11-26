package org.fluidmessages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Staying in touch without internet": a full page, reachable from the Nearby page and Settings → Help, drawn like
 * the Communities page (a big title, then tips). The same words are in docs/offline-guide.md. Plain and honest: what
 * still works, what to set up before you need it, what Nearby can't do, and other tools for longer range.
 */
internal object OfflineGuide {
    const val TITLE = "Staying in touch without internet"
    const val SUBTITLE = "What still works when the internet or the cell network is down, and what to set up before you need it."

    data class Section(val title: String, val tips: List<Tip>, val note: String = "")

    val sections = listOf(
        Section("If cell towers work but the internet is down", listOf(
            Tip(TipIcon.PHONE, "Texts and calls still work", "They use the cell network, not the internet. FluidMessages sends texts as usual."),
            Tip(TipIcon.CHAT, "Keep messages short", "A busy network passes short texts more easily than long ones or photos."),
            Tip(TipIcon.TIMER, "Texts often get through when calls don’t", "A text waits for a gap and then goes. A call needs a clear line the whole time."))),
        Section("If there’s no service at all", listOf(
            Tip(TipIcon.LAPTOP, "Phones on the same Wi-Fi", "Nearby reaches FluidMessages phones on one Wi-Fi network, even when that Wi-Fi has no internet: a router on battery, or one phone’s hotspot."),
            Tip(TipIcon.PEOPLE, "Bluetooth, about 10–30 m", "Through a wall or two, depending on the walls and the phones."),
            Tip(TipIcon.FOLDER, "Phones carry messages as people move", "With Help pass messages along on, a message goes from phone to phone and arrives a little later."),
            Tip(TipIcon.TAG, "Set up meeting points", "Agree on a place and a time, so phones come within range of each other."))),
        Section("Practical tips", listOf(
            Tip(TipIcon.PEOPLE, "Set up Nearby before an emergency", "Turn it on and add your family in person now, so you know it’s really them."),
            Tip(TipIcon.FOLDER, "Turn on Help pass messages along", "Your phone carries messages for others nearby, sealed so it can’t read one-to-one ones."),
            Tip(TipIcon.PHONE, "One charged phone can relay for a building", "Left on with Nearby, in a hallway or on a middle floor, it passes messages between floors."),
            Tip(TipIcon.TIMER, "Keep a battery pack charged", "A phone that’s off can’t carry anything."),
            Tip(TipIcon.OPTIONAL, "Stay discoverable costs battery", "It keeps Nearby running with FluidMessages closed. Turn it on when it matters."),
            Tip(TipIcon.LOCK, "Turn on Nearby only", "On the Nearby page. FluidMessages stops using the internet, which saves data and battery."),
            Tip(TipIcon.CHAT, "Use community channels", "Open channels like “Neighborhood” are for everyone nearby. Anyone close by can read them."))),
        Section("What it can’t do", listOf(
            Tip(TipIcon.OPTIONAL, "It can’t reach people far away", "Not without phones in between to carry the message."),
            Tip(TipIcon.PHONE, "It can’t call", "Nearby sends words only."),
            Tip(TipIcon.TIMER, "It isn’t instant across a city", "Messages move as fast as the people carrying them."),
            Tip(TipIcon.PEOPLE, "It only reaches FluidMessages phones", "With Nearby turned on."))),
        Section("For longer range, other tools exist", listOf(
            Tip(TipIcon.TAG, "LoRa mesh radios", "Small radios, such as Meshtastic devices, pair with a phone and can reach kilometres between radios."),
            Tip(TipIcon.PEOPLE, "GMRS and FRS walkie-talkies", "Voice over a few kilometres. In the US, GMRS needs a license and FRS doesn’t."),
            Tip(TipIcon.PRACTICE, "Amateur (ham) radio", "Much longer range, with a license and some practice.")),
            note = "FluidMessages doesn’t connect to these yet."),
    )

    /** One row of the page's list. Each tip is its own row with a steady [key], so rows are kept and reused while scrolling. */
    data class Row(val key: String, val kind: Kind, val section: Int = -1, val tip: Int = -1)
    enum class Kind { INTRO, OPEN_NEARBY, SECTION, TIP, NOTE }

    /** The page's rows, in order. [openNearby]: the tip that opens the Nearby page (not shown when the guide was opened from it). */
    fun rows(openNearby: Boolean): List<Row> = buildList {
        add(Row("intro", Kind.INTRO))
        if (openNearby) add(Row("open-nearby", Kind.OPEN_NEARBY))
        sections.forEachIndexed { s, section ->
            add(Row("section-$s", Kind.SECTION, s))
            section.tips.indices.forEach { t -> add(Row("tip-$s-$t", Kind.TIP, s, t)) }
            if (section.note.isNotEmpty()) add(Row("note-$s", Kind.NOTE, s))
        }
    }

    /** How long a row waits before it fades in when the page opens: a short, capped stagger down the first screen. */
    fun revealDelay(order: Int): Long = order.coerceIn(0, 8) * 45L

    /** Every line of the page, in order, for the test that keeps it and docs/offline-guide.md in step. */
    fun text(): List<String> = listOf(TITLE, SUBTITLE) + sections.flatMap { section ->
        listOf(section.title) + section.tips.flatMap { listOf(it.title, it.line) } + listOfNotNull(section.note.ifEmpty { null })
    }
}

/** Whether guide pages animate: motion speed above 0 and the system not removing animations (reduced motion). */
internal fun guideMotionOn(context: android.content.Context, speed: Int): Boolean = speed > 0 &&
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/**
 * The guide page. [openNearby] is shown as a tip that opens the Nearby page; null when the guide was opened from it.
 * [showTitle] is false under a page heading that already says the title (NearbyScreen), so it isn't shown twice.
 *
 * Rows fade in once, only those on screen as the page opens, by alpha and offset only, so no row changes size. (It used
 * to put whole sections of slide-in [Tips] in single lazy rows: a section below the fold started its stagger only when
 * scrolled to and grew from nothing, pushing the page around, and replayed each time it came back on screen.)
 */
@Composable
internal fun OfflineGuidePage(prefs: android.content.SharedPreferences, openNearby: (() -> Unit)?, showTitle: Boolean = true) {
    val appearance = remember { Appearance.read(prefs) }
    val context = LocalContext.current
    val motion = remember { guideMotionOn(context, appearance.speed) }
    val scheme = MaterialTheme.colorScheme
    val rows = remember(openNearby != null) { OfflineGuide.rows(openNearby != null) }
    val open by rememberUpdatedState(openNearby)
    val openTip = remember { Tip(TipIcon.PEOPLE, "Open Nearby", "Set it up, see who’s close by, and add your family") { open?.invoke() } }
    // True only for the rows composed in the page's first frame: the ones on screen as it opens.
    var opening by remember { mutableStateOf(motion) }
    LaunchedEffect(Unit) { if (motion) { withFrameNanos { }; opening = false } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, row -> row.kind }) { index, row ->
            Reveal(remember { opening }, index, appearance.speed) {
                when (row.kind) {
                    OfflineGuide.Kind.INTRO -> Column(Modifier.padding(bottom = 3.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (showTitle) Text(OfflineGuide.TITLE, Modifier.semantics { heading() }, fontSize = 32.sp, lineHeight = 36.sp,
                            color = scheme.onSurface, fontWeight = FontWeight.Bold)
                        Text(OfflineGuide.SUBTITLE, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    OfflineGuide.Kind.OPEN_NEARBY -> TipCard(openTip)
                    OfflineGuide.Kind.SECTION -> Text(OfflineGuide.sections[row.section].title, Modifier.padding(top = 9.dp).semantics { heading() },
                        color = scheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OfflineGuide.Kind.TIP -> TipCard(OfflineGuide.sections[row.section].tips[row.tip])
                    OfflineGuide.Kind.NOTE -> Text(OfflineGuide.sections[row.section].note, color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Fades and lifts [content] in once when [animate]; otherwise shows it as is. Draw-only, so the row's size never changes. */
@Composable
internal fun Reveal(animate: Boolean, order: Int, speed: Int, content: @Composable () -> Unit) {
    if (!animate) { content(); return }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(OfflineGuide.revealDelay(order))
        progress.animateTo(1f, tween(speed * 2, easing = FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 18.dp.toPx()
    }) { content() }
}
