package org.fluidmessages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Message extras that stay plain text underneath, so they read fine in any app:
 * formatting and spoilers, edits, effects, dice and other random picks, contact cards, and game links to other sites.
 */

// Formatting: *bold*, _italic_, ~strike~, `code`, ```code blocks```, and ||spoilers||, the way WhatsApp and Discord write them.

private val formatPattern = Regex(
    """```\n?([\s\S]+?)```|\|\|([\s\S]+?)\|\||`([^`\n]+)`|(?<![\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\w*])""" +
        """|(?<![\w_])_(?=\S)([^_\n]+?)(?<=\S)_(?![\w_])|(?<![\w~])~(?=\S)([^~\n]+?)(?<=\S)~(?![\w~])""")
private val urlPattern = Regex("""https?://\S+""")
private val spoilerPattern = Regex("""\|\|([\s\S]+?)\|\|""")

internal fun hasFormatting(text: String) = text.length <= 4_000 && text.any { it == '*' || it == '_' || it == '~' || it == '`' || it == '|' } &&
    formatPattern.containsMatchIn(text)

/** The message with formatting applied. Spoilers are covered until tapped; [revealed] holds the ones shown. */
internal fun formatMessage(text: String, foreground: Color, revealed: Set<Int>, reveal: (Int) -> Unit): AnnotatedString = buildAnnotatedString {
    var spoilers = 0
    fun AnnotatedString.Builder.add(part: String, depth: Int) {
        val links = urlPattern.findAll(part).map { it.range }.toList()
        var at = 0
        for (match in formatPattern.findAll(part)) {
            // Marks inside a web address (like _ in a link) are part of the address.
            if (links.any { it.first <= match.range.last && match.range.first <= it.last }) continue
            if (match.range.first < at) continue
            append(part.substring(at, match.range.first))
            val groups = match.groupValues
            when {
                groups[1].isNotEmpty() || groups[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                    background = foreground.copy(alpha = .14f))) { append(groups[1].ifEmpty { groups[3] }.removeSuffix("\n")) }
                groups[2].isNotEmpty() -> {
                    val index = spoilers++
                    if (index in revealed) withStyle(SpanStyle(background = foreground.copy(alpha = .14f))) { add(groups[2], depth + 1) }
                    else withLink(LinkAnnotation.Clickable("spoiler$index") { reveal(index) }) {
                        withStyle(SpanStyle(color = Color.Transparent, background = foreground.copy(alpha = .5f))) {
                            // Covered text keeps its length, so the block looks like the words it hides.
                            append(groups[2].replace(Regex("""[^\s]"""), "█"))
                        }
                    }
                }
                groups[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { if (depth < 4) add(groups[4], depth + 1) else append(groups[4]) }
                groups[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { if (depth < 4) add(groups[5], depth + 1) else append(groups[5]) }
                else -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { if (depth < 4) add(groups[6], depth + 1) else append(groups[6]) }
            }
            at = match.range.last + 1
        }
        append(part.substring(at))
    }
    add(text, 0)
}

/** For the inbox and notifications: spoilers stay hidden and formatting marks are dropped. */
internal fun plainPreview(text: String): String {
    val effect = parseEffect(text)
    // Invisible ink stays hidden in the inbox and notifications too.
    if (effect?.second == MessageEffect.INVISIBLE_INK) return "🫥 Invisible ink message"
    return dropMarks(effect?.first ?: text, 0)
}

/** Formatting marks removed and spoilers covered, leaving web addresses as they are. */
private fun dropMarks(text: String, depth: Int): String {
    if (!hasFormatting(text)) return text
    val links = urlPattern.findAll(text).map { it.range }.toList()
    val out = StringBuilder()
    var at = 0
    for (match in formatPattern.findAll(text)) {
        if (links.any { it.first <= match.range.last && match.range.first <= it.last } || match.range.first < at) continue
        out.append(text, at, match.range.first)
        val groups = match.groupValues
        out.append(when {
            groups[2].isNotEmpty() -> "▒▒▒"
            groups[1].isNotEmpty() || groups[3].isNotEmpty() -> groups[1].ifEmpty { groups[3] }.removeSuffix("\n")
            else -> groups.drop(4).first { it.isNotEmpty() }.let { if (depth < 4) dropMarks(it, depth + 1) else it }
        })
        at = match.range.last + 1
    }
    return out.append(text.substring(at)).toString()
}

/** How long tapped invisible ink stays readable before it covers itself again. */
internal const val INK_REVEAL_MILLIS = 6_000L

// Edits: "✏️ Edited “old” to: new". FluidMessages replaces the old bubble; other apps read it as a correction.

private val editPattern = Regex("""(?s)^✏️ Edited “(.+?)” to: (.+)$""")

internal fun editQuote(text: String) = messageExcerpt(text.replace('”', '"'), 300)

internal fun editText(old: String, new: String) = "✏️ Edited “${editQuote(old)}” to: ${new.trim()}"

internal fun parseEdit(text: String): Pair<String, String>? {
    val match = editPattern.matchEntire(text) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

/** Switches in Settings → Media, GIFs, and fun, so people choose what plays and what shows. */
object FunSettings {
    @JvmStatic fun effects(prefs: android.content.SharedPreferences) = prefs.getBoolean("fun.effects", true)
    @JvmStatic fun reactions(prefs: android.content.SharedPreferences) = prefs.getBoolean("fun.reactions", true)
    @JvmStatic fun games(prefs: android.content.SharedPreferences) = prefs.getBoolean("fun.games", true)
    @JvmStatic fun random(prefs: android.content.SharedPreferences) = prefs.getBoolean("fun.random", true)
    @JvmStatic fun set(prefs: android.content.SharedPreferences, key: String, on: Boolean) { prefs.edit().putBoolean("fun.$key", on).apply() }
}

// Effects: a line at the end, "(sent with Confetti 🎉)". Other apps just show the line.

internal enum class MessageEffect(val label: String, val emoji: String, val screen: Boolean, val detail: String) {
    SLAM("Slam", "💥", false, "Lands with a thud"),
    LOUD("Loud", "📢", false, "Big and shaking"),
    GENTLE("Gentle", "🕊️", false, "Small and soft"),
    INVISIBLE_INK("Invisible ink", "🫥", false, "Hidden until tapped"),
    CONFETTI("Confetti", "🎉", true, "Fills the screen"),
    BALLOONS("Balloons", "🎈", true, "Float up the screen"),
    HEARTS("Hearts", "💕", true, "Hearts everywhere"),
    FIREWORKS("Fireworks", "🎆", true, "Bursts of color");

    companion object { fun of(label: String) = entries.firstOrNull { it.label.equals(label, ignoreCase = true) } }
}

// The tail after the name holds the emoji and, for invisible ink, a note for other apps. It must fit the longest
// tail effectText writes (the ink note is 41 characters), with room for phones that add a variation selector.
private val effectPattern = Regex("""\r?\n?\(sent with (Slam|Loud|Gentle|Invisible ink|Confetti|Balloons|Hearts|Fireworks)\b[^)\n]{0,80}\)\s*$""")

internal const val INK_NOTE = " · hidden until tapped in FluidMessages"

internal fun effectText(text: String, effect: MessageEffect): String {
    val note = if (effect == MessageEffect.INVISIBLE_INK) INK_NOTE else ""
    return text.trimEnd() + "\n(sent with ${effect.label} ${effect.emoji}$note)"
}

/** The words without the effect line, and the effect. */
internal fun parseEffect(text: String): Pair<String, MessageEffect>? {
    val match = effectPattern.find(text) ?: return null
    val words = text.substring(0, match.range.first)
    if (words.isBlank()) return null
    return words to (MessageEffect.of(match.groupValues[1]) ?: return null)
}

internal fun stripEffect(text: String) = parseEffect(text)?.first ?: text

/** Plays a full-screen effect over the conversation. */
internal val LocalScreenEffect = staticCompositionLocalOf<(MessageEffect) -> Unit> { {} }

/** Remembers which effects already played, so each one plays once on its own and again only when you ask. */
internal object EffectMemory {
    private val seen = LinkedHashSet<String>()
    private var loaded = false

    @Synchronized fun firstTime(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences("effects", Context.MODE_PRIVATE)
        if (!loaded) { prefs.getString("played", "")!!.split('\n').filter { it.isNotEmpty() }.forEach { seen.add(it) }; loaded = true }
        if (!seen.add(id)) return false
        while (seen.size > 300) seen.remove(seen.first())
        prefs.edit().putString("played", seen.joinToString("\n")).apply()
        return true
    }
}

/** How a bubble effect moves the bubble as [progress] goes from 0 to 1. */
internal fun bubbleEffectScale(effect: MessageEffect?, progress: Float): Pair<Float, Float> {
    if (effect == null || progress >= 1f) return 1f to 0f
    return when (effect) {
        MessageEffect.SLAM -> (if (progress < .35f) 2.2f - progress / .35f * 1.25f else 1f + sin((progress - .35f) * 18f) * .04f * (1 - progress)) to 0f
        MessageEffect.LOUD -> (if (progress < .25f) 1f + progress * 2.4f else 1.6f - (progress - .25f) / .75f * .6f) to sin(progress * 60f) * 6f * (1 - progress)
        MessageEffect.GENTLE -> (if (progress < .5f) .55f else .55f + (progress - .5f) * .9f) to 0f
        else -> 1f to 0f
    }
}

/**
 * Invisible ink: while [hidden], the words are not drawn and a field of sparkles covers them instead. With [moving]
 * off (reduced motion) the sparkles hold still, but the words stay hidden all the same.
 */
@Composable
internal fun Modifier.invisibleInk(hidden: Boolean, color: Color, moving: Boolean = true): Modifier {
    if (!hidden) return this
    val time = if (moving) {
        val animated by rememberInfiniteTransition(label = "ink").animateFloat(0f, 1f,
            infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "ink")
        animated
    } else .35f
    return this.then(Modifier.drawWithContent {
        // drawContent() is skipped on purpose: the words stay hidden and only the sparkles show.
        val random = java.util.Random(7)
        val count = (size.width * size.height / 90f).toInt().coerceIn(40, 900)
        repeat(count) {
            val x = random.nextFloat() * size.width
            val y = random.nextFloat() * size.height
            val phase = (random.nextFloat() + time) % 1f
            drawCircle(color.copy(alpha = .25f + .6f * sin(phase * PI.toFloat())), 1.2f + random.nextFloat() * 1.6f,
                Offset(x + sin((phase + it) * 6f) * 2f, y + cos((phase + it) * 6f) * 2f))
        }
    })
}

/** Confetti, balloons, hearts, or fireworks drawn over everything for a moment. */
@Composable
internal fun ScreenEffectOverlay(effect: MessageEffect?, key: Long, done: () -> Unit) {
    if (effect == null) return
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.snapTo(0f); progress.animateTo(1f, tween(if (effect == MessageEffect.BALLOONS) 4200 else 3000, easing = LinearEasing)); done() }
    val pieces = remember(key) { val r = java.util.Random(key); List(if (effect == MessageEffect.FIREWORKS) 6 else 70) { FloatArray(6) { r.nextFloat() } } }
    Canvas(Modifier.fillMaxSize()) {
        val t = progress.value
        val fade = if (t > .85f) (1 - t) / .15f else 1f
        val colors = listOf(Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF007AFF), Color(0xFFAF52DE), Color(0xFFFF2D55))
        for ((index, p) in pieces.withIndex()) {
            val color = colors[index % colors.size].copy(alpha = fade)
            when (effect) {
                MessageEffect.CONFETTI -> {
                    val y = (-.1f + (t * (1.1f + p[1])) - p[2] * .3f) * size.height
                    val x = (p[0] + sin((t * 6f + p[3] * 6f)) * .04f) * size.width
                    rotate(t * 720f * (p[4] - .5f), Offset(x, y)) { drawRect(color, Offset(x - 6f, y - 3f), Size(12f + p[5] * 8f, 7f)) }
                }
                MessageEffect.BALLOONS -> if (index < 18) {
                    val x = (.05f + p[0] * .9f + sin(t * 5f + p[3] * 6f) * .02f) * size.width
                    val y = size.height * (1.15f + p[1] * .6f) - t * size.height * (1.9f + p[2] * .4f)
                    val radius = 26f + p[5] * 14f
                    drawLine(color.copy(alpha = .5f * fade), Offset(x, y + radius * 1.1f), Offset(x + sin(t * 9f) * 6f, y + radius * 3.2f), 2f)
                    drawOval(color, Offset(x - radius * .85f, y - radius), Size(radius * 1.7f, radius * 2.15f))
                    drawCircle(Color.White.copy(alpha = .35f * fade), radius * .22f, Offset(x - radius * .35f, y - radius * .4f))
                }
                MessageEffect.HEARTS -> if (index < 30) {
                    val x = p[0] * size.width
                    val y = size.height * (1.05f + p[1] * .4f) - t * size.height * (1.2f + p[2] * .5f)
                    heart(Color(0xFFFF2D55).copy(alpha = fade * (.6f + p[3] * .4f)), Offset(x + sin(t * 7f + p[4] * 6f) * 18f, y), 16f + p[5] * 22f)
                }
                MessageEffect.FIREWORKS -> {
                    val start = p[0] * .5f
                    val local = ((t - start) / .45f).coerceIn(0f, 1f)
                    if (t < start || local >= 1f) continue
                    val center = Offset((.15f + p[1] * .7f) * size.width, (.15f + p[2] * .45f) * size.height)
                    val spark = color.copy(alpha = (1 - local) * fade)
                    for (ray in 0 until 28) {
                        val angle = ray / 28f * 2 * PI.toFloat()
                        val reach = local * (120f + p[3] * 140f)
                        drawCircle(spark, 4f * (1 - local) + 1.5f, center + Offset(cos(angle) * reach, sin(angle) * reach + local * local * 60f))
                    }
                }
                else -> {}
            }
        }
    }
}

