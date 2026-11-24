package org.fluidmessages

import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/** The attachment sheet: one row of round tiles per kind, tinted with the protocol's send color. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(sources: List<AttachmentSource>, accent: Color, note: String, dismiss: () -> Unit,
                             pick: (AttachmentSource) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val fill = accessibleBubbleFill(accent)
            val ink = bubbleTextColor(accent)
            // Grouped, so a long list of choices reads as three short ones.
            val groups = listOf(
                "Share" to listOf(AttachmentSource.GALLERY, AttachmentSource.CAMERA, AttachmentSource.DOCUMENT, AttachmentSource.AUDIO,
                    AttachmentSource.LOCATION, AttachmentSource.CONTACT),
                "Create" to listOf(AttachmentSource.EDIT, AttachmentSource.GIF, AttachmentSource.STICKER, AttachmentSource.POLL,
                    AttachmentSource.EVENT, AttachmentSource.MONEY),
                "Play" to listOf(AttachmentSource.GAMES, AttachmentSource.RANDOM, AttachmentSource.EFFECT))
            for ((title, members) in groups) {
            val shown = members.filter { it in sources }
            if (shown.isEmpty()) continue
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (row in shown.chunked(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (source in row) {
                        Column(Modifier.width(82.dp).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = source.label) {
                            pick(source)
                        }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(54.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                                DrawGlyph(source.glyph, ink, Modifier.size(26.dp))
                            }
                            Text(source.label, fontSize = 13.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurface,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.width(82.dp)) }
                }
            }
            }
            if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

/** Selected attachment above the composer. */
@Composable
internal fun DraftAttachment(attachment: Attachment, ink: Color, muted: Color, remove: () -> Unit, closeTint: Color, edit: (() -> Unit)? = null) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AttachmentThumb(attachment, Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)), muted)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(when (attachment.kind) {
                AttachmentKind.PHOTO -> "Photo ready to send"
                AttachmentKind.LOCATION -> "Location ready to send"
                AttachmentKind.VOICE -> "Voice message · ${durationLabel(attachment.durationMs)}"
                else -> attachment.name
            }, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOf(attachment.kind.label.takeIf { attachment.kind != AttachmentKind.PHOTO }.orEmpty(),
                attachment.sizeLabel(context)).filter { it.isNotEmpty() }.joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, color = muted, fontSize = 12.sp)
        }
        if (edit != null && attachment.kind == AttachmentKind.PHOTO && attachment.uri != null) {
            TextButton(onClick = edit) { Text("Edit", color = closeTint, fontWeight = FontWeight.SemiBold) }
        }
        ActionIcon("Remove attachment", Glyph.Close, closeTint, remove)
    }
}

@Composable
private fun AttachmentThumb(attachment: Attachment, modifier: Modifier, tint: Color) {
    val context = LocalContext.current
    val uri = attachment.uri
    val preview by produceState<ImageBitmap?>(null, uri) {
        value = if (uri != null && (attachment.kind == AttachmentKind.PHOTO || attachment.kind == AttachmentKind.VIDEO)) {
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(uri, Size(320, 320), null).asImageBitmap() }.getOrNull()
            }
        } else null
    }
    preview?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop) }
        ?: Box(modifier.background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            DrawGlyph(glyphFor(attachment.kind), tint, Modifier.size(26.dp))
        }
}

internal fun glyphFor(kind: AttachmentKind) = when (kind) {
    AttachmentKind.PHOTO -> Glyph.Photo
    AttachmentKind.VIDEO -> Glyph.Video
    AttachmentKind.AUDIO -> Glyph.Music
    AttachmentKind.VOICE -> Glyph.Mic
    AttachmentKind.FILE -> Glyph.Document
    AttachmentKind.CONTACT -> Glyph.Person
    AttachmentKind.LOCATION -> Glyph.Pin
}

/**
 * Attachment inside a bubble, drawn with the bubble's own text color so the same contrast rule
 * applies. Not-yet-downloaded files show a Download action instead of opening.
 */
