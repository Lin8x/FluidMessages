package org.fluidmessages

import android.content.SharedPreferences
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Animated showcases for the setup guide: a small pretend chat that plays one feature at a time, built from the
 * same cards the real chat uses, plus the service and call cards. Everything shown is made up; nothing is sent.
 */

/** 0 before [start], 1 after [end], and a straight line between. */
private fun part(progress: Float, start: Float, end: Float) = ((progress - start) / (end - start)).coerceIn(0f, 1f)

/** What a demo is drawn with: how far it has played, and whether motion is off (a still frame, no finger). */
private class DemoFrame(val progress: Float, val still: Boolean, val cycle: Int)

/**
 * Plays [captions].size demos in turn, about every [millis]. Tapping skips to the next one. With motion off each
 * demo is a still frame at its [stills] point and only a tap changes it.
 */
@Composable
private fun DemoCarousel(appearance: Appearance, captions: List<String>, stills: List<Float>, height: Dp, millis: Int,
                         content: @Composable BoxScope.(index: Int, frame: DemoFrame) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val still = appearance.speed == 0
    var index by remember { mutableIntStateOf(0) }
    var cycle by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(index, cycle, still) {
        if (still) { progress.snapTo(stills[index]); return@LaunchedEffect }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(millis - 350, easing = LinearEasing))
        delay(350)
        index = (index + 1) % captions.size
        cycle++
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WallpaperSurface(appearance.wallpaper, Color(appearance.canvas), Modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(24.dp))) {
            key(index, cycle) {
                // A short fade in, so one demo gives way to the next instead of snapping. The pretend chat is a picture
                // for screen readers too: the caption below says what it shows.
                Box(Modifier.fillMaxSize().clearAndSetSemantics {}.graphicsLayer { alpha = if (still) 1f else part(progress.value, 0f, .1f) }) {
                    content(index, DemoFrame(progress.value, still, cycle))
                }
            }
            // On top of the demo, so a tap always moves on instead of opening the cards inside it.
            Box(Modifier.matchParentSize().clickable(onClickLabel = "Show the next example") { index = (index + 1) % captions.size; cycle++ }
                .semantics { contentDescription = captions[index] + ". Tap for the next example." })
        }
        Text(captions[index], color = scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            captions.indices.forEach { dot ->
                Box(Modifier.size(if (dot == index) 8.dp else 6.dp)
                    .background(if (dot == index) scheme.primary else scheme.outlineVariant, CircleShape))
            }
        }
    }
}

/** A chat bubble colored the way the real chat colors it, with white text on colored fills. */
@Composable
private fun DemoBubble(appearance: Appearance, outgoing: Boolean, modifier: Modifier = Modifier, tint: Color? = null,
                       content: @Composable (Color) -> Unit) {
    val chosen = tint ?: if (outgoing) Color(appearance.accent) else Color(appearance.incoming)
    val fill = accessibleBubbleFill(chosen)
    Box(modifier.widthIn(max = 290.dp).bubbleTail(fill, appearance.tails, outgoing)
        .background(fill, RoundedCornerShape(appearance.radius.dp))
        .padding(horizontal = appearance.padding.dp, vertical = (appearance.padding * .72f).dp)) {
        content(bubbleTextColor(chosen))
    }
}

@Composable
private fun BubbleText(text: String, color: Color, appearance: Appearance, modifier: Modifier = Modifier) =
    Text(text, modifier, color = color, fontSize = appearance.size.sp, lineHeight = (appearance.size + 4).sp)

/** A fingertip: a soft dot, with a ring that grows while it presses. Hidden in still frames. */
@Composable
private fun Fingertip(visible: Float, press: Float, modifier: Modifier = Modifier) {
    if (visible <= 0f) return
    Canvas(modifier.size(44.dp).graphicsLayer { alpha = visible }) {
        val radius = size.minDimension * .26f
        if (press > 0f) drawCircle(Color.White.copy(alpha = .45f * (1 - press * .6f)), radius * (1.2f + press * .8f), center)
        drawCircle(Color(0x66000000), radius + 2.dp.toPx(), center)
        drawCircle(Color.White.copy(alpha = .92f), radius, center)
        drawCircle(Color(0x33000000), radius, center, style = Stroke(1.dp.toPx()))
    }
}

/** Fingertip visibility for a gesture between [from] and [to]: fades in, holds, fades out. */
private fun touch(frame: DemoFrame, from: Float, to: Float): Float = if (frame.still) 0f else
    minOf(part(frame.progress, from - .05f, from), 1f - part(frame.progress, to, to + .06f))

