package org.fluidmessages

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One way to reach someone: message, call, or video, over a protocol, at an address. Conversation details and the
 * Phone tab's contact page build these, so both screens offer the same buttons.
 */
data class ReachOption @JvmOverloads constructor(
    val kind: String,
    val protocol: String,
    val detail: String,
    val action: Runnable,
    val key: String = "$kind:$protocol:$detail",
) {
    companion object {
        const val MESSAGE = "message"
        const val CALL = "call"
        const val VIDEO = "video"
    }
}

/**
 * Message, Call, and Video buttons under a contact's name, like iOS. With one way to do something, a tap does it.
 * With several, a tap always asks which one, so the choice is never hidden behind an earlier decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReachTiles(prefs: SharedPreferences, person: String, options: List<ReachOption>, tint: Color, fill: Color,
                        modifier: Modifier = Modifier) {
    var choosing by remember { mutableStateOf<String?>(null) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((kind, label) in listOf(ReachOption.MESSAGE to "Message", ReachOption.CALL to "Call", ReachOption.VIDEO to "Video")) {
            val available = options.filter { it.kind == kind }
            val enabled = available.isNotEmpty()
            val color = if (enabled) tint else tint.copy(alpha = .35f)
            // The line under the label says how it goes, or that you'll get to choose.
            val hint = when (available.size) {
                0 -> ""
                1 -> available[0].protocol
                else -> "Choose · ${available.size}"
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(fill)
                .clickable(enabled = enabled, role = Role.Button) {
                    if (available.size == 1) available[0].action.run() else choosing = kind
                }
                .semantics { contentDescription = if (hint.isEmpty()) "$label unavailable" else "$label, $hint" }
                .padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                when (kind) {
                    ReachOption.MESSAGE -> MessageIcon(color)
                    ReachOption.CALL -> HandsetGlyph(color, Modifier.size(21.dp))
                    else -> VideoIcon(color)
                }
                Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(hint.ifEmpty { " " }, color = color.copy(alpha = .75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val kind = choosing
    if (kind != null) {
        ModalBottomSheet(onDismissRequest = { choosing = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(when (kind) { ReachOption.MESSAGE -> "Message using"; ReachOption.CALL -> "Call using"; else -> "Video call using" },
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                for (option in options.filter { it.kind == kind }) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { choosing = null; option.action.run() }
                        .padding(vertical = 13.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(option.protocol, Modifier.widthIn(min = 64.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(option.detail, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(21.dp)) {
        drawPath(androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(size.width * .08f, size.height * .14f, size.width * .92f, size.height * .74f,
                androidx.compose.ui.geometry.CornerRadius(size.width * .32f)))
            moveTo(size.width * .24f, size.height * .66f); lineTo(size.width * .16f, size.height * .92f); lineTo(size.width * .44f, size.height * .73f)
        }, color)
    }
}

@Composable
private fun VideoIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        drawRoundRect(color, androidx.compose.ui.geometry.Offset(size.width * .06f, size.height * .26f),
            androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .48f), androidx.compose.ui.geometry.CornerRadius(size.width * .1f))
        drawPath(androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .7f, size.height * .44f); lineTo(size.width * .96f, size.height * .28f)
            lineTo(size.width * .96f, size.height * .72f); lineTo(size.width * .7f, size.height * .56f); close()
        }, color)
    }
}
