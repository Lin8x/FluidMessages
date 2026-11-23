package org.fluidmessages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Pictures and short tips for the setup guide, so each idea is shown rather than explained in paragraphs. */

internal enum class TipIcon { PHONE, CHAT, PRACTICE, TAG, PEOPLE, LOCK, FINGER, FOLDER, MASK, TIMER, LAPTOP, OPTIONAL }

/** One tip. With an action, it's a button that takes you there. */
internal data class Tip(val icon: TipIcon, val title: String, val line: String, val action: (() -> Unit)? = null)

/** Tips that slide in one after another (all at once when motion is off). */
@Composable
internal fun Tips(tips: List<Tip>, speed: Int) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tips.forEachIndexed { index, tip ->
            var shown by remember(tips) { mutableStateOf(speed == 0) }
            LaunchedEffect(tips) { if (speed > 0) { delay(120L + index * 140L); shown = true } }
            AnimatedVisibility(shown, enter = fadeIn(tween(speed.coerceAtLeast(1) * 2)) +
                slideInVertically(tween(speed.coerceAtLeast(1) * 2)) { it / 3 }) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surfaceContainer)
                    .then(if (tip.action != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { tip.action.invoke() } else Modifier)
                    .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(scheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        TipGlyph(tip.icon, scheme.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(tip.title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Text(tip.line, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    if (tip.action != null) Text("›", color = scheme.primary, fontSize = 26.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun TipGlyph(icon: TipIcon, color: Color) {
    val modifier = Modifier.size(24.dp)
    when (icon) {
        TipIcon.PHONE -> HandsetGlyph(color, modifier)
        TipIcon.LOCK -> LockGlyph(color, open = false, modifier = modifier)
        else -> Canvas(modifier) {
            val stroke = 1.9.dp.toPx()
            val w = size.width
            val h = size.height
            when (icon) {
                TipIcon.CHAT -> {
                    drawRoundRect(color, Offset(w * .08f, h * .14f), Size(w * .84f, h * .6f), androidx.compose.ui.geometry.CornerRadius(w * .3f), style = Stroke(stroke))
                    drawLine(color, Offset(w * .28f, h * .72f), Offset(w * .18f, h * .92f), stroke)
                }
                TipIcon.PRACTICE -> {
                    drawCircle(color, w * .4f, center, style = Stroke(stroke))
                    drawLine(color, Offset(w * .3f, h * .52f), Offset(w * .45f, h * .66f), stroke)
                    drawLine(color, Offset(w * .45f, h * .66f), Offset(w * .72f, h * .36f), stroke)
                }
                TipIcon.TAG -> drawRoundRect(color, Offset(w * .1f, h * .3f), Size(w * .8f, h * .4f), androidx.compose.ui.geometry.CornerRadius(w * .12f), style = Stroke(stroke))
                TipIcon.PEOPLE -> {
                    drawCircle(color, w * .14f, Offset(w * .35f, h * .32f), style = Stroke(stroke))
                    drawCircle(color, w * .14f, Offset(w * .68f, h * .32f), style = Stroke(stroke))
                    drawArc(color, 180f, 180f, false, Offset(w * .12f, h * .55f), Size(w * .46f, h * .5f), style = Stroke(stroke))
                    drawArc(color, 180f, 180f, false, Offset(w * .45f, h * .55f), Size(w * .46f, h * .5f), style = Stroke(stroke))
                }
                TipIcon.FINGER -> for (ring in 1..3) drawArc(color, 200f, 220f, false, Offset(w * (.5f - ring * .13f), h * (.5f - ring * .13f)),
                    Size(w * ring * .26f, h * ring * .26f), style = Stroke(stroke))
                TipIcon.FOLDER -> {
                    drawRoundRect(color, Offset(w * .08f, h * .3f), Size(w * .84f, h * .56f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = Stroke(stroke))
                    drawLine(color, Offset(w * .08f, h * .3f), Offset(w * .4f, h * .16f), stroke)
                }
                TipIcon.MASK -> {
                    drawOval(color, Offset(w * .08f, h * .28f), Size(w * .84f, h * .44f), style = Stroke(stroke))
                    drawCircle(color, w * .08f, Offset(w * .34f, h * .5f))
                    drawCircle(color, w * .08f, Offset(w * .66f, h * .5f))
                }
                TipIcon.TIMER -> {
                    drawCircle(color, w * .38f, Offset(w * .5f, h * .56f), style = Stroke(stroke))
                    drawLine(color, Offset(w * .5f, h * .56f), Offset(w * .5f, h * .34f), stroke)
                    drawLine(color, Offset(w * .4f, h * .08f), Offset(w * .6f, h * .08f), stroke)
                }
                TipIcon.LAPTOP -> {
                    drawRoundRect(color, Offset(w * .16f, h * .2f), Size(w * .68f, h * .46f), androidx.compose.ui.geometry.CornerRadius(w * .05f), style = Stroke(stroke))
                    drawLine(color, Offset(w * .04f, h * .78f), Offset(w * .96f, h * .78f), stroke)
                }
                else -> {
                    drawCircle(color, w * .38f, center, style = Stroke(stroke))
                    drawLine(color, Offset(w * .32f, h * .5f), Offset(w * .68f, h * .5f), stroke)
                }
            }
        }
    }
}

/** A breathing scale for illustrations, still when motion is off. */
@Composable
private fun pulse(speed: Int): Float {
    if (speed == 0) return 1f
    val transition = rememberInfiniteTransition(label = "Pulse")
    val value by transition.animateFloat(.94f, 1.04f, infiniteRepeatable(tween(speed * 6, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Pulse scale")
    return value
}

/** The two tabs, taking turns being selected. */
@Composable
internal fun TabsIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    var phone by remember { mutableStateOf(false) }
    LaunchedEffect(speed) { while (speed > 0) { delay(1600); phone = !phone } }
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (phone) "Calls, contacts, keypad" else "All your chats", color = scheme.onSurfaceVariant, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(18.dp)).padding(vertical = 10.dp)) {
            for (isPhone in listOf(true, false)) {
                val selected = isPhone == phone
                val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
                Column(Modifier.weight(1f).scale(if (selected) 1.08f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isPhone) HandsetGlyph(tint, Modifier.size(26.dp), filled = selected) else TipGlyph(TipIcon.CHAT, tint)
                    Text(if (isPhone) "Phone" else "Messages", color = tint, fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Three chats on three services, each with its tag. */
@Composable
internal fun WaysIllustration(appearance: Appearance) {
    val scheme = MaterialTheme.colorScheme
    val chats = listOf(Triple("Mom", "See you at dinner!", "SMS"), Triple("Elena", "Sent the photos", "Matrix"),
        Triple("Book club", "Next week’s pick?", "Email"))
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chats.forEachIndexed { index, (name, text, tag) ->
            var shown by remember { mutableStateOf(appearance.speed == 0) }
            LaunchedEffect(Unit) { if (appearance.speed > 0) { delay(200L + index * 260L); shown = true } }
            AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it / 2 }) {
                Row(Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(16.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(initialsOf(name), name, 36)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(name, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                        Text(text, color = scheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    Text(tag, Modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        color = scheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

/** The three lock colors, side by side, with what each one means. */
@Composable
internal fun LocksIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    val scale = pulse(speed)
    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((encryption, label) in listOf(Encryption.END_TO_END to "Private", Encryption.IN_TRANSIT to "Partly", Encryption.NONE to "Open")) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(54.dp).scale(if (encryption == Encryption.END_TO_END) scale else 1f)
                    .background(scheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                    SecurityLock(encryption, scheme.surface, size = 28)
                }
                Text(label, color = scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

/** A laptop showing a chat, for FluidMessages Web. */
@Composable
internal fun ComputerIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    val scale = pulse(speed)
    Box(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp, 96.dp).scale(scale)) {
            val stroke = 3.dp.toPx()
            drawRoundRect(scheme.onSurfaceVariant, Offset(size.width * .14f, 0f), Size(size.width * .72f, size.height * .78f),
                androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = Stroke(stroke))
            drawLine(scheme.onSurfaceVariant, Offset(0f, size.height * .92f), Offset(size.width, size.height * .92f), stroke * 1.4f)
            drawRoundRect(scheme.primary, Offset(size.width * .26f, size.height * .18f), Size(size.width * .3f, size.height * .16f),
                androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
            drawRoundRect(scheme.outlineVariant, Offset(size.width * .44f, size.height * .44f), Size(size.width * .32f, size.height * .16f),
                androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
        }
    }
}

/** A chat bubble that fades away while a timer ring runs down, then comes back. */
@Composable
internal fun DisappearingIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    val progress = if (speed == 0) 0.6f else {
        val transition = rememberInfiniteTransition(label = "Timer")
        val value by transition.animateFloat(1f, 0f, infiniteRepeatable(tween(speed * 14, easing = LinearEasing), RepeatMode.Restart),
            label = "Timer progress")
        value
    }
    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Canvas(Modifier.size(64.dp)) {
            val stroke = 6.dp.toPx()
            drawCircle(scheme.outlineVariant, size.minDimension / 2 - stroke, style = Stroke(stroke))
            drawArc(scheme.primary, -90f, 360f * progress, false, Offset(stroke, stroke), Size(size.width - stroke * 2, size.height - stroke * 2),
                style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Meet you at 6", Modifier.background(scheme.primary.copy(alpha = progress.coerceIn(.15f, 1f)), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp), color = scheme.onPrimary.copy(alpha = progress.coerceIn(.25f, 1f)))
            Text("Gone when the timer ends", color = scheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

/** A folder that closes behind a padlock, for the privacy tools page. */
@Composable
internal fun PrivacyToolsIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    var locked by remember { mutableStateOf(speed == 0) }
    LaunchedEffect(speed) { while (speed > 0) { delay(1400); locked = !locked } }
    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        for (icon in listOf(TipIcon.FINGER, TipIcon.FOLDER, TipIcon.MASK)) {
            Box(Modifier.size(58.dp).scale(if (locked && icon == TipIcon.FOLDER) 1.1f else 1f)
                .background(if (locked && icon == TipIcon.FOLDER) scheme.primary else scheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                TipGlyph(icon, if (locked && icon == TipIcon.FOLDER) scheme.onPrimary else scheme.onSurfaceVariant)
            }
        }
    }
}

/** Three ways to call: phone, Matrix video, and a meeting link. */
@Composable
internal fun CallsIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    val scale = pulse(speed)
    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((label, encryption) in listOf("Phone" to Encryption.NONE, "Matrix" to Encryption.END_TO_END, "Link" to Encryption.IN_TRANSIT)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(56.dp).scale(if (label == "Matrix") scale else 1f).background(Color(0xFF34C759), CircleShape),
                    contentAlignment = Alignment.Center) { HandsetGlyph(Color.White, Modifier.size(26.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SecurityLock(encryption, scheme.surfaceContainer, size = 12)
                    Text(label, color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** A message bubble that changes through theme colors, for picking colors. */
@Composable
internal fun ColorsIllustration(appearance: Appearance) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(Color(appearance.accent), Color(0xFF0A84FF), Color(0xFF34C759), Color(0xFFAF52DE), Color(0xFFFF9500), Color(0xFFFF2D55))
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(appearance.speed) { while (appearance.speed > 0) { delay(1100); index = (index + 1) % colors.size } }
    val fill by animateColorAsState(colors[index], tween(appearance.speed.coerceAtLeast(1) * 3), label = "Bubble color")
    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Love this color!", Modifier.background(fill, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
                color = bubbleTextColor(fill))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            colors.forEachIndexed { position, color ->
                Box(Modifier.size(if (position == index) 30.dp else 24.dp).background(color, CircleShape)
                    .border(if (position == index) 3.dp else 0.dp, scheme.onSurface, CircleShape))
            }
        }
    }
}

/** A message that springs in, with sound waves and a buzz, for motion, sound, and haptics. */
@Composable
internal fun MotionIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(speed) { while (speed > 0) { delay(1800); shown = false; delay(350); shown = true } }
    val offset by animateFloatAsState(if (shown) 0f else 60f,
        if (speed == 0) snap() else spring(dampingRatio = .45f, stiffness = 320f), label = "Bubble spring")
    val buzz by (if (speed == 0) remember { mutableFloatStateOf(0f) } else rememberInfiniteTransition(label = "Buzz")
        .animateFloat(-3f, 3f, infiniteRepeatable(tween(70), RepeatMode.Reverse), label = "Buzz offset"))
    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("New message!", Modifier.graphicsLayer { translationY = offset; alpha = 1f - offset / 80f }
                .background(scheme.primary, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 9.dp), color = scheme.onPrimary)
        }
        Canvas(Modifier.size(48.dp)) {
            val stroke = 3.dp.toPx()
            for (ring in 1..3) drawArc(scheme.primary.copy(alpha = if (shown) 1f / ring else .2f), -45f, 90f, false,
                Offset(size.width * (.5f - ring * .16f), size.height * (.5f - ring * .16f)), Size(size.width * ring * .32f, size.height * ring * .32f),
                style = Stroke(stroke))
        }
        Box(Modifier.padding(start = 10.dp).graphicsLayer { translationX = if (shown) buzz else 0f }.size(26.dp, 44.dp)
            .border(3.dp, scheme.onSurfaceVariant, RoundedCornerShape(7.dp)))
    }
}

/** Your phone, sending a locked backup file to a folder or the cloud. */
@Composable
internal fun BackupIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    val travel = if (speed == 0) .5f else {
        val transition = rememberInfiniteTransition(label = "Backup")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(speed * 8, easing = LinearEasing)), label = "Backup travel")
        value
    }
    Box(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(22.dp)) {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val stroke = 3.dp.toPx()
            val ink = scheme.onSurfaceVariant
            // Phone
            drawRoundRect(ink, Offset(0f, size.height * .1f), Size(size.height * .5f, size.height * .8f),
                androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = Stroke(stroke))
            // Cloud
            val cloudX = size.width - size.height * 1.1f
            drawCircle(ink, size.height * .22f, Offset(cloudX + size.height * .35f, size.height * .5f), style = Stroke(stroke))
            drawCircle(ink, size.height * .28f, Offset(cloudX + size.height * .7f, size.height * .42f), style = Stroke(stroke))
            drawCircle(ink, size.height * .2f, Offset(cloudX + size.height * .95f, size.height * .55f), style = Stroke(stroke))
            // The file moving across, with its lock
            val start = size.height * .7f
            val end = cloudX
            val x = start + (end - start - size.height * .3f) * travel
            drawRoundRect(scheme.primary, Offset(x, size.height * .3f), Size(size.height * .3f, size.height * .38f),
                androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawCircle(scheme.onPrimary, size.height * .05f, Offset(x + size.height * .15f, size.height * .5f))
        }
    }
}

/** The services you can turn on, popping in, with the ones that are on highlighted. */
@Composable
internal fun ServicesIllustration(speed: Int, on: Set<String>) {
    val scheme = MaterialTheme.colorScheme
    val names = listOf("SMS", "Matrix", "XMPP", "Email", "IRC")
    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(vertical = 20.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        names.forEachIndexed { index, name ->
            var shown by remember { mutableStateOf(speed == 0) }
            LaunchedEffect(Unit) { if (speed > 0) { delay(150L + index * 160L); shown = true } }
            val scale by animateFloatAsState(if (shown) 1f else 0f, if (speed == 0) snap() else spring(dampingRatio = .5f), label = "Pop")
            val active = name in on
            Text(name, Modifier.scale(scale).background(if (active) scheme.primary else scheme.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp), color = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A check mark drawing itself, with a little burst. */
@Composable
internal fun ReadyIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    val progress = remember { Animatable(if (speed == 0) 1f else 0f) }
    LaunchedEffect(Unit) { if (speed > 0) progress.animateTo(1f, tween(speed * 4, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(110.dp)) {
            val value = progress.value
            drawCircle(scheme.primary, size.minDimension * .4f * value.coerceAtLeast(.001f))
            for (ray in 0 until 8) {
                val angle = Math.toRadians(ray * 45.0)
                val inner = size.minDimension * .44f
                val outer = inner + size.minDimension * .08f * value
                drawLine(scheme.primary.copy(alpha = value), Offset(center.x + inner * Math.cos(angle).toFloat(), center.y + inner * Math.sin(angle).toFloat()),
                    Offset(center.x + outer * Math.cos(angle).toFloat(), center.y + outer * Math.sin(angle).toFloat()), 4.dp.toPx(),
                    androidx.compose.ui.graphics.StrokeCap.Round)
            }
            val check = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .32f, size.height * .52f)
                lineTo(size.width * .45f, size.height * .64f)
                lineTo(size.width * .7f, size.height * .38f)
            }
            val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(check, false) }
            val partial = androidx.compose.ui.graphics.Path()
            measure.getSegment(0f, measure.length * value, partial, true)
            drawPath(partial, scheme.onPrimary, style = Stroke(7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
