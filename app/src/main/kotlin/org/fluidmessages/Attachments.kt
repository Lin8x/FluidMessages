package org.fluidmessages

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.CancellationSignal
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer

/**
 * Everything a message can carry besides text. Photos, videos, audio, voice messages, files, and contact
 * cards are files; a location is coordinates that every protocol can also send as a map link.
 */
internal enum class AttachmentKind(val label: String) {
    PHOTO("Photo"), VIDEO("Video"), AUDIO("Audio"), VOICE("Voice message"), FILE("File"),
    CONTACT("Contact"), LOCATION("Location"),
}

internal data class Attachment @JvmOverloads constructor(
    val kind: AttachmentKind,
    val uri: Uri?,
    val name: String = kind.label,
    val mime: String = "application/octet-stream",
    val size: Long = -1,
    val durationMs: Long = 0,
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
) {
    /** Text shown in previews and sent as the caption when the message has no text of its own. */
    val caption: String get() = when (kind) {
        AttachmentKind.FILE, AttachmentKind.AUDIO, AttachmentKind.CONTACT -> name
        else -> kind.label
    }

    val isFile: Boolean get() = kind != AttachmentKind.LOCATION

    fun sizeLabel(context: Context): String = if (size < 0) "" else Formatter.formatShortFileSize(context, size)

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name.lowercase(Locale.ROOT)).put("name", name).put("mime", mime)
        .put("size", size).put("duration", durationMs).apply {
            if (kind == AttachmentKind.LOCATION) put("latitude", latitude).put("longitude", longitude)
        }

    companion object {
        @JvmStatic fun photo(uri: Uri) = Attachment(AttachmentKind.PHOTO, uri, "Photo", "image/jpeg")

        @JvmStatic fun location(latitude: Double, longitude: Double) =
            Attachment(AttachmentKind.LOCATION, null, "Location", "text/plain", latitude = latitude, longitude = longitude)

        @JvmStatic fun kindFor(mime: String): AttachmentKind = when {
            mime.startsWith("image/") -> AttachmentKind.PHOTO
            mime.startsWith("video/") -> AttachmentKind.VIDEO
            mime.startsWith("audio/") -> AttachmentKind.AUDIO
            mime == "text/vcard" || mime == "text/x-vcard" -> AttachmentKind.CONTACT
            else -> AttachmentKind.FILE
        }

        /** Stored attachment details, with [uri] pointing at the local copy when it has been downloaded. */
        @JvmStatic fun fromJson(json: JSONObject?, uri: Uri?): Attachment? {
            if (json == null) return null
            val kind = AttachmentKind.entries.firstOrNull { it.name.equals(json.optString("kind"), true) } ?: return null
            return Attachment(kind, uri, json.optString("name", kind.label), json.optString("mime", "application/octet-stream"),
                json.optLong("size", -1), json.optLong("duration"), json.optDouble("latitude"), json.optDouble("longitude"))
        }

        /** Reads the name, type, and size Android reports for a picked file. */
        @JvmStatic @JvmOverloads
        fun describe(context: Context, uri: Uri, kind: AttachmentKind? = null): Attachment {
            val resolver = context.contentResolver
            var name = ""
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        name = it.getString(0).orEmpty()
                        if (!it.isNull(1)) size = it.getLong(1)
                    }
                }
            }
            val mime = resolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"
            val chosen = kind ?: kindFor(mime)
            val duration = if (chosen == AttachmentKind.VIDEO || chosen == AttachmentKind.AUDIO || chosen == AttachmentKind.VOICE)
                mediaDuration(context, uri) else 0L
            return Attachment(chosen, uri, name.ifBlank { chosen.label }, mime, size, duration)
        }

        private fun mediaDuration(context: Context, uri: Uri): Long = runCatching {
            MediaMetadataRetriever().use {
                it.setDataSource(context, uri)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }
}

/** Sending choices on the attachment sheet. */
internal enum class AttachmentSource(val label: String, val glyph: Glyph, val needsFiles: Boolean = true) {
    GALLERY("Photos and videos", Glyph.Photo), CAMERA("Camera", Glyph.Camera), DOCUMENT("Document", Glyph.Document),
    AUDIO("Audio", Glyph.Music), LOCATION("Location", Glyph.Pin, false), CONTACT("Contact", Glyph.Person, false),
    MONEY("Money", Glyph.Money, false), GIF("GIF", Glyph.Gif, false), STICKER("Sticker", Glyph.Sticker, false),
    POLL("Poll", Glyph.Poll, false), EVENT("Event", Glyph.Calendar, false), CHESS("Chess", Glyph.Chess, false),
}

