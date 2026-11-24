package org.fluidmessages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection

/*
 * Cards inside messages: pictures and GIFs from links, polls, calendar events, link previews, and chess.
 * Every one travels as ordinary readable text, so other apps show something sensible, and FluidMessages
 * turns it back into a card. The web app (companion.js) reads and writes the same formats.
 */

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(8)

// Pictures and GIFs sent as links

internal data class PictureLink(val url: String, val sticker: Boolean)

private val pictureUrl = Regex("""^https://[^\s]+?\.(gif|webp|png|jpe?g)(\?[^\s#]*)?(#sticker)?$""", RegexOption.IGNORE_CASE)
private val gifHost = Regex("""^https://(media\d*\.giphy\.com|i\.giphy\.com|media\.tenor\.com|c\.tenor\.com)/[^\s]+$""", RegexOption.IGNORE_CASE)

/** A message that is only a picture or GIF link. "#sticker" at the end marks a sticker. */
internal fun parsePictureLink(text: String): PictureLink? {
    val trimmed = text.trim()
    if (!pictureUrl.matches(trimmed) && !gifHost.matches(trimmed)) return null
    return PictureLink(trimmed.removeSuffix("#sticker"), trimmed.endsWith("#sticker"))
}

// Polls

internal data class Poll(val id: String, val question: String, val options: List<String>)

private val pollPattern = Regex("""^📊 Poll: (.+)\n((?:\d{1,2}\. .+\n?){2,12})Reply with a number to vote · poll ([a-z0-9]{6,12})\s*$""")
private val votePattern = Regex("""^🗳️ (.+) · voted in “(.+)” \(poll ([a-z0-9]{6,12})\)\s*$""")
private val numberVote = Regex("""^\s*(\d{1,2})\s*$""")

internal fun pollText(question: String, options: List<String>): String =
    "📊 Poll: ${question.replace('\n', ' ').trim()}\n" +
        options.mapIndexed { index, option -> "${index + 1}. ${option.replace('\n', ' ').trim()}" }.joinToString("\n") +
        "\nReply with a number to vote · poll ${newId()}"

internal fun parsePoll(text: String): Poll? {
    val match = pollPattern.find(text.trim()) ?: return null
    val options = match.groupValues[2].trim().lines().map { it.substringAfter(". ").trim() }
    return Poll(match.groupValues[3], match.groupValues[1].trim(), options)
}

internal fun voteText(poll: Poll, option: Int) = "🗳️ ${poll.options[option]} · voted in “${poll.question}” (poll ${poll.id})"

internal fun parseVote(text: String): Pair<String, String>? = votePattern.find(text.trim())?.let { it.groupValues[3] to it.groupValues[1] }

/** Who voted for what in each poll of a chat. The newest vote from each person counts. */
internal class PollTally(val votes: Map<String, Int>) {
    fun count(option: Int) = votes.values.count { it == option }
    val total: Int get() = votes.size
}

internal fun tallyPolls(lines: List<ChatLine>): Map<String, PollTally> {
    val polls = linkedMapOf<String, Poll>()
    val votes = mutableMapOf<String, MutableMap<String, Int>>()
    var latest: Poll? = null
    for (line in lines) {
        val voter = if (line.outgoing) "you" else line.author ?: "them"
        parsePoll(line.text)?.let { polls[it.id] = it; latest = it; return@let }
        val vote = parseVote(line.text)
        if (vote != null) {
            val poll = polls[vote.first] ?: continue
            val index = poll.options.indexOf(vote.second)
            if (index >= 0) votes.getOrPut(poll.id) { mutableMapOf() }[voter] = index
            continue
        }
        // People on other apps can answer with just the option's number.
        val number = numberVote.find(line.text)?.groupValues?.get(1)?.toIntOrNull()
        val poll = latest
        if (number != null && poll != null && number in 1..poll.options.size) votes.getOrPut(poll.id) { mutableMapOf() }[voter] = number - 1
    }
    return polls.keys.associateWith { PollTally(votes[it].orEmpty()) }
}