private fun Modifier.pop(value: Float) = this.graphicsLayer {
    val scale = if (value >= 1f) 1f else value * 1.12f - (value * value * .12f)
    scaleX = scale; scaleY = scale; alpha = value.coerceIn(0f, 1f)
}

// "More than text": polls, games, effects, spoilers, and dice, drawn with the chat's own cards.

private val showcaseCaptions = listOf(
    "Polls · votes fill in as friends answer",
    "Games · tic-tac-toe, four in a row, and chess",
    "Effects · send with Slam, Confetti, and more",
    "Spoilers · hidden until tapped",
    "Dice · everyone sees the same roll",
)

private val demoPoll = Poll("demo0poll", "Movie night?", listOf("Friday", "Saturday", "Sunday"))

private fun demoGame(moves: List<String>, moveText: String?): GameView = GameView(GameKind.TIC_TAC_TOE, "demo0game", moves,
    GamePosition.replay(GameKind.TIC_TAC_TOE, moves)!!, mineFirst = true, resignedByMe = null, latest = true,
    move = moves.lastOrNull().takeIf { moveText != null }, byMe = false, moveText = moveText, reply = false, rejected = false)

@Composable
internal fun MoreThanTextShowcase(appearance: Appearance) {
    DemoCarousel(appearance, showcaseCaptions, stills = listOf(1f, 1f, 1f, .2f, 1f), height = 318.dp, millis = 2600) { index, frame ->
        val p = frame.progress
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (index) {
            0 -> {
                // Votes arrive one at a time and the bars grow with them.
                val voters = listOf("Ana" to 1, "Ben" to 1, "you" to 0, "Kai" to 1, "Lu" to 2)
                val arrived = voters.filterIndexed { at, _ -> p >= .18f + at * .14f }.toMap()
                DemoBubble(appearance, true, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { ink ->
                    PollCard(demoPoll, PollTally(arrived), ink, null)
                }
            }
            1 -> {
                // Their move lands on the board.
                val moved = p >= .45f
                val view = if (moved) demoGame(listOf("5", "1", "3", "7"), "the bottom left") else demoGame(listOf("5", "1", "3"), null)
                DemoBubble(appearance, false, Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                    .pop(if (moved && !frame.still) .6f + .4f * part(p, .45f, .6f) else 1f)) { ink -> GameCard(view, ink, null) }
            }
            2 -> {
                val slam = if (frame.still) 1f else part(p, .04f, .4f)
                val second = if (frame.still) 1f else part(p, .45f, .55f)
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.End) {
                    val (scale, turn) = bubbleEffectScale(MessageEffect.SLAM, slam)
                    DemoBubble(appearance, true, Modifier.graphicsLayer {
                        scaleX = scale; scaleY = scale; rotationZ = turn; alpha = if (frame.still) 1f else part(p, .02f, .08f)
                    }) { ink -> BubbleText("Guess what…", ink, appearance) }
                    Text("Sent with Slam 💥", color = muted, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    DemoBubble(appearance, true, Modifier.pop(second)) { ink -> BubbleText("We got the apartment!", ink, appearance) }
                    Text("Sent with Confetti 🎉", Modifier.graphicsLayer { alpha = second }, color = muted, fontSize = 11.sp)
                }
                if (!frame.still && p >= .45f) ScreenEffectOverlay(MessageEffect.CONFETTI, frame.cycle.toLong() + 1) {}
            }
            3 -> {
                val revealed = p >= .5f
                Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 40.dp)) {
                    DemoBubble(appearance, false) { ink ->
                        val text = remember(revealed, ink) {
                            formatMessage("Finished the book! The twist: ||the lighthouse keeper was her sister||", ink,
                                if (revealed) setOf(0) else emptySet()) {}
                        }
                        Text(text, color = ink, fontSize = appearance.size.sp, lineHeight = (appearance.size + 4).sp)
                    }
                    Fingertip(touch(frame, .3f, .5f), part(p, .38f, .5f), Modifier.align(Alignment.BottomEnd).offset(x = (-30).dp, y = 8.dp))
                }
            }
            else -> DemoBubble(appearance, true, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) { ink ->
                RandomCard(RandomResult.Dice(6, listOf(4, 6)), true, ink, spin = !frame.still)
            }
        }
    }
}

// "Quick moves": the gestures the chat and inbox really have, played by a fingertip.