// A location travels as a map link so it reads correctly on every protocol and in other apps.
private val osmLink = Regex("""openstreetmap\.org/\?mlat=(-?\d{1,2}(?:\.\d+)?)&mlon=(-?\d{1,3}(?:\.\d+)?)""")
private val geoLink = Regex("""\bgeo:(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")
private val googleLink = Regex("""maps\.google\.com/\?q=(-?\d{1,2}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")

internal fun locationText(latitude: Double, longitude: Double): String {
    val lat = String.format(Locale.ROOT, "%.6f", latitude)
    val lon = String.format(Locale.ROOT, "%.6f", longitude)
    return "📍 Location: https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
}

internal data class SharedLocation(val latitude: Double, val longitude: Double, val note: String)

internal fun parseLocation(text: String): SharedLocation? {
    val match = osmLink.find(text) ?: geoLink.find(text) ?: googleLink.find(text) ?: return null
    val lat = match.groupValues[1].toDoubleOrNull() ?: return null
    val lon = match.groupValues[2].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val note = text.lines().filterNot { match.value in it || it.startsWith("📍") }.joinToString("\n").trim()
    return SharedLocation(lat, lon, note)
}

internal fun openLocation(context: Context, location: SharedLocation) {
    val geo = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}")
    try { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
    catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(locationText(location.latitude, location.longitude)
            .substringAfter("Location: "))).addCategory(Intent.CATEGORY_BROWSABLE))
    }
}

/** Opens a file in the app Android picks for its type. */
internal fun openAttachment(context: Context, attachment: Attachment) {
    val uri = attachment.uri ?: return
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(Intent.createChooser(intent, attachment.name)) }
    catch (_: ActivityNotFoundException) {}
}

internal object AttachmentFiles {
    /** Private scratch folder for camera shots, voice notes, contact cards, and cleaned copies. */
    fun scratch(context: Context, extension: String): File {
        val folder = File(context.cacheDir, "attachments")
        if (!folder.isDirectory && !folder.mkdirs()) throw IllegalStateException("Could not prepare attachment storage")
        return File(folder, UUID.randomUUID().toString() + "." + extension)
    }

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".backup-files", file)

    /** Turns a picked contact into a vCard file, the format every messaging protocol and phone understands. */
    fun contactCard(context: Context, picked: Uri): Attachment {
        val resolver = context.contentResolver
        var name = "Contact"
        var lookup: String? = null
        resolver.query(picked, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.LOOKUP_KEY),
            null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0) ?: name
                lookup = it.getString(1)
            }
        }
        val file = scratch(context, "vcf")
        val copied = lookup?.let { key ->
            runCatching {
                resolver.openAssetFileDescriptor(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, key), "r")
                    ?.use { source -> source.createInputStream().use { input -> file.outputStream().use { input.copyTo(it) } } }
                file.length() > 0
            }.getOrDefault(false)
        } ?: false
        if (!copied) file.writeText("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:${name.replace(Regex("[\\r\\n]"), " ")}\r\nEND:VCARD\r\n")
        return Attachment(AttachmentKind.CONTACT, uri(context, file), name, "text/vcard", file.length())
    }

    /** One fresh fix from the best provider Android offers, without Google Play services. */
    fun currentLocation(context: Context, done: Consumer<Location?>): CancellationSignal {
        val manager = context.getSystemService(LocationManager::class.java)
        val cancel = CancellationSignal()
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.hasProvider(it) && manager.isProviderEnabled(it) }
        if (provider == null) { done.accept(null); return cancel }
        try { manager.getCurrentLocation(provider, cancel, context.mainExecutor, done) }
        catch (_: SecurityException) { done.accept(null) }
        return cancel
    }
}

/** Records a voice message as AAC in an MPEG-4 file, small enough for MMS and playable everywhere. */
internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L

    fun start() {
        val target = AttachmentFiles.scratch(context, "m4a")
        val next = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(24_000)
            setMaxDuration(MAX_MS)
            setOutputFile(target)
            prepare()
            start()
        }
        recorder = next
        file = target
        started = System.currentTimeMillis()
    }

    val elapsed: Long get() = if (recorder == null) 0 else System.currentTimeMillis() - started

    fun stop(): Attachment? {
        val active = recorder ?: return null
        val length = elapsed
        recorder = null
        val result = runCatching { active.stop() }.isSuccess
        active.release()
        val saved = file ?: return null
        if (!result || length < 700) { saved.delete(); return null }
        return Attachment(AttachmentKind.VOICE, AttachmentFiles.uri(context, saved), "Voice message", "audio/mp4",
            saved.length(), length)
    }

    fun cancel() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
    }

    companion object { const val MAX_MS = 3 * 60_000 }
}

/** Shared playback so only one voice message or audio file plays at a time. */
internal object AudioPlayback {
    private var player: MediaPlayer? = null
    var playing: Uri? = null
        private set
    private var finished: (() -> Unit)? = null

    fun toggle(context: Context, uri: Uri, done: () -> Unit): Boolean {
        val same = playing == uri
        stop()
        if (same) return false
        val next = runCatching { MediaPlayer.create(context, uri) }.getOrNull() ?: return false
        player = next
        playing = uri
        finished = done
        next.setOnCompletionListener { stop() }
        next.start()
        return true
    }

    fun position(): Int = player?.currentPosition ?: 0

    fun stop() {
        player?.release()
        player = null
        playing = null
        finished?.invoke()
        finished = null
    }
}

internal fun durationLabel(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
