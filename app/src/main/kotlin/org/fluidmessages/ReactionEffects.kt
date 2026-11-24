package org.fluidmessages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// Reaction animations: each built-in reaction (the SmsReaction set) plays its own short effect around the bubble.
// Custom emoji reactions keep the small pop in the reaction pill. The web companion draws the same ones in cards.js.

internal enum class ReactionAnimation(val millis: Int) {
    HEARTS(1400), THUMB_POP(1100), SLOW_DROP(1400), HAHA(1300), EMPHASIS(1100), TUMBLE(1300)
}

internal val SmsReaction.animation: ReactionAnimation
    get() = when (this) {
        SmsReaction.LIKE -> ReactionAnimation.THUMB_POP
        SmsReaction.LOVE -> ReactionAnimation.HEARTS
        SmsReaction.DISLIKE -> ReactionAnimation.SLOW_DROP
        SmsReaction.LAUGH -> ReactionAnimation.HAHA
        SmsReaction.EMPHASIZE -> ReactionAnimation.EMPHASIS
        SmsReaction.QUESTION -> ReactionAnimation.TUMBLE
    }

private fun bare(symbol: String) = symbol.trim().replace("️", "").replace("︎", "")

/** The animation for a built-in reaction, or null for a custom emoji. Variation selectors don't matter (❤ is ❤️). */
internal fun reactionAnimation(symbol: String): ReactionAnimation? {
    val wanted = bare(symbol)
    if (wanted.isEmpty()) return null
    return SmsReaction.entries.firstOrNull { bare(it.symbol) == wanted }?.animation
}

/**
 * The reaction that [after] adds to [before], or null when nothing was added (same list, a removal, or a reorder).
 * Counts repeats, so a second person giving the same emoji in a group still counts as new. A change from one
 * reaction to another returns the new one. The latest addition wins when several arrive at once.
 */
internal fun addedReaction(before: List<String>, after: List<String>): String? {
    val left = before.groupingBy { it }.eachCount().toMutableMap()
    var added: String? = null
    for (reaction in after) {
        val count = left[reaction] ?: 0
        if (count > 0) left[reaction] = count - 1 else added = reaction
    }
    return added
}

/** How the reacted bubble itself moves. Offsets are in dp; rotation in degrees. */
internal data class ReactionMotion(val scale: Float = 1f, val rotation: Float = 0f, val dx: Float = 0f, val dy: Float = 0f)

internal fun reactionBubbleMotion(animation: ReactionAnimation?, progress: Float): ReactionMotion {
    if (animation == null || progress <= 0f || progress >= 1f) return ReactionMotion()
    val t = progress
    val decay = 1 - t
    val pi = PI.toFloat()
    return when (animation) {
        // Two heartbeats.
        ReactionAnimation.HEARTS -> ReactionMotion(scale = 1f + .07f * maxOf(0f, sin(t * 4 * pi)) * decay)
        // A springy bounce.
        ReactionAnimation.THUMB_POP -> ReactionMotion(scale = 1f + .09f * sin(t * 3 * pi) * decay)
        // Sinks a little and settles back.
        ReactionAnimation.SLOW_DROP -> ReactionMotion(scale = 1f - .03f * sin(t * pi), dy = 6f * sin(t * pi))
        // Giggles side to side.
        ReactionAnimation.HAHA -> ReactionMotion(rotation = 4f * sin(t * 8 * pi) * decay)
        // A firm shake.
        ReactionAnimation.EMPHASIS -> ReactionMotion(dx = 7f * sin(t * 10 * pi) * decay)
        // Tilts its head, puzzled, then straightens.
        ReactionAnimation.TUMBLE -> ReactionMotion(rotation = -7f * sin(t * pi))
    }
}

/** Off with Reaction animations off, with motion speed at 0, or when the system removes animations. */
internal fun reactionAnimationsOn(context: android.content.Context, speed: Int): Boolean {
    if (speed <= 0) return false
    if (!FunSettings.reactions(context.getSharedPreferences("fluid", android.content.Context.MODE_PRIVATE))) return false
    val scale = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return scale > 0f
}

/** One play of a reaction animation, anchored at the bubble's top corner in root coordinates. */
internal data class ReactionShow(val animation: ReactionAnimation, val anchor: Offset, val key: Long)

/** Plays a reaction animation over the conversation. */
internal val LocalReactionEffect = staticCompositionLocalOf<(ReactionAnimation, Offset) -> Unit> { { _, _ -> } }

