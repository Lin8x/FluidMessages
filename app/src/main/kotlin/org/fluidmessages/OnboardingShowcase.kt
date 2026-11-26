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

// Small pieces of the real inbox for the demos below: a chat row and a community server's header.

@Composable
private fun DemoRow(palette: Palette, name: String, preview: String, time: String, unread: Boolean = false, muted: String = "",
                    photo: Int = 0, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(palette.bg, RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(9.dp)) { if (unread) Box(Modifier.size(6.dp).background(palette.inboxAction, CircleShape)) }
        Avatar(initialsOf(name), name, 34, photo)
        Column(Modifier.weight(1f).padding(start = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f, fill = false), color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (muted.isNotEmpty()) MutedMark(muted, palette.muted, Modifier.padding(start = 4.dp), 12)
                Spacer(Modifier.weight(1f))
                Text(time, Modifier.padding(start = 6.dp), color = palette.muted, fontSize = 11.sp)
            }
            Text(if (muted.isNotEmpty() && preview.isEmpty()) muted else preview, color = palette.muted, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DemoServer(palette: Palette, name: String, service: MessageProtocol, detail: String, unread: Int, open: Float,
                       muted: String = "") {
    val tint = accessibleBubbleFill(service.tint(palette.appearance))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DrawGlyph(Glyph.Forward, palette.muted, Modifier.size(10.dp).graphicsLayer { rotationZ = 90f * open })
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint), contentAlignment = Alignment.Center) {
            Text(Communities.initials(name), color = bubbleTextColor(tint), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text(service.label, Modifier.padding(start = 6.dp).border(1.dp, palette.edge, RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp), color = palette.muted, fontSize = 10.sp)
                if (muted.isNotEmpty()) MutedMark(muted, palette.muted, Modifier.padding(start = 4.dp), 12)
            }
            Text(detail, color = palette.muted, fontSize = 12.sp, maxLines = 1)
        }
        if (unread > 0) Text(unread.toString(), Modifier.clip(CircleShape).background(palette.inboxAction).widthIn(min = 20.dp)
            .padding(horizontal = 6.dp), color = Color(MessageStyle.textOn(palette.inboxAction.toArgb())), fontSize = 11.sp,
            fontWeight = FontWeight.Bold)
    }
}

// "Communities": Chats | Communities, then a server folding open to its channels.

private val communityCaptions = listOf(
    "Chats | Communities · one tap apart",
    "Each server folds open to its channels and rooms",
)

@Composable
internal fun CommunitiesShowcase(appearance: Appearance) {
    val palette = remember(appearance) { Palette(appearance) }
    DemoCarousel(appearance, communityCaptions, stills = listOf(1f, 1f), height = 290.dp, millis = 3200) { index, frame ->
        val p = frame.progress
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (index == 0) {
                val communities = frame.still || p >= .42f
                Box {
                    InboxSwitch(palette, communities, if (communities) 0 else 3) {}
                    Fingertip(touch(frame, .18f, .44f), part(p, .26f, .42f), Modifier.align(Alignment.CenterEnd).offset(x = (-60).dp, y = 4.dp))
                }
                val reveal = if (frame.still) 1f else part(p, .44f, .56f)
                if (!communities) {
                    DemoRow(palette, "Maya Chen", "Coffee at ten?", "9:41", unread = true, photo = R.drawable.sample_maya)
                    DemoRow(palette, "Jordan Ellis", "Here’s the loop we talked about", "Yesterday", photo = R.drawable.sample_jordan)
                    DemoRow(palette, "Weekend Plans", "Avery: I booked the tables", "Today")
                } else Column(Modifier.graphicsLayer { alpha = reveal; translationY = (1f - reveal) * 14.dp.toPx() },
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DemoServer(palette, "Harbor Net", MessageProtocol.IRC, "2 channels · you@irc.harbor.example", 1, 1f)
                    DemoRow(palette, "#radio-club", "kestrel: tuning starts at seven", "Today", unread = true, modifier = Modifier.padding(start = 16.dp))
                    DemoServer(palette, "Lakeside Commons", MessageProtocol.MATRIX, "2 rooms · @you:lakeside.example", 1, 0f)
                }
            } else {
                // Tapping the header folds the server open; its channels slide in one by one.
                val open = if (frame.still) 1f else part(p, .3f, .4f)
                Box {
                    DemoServer(palette, "Harbor Net", MessageProtocol.IRC, "3 channels · you@irc.harbor.example", 2, open)
                    Fingertip(touch(frame, .1f, .32f), part(p, .16f, .3f), Modifier.align(Alignment.CenterStart).offset(x = 40.dp))
                }
                val channels = listOf(Triple("#radio-club", "mosaic: bring the analyzer", true),
                    Triple("#trail-maps", "switchback: new switchbacks traced", true), Triple("#night-sky", "cairn: clear skies tonight", false))
                channels.forEachIndexed { at, (name, line, unread) ->
                    val shown = if (frame.still) 1f else part(p, .4f + at * .1f, .5f + at * .1f)
                    if (shown > 0f) DemoRow(palette, name, line, "Today", unread = unread, modifier = Modifier.padding(start = 16.dp)
                        .graphicsLayer { alpha = shown; translationY = (1f - shown) * -10.dp.toPx() })
                }
                DemoServer(palette, "Lakeside Commons", MessageProtocol.MATRIX, "2 rooms · muted", 0, 0f, muted = "Muted until 6:00 PM")
            }
        }
    }
}