internal fun DrawScope.heart(color: Color, at: Offset, size: Float) {
    val path = Path().apply {
        moveTo(at.x, at.y + size * .3f)
        cubicTo(at.x - size, at.y - size * .4f, at.x - size * .35f, at.y - size, at.x, at.y - size * .35f)
        cubicTo(at.x + size * .35f, at.y - size, at.x + size, at.y - size * .4f, at.x, at.y + size * .3f)
        close()
    }
    drawPath(path, color)
}

/** Choose an effect for the words in the message box. */
@Composable
internal fun EffectsDialog(dismiss: () -> Unit, send: (MessageEffect) -> Unit) {
    FormPage(onDismissRequest = dismiss, title = { Text("Send with effect") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("On other apps, your message arrives as normal text with a note saying which effect you picked.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (effect in MessageEffect.entries) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).clickable { send(effect) }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(effect.emoji, fontSize = 20.sp)
                Column {
                    Text(effect.label, fontWeight = FontWeight.SemiBold)
                    Text(effect.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

// Dice, coin flips, and random picks. The sender's phone picks; everyone sees the same result as text.

internal sealed class RandomResult {
    data class Dice(val sides: Int, val rolls: List<Int>) : RandomResult()
    data class Coin(val heads: Boolean) : RandomResult()
    data class Pick(val choice: String, val options: List<String>) : RandomResult()
}

private val dicePattern = Regex("""^🎲 Rolled (\d)d(\d{1,3}): (\d{1,3}(?: \+ \d{1,3}){0,8})(?: = \d+)?$""")
private val coinPattern = Regex("""^🪙 Flipped a coin: (Heads|Tails)$""")
private val pickPattern = Regex("""^🎯 Random pick: (.{1,80})\nFrom: (.{1,1600})$""")

internal fun parseRandom(text: String): RandomResult? {
    dicePattern.matchEntire(text)?.let { match ->
        val count = match.groupValues[1].toInt()
        val sides = match.groupValues[2].toInt()
        val rolls = match.groupValues[3].split(" + ").map { it.toInt() }
        if (rolls.size != count || sides < 2 || rolls.any { it !in 1..sides }) return null
        return RandomResult.Dice(sides, rolls)
    }
    coinPattern.matchEntire(text)?.let { return RandomResult.Coin(it.groupValues[1] == "Heads") }
    pickPattern.matchEntire(text)?.let { match ->
        val options = match.groupValues[2].split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        if (match.groupValues[1] !in options) return null
        return RandomResult.Pick(match.groupValues[1], options)
    }
    return null
}

private val secure = SecureRandom()

internal fun rollText(count: Int, sides: Int): String {
    val rolls = List(count.coerceIn(1, 9)) { 1 + secure.nextInt(sides.coerceIn(2, 999)) }
    return "🎲 Rolled ${rolls.size}d$sides: ${rolls.joinToString(" + ")}" + if (rolls.size > 1) " = ${rolls.sum()}" else ""
}

internal fun coinText() = "🪙 Flipped a coin: ${if (secure.nextBoolean()) "Heads" else "Tails"}"

internal fun pickText(options: List<String>): String {
    val clean = options.map { it.replace('\n', ' ').replace(" · ", " - ").trim().take(80) }.filter { it.isNotEmpty() }.distinct().take(20)
    return "🎯 Random pick: ${clean[secure.nextInt(clean.size)]}\nFrom: ${clean.joinToString(" · ")}"
}

private val diceFaces = listOf("⚀", "⚁", "⚂", "⚃", "⚄", "⚅")

@Composable
internal fun RandomCard(result: RandomResult, outgoing: Boolean, foreground: Color, spin: Boolean = true) {
    // A short spin when the card first appears, unless motion is off.
    val turning = remember { Animatable(if (spin) 0f else 1f) }
    LaunchedEffect(Unit) { if (spin) turning.animateTo(1f, tween(650)) }
    Column(Modifier.widthIn(min = 180.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(when (result) { is RandomResult.Dice -> "DICE"; is RandomResult.Coin -> "COIN FLIP"; is RandomResult.Pick -> "RANDOM PICK" },
            color = foreground.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        val turn = (1 - turning.value) * 360f
        when (result) {
            is RandomResult.Dice -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (roll in result.rolls) Box(Modifier.size(46.dp).androidxRotate(turn).clip(RoundedCornerShape(10.dp)).background(foreground.copy(alpha = .16f)),
                        contentAlignment = Alignment.Center) {
                        Text(if (result.sides == 6) diceFaces[roll - 1] else "$roll", color = foreground, fontSize = if (result.sides == 6) 34.sp else 20.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
                Text(if (result.rolls.size > 1) "Total ${result.rolls.sum()} · ${result.rolls.size} d${result.sides}" else "d${result.sides}",
                    color = foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            is RandomResult.Coin -> {
                Box(Modifier.size(64.dp).androidxRotate(turn * 2).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFE0B23A)),
                    contentAlignment = Alignment.Center) { Text(if (result.heads) "H" else "T", color = Color(0xFF5A3E00), fontSize = 28.sp, fontWeight = FontWeight.Black) }
                Text(if (result.heads) "Heads" else "Tails", color = foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            is RandomResult.Pick -> {
                Text(result.choice, color = foreground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("From " + result.options.joinToString(", "), color = foreground.copy(alpha = .8f), fontSize = 12.sp)
            }
        }
        Text(if (outgoing) "Picked at random by your phone" else "Picked at random by their phone", color = foreground.copy(alpha = .7f), fontSize = 11.sp)
    }
}

private fun Modifier.androidxRotate(degrees: Float) = this.then(Modifier.graphicsLayer { rotationZ = degrees })

@Composable
internal fun RandomDialog(dismiss: () -> Unit, post: (String) -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(1) }
    var sides by remember { mutableIntStateOf(6) }
    var options by remember { mutableStateOf("") }
    val choices = options.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    FormPage(onDismissRequest = dismiss, title = { Text("Dice and random") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Dice", "Coin", "Pick").forEachIndexed { index, label ->
                    SegmentedButton(mode == index, { mode = index }, SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                }
            }
            when (mode) {
                0 -> {
                    Text("How many dice")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in 1..6) FilterChip(count == n, { count = n }, label = { Text("$n") })
                    }
                    Text("Sides")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in listOf(4, 6, 8, 10, 12, 20, 100)) FilterChip(sides == n, { sides = n }, label = { Text("d$n") })
                    }
                }
                1 -> Text("Heads or tails, picked at random by your phone.")
                else -> OutlinedTextField(options, { options = it.take(800) }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    label = { Text("Choices, one per line") }, placeholder = { Text("Pizza\nTacos\nSushi") })
            }
            Text("Everyone sees the same result, including people on other apps.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton(enabled = mode != 2 || choices.size >= 2, onClick = {
            post(when (mode) { 0 -> rollText(count, sides); 1 -> coinText(); else -> pickText(choices) }); dismiss()
        }) { Text(when (mode) { 0 -> "Roll"; 1 -> "Flip"; else -> "Pick" }) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

// Contact cards: vCard files shown as a card you can save, text, or call.

internal data class SharedContact(val name: String, val phones: List<String>, val emails: List<String>)

internal fun parseVCard(text: String): SharedContact? {
    val lines = text.replace("\r\n ", "").replace("\n ", "").replace("\r\n\t", "").lines()
    fun value(line: String) = line.substringAfter(':').replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").replace("\\\\", "\\").trim()
    var name = ""
    val phones = mutableListOf<String>()
    val emails = mutableListOf<String>()
    for (line in lines) {
        val key = line.substringBefore(':').substringBefore(';').uppercase()
        when (key) {
            "FN" -> name = value(line)
            "N" -> if (name.isEmpty()) name = value(line).split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
            "TEL" -> value(line).removePrefix("tel:").takeIf { it.isNotEmpty() }?.let { if (it !in phones) phones.add(it) }
            "EMAIL" -> value(line).takeIf { it.contains('@') }?.let { if (it !in emails) emails.add(it) }
        }
    }
    if (name.isEmpty() && phones.isEmpty() && emails.isEmpty()) return null
    return SharedContact(name.ifEmpty { phones.firstOrNull() ?: emails.first() }.take(120), phones.take(6), emails.take(6))
}

@Composable
internal fun ContactCard(attachment: Attachment, foreground: Color, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val contact by produceState<SharedContact?>(null, attachment.uri) {
        value = withContext(Dispatchers.IO) {
            attachment.uri?.let { uri ->
                runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readNBytes(64 * 1024), Charsets.UTF_8) } }.getOrNull()
            }?.let(::parseVCard)
        }
    }
    val person = contact ?: return fallback()
    Column(Modifier.widthIn(min = 220.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.CircleShape).background(foreground.copy(alpha = .18f)),
                contentAlignment = Alignment.Center) { Text(initialsOf(person.name), color = foreground, fontWeight = FontWeight.SemiBold) }
            Column(Modifier.padding(start = 10.dp)) {
                Text("CONTACT", color = foreground.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(person.name, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        for (phone in person.phones) Text("📞 $phone", color = foreground, fontSize = 14.sp)
        for (email in person.emails) Text("✉️ $email", color = foreground, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(onClick = {
                val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.NAME, person.name)
                val phoneKeys = listOf(ContactsContract.Intents.Insert.PHONE, ContactsContract.Intents.Insert.SECONDARY_PHONE, ContactsContract.Intents.Insert.TERTIARY_PHONE)
                val emailKeys = listOf(ContactsContract.Intents.Insert.EMAIL, ContactsContract.Intents.Insert.SECONDARY_EMAIL, ContactsContract.Intents.Insert.TERTIARY_EMAIL)
                person.phones.take(3).forEachIndexed { index, phone -> intent.putExtra(phoneKeys[index], phone) }
                person.emails.take(3).forEachIndexed { index, email -> intent.putExtra(emailKeys[index], email) }
                runCatching { context.startActivity(intent) }
            }) { Text("Add to contacts") }
            person.phones.firstOrNull()?.let { phone ->
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone)))) } }) {
                    Text("Call", color = foreground)
                }
            }
        }
    }
}

