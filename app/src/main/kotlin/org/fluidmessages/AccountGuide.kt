package org.fluidmessages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full page shown before the form when adding an account: what the service is, where to get an account if you
 * don't have one (tap the example to have it filled in), what happens next, and Get started. Written for someone who
 * has never heard of the service. The words live in ServiceGuides, shared with FluidMessages Web.
 *
 * [start] gets the fields to fill in. In Add server, [pickServer] is set: the main button is Pick a server (the list of
 * public servers), and tapping the example goes straight to the form with it filled in.
 */
@Composable
internal fun AccountGuide(protocol: MessageProtocol, start: (Map<String, String>) -> Unit, skip: () -> Unit, back: () -> Unit,
                          pickServer: (() -> Unit)? = null) {
    val guide = remember(protocol) { ServiceGuides.of(protocol) }
    val scheme = MaterialTheme.colorScheme
    val links = LocalUriHandler.current
    // Email's example is only a reassurance; the others fill in a server when tapped.
    var useExample by remember { mutableStateOf(false) }
    val appearance = LocalAppearance.current
    val tint = accessibleBubbleFill(appearance?.let { protocol.tint(it) } ?: Color(MessageStyle.filledControl(LocalControlTint.current)))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp)).background(scheme.surfaceContainer)
                .clearAndSetSemantics { contentDescription = "A simple picture of ${protocol.label}" }, contentAlignment = Alignment.Center) {
                ServicePicture(protocol, tint, scheme.onSurfaceVariant, Modifier.size(width = 260.dp, height = 126.dp))
            }
        }
        item { Text(guide.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
        item { Text(guide.about, style = MaterialTheme.typography.bodyLarge) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Don’t have an account yet?", style = MaterialTheme.typography.titleMedium)
                run {
                    val fills = guide.examplePreset.isNotEmpty()
                    Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
                        border = BorderStroke(if (useExample) 2.dp else 1.dp, if (useExample) tint else scheme.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .then(if (fills && pickServer != null) Modifier.clickable(role = Role.Button, onClickLabel = "Use ${guide.exampleTitle}") { start(guide.examplePreset) }
                                else if (fills) Modifier.clickable(role = Role.Checkbox, onClickLabel = "Use ${guide.exampleTitle}") { useExample = !useExample } else Modifier)
                            .padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(guide.exampleTitle, style = MaterialTheme.typography.titleMedium)
                                Text(guide.exampleLine, color = scheme.onSurfaceVariant)
                                if (fills) Text(if (useExample) "Picked. We’ll fill it in for you." else "Tap to use it. We’ll fill it in for you.",
                                    color = scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                            // Add server: the example opens the form at once, so it shows an arrow instead of a check.
                            if (fills && pickServer != null) Text("›", color = scheme.onSurfaceVariant, fontSize = 26.sp)
                            else if (fills) Box(Modifier.size(26.dp).background(if (useExample) tint else scheme.surfaceContainerHighest, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (useExample) Canvas(Modifier.size(14.dp)) {
                                    val ink = bubbleTextColor(tint)
                                    val stroke = 2.2.dp.toPx()
                                    drawLine(ink, Offset(size.width * .1f, size.height * .55f), Offset(size.width * .4f, size.height * .85f), stroke, StrokeCap.Round)
                                    drawLine(ink, Offset(size.width * .4f, size.height * .85f), Offset(size.width * .92f, size.height * .2f), stroke, StrokeCap.Round)
                                }
                            }
                        }
                    }
                    if (guide.exampleUrl.isNotEmpty()) TextButton(onClick = { runCatching { links.openUri(guide.exampleUrl) } }) {
                        Text(guide.exampleUrlLabel + " in your browser")
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What happens next", style = MaterialTheme.typography.titleMedium)
                guide.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(32.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) {
                            Text((index + 1).toString(), color = bubbleTextColor(tint), fontWeight = FontWeight.Bold)
                        }
                        Text(step, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        // IRC: registering a nickname for networks that ask you to sign in, and where its password goes.
        if (guide.signIn.size > 1) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(guide.signIn.first(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                for (line in guide.signIn.drop(1)) Text(line, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppButton(onClick = { pickServer?.invoke() ?: start(if (useExample) guide.examplePreset else emptyMap()) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    Text(if (pickServer != null) "Pick a server" else "Get started", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = skip) { Text("I already know what I’m doing") }
                TextButton(onClick = back) { Text("Back") }
            }
        }
    }
}

/** An original little drawing for each service, in the service's own color. */
@Composable
private fun ServicePicture(protocol: MessageProtocol, tint: Color, quiet: Color, modifier: Modifier) {
    val ink = bubbleTextColor(tint)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val line = 2.5.dp.toPx()
        when (protocol) {
            // Matrix: rooms linked to each other with nobody in the middle.
            MessageProtocol.MATRIX -> {
                val spots = listOf(Offset(w * .2f, h * .3f), Offset(w * .5f, h * .16f), Offset(w * .8f, h * .32f), Offset(w * .68f, h * .8f), Offset(w * .3f, h * .78f))
                for (i in spots.indices) for (j in i + 1 until spots.size) drawLine(quiet.copy(alpha = .45f), spots[i], spots[j], line * .6f)
                spots.forEachIndexed { index, spot -> bubble(if (index % 2 == 0) tint else quiet, spot, h * .15f) }
            }
            // XMPP: two people on two different providers, joined like email.
            MessageProtocol.XMPP -> {
                val left = Offset(w * .2f, h * .5f)
                val right = Offset(w * .8f, h * .5f)
                drawLine(quiet, Offset(w * .32f, h * .5f), Offset(w * .68f, h * .5f), line, StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(line * 3, line * 3)))
                for (x in listOf(.42f, .58f)) drawRoundRect(quiet, Offset(w * x - h * .1f, h * .36f), Size(h * .2f, h * .28f), CornerRadius(h * .05f), style = Stroke(line))
                bubble(tint, left, h * .2f)
                bubble(quiet, right, h * .2f)
            }
            // Email: an envelope with a chat bubble coming out of it.
            MessageProtocol.EMAIL -> {
                val top = Offset(w * .16f, h * .3f)
                val box = Size(w * .4f, h * .52f)
                drawRoundRect(quiet, top, box, CornerRadius(h * .06f), style = Stroke(line))
                drawLine(quiet, top + Offset(line, line), Offset(top.x + box.width / 2, top.y + box.height * .55f), line, StrokeCap.Round)
                drawLine(quiet, Offset(top.x + box.width - line, top.y + line), Offset(top.x + box.width / 2, top.y + box.height * .55f), line, StrokeCap.Round)
                drawLine(quiet, Offset(w * .6f, h * .56f), Offset(w * .68f, h * .56f), line, StrokeCap.Round)
                drawLine(quiet, Offset(w * .65f, h * .49f), Offset(w * .69f, h * .56f), line, StrokeCap.Round)
                drawLine(quiet, Offset(w * .65f, h * .63f), Offset(w * .69f, h * .56f), line, StrokeCap.Round)
                drawRoundRect(tint, Offset(w * .72f, h * .34f), Size(w * .2f, h * .34f), CornerRadius(h * .12f))
                drawLine(tint, Offset(w * .76f, h * .64f), Offset(w * .73f, h * .78f), line * 1.6f, StrokeCap.Round)
                for (x in listOf(.78f, .82f, .86f)) drawCircle(ink, h * .022f, Offset(w * x, h * .51f))
            }
            // IRC: a big room marked #, full of people.
            else -> {
                drawRoundRect(tint, Offset(w * .3f, h * .08f), Size(w * .4f, h * .56f), CornerRadius(h * .12f))
                val hash = Stroke(line * 1.3f, cap = StrokeCap.Round).width
                for (x in listOf(.45f, .55f)) drawLine(ink, Offset(w * (x + .012f), h * .2f), Offset(w * (x - .012f), h * .52f), hash, StrokeCap.Round)
                for (y in listOf(.3f, .42f)) drawLine(ink, Offset(w * .4f, h * y), Offset(w * .6f, h * y), hash, StrokeCap.Round)
                for (index in 0 until 7) drawCircle(if (index % 3 == 0) tint else quiet, h * .065f, Offset(w * (.14f + index * .12f), h * .86f))
            }
        }
    }
}

/** A round chat bubble with a small tail. */
private fun DrawScope.bubble(color: Color, center: Offset, radius: Float) {
    drawCircle(color, radius, center)
    drawLine(color, center + Offset(-radius * .5f, radius * .6f), center + Offset(-radius * .9f, radius * 1.25f), radius * .35f, StrokeCap.Round)
}