private fun easeOut(t: Float) = 1 - (1 - t) * (1 - t) * (1 - t)
private fun easeInOut(t: Float) = if (t < .5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2
/** Overshoots past 1 and settles, like a spring. */
private fun springPop(t: Float) = 1 - exp(-6f * t) * cos(12f * t)
private fun fadeOut(t: Float, from: Float) = if (t < from) 1f else ((1 - t) / (1 - from)).coerceIn(0f, 1f)

private val hearts = listOf(Color(0xFFFF2D55), Color(0xFFFF375F), Color(0xFFE0245E))
// White text on a shaded orange, so "HA" stays readable on any wallpaper.
private val laughFill = Color(0xFFC2410C)
private val likeRing = Color(0xFF0A84FF)

/** Hearts, a thumb, "haha", exclamation marks, or question marks, drawn over everything for about a second. Ignores touches. */
@Composable
internal fun ReactionEffectOverlay(show: ReactionShow?, done: () -> Unit) {
    if (show == null) return
    val progress = remember(show.key) { Animatable(0f) }
    LaunchedEffect(show.key) { progress.animateTo(1f, tween(show.animation.millis, easing = LinearEasing)); done() }
    val measurer = rememberTextMeasurer()
    val glyphs = remember(measurer) {
        listOf("👍", "👎", "😆", "❗", "‼️", "❓").associateWith { measurer.measure(it, TextStyle(fontSize = 40.sp)) }
    }
    val ha = remember(measurer) { measurer.measure("HA", TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)) }
    val pieces = remember(show.key) { val random = java.util.Random(show.key); List(14) { FloatArray(6) { random.nextFloat() } } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val t = progress.value
        val at = show.anchor - origin
        val dp = density
        when (show.animation) {
            ReactionAnimation.HEARTS -> pieces.forEachIndexed { index, p ->
                val delay = p[0] * .25f
                val local = ((t - delay) / (1 - delay)).coerceIn(0f, 1f)
                if (local <= 0f) return@forEachIndexed
                val angle = (-90f + (p[1] - .5f) * 130f) * PI.toFloat() / 180f
                val burst = easeOut(minOf(1f, local / .35f)) * (50f + p[2] * 60f) * dp
                val rise = local * (160f + p[3] * 220f) * dp
                val x = at.x + cos(angle) * burst + sin(local * 6f + p[4] * 6f) * 10f * dp
                val y = at.y + sin(angle) * burst - rise
                val size = (12f + p[5] * 14f) * dp * minOf(1f, local / .15f)
                heart(hearts[index % hearts.size].copy(alpha = fadeOut(local, .7f)), Offset(x, y), size)
            }
            ReactionAnimation.THUMB_POP -> {
                val ring = easeOut(t) * 70f * dp
                val fade = 1 - t
                drawCircle(likeRing.copy(alpha = fade * .8f), ring, at, style = Stroke(width = maxOf(1f, 5f * dp * fade)))
                for (spark in 0 until 8) {
                    val angle = spark / 8f * 2 * PI.toFloat()
                    drawCircle(likeRing.copy(alpha = fade), 3f * dp * fade + 1f, at + Offset(cos(angle), sin(angle)) * ring * 1.18f)
                }
                glyph(glyphs.getValue("👍"), at + Offset(0f, (-44f - t * 10f) * dp), 54f * dp * springPop(minOf(1f, t / .7f)), 0f, fadeOut(t, .75f))
            }
            ReactionAnimation.SLOW_DROP -> {
                val sway = sin(t * 2 * PI.toFloat())
                val alpha = minOf(1f, t / .15f) * fadeOut(t, .65f)
                glyph(glyphs.getValue("👎"), at + Offset(sway * 8f * dp, (-60f + easeInOut(t) * 140f) * dp), 46f * dp, sway * 8f, alpha)
            }
            ReactionAnimation.HAHA -> for (index in 0 until 7) {
                val p = pieces[index]
                val local = ((t - index * .05f) / .8f).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val angle = (-160f + index / 6f * 140f + (p[0] - .5f) * 12f) * PI.toFloat() / 180f
                val reach = easeOut(local) * (70f + p[1] * 70f) * dp
                val center = at + Offset(cos(angle) * reach, sin(angle) * reach - local * 30f * dp)
                val wiggle = sin(t * 30f + index) * 14f
                val grow = minOf(1f, local / .2f)
                val alpha = fadeOut(local, .7f)
                if (index % 2 == 0) withTransform({ translate(center.x, center.y); rotate(wiggle, Offset.Zero); scale(grow, grow, Offset.Zero) }) {
                    val w = ha.size.width + 14f * dp
                    val h = ha.size.height + 6f * dp
                    drawRoundRect(laughFill.copy(alpha = alpha), Offset(-w / 2, -h / 2), Size(w, h), CornerRadius(h / 2))
                    drawText(ha, topLeft = Offset(-ha.size.width / 2f, -ha.size.height / 2f), alpha = alpha)
                } else glyph(glyphs.getValue("😆"), center, (24f + p[2] * 10f) * dp * grow, wiggle, alpha)
            }
            ReactionAnimation.EMPHASIS -> for (index in 0 until 8) {
                val p = pieces[index]
                val local = ((t - p[0] * .15f) / .85f).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val degrees = -180f + index / 7f * 180f + (p[1] - .5f) * 14f
                val angle = degrees * PI.toFloat() / 180f
                val reach = springPop(minOf(1f, local / .6f)) * (55f + p[2] * 45f) * dp
                glyph(glyphs.getValue(if (index % 3 == 0) "‼️" else "❗"), at + Offset(cos(angle), sin(angle)) * reach,
                    (18f + p[3] * 12f) * dp, degrees + 90f, fadeOut(local, .7f))
            }
            ReactionAnimation.TUMBLE -> for (index in 0 until 6) {
                val p = pieces[index]
                val local = ((t - index * .04f) / .8f).coerceIn(0f, 1f)
                if (local <= 0f) continue
                val vx = (p[0] - .5f) * 220f
                val vy = -(220f + p[1] * 120f)
                val x = at.x + vx * local * dp
                val y = at.y + (vy * local + .5f * 420f * local * local) * dp
                val turn = local * (360f + p[2] * 360f) * (if (p[4] < .5f) -1 else 1)
                glyph(glyphs.getValue("❓"), Offset(x, y), (20f + p[3] * 12f) * dp, turn, fadeOut(local, .7f))
            }
        }
    }
}

/** Draws a measured glyph centered on [at], [size] pixels tall. */
private fun DrawScope.glyph(layout: TextLayoutResult, at: Offset, size: Float, rotation: Float, alpha: Float) {
    if (size <= 0f || alpha <= 0f) return
    val scale = size / layout.size.height
    withTransform({ translate(at.x, at.y); rotate(rotation, Offset.Zero); scale(scale, scale, Offset.Zero) }) {
        drawText(layout, topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f), alpha = alpha.coerceIn(0f, 1f))
    }
}
