package org.fluidmessages

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A meeting-link call, sent as plain text ("📹 Join my video call: https://…") so it works everywhere, and shown in
 * FluidMessages as a call card with a Join button. The browser companion draws the same card.
 */
internal data class MeetingInvite(val video: Boolean, val link: String) {
    val server: String get() = Uri.parse(link).host.orEmpty()
}

private val meetingPattern = Regex("""^(?:📹|📞)?\s*Join my (video )?call: (https://[A-Za-z0-9.-]+(?::\d+)?/[A-Za-z0-9_-]+)\s*$""")

internal fun parseMeeting(text: String): MeetingInvite? =
    meetingPattern.find(text.trim())?.let { MeetingInvite(it.groupValues[1].isNotEmpty(), it.groupValues[2]) }

/** The inbox preview for a call card. */
fun meetingPreview(text: String): String? = parseMeeting(text)?.let { if (it.video) "📹 Video call · tap to join" else "📞 Call · tap to join" }

internal fun meetingText(video: Boolean, link: String) = if (video) "📹 Join my video call: $link" else "📞 Join my call: $link"

@Composable
internal fun MeetingCard(invite: MeetingInvite, outgoing: Boolean, foreground: Color) {
    val context = LocalContext.current
    Row(Modifier.widthIn(min = 220.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(foreground.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
            HandsetGlyph(foreground, Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(if (invite.video) "Video call" else "Voice call", color = foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text((if (outgoing) "You started a call" else "Started a call") + " · " + invite.server, color = foreground.copy(alpha = .8f),
                fontSize = 12.sp, maxLines = 1)
        }
        Text("Join", Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF34C759))
            .clickable(role = Role.Button) {
                val target = if (invite.video) invite.link else invite.link + "#config.startWithVideoMuted=true"
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (error: ActivityNotFoundException) { /* no browser */ }
            }.padding(horizontal = 14.dp, vertical = 7.dp), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