@Composable
internal fun PollCard(poll: Poll, tally: PollTally?, foreground: Color, vote: ((Int) -> Unit)?) {
    val mine = tally?.votes?.get("you")
    Column(Modifier.widthIn(min = 220.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("POLL", color = foreground.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(poll.question, color = foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        val total = tally?.total ?: 0
        poll.options.forEachIndexed { index, option ->
            val count = tally?.count(index) ?: 0
            val share = if (total == 0) 0f else count.toFloat() / total
            val chosen = mine == index
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(foreground.copy(alpha = .12f))
                .then(if (chosen) Modifier.border(1.5.dp, foreground.copy(alpha = .85f), RoundedCornerShape(10.dp)) else Modifier)
                .clickable(enabled = vote != null, onClickLabel = "Vote for $option") { vote?.invoke(index) }) {
                Box(Modifier.matchParentSize().fillMaxWidth(share).background(foreground.copy(alpha = .18f)))
                Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (chosen) "✓ " else "") + option, Modifier.weight(1f), color = foreground, fontSize = 15.sp)
                    if (total > 0) Text("$count", color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(when {
            total == 0 -> "No votes yet · tap an option"
            total == 1 -> "1 vote"
            else -> "$total votes"
        }, color = foreground.copy(alpha = .75f), fontSize = 12.sp)
    }
}

@Composable
internal fun PollDialog(dismiss: () -> Unit, post: (String) -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("New poll") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(question, { question = it.take(140); problem = null }, label = { Text("Question") })
            options.forEachIndexed { index, value ->
                OutlinedTextField(value, { options[index] = it.take(60); problem = null }, singleLine = true,
                    label = { Text("Option ${index + 1}") }, trailingIcon = if (options.size > 2) ({
                        TextButton(onClick = { options.removeAt(index) }) { Text("✕") } }) else null)
            }
            if (options.size < 10) TextButton(onClick = { options.add("") }) { Text("Add an option") }
            Text("Everyone in the chat can vote. People on other apps see a numbered list and can reply with a number.",
                style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val filled = options.map(String::trim).filter(String::isNotEmpty)
            when {
                question.isBlank() -> problem = "Enter a question"
                filled.size < 2 -> problem = "Add at least two options"
                filled.distinct().size != filled.size -> problem = "Each option needs to be different"
                else -> { post(pollText(question, filled)); dismiss() }
            }
        }) { Text("Send poll") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

// Calendar events

internal data class CalendarEvent(val title: String, val start: Long, val end: Long, val allDay: Boolean,
                                  val location: String, val notes: String) {
    /** A Google Calendar link: it opens in any browser and adds the event in one tap. */
    fun googleLink(): String {
        val format = if (allDay) "yyyyMMdd" else "yyyyMMdd'T'HHmmss'Z'"
        val formatter = SimpleDateFormat(format, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val dates = formatter.format(Date(start)) + "/" + formatter.format(Date(end))
        return "https://calendar.google.com/calendar/render?action=TEMPLATE&text=${encode(title)}&dates=$dates" +
            (if (location.isBlank()) "" else "&location=${encode(location)}") + (if (notes.isBlank()) "" else "&details=${encode(notes)}")
    }

    fun whenText(): String {
        if (allDay) {
            val day = DateFormat.getDateInstance(DateFormat.FULL).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val last = end - 86_400_000L
            return if (last <= start) day.format(Date(start)) else day.format(Date(start)) + " – " + day.format(Date(last))
        }
        val day = DateFormat.getDateInstance(DateFormat.FULL)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val sameDay = day.format(Date(start)) == day.format(Date(end))
        return day.format(Date(start)) + " · " + time.format(Date(start)) + " – " +
            (if (sameDay) "" else day.format(Date(end)) + " ") + time.format(Date(end))
    }

    /** The iCalendar (RFC 5545) form, the open standard every calendar app imports. */
    fun ics(): String {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun esc(text: String) = text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        return listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//FluidMessages//EN", "BEGIN:VEVENT",
            "UID:${newId()}@fluidmessages", "DTSTAMP:${stamp.format(Date())}",
            if (allDay) "DTSTART;VALUE=DATE:${day.format(Date(start))}" else "DTSTART:${stamp.format(Date(start))}",
            if (allDay) "DTEND;VALUE=DATE:${day.format(Date(end))}" else "DTEND:${stamp.format(Date(end))}",
            "SUMMARY:${esc(title)}") .plus(if (location.isBlank()) emptyList() else listOf("LOCATION:${esc(location)}"))
            .plus(if (notes.isBlank()) emptyList() else listOf("DESCRIPTION:${esc(notes)}"))
            .plus(listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n") + "\r\n"
    }
}

internal fun eventText(event: CalendarEvent): String = listOfNotNull("📅 ${event.title}", event.whenText(),
    event.location.takeIf { it.isNotBlank() }?.let { "📍 $it" }, event.notes.takeIf { it.isNotBlank() },
    "Add to calendar: ${event.googleLink()}").joinToString("\n")

private val eventLink = Regex("""Add to calendar: (https://calendar\.google\.com/calendar/render\?action=TEMPLATE\S+)""")

internal fun parseEvent(text: String): CalendarEvent? {
    if (!text.startsWith("📅 ")) return null
    val link = eventLink.find(text)?.groupValues?.get(1) ?: return null
    val query = Uri.parse(link)
    val dates = query.getQueryParameter("dates")?.split("/") ?: return null
    if (dates.size != 2) return null
    val allDay = dates[0].length == 8
    val parser = SimpleDateFormat(if (allDay) "yyyyMMdd" else "yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val start = runCatching { parser.parse(dates[0])!!.time }.getOrNull() ?: return null
    val end = runCatching { parser.parse(dates[1])!!.time }.getOrNull() ?: return null
    return CalendarEvent(query.getQueryParameter("text").orEmpty().ifEmpty { "Event" }, start, end, allDay,
        query.getQueryParameter("location").orEmpty(), query.getQueryParameter("details").orEmpty())
}

/** Reads the first event from an .ics file someone sent, so it shows as the same card. */
internal fun parseIcs(text: String): CalendarEvent? {
    val unfolded = text.replace(Regex("\r?\n[ \t]"), "")
    val block = unfolded.substringAfter("BEGIN:VEVENT", "").substringBefore("END:VEVENT").ifEmpty { return null }
    fun field(name: String): Pair<String, String>? = block.lines().firstOrNull { it.startsWith("$name:") || it.startsWith("$name;") }
        ?.let { it.substringBefore(':') to it.substringAfter(':').trim() }
    fun unescape(value: String) = value.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
    fun time(entry: Pair<String, String>?): Pair<Long, Boolean>? {
        val (params, value) = entry ?: return null
        val allDay = "VALUE=DATE" in params || value.length == 8
        val zone = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)
        val format = when { allDay -> "yyyyMMdd"; value.endsWith("Z") -> "yyyyMMdd'T'HHmmss'Z'"; else -> "yyyyMMdd'T'HHmmss" }
        val parser = SimpleDateFormat(format, Locale.US).apply {
            timeZone = if (allDay || value.endsWith("Z")) TimeZone.getTimeZone("UTC") else zone?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
        }
        return runCatching { parser.parse(value)!!.time to allDay }.getOrNull()
    }
    val (start, allDay) = time(field("DTSTART")) ?: return null
    val end = time(field("DTEND"))?.first ?: (start + if (allDay) 86_400_000L else 3_600_000L)
    return CalendarEvent(unescape(field("SUMMARY")?.second.orEmpty()).ifEmpty { "Event" }, start, end, allDay,
        unescape(field("LOCATION")?.second.orEmpty()), unescape(field("DESCRIPTION")?.second.orEmpty()).take(500))
}

/** Adds the event with the phone's calendar app (Google Calendar, Etar, Fossify, or any other). */
internal fun addToCalendar(context: Context, event: CalendarEvent): Boolean {
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay).putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, event.location).putExtra(CalendarContract.Events.DESCRIPTION, event.notes)
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** Shares the event as an .ics file, for calendars that import files (Thunderbird, Apple, Outlook, Nextcloud). */
internal fun shareIcs(context: Context, event: CalendarEvent) {
    val file = AttachmentFiles.scratch(context, "ics")
    file.writeText(event.ics())
    val named = File(file.parentFile, event.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "event" }.take(40) + ".ics")
    file.renameTo(named)
    val uri = AttachmentFiles.uri(context, named)
    val intent = Intent(Intent.ACTION_SEND).setType("text/calendar").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Save event")) }
}

@Composable
internal fun EventCard(event: CalendarEvent, foreground: Color) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(min = 230.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val month = SimpleDateFormat("MMM", Locale.getDefault()).apply { if (event.allDay) timeZone = TimeZone.getTimeZone("UTC") }
            val dayNumber = SimpleDateFormat("d", Locale.getDefault()).apply { if (event.allDay) timeZone = TimeZone.getTimeZone("UTC") }
            Column(Modifier.width(52.dp).clip(RoundedCornerShape(10.dp)).background(foreground.copy(alpha = .14f)).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(month.format(Date(event.start)).uppercase(), color = Color(0xFFFF453A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(dayNumber.format(Date(event.start)), color = foreground, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(event.title, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(event.whenText(), color = foreground.copy(alpha = .85f), fontSize = 13.sp)
            }
        }
        if (event.location.isNotBlank()) Text("📍 " + event.location, color = foreground, fontSize = 14.sp)
        if (event.notes.isNotBlank()) Text(event.notes, color = foreground.copy(alpha = .9f), fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { missing = !addToCalendar(context, event) }) { Text("Add to calendar") }
            TextButton(onClick = { shareIcs(context, event) }) { Text(".ics", color = foreground) }
        }
        if (missing) Text("No calendar app is installed. Use .ics to save it elsewhere.", color = foreground, fontSize = 12.sp)
    }
}

@Composable
internal fun EventDialog(dismiss: () -> Unit, post: (String) -> Unit) {
    val context = LocalContext.current
    val now = remember { java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 1); set(java.util.Calendar.MINUTE, 0) } }
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var start by remember { mutableLongStateOf(now.timeInMillis) }
    var end by remember { mutableLongStateOf(now.timeInMillis + 3_600_000L) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun pickDate(current: Long, set: (Long) -> Unit) {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = current }
        android.app.DatePickerDialog(context, { _, year, month, day ->
            calendar.set(year, month, day); set(calendar.timeInMillis)
        }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }
    fun pickTime(current: Long, set: (Long) -> Unit) {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = current }
        android.app.TimePickerDialog(context, { _, hour, minute ->
            calendar.set(java.util.Calendar.HOUR_OF_DAY, hour); calendar.set(java.util.Calendar.MINUTE, minute); set(calendar.timeInMillis)
        }, calendar.get(java.util.Calendar.HOUR_OF_DAY), calendar.get(java.util.Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(context)).show()
    }
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Share an event") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it.take(100); problem = null }, singleLine = true, label = { Text("What") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(allDay, { allDay = it })
                Text("All day", Modifier.padding(start = 8.dp))
            }
            Text("Starts", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { pickDate(start) { val shift = end - start; start = it; end = it + shift } }) { Text(dateFormat.format(Date(start))) }
                if (!allDay) OutlinedButton(onClick = { pickTime(start) { val shift = end - start; start = it; end = it + shift } }) { Text(timeFormat.format(Date(start))) }
            }
            Text("Ends", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { pickDate(end) { end = it } }) { Text(dateFormat.format(Date(end))) }
                if (!allDay) OutlinedButton(onClick = { pickTime(end) { end = it } }) { Text(timeFormat.format(Date(end))) }
            }
            OutlinedTextField(location, { location = it.take(140) }, singleLine = true, label = { Text("Where (optional)") })
            OutlinedTextField(notes, { notes = it.take(500) }, label = { Text("Notes (optional)") })
            Text("They can add it to any calendar app, Google Calendar, or import the .ics file.", style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (title.isBlank()) { problem = "Enter what the event is"; return@TextButton }
            val event = if (allDay) {
                // All-day events are whole dates, written in UTC so the day never shifts between time zones.
                fun utcDay(millis: Long): Long {
                    val local = java.util.Calendar.getInstance().apply { timeInMillis = millis }
                    return java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        clear(); set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH))
                    }.timeInMillis
                }
                val first = utcDay(start)
                CalendarEvent(title.trim(), first, maxOf(utcDay(end), first) + 86_400_000L, true, location.trim(), notes.trim())
            } else {
                if (end <= start) { problem = "The event needs to end after it starts"; return@TextButton }
                CalendarEvent(title.trim(), start, end, false, location.trim(), notes.trim())
            }
            post(eventText(event)); dismiss()
        }) { Text("Send event") }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

