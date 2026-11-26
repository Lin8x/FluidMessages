package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "What is Tor?": a full page in the style of the service guides (AccountGuide), in plain words. It shows the first
 * time someone turns on Connect through Tor, before the warning and "I understand" (TorSettingsPage), and any time
 * from the Tor page and Settings → Help. FluidMessages Web shows the same words (server/tor.js TOR_INTRO).
 */
internal object TorIntro {
    /** Set once the page has shown on the way to turning Tor on. This phone only. */
    const val SEEN_PREF = "torIntroSeen"
    const val TITLE = "What is Tor?"
    const val ABOUT = "Tor hides your internet address from the servers you talk to and from anyone watching your " +
        "network. It does this by bouncing your connection through three computers run by volunteers. Each one only " +
        "knows the step before it and the step after it."
    const val WHO_TITLE = "Who it’s for"
    const val WHO = "People who don’t want a chat server, a school, a workplace, or an internet provider to see who " +
        "they talk to or where they connect from."
    const val NOT_TITLE = "What it doesn’t do"
    val not = listOf(
        "It doesn’t lock your messages. That’s the job of the lock next to a chat’s name.",
        "Texts and phone calls never use Tor. They go over your phone plan.")
    const val DOWNSIDES_TITLE = "The downsides"
    val downsides = listOf(
        "It’s slower. Messages and pictures take longer.",
        "The connection sometimes drops and has to start again.",
        "Some servers block Tor, so a chat there may not connect.")
    const val STEPS_TITLE = "How it works here"
    val steps = listOf(
        "Turn on Connect through Tor for every chat service in Settings → Privacy and security.",
        "Or turn it on for only some chats: each chat’s details have their own Connect through Tor switch.",
        "Chats that go through Tor show a small onion next to their name.")
    const val AGAIN = "See this again any time in Settings → Help → What is Tor?"

    /** Whether turning Tor on shows this page first: only on the way to turning it on, and only the first time. */
    fun due(seen: Boolean, turningOn: Boolean) = turningOn && !seen

    @JvmStatic
    fun due(prefs: SharedPreferences, turningOn: Boolean) = due(prefs.getBoolean(SEEN_PREF, false), turningOn)

    @JvmStatic
    fun markSeen(prefs: SharedPreferences) = prefs.edit().putBoolean(SEEN_PREF, true).apply()

    /** Settings → Help → What is Tor?: the page on its own; Done goes back. */
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, done: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { AppTheme(remember { Appearance.read(prefs) }) { TorIntroPage("Done", { done.run() }) } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

/**
 * The page: picture, title, explanation, then who it's for, what it doesn't do, the downsides, and numbered steps.
 * [go] is the big button ("Continue" on the way to turning Tor on, "Done" otherwise); [later], when given, is a
 * quieter "Not now" under it.
 */
@Composable
internal fun TorIntroPage(go: String, onGo: () -> Unit, later: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val appearance = LocalAppearance.current
    val dark = appearance?.dark ?: false
    val tint = accessibleBubbleFill(Color(MessageStyle.filledControl(LocalControlTint.current)))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp)).background(scheme.surfaceContainer)
                .clearAndSetSemantics { contentDescription = "A path from your phone that hops through three computers before it reaches a server" },
                contentAlignment = Alignment.Center) {
                TorPathPicture(tint, torInk(dark), scheme.onSurfaceVariant, Modifier.size(width = 280.dp, height = 126.dp))
            }
        }
        item { Text(TorIntro.TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
        item { Text(TorIntro.ABOUT, style = MaterialTheme.typography.bodyLarge) }
        item { IntroSection(TorIntro.WHO_TITLE, listOf(TorIntro.WHO)) }
        item { IntroSection(TorIntro.NOT_TITLE, TorIntro.not) }
        item { IntroSection(TorIntro.DOWNSIDES_TITLE, TorIntro.downsides) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(TorIntro.STEPS_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                TorIntro.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(32.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
                            Text((index + 1).toString(), color = bubbleTextColor(tint), fontWeight = FontWeight.Bold)
                        }
                        Text(step, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (index == TorIntro.steps.lastIndex) TorMark(dark, size = 20)
                    }
                }
            }
        }
        if (later == null) item { Text(TorIntro.AGAIN, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppButton(onClick = onGo, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    Text(go, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (later != null) TextButton(onClick = later) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun IntroSection(title: String, lines: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        for (line in lines) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lines.size > 1) Box(Modifier.padding(top = 9.dp).size(6.dp).background(scheme.onSurfaceVariant, CircleShape))
            Text(line, Modifier.weight(1f), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * An original drawing (not the Tor Project's logo): your phone on the left, a server on the right, and a dashed path
 * that hops up and down through three round computers between them, each in the onion's violet.
 */
@Composable
private fun TorPathPicture(tint: Color, node: Color, quiet: Color, modifier: Modifier) {
    val ink = bubbleTextColor(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val line = 2.5.dp.toPx()
        // Your phone, with a chat bubble on its screen.
        drawRoundRect(tint, Offset(w * .03f, h * .22f), Size(w * .12f, h * .56f), CornerRadius(h * .07f))
        drawRoundRect(ink, Offset(w * .055f, h * .4f), Size(w * .07f, h * .12f), CornerRadius(h * .05f))
        // The server: a stack of three boxes.
        for (i in 0 until 3) {
            val top = h * (.26f + i * .17f)
            drawRoundRect(quiet, Offset(w * .83f, top), Size(w * .14f, h * .14f), CornerRadius(h * .04f), style = Stroke(line))
            drawCircle(quiet, h * .022f, Offset(w * .87f, top + h * .07f))
        }
        // The path, hopping through the three volunteer computers.
        val hops = listOf(Offset(w * .15f, h * .5f), Offset(w * .32f, h * .24f), Offset(w * .5f, h * .74f), Offset(w * .68f, h * .26f),
            Offset(w * .83f, h * .5f))
        val dashes = PathEffect.dashPathEffect(floatArrayOf(line * 2.4f, line * 2.4f))
        for (i in 0 until hops.size - 1) drawLine(quiet.copy(alpha = .8f), hops[i], hops[i + 1], line, StrokeCap.Round, pathEffect = dashes)
        for (spot in hops.subList(1, 4)) {
            drawCircle(node, h * .12f, spot)
            drawCircle(Color.White.copy(alpha = .9f), h * .12f, spot, style = Stroke(line * .8f))
            // A little screen in each computer.
            drawRoundRect(Color.White, spot - Offset(h * .055f, h * .04f), Size(h * .11f, h * .07f), CornerRadius(h * .015f))
        }
    }
}
