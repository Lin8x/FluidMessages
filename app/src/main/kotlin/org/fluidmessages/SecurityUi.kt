package org.fluidmessages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How protected a conversation is, as a traffic light: red not encrypted, yellow in transit only, green end to end. */
internal enum class SecurityTone(val fill: Color, val ink: Color) {
    RED(Color(0xFFD32F2F), Color.White),
    YELLOW(Color(0xFFF2B705), Color.Black),
    GREEN(Color(0xFF1E8E3E), Color.White),
}

internal val Encryption.tone: SecurityTone
    get() = when (this) {
        Encryption.NONE, Encryption.PREVIEW -> SecurityTone.RED
        Encryption.IN_TRANSIT, Encryption.UNVERIFIED -> SecurityTone.YELLOW
        Encryption.END_TO_END, Encryption.COMPANION_E2EE, Encryption.FLUIDMESSAGES -> SecurityTone.GREEN
    }

val Encryption.shortLabel: String
    get() = when (this) {
        Encryption.NONE -> "Not encrypted"
        Encryption.PREVIEW -> "Not encrypted · preview"
        Encryption.IN_TRANSIT -> "Encrypted in transit"
        Encryption.END_TO_END -> "End-to-end encrypted"
        Encryption.COMPANION_E2EE -> "End-to-end · via FluidMessages Web"
        Encryption.FLUIDMESSAGES -> "Encrypted by FluidMessages"
        Encryption.UNVERIFIED -> "Not verified yet"
    }

/** A padlock, open when nothing protects the conversation. */
@Composable
internal fun LockGlyph(color: Color, open: Boolean, modifier: Modifier = Modifier.size(12.dp)) {
    Canvas(modifier) {
        val stroke = size.minDimension * .12f
        val body = Size(size.width * .7f, size.height * .46f)
        // Outlined, like the app's other line icons.
        drawRoundRect(color, Offset(size.width * .15f, size.height * .48f), body,
            androidx.compose.ui.geometry.CornerRadius(stroke * 1.3f), style = Stroke(stroke))
        drawCircle(color, stroke * .55f, Offset(size.width * .5f, size.height * .7f))
        val shackleWidth = size.width * .48f
        val left = size.width * .26f
        val shift = if (open) size.width * .26f else 0f
        drawArc(color, 180f, 180f, false, Offset(left + shift, size.height * .08f), Size(shackleWidth, size.height * .5f),
            style = Stroke(stroke))
        drawLine(color, Offset(left + shift + shackleWidth, size.height * .33f), Offset(left + shift + shackleWidth, size.height * .48f),
            stroke, StrokeCap.Round)
        if (!open) drawLine(color, Offset(left, size.height * .33f), Offset(left, size.height * .48f), stroke, StrokeCap.Round)
    }
}

/** A colored pill with a lock, readable on any theme because it carries its own fill. */
@Composable
internal fun EncryptionBadge(encryption: Encryption, compact: Boolean = false, modifier: Modifier = Modifier) {
    val tone = encryption.tone
    Row(modifier.background(tone.fill, RoundedCornerShape(50)).padding(horizontal = if (compact) 7.dp else 8.dp, vertical = 3.dp)
        .semantics { contentDescription = encryption.label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LockGlyph(tone.ink, open = tone == SecurityTone.RED, modifier = Modifier.size(if (compact) 11.dp else 12.dp))
        Text(if (compact) when (tone) {
            SecurityTone.RED -> "Not encrypted"
            SecurityTone.YELLOW -> if (encryption == Encryption.UNVERIFIED) "Not verified" else "In transit"
            SecurityTone.GREEN -> if (encryption == Encryption.FLUIDMESSAGES) "FluidMessages" else "End-to-end"
        } else encryption.shortLabel, color = tone.ink, fontSize = if (compact) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1)
    }
}

/** Marks a row that only gives information. */
@Composable
internal fun InfoMark(color: Color) {
    Canvas(Modifier.size(18.dp).semantics { contentDescription = "Information" }) {
        val stroke = 1.6.dp.toPx()
        drawCircle(color, size.minDimension / 2 - stroke, style = Stroke(stroke))
        drawCircle(color, stroke * .75f, Offset(center.x, size.height * .3f))
        drawLine(color, Offset(center.x, size.height * .45f), Offset(center.x, size.height * .72f), stroke, StrokeCap.Round)
    }
}

/** The padlock's color, adjusted so it stays visible on light and dark backgrounds. */
internal fun lockColor(encryption: Encryption, background: Color): Color {
    val dark = background.luminance() < .4f
    return when (encryption.tone) {
        SecurityTone.RED -> if (dark) Color(0xFFFF6B60) else Color(0xFFC62828)
        SecurityTone.YELLOW -> if (dark) Color(0xFFFFC83D) else Color(0xFF9A6700)
        SecurityTone.GREEN -> if (dark) Color(0xFF4CD964) else Color(0xFF1B7F3B)
    }
}

/** A small padlock in the protection color, closed when encrypted and open when not. */
@Composable
internal fun SecurityLock(encryption: Encryption, background: Color, modifier: Modifier = Modifier, size: Int = 11) {
    LockGlyph(lockColor(encryption, background), open = encryption.tone == SecurityTone.RED,
        modifier.size(size.dp).semantics { contentDescription = encryption.label })
}

/** The end of a settings row: a badge, an info mark, or an arrow for rows that open something. */
@Composable
internal fun RowEnd(entry: SettingsAction, action: Color, muted: Color) {
    when {
        entry.security != null -> SecurityLock(entry.security, androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
            Modifier.padding(start = 10.dp), 15)
        entry.info -> Box(Modifier.padding(start = 10.dp)) { InfoMark(muted) }
        else -> Text("›", Modifier.padding(start = 10.dp), color = action, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
    }
}