// Link previews

internal data class LinkPreview(val url: String, val title: String, val description: String, val image: String, val site: String) {
    val video: Boolean get() = Regex("""^https://(www\.|m\.)?(youtube\.com|youtu\.be|vimeo\.com|twitch\.tv)/""").containsMatchIn(url)
}

private val firstLink = Regex("""https://[^\s<>"]+""")

/** The first web link in a message, if it has one worth previewing. */
internal fun previewableLink(text: String): String? {
    val link = firstLink.find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';', ':', '”', '’') ?: return null
    if (parsePictureLink(link) != null || link.contains("calendar.google.com/calendar/render") || link.contains("lichess.org/analysis")) return null
    return link
}

internal object LinkPreviews {
    private val memory = object : LinkedHashMap<String, LinkPreview?>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkPreview?>?) = size > 120
    }

    fun cachedOnly(url: String): LinkPreview? = synchronized(memory) { memory[url] }

    /** Reads the page's title, summary, and picture (Open Graph tags). HTTPS only, the first 512 KB. */
    fun fetch(context: Context, url: String): LinkPreview? {
        synchronized(memory) { if (memory.containsKey(url)) return memory[url] }
        val disk = File(File(context.cacheDir, "link-previews").apply { mkdirs() },
            java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(40))
        val fresh = disk.isFile && System.currentTimeMillis() - disk.lastModified() < 86_400_000L
        val result = if (fresh) runCatching { org.json.JSONObject(disk.readText()) }.getOrNull()?.let {
            if (it.optBoolean("none")) null else LinkPreview(url, it.optString("title"), it.optString("description"), it.optString("image"), it.optString("site"))
        } else download(url).also { preview ->
            runCatching {
                disk.writeText((if (preview == null) org.json.JSONObject().put("none", true) else org.json.JSONObject().put("title", preview.title)
                    .put("description", preview.description).put("image", preview.image).put("site", preview.site)).toString())
            }
        }
        synchronized(memory) { memory[url] = result }
        return result
    }

    private fun download(url: String): LinkPreview? { return try {
        var target = URL(url)
        var html = ""
        for (hop in 0..3) {
            val connection = target.openConnection() as HttpsURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FluidMessages link preview)")
            connection.setRequestProperty("Accept", "text/html")
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: return null
                connection.disconnect()
                target = URL(target, next)
                if (target.protocol != "https") return null
                continue
            }
            if (code !in 200..299 || connection.contentType?.contains("html") != true) { connection.disconnect(); return null }
            html = connection.inputStream.use { String(it.readNBytes(512 * 1024), Charsets.UTF_8) }
            connection.disconnect()
            break
        }
        fun meta(vararg names: String): String {
            for (name in names) {
                val pattern = Regex("""<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(name)}["'][^>]*>""", RegexOption.IGNORE_CASE)
                val tag = pattern.find(html)?.value ?: continue
                val content = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                if (!content.isNullOrBlank()) return unescapeHtml(content.trim())
            }
            return ""
        }
        val title = meta("og:title", "twitter:title").ifEmpty {
            Regex("""<title[^>]*>([^<]{1,300})</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { unescapeHtml(it.trim()) }.orEmpty()
        }
        val image = meta("og:image:secure_url", "og:image", "twitter:image").let { if (it.isEmpty()) "" else URL(target, it).toString() }
        if (title.isEmpty()) null else LinkPreview(url, title.take(200), meta("og:description", "description", "twitter:description").take(300),
            image.takeIf { it.startsWith("https://") }.orEmpty(), meta("og:site_name").ifEmpty { target.host.removePrefix("www.") })
    } catch (_: Exception) { null } }

    private fun unescapeHtml(text: String) = text.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("&#(\\d{1,6});")) { runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault("") }
}

@Composable
internal fun LinkPreviewCard(url: String, foreground: Color) {
    val context = LocalContext.current
    val preview by produceState(LinkPreviews.cachedOnly(url), url) { value = withContext(Dispatchers.IO) { LinkPreviews.fetch(context, url) } }
    val item = preview ?: return
    Column(Modifier.padding(top = 6.dp, bottom = 2.dp).widthIn(max = 280.dp).clip(RoundedCornerShape(12.dp))
        .background(foreground.copy(alpha = .1f))
        .clickable(onClickLabel = "Open ${item.site}") { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) {
        if (item.image.isNotEmpty()) Box(contentAlignment = Alignment.Center) {
            val media by produceState<DecodedMedia?>(null, item.image) {
                value = withContext(Dispatchers.IO) { RemoteMedia.fetch(context, item.image)?.let { MediaDecoding.decode(context, Uri.fromFile(it), 900) } }
            }
            media?.let { decoded ->
                MediaPicture(decoded, Modifier.fillMaxWidth().aspectRatio(decoded.ratio.coerceIn(1.2f, 2f)), crop = true, description = item.title)
                if (item.video) Box(Modifier.size(46.dp).background(Color.Black.copy(alpha = .6f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center) { Text("▶", color = Color.White, fontSize = 20.sp) }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.site, color = foreground.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(item.title, color = foreground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.description.isNotEmpty()) Text(item.description, color = foreground.copy(alpha = .85f), fontSize = 12.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        }
    }
}

// Chess

/** One message of a chess game: who sent it as which color, and every move so far. */
internal data class ChessMessage(val id: String, val senderWhite: Boolean, val moves: List<String>, val resigned: Boolean)

private val chessPattern = Regex("""fm-chess:([a-z0-9]{6,12}):([wb]):([a-h1-8qrbn,]*)(:resign)?""")

internal fun parseChess(text: String): ChessMessage? {
    if (!text.startsWith("♟")) return null
    val match = chessPattern.find(text) ?: return null
    val moves = match.groupValues[3].split(',').filter { it.isNotEmpty() }
    if (moves.size > 600) return null
    return ChessMessage(match.groupValues[1], match.groupValues[2] == "w", moves, match.groupValues[4].isNotEmpty())
}

internal fun chessText(id: String, white: Boolean, moves: List<String>, summary: String, resign: Boolean = false): String {
    val game = Chess.replay(moves) ?: Chess.start()
    return "♟️ Chess · $summary\nSee the board: https://lichess.org/analysis/standard/${game.fen().replace(' ', '_')}\n" +
        "fm-chess:$id:${if (white) "w" else "b"}:${moves.joinToString(",")}${if (resign) ":resign" else ""}"
}

internal fun newChessGame(white: Boolean): String = chessText(newId(), white, emptyList(),
    if (white) "Let's play! I'm white, so I go first." else "Let's play! I'm black, so you go first.")

private val pieceGlyph = mapOf(1 to "♟", 2 to "♞", 3 to "♝", 4 to "♜", 5 to "♛", 6 to "♚")

@Composable
private fun ChessBoard(game: Chess, flipped: Boolean, square: Dp, selected: Int?, targets: Set<Int>, last: Pair<Int, Int>?,
                       tap: ((Int) -> Unit)?) {
    val light = Color(0xFFEDE6D6)
    val dark = Color(0xFF8DA37A)
    Column(Modifier.clip(RoundedCornerShape(8.dp))) {
        for (row in 0..7) Row {
            for (column in 0..7) {
                val rank = if (flipped) row else 7 - row
                val file = if (flipped) 7 - column else column
                val index = rank * 8 + file
                val base = if ((rank + file) % 2 == 0) dark else light
                val tint = when {
                    index == selected -> Color(0xFFF6D365)
                    last != null && (index == last.first || index == last.second) -> base.copy(red = (base.red + .25f).coerceAtMost(1f))
                    else -> base
                }
                Box(Modifier.size(square).background(tint).then(if (tap != null) Modifier.clickable { tap(index) } else Modifier),
                    contentAlignment = Alignment.Center) {
                    val piece = game.board[index]
                    if (piece != 0) Text(pieceGlyph[kotlin.math.abs(piece)] + "\uFE0E", fontSize = (square.value * .72f).sp,
                        textAlign = TextAlign.Center, style = TextStyle(color = if (piece > 0) Color.White else Color(0xFF1E1E1E),
                            shadow = Shadow(if (piece > 0) Color.Black else Color.White.copy(alpha = .4f), Offset(0f, 0f), 3f)))
                    if (index in targets) Box(Modifier.size(square * .3f).background(Color.Black.copy(alpha = .25f), androidx.compose.foundation.shape.CircleShape))
                }
            }
        }
    }
}

/** Where a game stands for you, from the newest message of that game. */
private fun chessStatus(game: Chess, mineWhite: Boolean, message: ChessMessage, latest: Boolean, outgoing: Boolean): String {
    if (message.resigned) return if (outgoing) "You resigned" else "They resigned · you win"
    return when (game.outcome()) {
        Chess.Outcome.CHECKMATE -> if (game.whiteToMove != mineWhite) "Checkmate · you win! 🎉" else "Checkmate · they win"
        Chess.Outcome.STALEMATE -> "Stalemate · it's a draw"
        Chess.Outcome.DRAW -> "Draw"
        Chess.Outcome.PLAYING -> when {
            !latest -> "Move ${message.moves.size}"
            game.whiteToMove == mineWhite -> if (game.inCheck()) "Check! Your move" else "Your move"
            else -> "Their move"
        }
    }
}

@Composable
internal fun ChessCard(message: ChessMessage, outgoing: Boolean, latest: Boolean, foreground: Color, respond: ((String) -> Unit)?) {
    val game = remember(message) { Chess.replay(message.moves) }
    if (game == null) { Text("♟ This chess move isn't valid.", color = foreground); return }
    val mineWhite = if (outgoing) message.senderWhite else !message.senderWhite
    var open by remember { mutableStateOf(false) }
    val lastMove = remember(message) { message.moves.lastOrNull()?.let { game.parseUci(it) }?.let { it.from to it.to } }
    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("CHESS", color = foreground.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Box(Modifier.clickable(onClickLabel = "Open the board") { open = true }) {
            ChessBoard(game, !mineWhite, 21.dp, null, emptySet(), lastMove, null)
        }
        val status = chessStatus(game, mineWhite, message, latest, outgoing)
        Text(status, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val yourTurn = latest && !message.resigned && game.outcome() == Chess.Outcome.PLAYING && game.whiteToMove == mineWhite
        if (yourTurn && respond != null) FilledTonalButton(onClick = { open = true }) { Text("Make your move") }
        else TextButton(onClick = { open = true }) { Text("View board", color = foreground) }
    }
    if (open) ChessDialog(message, game, mineWhite, latest && respond != null, { open = false }) { text -> respond?.invoke(text); open = false }
}

@Composable
private fun ChessDialog(message: ChessMessage, start: Chess, mineWhite: Boolean, canPlay: Boolean, dismiss: () -> Unit, send: (String) -> Unit) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<Chess.Move?>(null) }
    var promotionFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val playing = canPlay && !message.resigned && start.outcome() == Chess.Outcome.PLAYING && start.whiteToMove == mineWhite
    val legal = remember(start) { if (playing) start.legalMoves() else emptyList() }
    val shown = remember(pending) { pending?.let { start.copy().apply { play(it) } } ?: start }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
        Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 6.dp) {
            BoxWithConstraints(Modifier.padding(16.dp)) {
                val square = minOf((maxWidth - 2.dp) / 8, 46.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (playing) "Your move as ${if (mineWhite) "white" else "black"}" else "Chess", style = MaterialTheme.typography.titleMedium)
                    val targets = selected?.let { from -> legal.filter { it.from == from }.map { it.to }.toSet() }.orEmpty()
                    ChessBoard(shown, !mineWhite, square, selected, if (pending == null) targets else emptySet(),
                        pending?.let { it.from to it.to }, if (playing && pending == null) { index ->
                            val piece = start.board[index]
                            val from = selected
                            when {
                                from != null && index in targets -> {
                                    val options = legal.filter { it.from == from && it.to == index }
                                    if (options.size > 1) promotionFor = from to index else pending = options.first()
                                    selected = null
                                }
                                piece != 0 && (piece > 0) == mineWhite -> selected = index
                                else -> selected = null
                            }
                        } else null)
                    promotionFor?.let { (from, to) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (kind in listOf(Chess.QUEEN, Chess.ROOK, Chess.BISHOP, Chess.KNIGHT)) OutlinedButton(onClick = {
                                pending = Chess.Move(from, to, kind); promotionFor = null
                            }) { Text(pieceGlyph[kind] + "\uFE0E", fontSize = 20.sp) }
                        }
                    }
                    val move = pending
                    if (move != null) {
                        val san = start.san(move)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pending = null }) { Text("Undo") }
                            Button(onClick = {
                                val moves = message.moves + start.uci(move)
                                val after = Chess.replay(moves)!!
                                val summary = when (after.outcome()) {
                                    Chess.Outcome.CHECKMATE -> "I played $san. Checkmate!"
                                    Chess.Outcome.STALEMATE -> "I played $san. Stalemate, it's a draw."
                                    Chess.Outcome.DRAW -> "I played $san. It's a draw."
                                    else -> "I played $san (move ${(moves.size + 1) / 2}). Your turn!"
                                }
                                send(chessText(message.id, mineWhite, moves, summary))
                            }) { Text("Send $san") }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (playing) TextButton(onClick = { send(chessText(message.id, mineWhite, message.moves, "I resign. Good game!", resign = true)) }) {
                            Text("Resign", color = MaterialTheme.colorScheme.error)
                        }
                        if (!playing && canPlay && (message.resigned || start.outcome() != Chess.Outcome.PLAYING)) {
                            TextButton(onClick = { send(newChessGame(!mineWhite)) }) { Text("Rematch") }
                        }
                        TextButton(onClick = dismiss) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChessChallengeDialog(dismiss: () -> Unit, post: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Play chess") }, text = {
        Text("Take turns right in this chat. People on other apps get each move as text with a link to see the board.")
    }, confirmButton = {
        Row {
            TextButton(onClick = { post(newChessGame(false)); dismiss() }) { Text("Play black") }
            TextButton(onClick = { post(newChessGame(true)); dismiss() }) { Text("Play white") }
        }
    }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

/** What cards in a chat need from the conversation around them. */
internal class RichContext(
    val polls: Map<String, PollTally>,
    /** The newest message of each chess game, by game id: only that one offers a move. */
    val latestChess: Map<String, String>,
    val respond: ((String) -> Unit)?,
    val previews: Boolean,
    /** Show pictures and GIFs from links; off means they stay plain links. */
    val pictures: Boolean = true,
)

internal fun richContext(lines: List<ChatLine>, respond: ((String) -> Unit)?, previews: Boolean, pictures: Boolean = true): RichContext {
    val latest = mutableMapOf<String, String>()
    for (line in lines) parseChess(line.text)?.let { latest[it.id] = line.id }
    return RichContext(tallyPolls(lines), latest, respond, previews, pictures)
}

/** A short inbox line for a card message, so the inbox never shows the raw text behind it. */
internal fun cardPreview(text: String): String? {
    parsePictureLink(text)?.let { return if (it.sticker) "Sticker" else "GIF" }
    parsePoll(text)?.let { return "📊 Poll: " + it.question }
    parseVote(text)?.let { return "🗳️ Voted “" + it.second + "”" }
    parseEvent(text)?.let { return "📅 " + it.title }
    parseChess(text)?.let { return "♟️ " + text.substringAfter("Chess · ").substringBefore('\n') }
    parseMoney(text)?.let { return "💸 " + (if (it.sent) "Sent " else "Requested ") + it.amount + " " + it.asset.substringBefore(" on ").substringBefore(" via ") }
    return null
}