@Composable
internal fun AttachmentContent(attachment: Attachment, foreground: Color, download: (() -> Unit)?) {
    val context = LocalContext.current
    when (attachment.kind) {
        AttachmentKind.VOICE, AttachmentKind.AUDIO -> if (attachment.uri != null) AudioRow(attachment, foreground)
            else FileRow(attachment, foreground, download)
        AttachmentKind.PHOTO -> attachment.uri?.let { MessagePicture(it, null, attachment.name, attachment.mime, sticker = attachment.isSticker()) }
            ?: FileRow(attachment, foreground, download)
        AttachmentKind.VIDEO -> if (attachment.uri != null) {
            var playing by remember { mutableStateOf(false) }
            Box(Modifier.width(230.dp).aspectRatio(16f / 10f).padding(bottom = 5.dp)
                .clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Play video") { playing = true },
                contentAlignment = Alignment.Center) {
                AttachmentThumb(attachment, Modifier.matchParentSize(), foreground)
                Box(Modifier.size(46.dp).background(Color.Black.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                    DrawGlyph(Glyph.Play, Color.White, Modifier.size(22.dp))
                }
            }
            if (playing) MediaViewer(attachment.uri, attachment.mime, attachment.name) { playing = false }
        } else FileRow(attachment, foreground, download)
        // A shared contact (vCard) shows the person with Add to contacts and Call.
        AttachmentKind.CONTACT -> if (attachment.uri != null) ContactCard(attachment, foreground) { FileRow(attachment, foreground, download) }
            else FileRow(attachment, foreground, download)
        else -> {
            // A calendar invite (.ics) shows as an event card with Add to calendar.
            val calendar = attachment.uri != null && (attachment.mime.contains("calendar") || attachment.name.endsWith(".ics", true))
            val event by produceState<CalendarEvent?>(null, attachment.uri) {
                if (calendar) value = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(attachment.uri!!)?.use { String(it.readNBytes(200_000)) }?.let(::parseIcs) }.getOrNull()
                }
            }
            event?.let { EventCard(it, foreground) } ?: FileRow(attachment, foreground, download)
        }
    }
}

