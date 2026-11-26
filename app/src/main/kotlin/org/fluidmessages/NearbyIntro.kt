package org.fluidmessages

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "What is Nearby?": a full page in the style of the account guides (AccountGuide): a picture, a title, plain words,
 * and what happens next. It shows once, the first time someone turns Nearby on, before they pick a name; and any time
 * from the Help card on the Nearby page and Settings → Help. Nearby is phone only, so FluidMessages Web has no copy.
 */
internal object NearbyIntro {
    /** Set when someone taps Turn on Nearby on this page. This phone only. */
    const val SEEN_PREF = "nearbyIntroSeen"
    const val TITLE = "What is Nearby?"
    const val ABOUT = "Nearby lets you message FluidMessages friends close by, with no internet and no cell signal. " +
        "It works when your phones are on the same Wi-Fi, or over Bluetooth."
    const val GOOD_FOR = "Good for blackouts and storms, big events, camping, and school or office Wi-Fi that has no internet."
    const val AGAIN = "See this again any time in Settings → Help → What is Nearby?"

    data class Part(val title: String, val tips: List<Tip>)

    val parts = listOf(
        Part("How it works", listOf(
            Tip(TipIcon.PEOPLE, "See who’s close by", "People with Nearby on show up on your screen. Tap someone to chat."),
            Tip(TipIcon.LOCK, "Locked between you two", "Only you and the person you’re writing to can read your messages."),
            Tip(TipIcon.FOLDER, "Phones help carry messages", "Phones nearby can pass a message along, so it can reach someone a bit farther away, a little later."),
            Tip(TipIcon.CHAT, "Community channels", "Open rooms like “Neighborhood” for everyone close by. Anyone nearby can read them."))),
        Part("What it can’t do", listOf(
            Tip(TipIcon.OPTIONAL, "It can’t reach people far away", "Not unless there are phones with Nearby in between to carry the message."),
            Tip(TipIcon.PHONE, "It can’t make calls", "Nearby sends words only."))),
        Part("Battery", listOf(
            Tip(TipIcon.TIMER, "Easy on your battery", "Nearby runs while FluidMessages is open. Only Stay discoverable keeps it running in the background, and that uses more battery."))),
    )

    val steps = listOf(
        "Pick the name people close by will see.",
        "Ask the people with you to turn on Nearby too.",
        "Tap someone under People nearby to start a chat.")

    /** Whether turning Nearby on shows this page first: no Nearby name yet (never set up), and the page not seen yet. */
    fun due(seen: Boolean, setUp: Boolean) = !seen && !setUp

    @JvmStatic
    fun due(prefs: SharedPreferences, setUp: Boolean) = due(prefs.getBoolean(SEEN_PREF, false), setUp)

    @JvmStatic
    fun markSeen(prefs: SharedPreferences) = prefs.edit().putBoolean(SEEN_PREF, true).apply()

    /** The main button: Turn on Nearby until it's set up and on; then it just opens the Nearby page. */
    fun primary(on: Boolean) = if (on) "Open Nearby" else "Turn on Nearby"

    /** Not now is only for someone deciding whether to turn Nearby on. */
    fun showNotNow(on: Boolean) = !on

    /** Every line of the page, in order, for tests. */
    fun text(): List<String> = listOf(TITLE, ABOUT, GOOD_FOR) +
        parts.flatMap { part -> listOf(part.title) + part.tips.flatMap { listOf(it.title, it.line) } } + listOf("What happens next") + steps
}

/** The page. [on]: Nearby is already set up and on. [start] is Turn on Nearby (or Open Nearby); [later] is Not now. */
@Composable
internal fun NearbyIntroPage(prefs: SharedPreferences, on: Boolean, start: () -> Unit, later: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val appearance = remember { Appearance.read(prefs) }
    val tint = accessibleBubbleFill(MessageProtocol.NEARBY.tint(appearance))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "picture") {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp)).background(scheme.surfaceContainer)
                .clearAndSetSemantics { contentDescription = "A simple picture of phones linked to each other by dotted lines" },
                contentAlignment = Alignment.Center) {
                NearbyPicture(tint, scheme.onSurfaceVariant, Modifier.size(width = 260.dp, height = 126.dp))
            }
        }
        item(key = "title") { Text(NearbyIntro.TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
        item(key = "about") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(NearbyIntro.ABOUT, style = MaterialTheme.typography.bodyLarge)
                Text(NearbyIntro.GOOD_FOR, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
        }
        for (part in NearbyIntro.parts) {
            item(key = "part-" + part.title) {
                Text(part.title, Modifier.padding(top = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            }
            for (tip in part.tips) item(key = "tip-" + tip.title) { TipCard(tip) }
        }
        item(key = "next") {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What happens next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                NearbyIntro.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(32.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
                            Text((index + 1).toString(), color = bubbleTextColor(tint), fontWeight = FontWeight.Bold)
                        }
                        Text(step, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item(key = "buttons") {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppButton(onClick = start, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    Text(NearbyIntro.primary(on), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (NearbyIntro.showNotNow(on)) TextButton(onClick = later) { Text("Not now") }
                Text(NearbyIntro.AGAIN, Modifier.padding(top = 4.dp), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** An original drawing: four phones, linked to each other by dotted lines, with a lock on the middle link. */
@Composable
private fun NearbyPicture(tint: Color, quiet: Color, modifier: Modifier) {
    val ink = bubbleTextColor(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val line = 2.2.dp.toPx()
        val dots = PathEffect.dashPathEffect(floatArrayOf(line * .1f, line * 2.6f))
        val phones = listOf(Offset(w * .12f, h * .62f), Offset(w * .38f, h * .3f), Offset(w * .64f, h * .64f), Offset(w * .88f, h * .32f))
        for ((a, b) in listOf(0 to 1, 1 to 2, 2 to 3, 0 to 2)) {
            drawLine(quiet.copy(alpha = .7f), phones[a], phones[b], line, StrokeCap.Round, pathEffect = dots)
        }
        phones.forEachIndexed { index, spot -> phone(if (index == 0 || index == 3) tint else quiet, ink, spot, h * .19f, filled = index == 0 || index == 3) }
        // A small lock on the middle link: the message stays locked on its way.
        val lock = Offset((phones[1].x + phones[2].x) / 2, (phones[1].y + phones[2].y) / 2)
        drawCircle(tint, h * .1f, lock)
        drawRoundRect(ink, Offset(lock.x - h * .045f, lock.y - h * .01f), Size(h * .09f, h * .065f), CornerRadius(h * .012f))
        drawArc(ink, 180f, 180f, false, Offset(lock.x - h * .03f, lock.y - h * .055f), Size(h * .06f, h * .08f), style = Stroke(line * .7f))
    }
}

/** A phone outline centred on [center], [height] tall. Filled phones are the two people chatting. */
private fun DrawScope.phone(color: Color, ink: Color, center: Offset, height: Float, filled: Boolean) {
    val width = height * .56f
    val top = Offset(center.x - width / 2, center.y - height / 2)
    if (filled) drawRoundRect(color, top, Size(width, height), CornerRadius(width * .22f))
    else drawRoundRect(color, top, Size(width, height), CornerRadius(width * .22f), style = Stroke(2.dp.toPx()))
    drawLine(if (filled) ink else color, Offset(center.x - width * .18f, top.y + height * .86f), Offset(center.x + width * .18f, top.y + height * .86f),
        1.6.dp.toPx(), StrokeCap.Round)
}