private val gestureCaptions = listOf(
    "Swipe a message right to reply",
    "Touch and hold a message to react",
    "Tap invisible ink or a spoiler to reveal it",
    "Touch and hold a chat to pin, mute, or archive it",
    "Touch and hold Send to choose how it goes",
)

@Composable
internal fun QuickMovesShowcase(appearance: Appearance) {
    val palette = remember(appearance) { Palette(appearance) }
    DemoCarousel(appearance, gestureCaptions, stills = listOf(1f, 1f, .2f, .58f, .58f), height = 250.dp, millis = 3000) { index, frame ->
        val p = frame.progress
        when (index) {
            0 -> {
                // The bubble follows the finger, springs back, and the reply bar appears above the message box.
                val pull = if (frame.still) 0f else part(p, .1f, .38f) - part(p, .45f, .55f)
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.padding(top = 18.dp).graphicsLayer { translationX = pull * 60.dp.toPx() }) {
                        DemoBubble(appearance, false) { ink -> BubbleText("Anyone up for a hike Saturday?", ink, appearance) }
                        Fingertip(touch(frame, .1f, .42f), 0f, Modifier.align(Alignment.CenterStart).offset(x = 14.dp))
                    }
                    val bar = if (frame.still) 1f else part(p, .5f, .6f)
                    Row(Modifier.fillMaxWidth().pop(bar).background(palette.wash, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Reply to “Anyone up for a hike Saturday?”", Modifier.weight(1f), color = palette.ink, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        DrawGlyph(Glyph.Close, palette.toolbarAction, Modifier.size(16.dp))
                    }
                }
            }
            1 -> {
                // Touch and hold: the reaction bar pops up, a heart is picked, and it lands on the bubble.
                val bar = if (frame.still) 0f else part(p, .3f, .38f) * (1f - part(p, .62f, .68f))
                val landed = if (frame.still) 1f else part(p, .66f, .78f)
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                    Row(Modifier.pop(bar).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (reaction in SmsReaction.entries) {
                            val picked = reaction == SmsReaction.LOVE && p >= .52f
                            Text(reaction.symbol, Modifier.graphicsLayer { val s = if (picked) 1.35f else 1f; scaleX = s; scaleY = s }
                                .padding(horizontal = 5.dp), fontSize = 21.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box {
                        Column {
                            DemoBubble(appearance, false) { ink -> BubbleText("I got the job!!", ink, appearance) }
                            Text("❤️", Modifier.padding(start = 10.dp).offset(y = (-6).dp).pop(landed)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                                .border(2.dp, Color(appearance.canvas), RoundedCornerShape(50))
                                .padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 14.sp)
                        }
                        Fingertip(touch(frame, .08f, .34f), part(p, .1f, .32f), Modifier.align(Alignment.TopStart).offset(x = 40.dp, y = (-6).dp))
                    }
                }
            }
            2 -> {
                // Invisible ink: sparkles until the tap, then the words.
                val hidden = p < .45f
                Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp, end = 40.dp)) {
                    DemoBubble(appearance, false) { ink ->
                        BubbleText("Your birthday present is a weekend at the coast", ink, appearance,
                            Modifier.invisibleInk(hidden, ink, moving = !frame.still))
                    }
                    Fingertip(touch(frame, .3f, .48f), part(p, .34f, .45f), Modifier.align(Alignment.Center))
                }
            }
            3 -> {
                // Touch and hold a chat in the inbox for its menu; Pin moves it to the top.
                val menu = part(p, .3f, .38f) * (1f - part(p, .66f, .72f))
                val pinned = p >= .66f
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        Row(Modifier.fillMaxWidth().background(palette.bg, RoundedCornerShape(16.dp)).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(initialsOf("Riley Park"), "Riley Park", 40)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Riley Park", Modifier.weight(1f), color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    Text(if (pinned) "Pinned · 9:41" else "9:41", color = palette.muted, fontSize = 12.sp)
                                }
                                Text("Sounds good, see you then", color = palette.muted, fontSize = 14.sp, maxLines = 1)
                            }
                        }
                        Fingertip(touch(frame, .08f, .34f), part(p, .1f, .32f), Modifier.align(Alignment.CenterStart).offset(x = 110.dp))
                    }
                    Column(Modifier.padding(start = 60.dp).width(170.dp).pop(menu)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(vertical = 4.dp)) {
                        listOf("Pin", "Mute", "Archive", "Block").forEachIndexed { at, label ->
                            val chosen = at == 0 && p >= .52f
                            if (at > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(label, Modifier.fillMaxWidth()
                                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        }
                    }
                }
            }
            else -> {
                // Touch and hold Send: pick another way to send, and the chip under the box shows it.
                val menu = part(p, .3f, .38f) * (1f - part(p, .66f, .72f))
                val switched = p >= .66f
                val matrix = MessageProtocol.MATRIX.tint(appearance)
                val sendColor = if (switched) matrix else Color(appearance.accent)
                Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                    horizontalAlignment = Alignment.End) {
                    Column(Modifier.width(150.dp).pop(menu).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                        .padding(vertical = 4.dp)) {
                        listOf("SMS ✓", "Matrix").forEachIndexed { at, label ->
                            val chosen = at == 1 && p >= .52f
                            Text(label, Modifier.fillMaxWidth()
                                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("On my way!", Modifier.weight(1f).background(palette.wash, RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp), color = palette.ink, fontSize = 15.sp)
                        Box(Modifier.padding(start = 8.dp).size(36.dp)) {
                            Box(Modifier.fillMaxSize().background(Color(MessageStyle.filledControl(sendColor.toArgb())), CircleShape),
                                contentAlignment = Alignment.Center) {
                                DrawGlyph(Glyph.Send, Color(MessageStyle.filledControlInk(sendColor.toArgb())), Modifier.size(18.dp))
                            }
                            Fingertip(touch(frame, .08f, .34f), part(p, .1f, .32f), Modifier.align(Alignment.Center))
                        }
                    }
                    val chip = if (switched) matrix else Color(appearance.accent)
                    Text("via ${if (switched) "Matrix" else "SMS"}  ▾", Modifier.align(Alignment.Start)
                        .background(accessibleBubbleFill(chip), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        color = bubbleTextColor(chip), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// "Chat services" and "Calls": one card per service, saying what it is, who it suits, and how private it is.

/** One service card's words. The same text is used by FluidMessages Web's setup guide. */
internal data class ServiceText(val badge: String, val name: String, val about: String)

internal val chatServiceTexts: List<Pair<MessageProtocol, ServiceText>> = listOf(
    MessageProtocol.SMS to ServiceText("SMS", "Texts (SMS and MMS)",
        "Regular texts and picture messages through your phone plan. Works with any phone number, no internet needed."),
    MessageProtocol.RCS to ServiceText("RCS", "RCS · preview",
        "The newer carrier texting standard. It isn’t available in FluidMessages yet, so your texts go as SMS for now."),
    MessageProtocol.MATRIX to ServiceText("M", "Matrix",
        "An open chat network, like email for instant messages. Talk to anyone on any Matrix server, including people using Element."),
    MessageProtocol.XMPP to ServiceText("X", "XMPP",
        "A long-running open chat standard, also called Jabber. Good for friends on Conversations, Monal, or their own server."),
    MessageProtocol.EMAIL to ServiceText("@", "Email",
        "Chat with anyone who has an email address. Only the conversations you choose show up, never your whole mailbox."),
    MessageProtocol.IRC to ServiceText("#", "IRC",
        "Classic chat rooms, called channels, used by many open-source projects and communities. Connects over TLS."),
)

internal val callServiceTexts: List<Pair<Calling.Method, ServiceText>> = listOf(
    Calling.Method.PHONE to ServiceText("", "Phone calls",
        "Regular calls with your phone number, through your carrier and Android’s call screen. Works with anyone."),
    Calling.Method.MATRIX to ServiceText("M", "Matrix calls",
        "Voice and video with your Matrix contacts over the internet. End-to-end encrypted."),
    Calling.Method.XMPP to ServiceText("X", "XMPP calls",
        "Voice and video with XMPP contacts, such as people using Conversations. End-to-end encrypted. Needs XMPP connected from this phone."),
    Calling.Method.SIP to ServiceText("SIP", "SIP",
        "Internet phone service from a SIP provider, including calls to regular phone numbers over Wi-Fi or data. Your provider can access calls."),
    Calling.Method.MEETING to ServiceText("Meet", "Meeting links",
        "A Jitsi Meet link anyone can tap to join, from any app or browser. Free and open source. The meeting server can access the call."),
)

/** The lock each way to call shows on the call screen (see CallHub and CallActivity). */
internal fun callEncryption(method: Calling.Method): Encryption = when (method) {
    Calling.Method.PHONE -> Encryption.NONE
    Calling.Method.MATRIX, Calling.Method.XMPP -> Encryption.END_TO_END
    Calling.Method.SIP, Calling.Method.MEETING -> Encryption.IN_TRANSIT
}

/** The lock a chat on [protocol] shows, from its transport. */
internal fun chatEncryption(protocol: MessageProtocol): Encryption = protocol.transport().capabilities.encryption

@Composable
private fun ServiceCard(text: ServiceText, tint: Color, encryption: Encryption, trailing: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = accessibleBubbleFill(tint)
    val ink = bubbleTextColor(tint)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surfaceContainer).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(fill, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            if (text.badge.isEmpty()) HandsetGlyph(ink, Modifier.size(22.dp))
            else Text(text.badge, color = ink, fontWeight = FontWeight.Bold, fontSize = if (text.badge.length > 2) 12.sp else 18.sp, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(start = 13.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text.name, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text(text.about, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SecurityLock(encryption, scheme.surfaceContainer, size = 12)
                Text(encryption.shortLabel, color = lockColor(encryption, scheme.surfaceContainer), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        trailing()
    }
}

/** The app's own switch, without a row of its own, for the end of a card. */
@Composable
private fun CardSwitch(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    val fill = Color(MessageStyle.filledControl(LocalControlTint.current))
    val offset = if (checked) 21.dp else 3.dp
    Box(Modifier.size(51.dp, 31.dp).clip(CircleShape)
        .background(if (checked) fill else MaterialTheme.colorScheme.outline)
        .then(Modifier.clickable(role = Role.Switch, onClickLabel = if (checked) "Turn off $label" else "Turn on $label") { changed(!checked) })
        .clearAndSetSemantics { contentDescription = "$label, ${if (checked) "on" else "off"}"; role = Role.Switch },
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.offset(x = offset).size(27.dp).background(Color.White, CircleShape))
    }
}

@Composable
private fun StatusLabel(text: String) {
    Text(text, Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

/** One card per chat service. Texts are always there; the internet services have their on/off switch. */
@Composable
internal fun ChatServiceCards(prefs: SharedPreferences, appearance: Appearance) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((protocol, text) in chatServiceTexts) {
            ServiceCard(text, protocol.tint(appearance), chatEncryption(protocol)) {
                when (protocol) {
                    MessageProtocol.SMS -> StatusLabel("Ready")
                    MessageProtocol.RCS -> StatusLabel("Preview")
                    else -> {
                        var on by remember(protocol) { mutableStateOf(protocol.enabled(prefs)) }
                        CardSwitch(text.name, on) { on = it; prefs.edit().putBoolean(protocol.key(), it).apply() }
                    }
                }
            }
        }
    }
}

/** One card per way to call, each with the same switch as Settings → Accounts and calls → Calls. */
@Composable
internal fun CallCards(prefs: SharedPreferences, appearance: Appearance) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var sipOn by remember { mutableStateOf(Calling.allowed(prefs, Calling.Method.SIP)) }
        for ((method, text) in callServiceTexts) {
            val tint = when (method) {
                Calling.Method.PHONE -> Color(0xFF34C759)
                Calling.Method.MATRIX -> MessageProtocol.MATRIX.tint(appearance)
                Calling.Method.XMPP -> MessageProtocol.XMPP.tint(appearance)
                Calling.Method.SIP -> Color(0xFF0A84FF)
                Calling.Method.MEETING -> Color(0xFFAF52DE)
            }
            ServiceCard(text, tint, callEncryption(method)) {
                var on by remember(method) { mutableStateOf(Calling.allowed(prefs, method)) }
                CardSwitch(text.name, on) {
                    on = it
                    if (method == Calling.Method.SIP) sipOn = it
                    Calling.set(prefs, method, it)
                    DirectProtocols.restart(context)
                    SipAgent.sync(context)
                }
            }
        }
        if (sipOn && Calling.sipAccount(prefs) == null) Text("Add your SIP account later in Settings → Accounts and calls → Calls.",
            Modifier.padding(horizontal = 12.dp), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        PreferencesCard("Phone tab") {
            var phoneCalls by remember { mutableStateOf(prefs.getBoolean("showPhoneCalls", true)) }
            SettingsSwitch("Show regular phone calls in Recents", "Next to internet calls in the Phone tab", phoneCalls) {
                phoneCalls = it
                prefs.edit().putBoolean("showPhoneCalls", it).apply()
            }
        }
    }
}