@Composable
private fun FileRow(attachment: Attachment, foreground: Color, download: (() -> Unit)?) {
    val context = LocalContext.current
    val action = when {
        attachment.uri != null -> ({ openAttachment(context, attachment) })
        download != null -> download
        else -> null
    }
    Row(Modifier.widthIn(min = 200.dp).padding(bottom = 5.dp).clip(RoundedCornerShape(10.dp))
        .background(foreground.copy(alpha = .13f))
        .then(if (action != null) Modifier.clickable(onClickLabel = if (attachment.uri != null) "Open ${attachment.name}"
            else "Download ${attachment.name}", onClick = action) else Modifier)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        DrawGlyph(glyphFor(attachment.kind), foreground, Modifier.size(26.dp))
        Column(Modifier.padding(start = 9.dp).weight(1f, fill = false)) {
            Text(attachment.name, color = foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOf(attachment.kind.label, attachment.sizeLabel(context),
                if (attachment.uri == null) (if (download != null) "Tap to download" else "Not downloaded") else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")
            Text(detail, color = foreground.copy(alpha = .8f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun AudioRow(attachment: Attachment, foreground: Color) {
    val context = LocalContext.current
    val uri = attachment.uri ?: return
    var playing by remember(uri) { mutableStateOf(AudioPlayback.playing == uri) }
    var position by remember(uri) { mutableStateOf(0) }
    LaunchedEffect(playing) {
        while (playing) {
            position = AudioPlayback.position()
            delay(250)
        }
    }
    DisposableEffect(uri) { onDispose { if (AudioPlayback.playing == uri) AudioPlayback.stop() } }
    val total = attachment.durationMs.coerceAtLeast(1)
    Row(Modifier.widthIn(min = 210.dp).padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(foreground.copy(alpha = .18f))
            .clickable(onClickLabel = if (playing) "Pause" else "Play ${attachment.kind.label.lowercase(Locale.ROOT)}") {
                playing = AudioPlayback.toggle(context, uri) { playing = false; position = 0 }
            }
            .semantics { contentDescription = if (playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
            DrawGlyph(if (playing) Glyph.Pause else Glyph.Play, foreground, Modifier.size(16.dp))
        }
        Column(Modifier.padding(start = 9.dp).weight(1f)) {
            if (attachment.kind == AttachmentKind.AUDIO) Text(attachment.name, color = foreground, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { (position.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = foreground,
                trackColor = foreground.copy(alpha = .25f))
            Text(durationLabel(if (playing) position.toLong() else attachment.durationMs), color = foreground.copy(alpha = .8f),
                fontSize = 11.sp)
        }
    }
    if (attachment.kind == AttachmentKind.VOICE) VoiceTranscript(uri, foreground)
}

/** A transcript made on this phone only; the recording never leaves it. */
@Composable
private fun VoiceTranscript(uri: android.net.Uri, foreground: Color) {
    val context = LocalContext.current
    var text by remember(uri) { mutableStateOf(VoiceTranscripts.cached(context, uri)) }
    var working by remember(uri) { mutableStateOf(false) }
    var problem by remember(uri) { mutableStateOf<String?>(null) }
    val microphone = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) problem = "Transcripts use the phone's own speech recognizer, which needs microphone access. Nothing is recorded."
        else { working = true; VoiceTranscripts.transcribe(context, uri) { result -> working = false; text = result.getOrNull(); problem = result.exceptionOrNull()?.message } }
    }
    when {
        text != null -> Text("“${text}”", color = foreground, fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 280.dp))
        working -> Text("Transcribing on this phone…", color = foreground.copy(alpha = .8f), fontSize = 12.sp)
        else -> Column {
            problem?.let { Text(it, color = foreground.copy(alpha = .85f), fontSize = 12.sp, modifier = Modifier.widthIn(max = 260.dp)) }
            Text("Show transcript", color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClickLabel = "Transcribe this voice message on this phone") {
                    problem = null
                    if (!VoiceTranscripts.available(context)) { problem = "This phone can't transcribe voice messages on the device."; return@clickable }
                    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        microphone.launch(android.Manifest.permission.RECORD_AUDIO); return@clickable
                    }
                    working = true
                    VoiceTranscripts.transcribe(context, uri) { result -> working = false; text = result.getOrNull(); problem = result.exceptionOrNull()?.message }
                }.padding(vertical = 3.dp))
        }
    }
}

/** A shared location: coordinates, an optional note, and a button that opens the device's map app. */
@Composable
internal fun LocationCard(location: SharedLocation, foreground: Color) {
    val context = LocalContext.current
    Column(Modifier.widthIn(min = 200.dp).clip(RoundedCornerShape(10.dp)).background(foreground.copy(alpha = .13f))
        .clickable(onClickLabel = "Open location in maps") { openLocation(context, location) }
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawGlyph(Glyph.Pin, foreground, Modifier.size(24.dp))
            Text("Shared location", Modifier.padding(start = 6.dp), color = foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(String.format(Locale.ROOT, "%.5f, %.5f", location.latitude, location.longitude), color = foreground.copy(alpha = .85f),
            fontSize = 12.sp)
        Text("Open in maps", color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Replaces the composer while a voice message records. */
@Composable
internal fun RecordingBar(recorder: VoiceRecorder, accent: Color, ink: Color, muted: Color, cancel: () -> Unit, send: () -> Unit) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(recorder) {
        while (true) {
            elapsed = recorder.elapsed
            if (elapsed >= VoiceRecorder.MAX_MS) { send(); break }
            delay(200)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = cancel) { Text("Cancel", color = muted) }
        Box(Modifier.size(10.dp).background(Color(0xffff3b30), CircleShape))
        Text("Recording " + durationLabel(elapsed), Modifier.weight(1f), color = ink)
        Box(Modifier.size(40.dp).clip(CircleShape).background(accessibleBubbleFill(accent))
            .clickable(onClickLabel = "Send voice message", onClick = send)
            .semantics { contentDescription = "Send voice message" }, contentAlignment = Alignment.Center) {
            DrawGlyph(Glyph.Send, bubbleTextColor(accent), Modifier.size(18.dp))
        }
    }
}