// Games on other sites: "🎮 Chess on Lichess · Join my game: https://…".

internal data class GameLink(val game: String, val site: String, val url: String)

private val gameLinkPattern = Regex("""^🎮 (.{1,40}?) on (.{1,40}?) · Join my game: (https://\S{1,500})""")

internal fun parseGameLink(text: String): GameLink? {
    val match = gameLinkPattern.find(text) ?: return null
    return GameLink(match.groupValues[1], match.groupValues[2], match.groupValues[3].trimEnd('.', ',', ')'))
}

internal fun gameLinkText(game: String, site: String, url: String, note: String) =
    "🎮 $game on $site · Join my game: $url\n$note"

@Composable
internal fun GameLinkCard(link: GameLink, outgoing: Boolean, foreground: Color) {
    val context = LocalContext.current
    Column(Modifier.widthIn(min = 210.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(link.game.uppercase(), color = foreground.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(if (outgoing) "You started a game on ${link.site}" else "A game on ${link.site}", color = foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(Uri.parse(link.url).host.orEmpty(), color = foreground.copy(alpha = .75f), fontSize = 12.sp)
        FilledTonalButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } }) {
            Text(if (outgoing) "Open my game" else "Join the game")
        }
    }
}

/** Sites that host each game for people without FluidMessages. */
internal data class GameSite(val name: String, val url: String, val detail: String, val automatic: Boolean = false)