// "Stay organized": the filter menu at the top corner picks Unread, and a chat is muted for a few hours.

private val organizedCaptions = listOf(
    "Filters · top corner of the inbox, like Unread",
    "Mute for a while · the bell shows until when",
)

@Composable
internal fun StayOrganizedShowcase(appearance: Appearance) {
    val palette = remember(appearance) { Palette(appearance) }
    val scheme = MaterialTheme.colorScheme
    DemoCarousel(appearance, organizedCaptions, stills = listOf(1f, 1f), height = 300.dp, millis = 3400) { index, frame ->
        val p = frame.progress
        if (index == 0) {
            val filtered = frame.still || p >= .62f
            val menu = if (frame.still) 0f else part(p, .24f, .32f) * (1f - part(p, .6f, .66f))
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DrawGlyph(Glyph.Settings, palette.inboxAction, Modifier.size(20.dp))
                    Spacer(Modifier.weight(1f))
                    DrawGlyph(Glyph.Filter, palette.inboxAction, Modifier.size(20.dp))
                }
                Text(if (filtered) "Unread" else "Messages", color = palette.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                val rows = listOf(Triple("Maya Chen", "Coffee at ten?", true), Triple("Avery Brooks", "Yes, let’s meet at the park.", false),
                    Triple("Book Swap", "Leah: three new paperbacks", true), Triple("Leah Morgan", "I’ll update the signup form.", false))
                val leave = if (frame.still) 1f else part(p, .62f, .74f)
                for ((name, line, unread) in rows) {
                    val fade = if (unread || !filtered) 1f else 1f - leave
                    if (fade > 0f) DemoRow(palette, name, line, if (unread) "9:41" else "Mon", unread,
                        photo = if (name == "Maya Chen") R.drawable.sample_maya else 0,
                        modifier = Modifier.graphicsLayer { alpha = fade; scaleY = .6f + .4f * fade })
                }
            }
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                Column(Modifier.align(Alignment.TopEnd).padding(top = 26.dp).width(170.dp).pop(menu)
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(vertical = 4.dp)) {
                    listOf("Inbox", "Unread", "Favorites", "Groups", "Direct messages").forEachIndexed { at, label ->
                        val picked = at == 1 && p >= .5f
                        Row(Modifier.fillMaxWidth().background(if (picked) scheme.primary.copy(alpha = .16f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 6.dp)) {
                            Text(label, Modifier.weight(1f), color = scheme.onSurface, fontSize = 14.sp)
                            if (at == 0 && p < .5f || picked) Text("✓", color = palette.inboxAction, fontSize = 14.sp)
                        }
                    }
                }
                Fingertip(touch(frame, .06f, .26f), part(p, .12f, .24f), Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-12).dp))
                Fingertip(touch(frame, .4f, .6f), part(p, .44f, .5f), Modifier.align(Alignment.TopEnd).offset(x = (-90).dp, y = 38.dp))
            }
        } else {
            // Touch and hold → Mute… → 6 hours, and the bell lands on the row with when it ends.
            val menu = if (frame.still) 0f else part(p, .22f, .3f) * (1f - part(p, .44f, .48f))
            val times = if (frame.still) 0f else part(p, .48f, .55f) * (1f - part(p, .7f, .74f))
            val muted = frame.still || p >= .74f
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DemoRow(palette, "Maya Chen", "Coffee at ten?", "9:41", true, photo = R.drawable.sample_maya)
                Box {
                    DemoRow(palette, "Trail Crew", "Trail Alerts: the footpath reopens at noon", "Today", unread = !muted,
                        muted = if (muted) "Muted until 3:40 PM" else "",
                        modifier = Modifier.pop(if (muted && !frame.still) .7f + .3f * part(p, .74f, .82f) else 1f))
                    Fingertip(touch(frame, .04f, .24f), part(p, .06f, .22f), Modifier.align(Alignment.CenterStart).offset(x = 120.dp))
                }
                DemoRow(palette, "Jordan Ellis", "The parking pass is on the dash", "Yesterday", photo = R.drawable.sample_jordan)
                if (muted) Text("Muted until 3:40 PM · it unmutes by itself", Modifier.padding(start = 8.dp)
                    .graphicsLayer { alpha = if (frame.still) 1f else part(p, .78f, .86f) }, color = palette.muted, fontSize = 12.sp)
            }
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                Column(Modifier.padding(start = 80.dp, top = 96.dp).width(160.dp).pop(menu)
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(14.dp)).padding(vertical = 4.dp)) {
                    listOf("Pin", "Mute…", "Archive").forEachIndexed { at, label ->
                        Text(label, Modifier.fillMaxWidth().background(if (at == 1 && p >= .36f) scheme.primary.copy(alpha = .16f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 7.dp), color = scheme.onSurface, fontSize = 14.sp)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().pop(times)
                    .background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Mute Trail Crew", color = scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in Mutes.CHOICES.take(4)) {
                            val picked = choice.second == 6 * Mutes.HOUR && p >= .62f
                            Text(choice.first, Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (picked) scheme.primary else scheme.surface)
                                .padding(horizontal = 8.dp, vertical = 5.dp), color = if (picked) scheme.onPrimary else scheme.onSurface,
                                fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// "Two tabs": a contact made in the Phone tab, saved to the phone's own contacts.

private val contactCaptions = listOf(
    "New contact · from the Phone tab",
    "Saved to your phone’s contacts, for every app",
)

@Composable
internal fun NewContactShowcase(appearance: Appearance) {
    val palette = remember(appearance) { Palette(appearance) }
    val scheme = MaterialTheme.colorScheme
    DemoCarousel(appearance, contactCaptions, stills = listOf(1f, 1f), height = 250.dp, millis = 3200) { index, frame ->
        val p = frame.progress
        if (index == 0) {
            // The name and number type themselves in, then Save.
            val name = "Riley Park"
            val number = "(415) 555-0142"
            val typedName = if (frame.still) name.length else (name.length * part(p, .08f, .34f)).toInt()
            val typedNumber = if (frame.still) number.length else (number.length * part(p, .38f, .62f)).toInt()
            val saved = frame.still || p >= .8f
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("New contact", Modifier.weight(1f), color = palette.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Box {
                        Text(if (saved) "Saved ✓" else "Save", Modifier.clip(RoundedCornerShape(14.dp))
                            .background(Color(MessageStyle.filledControl(palette.inboxAction.toArgb())))
                            .padding(horizontal = 14.dp, vertical = 6.dp), color = Color(MessageStyle.filledControlInk(palette.inboxAction.toArgb())),
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Fingertip(touch(frame, .66f, .82f), part(p, .7f, .8f), Modifier.align(Alignment.Center))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Avatar(initialsOf(name.take(typedName).ifEmpty { "?" }), name, 56)
                }
                for ((label, value) in listOf("Name" to name.take(typedName), "Phone" to number.take(typedNumber))) {
                    Column(Modifier.fillMaxWidth().background(palette.wash, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(label, color = palette.muted, fontSize = 11.sp)
                        Text(value.ifEmpty { " " }, color = palette.ink, fontSize = 15.sp)
                    }
                }
            }
        } else {
            // The new contact lands in Contacts, and the phone's own Contacts app has it too.
            val landed = if (frame.still) 1f else part(p, .15f, .3f)
            val shared = if (frame.still) 1f else part(p, .5f, .62f)
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Contacts", color = palette.ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                DemoRow(palette, "Maya Chen", "(415) 555-0118", "★", photo = R.drawable.sample_maya)
                DemoRow(palette, "Riley Park", "(415) 555-0142", "New", modifier = Modifier.pop(landed)
                    .border(2.dp, palette.inboxAction.copy(alpha = .6f * (1f - shared * .6f)), RoundedCornerShape(14.dp)))
                DemoRow(palette, "Sam Rivera", "(212) 555-0163", "")
                Row(Modifier.fillMaxWidth().pop(shared).background(scheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HandsetGlyph(palette.inboxAction, Modifier.size(18.dp))
                    Text("Also in your phone’s Contacts app", Modifier.padding(start = 8.dp), color = scheme.onSurface, fontSize = 13.sp)
                }
            }
        }
    }
}

// "Disappearing messages": changing a chat's setting posts the same outlined note a real chat shows.

private val noticeCaptions = listOf(
    "Change the timer · the chat gets a short note",
    "Turn on encryption · they’re told too",
)

@Composable
internal fun SettingNoticeShowcase(appearance: Appearance) {
    val palette = remember(appearance) { Palette(appearance) }
    val scheme = MaterialTheme.colorScheme
    val tint = Color(appearance.accent)
    val ink = if (appearance.dark) tint else accessibleBubbleFill(tint)
    DemoCarousel(appearance, noticeCaptions, stills = listOf(1f, 1f), height = 230.dp, millis = 3000) { index, frame ->
        val p = frame.progress
        val menu = if (frame.still) 0f else part(p, .12f, .2f) * (1f - part(p, .42f, .48f))
        val note = if (frame.still) 1f else part(p, .5f, .62f)
        val text = if (index == 0) timerNoticeText(86_400) else encryptionNoticeText(true)
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            DemoBubble(appearance, false) { color -> BubbleText("Did you want the window seat?", color, appearance) }
            Box(Modifier.align(Alignment.End)) {
                DemoBubble(appearance, true) { color -> BubbleText("That would be great.", color, appearance) }
            }
            Text(text, Modifier.align(Alignment.CenterHorizontally).pop(note).border(1.dp, ink.copy(alpha = .7f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp), color = ink, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.align(Alignment.TopEnd).pop(menu).background(scheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (index == 0) "Disappearing messages · 1 day" else "Encryption · On", color = scheme.onSurface, fontSize = 13.sp)
                Text("  ✓", color = palette.inboxAction, fontSize = 13.sp)
            }
            Fingertip(touch(frame, .22f, .44f), part(p, .28f, .4f), Modifier.align(Alignment.TopEnd).offset(x = (-40).dp, y = 2.dp))
        }
    }
}

// "Sounds and notifications": a new message arrives as a notification; a muted chat stays quiet.

private val notificationCaptions = listOf(
    "New messages arrive as notifications",
    "Muted chats stay quiet until the mute ends",
)

/** A notification as it drops in from the top of the screen. */
@Composable
private fun DemoBanner(palette: Palette, title: String, line: String, shown: Float) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().graphicsLayer { alpha = shown; translationY = (1f - shown) * -40.dp.toPx() }
        .background(scheme.surfaceContainerHigh, RoundedCornerShape(18.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).background(Color(MessageStyle.filledControl(palette.inboxAction.toArgb())), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center) {
            Text("F", color = Color(MessageStyle.filledControlInk(palette.inboxAction.toArgb())), fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row {
                Text(title, Modifier.weight(1f), color = scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                Text("now", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(line, color = scheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun NotificationsShowcase(appearance: Appearance) {
    val palette = remember(appearance) { Palette(appearance) }
    val scheme = MaterialTheme.colorScheme
    DemoCarousel(appearance, notificationCaptions, stills = listOf(1f, 1f), height = 236.dp, millis = 3000) { index, frame ->
        val p = frame.progress
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("9:41", Modifier.align(Alignment.CenterHorizontally), color = palette.ink, fontSize = 34.sp, fontWeight = FontWeight.Light)
            if (index == 0) {
                DemoBanner(palette, "Maya Chen", "Coffee at ten?", if (frame.still) 1f else part(p, .1f, .24f))
                DemoBanner(palette, "Weekend Plans", "Avery: I booked the tables by the willow tree.", if (frame.still) 1f else part(p, .5f, .64f))
            } else {
                // The message still arrives in the chat; only the banner and sound wait.
                val arrived = frame.still || p >= .35f
                DemoRow(palette, "Trail Crew", if (arrived) "Trail Alerts: footpath reopens at noon" else "Great, I’ll bring the water.",
                    if (arrived) "now" else "Today", unread = arrived, muted = "Muted until 3:40 PM")
                Row(Modifier.align(Alignment.CenterHorizontally).graphicsLayer { alpha = if (frame.still) 1f else part(p, .45f, .58f) }
                    .border(1.dp, palette.edge, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DrawGlyph(Glyph.Muted, palette.muted, Modifier.size(14.dp))
                    Text("  No sound or banner until 3:40 PM", color = palette.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

// "On a computer too": pairing with FluidMessages Web, then choosing what to keep when both changed a setting.

private val webCaptions = listOf(
    "Link FluidMessages Web with this phone",
    "Both changed something? Choose what to keep",
)

@Composable
internal fun LinkWebShowcase(appearance: Appearance) {
    val palette = remember(appearance) { Palette(appearance) }
    val scheme = MaterialTheme.colorScheme
    DemoCarousel(appearance, webCaptions, stills = listOf(1f, 1f), height = 250.dp, millis = 3400) { index, frame ->
        val p = frame.progress
        if (index == 0) {
            val travel = if (frame.still) 1f else part(p, .15f, .65f)
            val linked = frame.still || p >= .68f
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val stroke = 3.dp.toPx()
                    val ink = scheme.onSurfaceVariant
                    // The phone on the left, the laptop on the right.
                    drawRoundRect(ink, Offset(size.width * .06f, size.height * .1f), androidx.compose.ui.geometry.Size(size.height * .45f, size.height * .8f),
                        androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = Stroke(stroke))
                    val laptop = size.width * .58f
                    drawRoundRect(ink, Offset(laptop, size.height * .14f), androidx.compose.ui.geometry.Size(size.width * .34f, size.height * .56f),
                        androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = Stroke(stroke))
                    drawLine(ink, Offset(laptop - size.width * .04f, size.height * .82f), Offset(laptop + size.width * .38f, size.height * .82f), stroke * 1.3f)
                    // Messages travel along the link, both ways.
                    val from = size.width * .06f + size.height * .45f + 8.dp.toPx()
                    val to = laptop - 8.dp.toPx()
                    for (dot in 0 until 5) {
                        val x = from + (to - from) * dot / 4f
                        val lit = linked || dot / 4f <= travel
                        drawCircle(if (lit) palette.inboxAction else scheme.outlineVariant, 4.dp.toPx(), Offset(x, size.height * .45f))
                    }
                    drawRoundRect(palette.inboxAction, Offset(laptop + size.width * .04f, size.height * .24f), androidx.compose.ui.geometry.Size(size.width * .14f, size.height * .1f),
                        androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                    drawRoundRect(scheme.outlineVariant, Offset(laptop + size.width * .14f, size.height * .42f), androidx.compose.ui.geometry.Size(size.width * .14f, size.height * .1f),
                        androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                }
                Text(if (linked) "Linked ✓ · texts sync both ways" else "Linking…", Modifier.pop(if (linked && !frame.still) part(p, .68f, .8f) else 1f),
                    color = if (linked) palette.inboxAction else palette.muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        } else {
            // One setting changed on both: tap the side to keep, then Keep these and sync.
            val phone = frame.still || p >= .4f
            val synced = frame.still || p >= .82f
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose what to keep", color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appearance · Color theme", color = scheme.onSurfaceVariant, fontSize = 12.sp)
                    Box {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((side, value) in listOf("This phone" to "Tideline", "FluidMessages Web" to "Ember")) {
                                val chosen = (side == "This phone") == phone
                                Column(Modifier.weight(1f).border(if (chosen) 2.dp else 1.dp, if (chosen) scheme.primary else scheme.outlineVariant,
                                    RoundedCornerShape(12.dp)).padding(8.dp)) {
                                    Text(side, color = scheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                                    Text(value + if (chosen) "  ✓" else "", color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Fingertip(touch(frame, .22f, .42f), part(p, .28f, .4f), Modifier.align(Alignment.CenterStart).offset(x = 40.dp))
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    Text(if (synced) "Synced ✓" else "Keep these and sync", Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color(MessageStyle.filledControl(palette.inboxAction.toArgb()))).padding(vertical = 9.dp),
                        color = Color(MessageStyle.filledControlInk(palette.inboxAction.toArgb())), fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Fingertip(touch(frame, .62f, .84f), part(p, .68f, .8f), Modifier.align(Alignment.Center))
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
    MessageProtocol.NEARBY to ServiceText("Near", "Nearby",
        "Message FluidMessages friends close by, over Bluetooth or the same Wi-Fi, with no internet or cell signal, like in a blackout. Add each other in person first."),
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
        "A link anyone can tap to join, from any app or browser. Pick Zoom, Jitsi Meet, Google Meet, or Teams each time you send one. " +
            "The meeting service can access the call."),
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
internal fun StatusLabel(text: String) {
    Text(text, Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}

/** One card per chat service. Texts follow the default messages app step; the internet services have their on/off switch. */
@Composable
internal fun ChatServiceCards(prefs: SharedPreferences, appearance: Appearance) {
    val texting = TextingGate.showsTexts(LocalContext.current, prefs)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((protocol, text) in chatServiceTexts) {
            ServiceCard(text, protocol.tint(appearance), chatEncryption(protocol)) {
                when (protocol) {
                    MessageProtocol.SMS -> StatusLabel(if (texting) "Ready" else "Off")
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