internal fun gameSites(game: String): List<GameSite> = when (game) {
    "Chess" -> listOf(GameSite("Lichess", "https://lichess.org", "Free, no account needed. Makes a game link for you.", automatic = true),
        GameSite("Chess.com", "https://www.chess.com/play/online", "Needs a free account. Start a game with a friend, then paste its link."))
    "Tic-tac-toe" -> listOf(GameSite("Foony", "https://foony.com/games/tic-tac-toe", "Start a private room, then paste its link."),
        GameSite("Papergames", "https://papergames.io/en/tic-tac-toe", "Play with a friend, then paste its link."))
    "Four in a row" -> listOf(GameSite("Foony", "https://foony.com/games/fourinarow", "Start a private room, then paste its link."),
        GameSite("Papergames", "https://papergames.io/en/connect4", "Play with a friend, then paste its link."))
    else -> emptyList()
}

/** A Lichess game anyone can join, no account needed. Returns the link for you and the one to send. */
internal fun lichessGame(white: Boolean): Pair<String, String>? = runCatching {
    val connection = java.net.URL("https://lichess.org/api/challenge/open").openConnection() as javax.net.ssl.HttpsURLConnection
    connection.requestMethod = "POST"
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    connection.setRequestProperty("Accept", "application/json")
    connection.outputStream.use { it.write("days=3&name=FluidMessages".toByteArray()) }
    try {
        if (connection.responseCode !in 200..299) return null
        val json = org.json.JSONObject(connection.inputStream.use { String(it.readNBytes(64 * 1024), Charsets.UTF_8) })
        val mine = json.optString(if (white) "urlWhite" else "urlBlack")
        val theirs = json.optString(if (white) "urlBlack" else "urlWhite")
        if (!mine.startsWith("https://lichess.org/") || !theirs.startsWith("https://lichess.org/")) null else mine to theirs
    } finally { connection.disconnect() }
}.getOrNull()

// Chat setting notices: "⏱️ Disappearing messages are on. New messages disappear after 1 day." and
// "🔒 Messages in this chat are now encrypted with FluidMessages." Plain text for any app; FluidMessages shows an
// outlined note instead of a bubble. server/cards.js parses the same sentences.

internal sealed class ChatNotice {
    /** Disappearing messages; 0 means off. */
    data class Timer(val seconds: Int) : ChatNotice()
    data class Encryption(val on: Boolean) : ChatNotice()
}

private val timerOnPattern = Regex("""^⏱️? Disappearing messages are on\. New messages disappear after (\d{1,4}) (second|minute|hour|day|week)s?\.$""")
private val timerOffPattern = Regex("""^⏱️? Disappearing messages are off\.$""")
private const val ENCRYPTED_NOTICE = "🔒 Messages in this chat are now encrypted with FluidMessages."
private const val UNENCRYPTED_NOTICE = "🔓 Messages in this chat are no longer encrypted with FluidMessages."
private val unitSeconds = mapOf("second" to 1, "minute" to 60, "hour" to 3600, "day" to 86400, "week" to 7 * 86400)

internal fun chatNoticeText(notice: ChatNotice): String = when (notice) {
    is ChatNotice.Timer -> if (notice.seconds <= 0) "⏱️ Disappearing messages are off."
        else "⏱️ Disappearing messages are on. New messages disappear after ${Disappearing.label(notice.seconds)}."
    is ChatNotice.Encryption -> if (notice.on) ENCRYPTED_NOTICE else UNENCRYPTED_NOTICE
}

internal fun timerNoticeText(seconds: Int) = chatNoticeText(ChatNotice.Timer(seconds.coerceAtLeast(0)))

internal fun encryptionNoticeText(on: Boolean) = chatNoticeText(ChatNotice.Encryption(on))

internal fun parseChatNotice(text: String): ChatNotice? {
    val clean = text.trim()
    if (clean == ENCRYPTED_NOTICE) return ChatNotice.Encryption(true)
    if (clean == UNENCRYPTED_NOTICE) return ChatNotice.Encryption(false)
    if (timerOffPattern.matches(clean)) return ChatNotice.Timer(0)
    val match = timerOnPattern.matchEntire(clean) ?: return null
    val seconds = match.groupValues[1].toInt() * unitSeconds.getValue(match.groupValues[2])
    return if (seconds > 0) ChatNotice.Timer(seconds) else null
}

/** Whether a message is a chat setting notice, which is always sent as plain text. */
internal fun isChatNotice(text: String?) = text != null && parseChatNotice(text) != null
