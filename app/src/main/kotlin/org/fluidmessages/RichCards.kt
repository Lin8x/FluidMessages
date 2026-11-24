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
internal fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(8)

// Pictures and GIFs sent as links

internal data class PictureLink(val url: String, val sticker: Boolean)

private val pictureUrl = Regex("""^https://[^\s]+?\.(gif|webp|png|jpe?g)(\?[^\s#]*)?(#sticker)?$""", RegexOption.IGNORE_CASE)
private val gifHost = Regex("""^https://(media\d*\.giphy\.com|i\.giphy\.com|media\.tenor\.com|c\.tenor\.com)/[^\s]+(?<!\.mp4)(?<!\.webm)$""", RegexOption.IGNORE_CASE)

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

internal fun tallyPolls(lines: List<ChatLine>, gameMoves: Set<String> = emptySet()): Map<String, PollTally> {
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
        if (line.id in gameMoves) continue
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
    FormPage(onDismissRequest = dismiss, title = { Text("New poll") }, text = {
        // The full page scrolls already; a second scrolling area inside it would crash.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    "Add to calendar: ${event.googleLink()}", RSVP_ASK).joinToString("\n")

private const val RSVP_ASK = "Coming? Reply yes, maybe, or no."

/** An answer to an event invite. */
internal enum class Rsvp(val label: String, val mark: String) { GOING("Going", "✅"), MAYBE("Maybe", "🤔"), NO("Can't go", "❌") }

internal fun rsvpText(event: CalendarEvent, answer: Rsvp): String {
    val title = event.title.replace('”', '"').take(200)
    return when (answer) { Rsvp.GOING -> "✅ Going to “$title”"; Rsvp.MAYBE -> "🤔 Maybe going to “$title”"; Rsvp.NO -> "❌ Can't make “$title”" }
}

private val rsvpPattern = Regex("""^(✅ Going to|🤔 Maybe going to|❌ Can't make) “(.{1,200})”$""")
private val rsvpReply = Regex("""(?i)^\s*(yes|yeah|yep|yup|sure|going|i'?m in|count me in|maybe|perhaps|not sure|no|nope|can'?t|can ?not|i can'?t)\s*[.!]*\s*$""")

internal fun parseRsvp(text: String): Pair<Rsvp, String>? {
    val match = rsvpPattern.matchEntire(text) ?: return null
    val answer = when (match.groupValues[1]) { "✅ Going to" -> Rsvp.GOING; "🤔 Maybe going to" -> Rsvp.MAYBE; else -> Rsvp.NO }
    return answer to match.groupValues[2]
}

/**
 * Answers to each event, by the event message's id. FluidMessages sends "✅ Going to “Dinner”"; people on other apps
 * can just reply yes, maybe, or no to the newest invite that asks for it, while the event hasn't happened yet.
 */
internal fun tallyRsvps(lines: List<ChatLine>, now: Long = System.currentTimeMillis()): Map<String, Map<String, Rsvp>> {
    val byTitle = mutableMapOf<String, String>()
    val answers = mutableMapOf<String, LinkedHashMap<String, Rsvp>>()
    var newest: Pair<String, CalendarEvent>? = null
    var asks = false
    for (line in lines) {
        if (line.attachment != null) continue
        val person = if (line.outgoing) "you" else line.author ?: "them"
        val event = parseEvent(line.text)
        if (event != null) {
            byTitle[event.title.replace('”', '"').take(200)] = line.id
            newest = line.id to event
            asks = line.text.contains(RSVP_ASK)
            continue
        }
        val explicit = parseRsvp(line.text)
        if (explicit != null) {
            val target = byTitle[explicit.second] ?: continue
            answers.getOrPut(target) { LinkedHashMap() }[person] = explicit.first
            continue
        }
        val (eventId, invite) = newest ?: continue
        if (!asks || invite.end < now) continue
        val word = rsvpReply.matchEntire(line.text)?.groupValues?.get(1)?.lowercase() ?: continue
        val answer = when {
            word.startsWith("maybe") || word.startsWith("perhaps") || word.startsWith("not sure") -> Rsvp.MAYBE
            word.startsWith("no") || word.contains("can") -> Rsvp.NO
            else -> Rsvp.GOING
        }
        answers.getOrPut(eventId) { LinkedHashMap() }[person] = answer
    }
    return answers
}

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
    // A chooser, so a calendar app that doesn't work can be skipped for another one.
    return runCatching { context.startActivity(Intent.createChooser(intent, "Add to calendar")) }.isSuccess
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
internal fun EventCard(event: CalendarEvent, foreground: Color, answers: Map<String, Rsvp>? = null, respond: ((String) -> Unit)? = null) {
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
        val replies = answers.orEmpty()
        if (replies.isNotEmpty()) {
            val counts = Rsvp.entries.mapNotNull { answer -> replies.values.count { it == answer }.takeIf { it > 0 }?.let { "${answer.mark} $it ${answer.label.lowercase()}" } }
            Text(counts.joinToString(" · "), color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val names = replies.filterKeys { it != "you" && it != "them" }
            if (names.isNotEmpty()) Text(names.entries.joinToString(", ") { "${it.key} ${it.value.mark}" }, color = foreground.copy(alpha = .85f),
                fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (respond != null && event.end >= System.currentTimeMillis()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (answer in Rsvp.entries) {
                val chosen = replies["you"] == answer
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(foreground.copy(alpha = if (chosen) .32f else .12f))
                    .clickable(onClickLabel = answer.label) { if (!chosen) respond(rsvpText(event, answer)) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text((if (chosen) "✓ " else "") + answer.label, color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        var choosing by remember { mutableStateOf(false) }
        FilledTonalButton(onClick = { choosing = true }) { Text("Add to calendar") }
        if (missing) Text("No calendar app could open it. Use Save .ics to keep it.", color = foreground, fontSize = 12.sp)
        if (choosing) CalendarSheet(event, { choosing = false }) { missing = it }
    }
}

/** A calendar on this phone you can add events to: Google, CalDAV (DAVx⁵), Exchange, or one only on the phone. */
internal data class PhoneCalendar(val id: Long, val name: String, val account: String, val color: Int)

internal object PhoneCalendars {
    fun allowed(context: Context) = context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    fun list(context: Context): List<PhoneCalendar> = runCatching {
        val calendars = CalendarContract.Calendars.CONTENT_URI
        context.contentResolver.query(calendars, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(PhoneCalendar(cursor.getLong(0), cursor.getString(1) ?: "Calendar",
                cursor.getString(2).orEmpty(), cursor.getInt(3))) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** Saves the event straight into the calendar, without opening any other app. */
    fun add(context: Context, calendar: PhoneCalendar, event: CalendarEvent): Boolean = runCatching {
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar.id)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.start)
            put(CalendarContract.Events.DTEND, event.end)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else TimeZone.getDefault().id)
            if (event.location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, event.location)
            if (event.notes.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, event.notes)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
    }.getOrDefault(false)
}

/**
 * Where to add an event: straight into a calendar on this phone, another calendar app, Google Calendar on the web,
 * or an .ics file. None of these depends on one particular calendar app working.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarSheet(event: CalendarEvent, dismiss: () -> Unit, failed: (Boolean) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(PhoneCalendars.allowed(context)) }
    val calendars by produceState(emptyList<PhoneCalendar>(), allowed) {
        if (allowed) value = withContext(Dispatchers.IO) { PhoneCalendars.list(context) }
    }
    var done by remember { mutableStateOf<String?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { allowed = PhoneCalendars.allowed(context) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Add “${event.title}”", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            done?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp)) }
            @Composable fun Choice(title: String, detail: String, dot: Color? = null, run: () -> Unit) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = run).padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (dot != null) Box(Modifier.padding(end = 12.dp).size(12.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
                    Column {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!allowed) Choice("Add to a calendar on this phone", "Allow calendar access once; FluidMessages only adds events you tap") {
                permission.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
            } else if (calendars.isEmpty()) Text("No calendars on this phone accept new events. Add an account in a calendar app, or use an option below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            for (calendar in calendars) Choice(calendar.name, calendar.account, Color(calendar.color or 0xFF000000.toInt())) {
                done = if (PhoneCalendars.add(context, calendar, event)) "Added to ${calendar.name} ✓" else "Couldn't add it to ${calendar.name}"
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Choice("Open in a calendar app", "Choose which app") { failed(!addToCalendar(context, event)); dismiss() }
            Choice("Google Calendar on the web", "Opens in your browser") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.googleLink()))) }; dismiss()
            }
            Choice("Save .ics file", "The standard file every calendar can import") { shareIcs(context, event); dismiss() }
        }
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
    FormPage(onDismissRequest = dismiss, title = { Text("Share an event") }, text = {
        // The full page scrolls already; a second scrolling area inside it would crash.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (!PublicHosts.allowed(target)) return null
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

/** What cards in a chat need from the conversation around them. */
internal class RichContext(
    val polls: Map<String, PollTally>,
    /** Each message that's part of a game, by message id, including plain replies that counted as moves. */
    val games: Map<String, GameView>,
    val rps: Map<String, RpsView>,
    /** Answers to each event, by the event message's id. */
    val rsvps: Map<String, Map<String, Rsvp>>,
    val respond: ((String) -> Unit)?,
    val previews: Boolean,
    /** Show pictures and GIFs from links; off means they stay plain links. */
    val pictures: Boolean = true,
)

internal fun richContext(lines: List<ChatLine>, respond: ((String) -> Unit)?, previews: Boolean, pictures: Boolean = true): RichContext {
    val moves = mutableSetOf<String>()
    val games = trackGames(lines, moves)
    return RichContext(tallyPolls(lines, moves), games, trackRps(lines), tallyRsvps(lines), respond, previews, pictures)
}

/** A short inbox line for a card message, so the inbox never shows the raw text behind it. */
internal fun cardPreview(text: String): String? {
    parsePictureLink(text)?.let { return if (it.sticker) "Sticker" else "GIF" }
    parsePoll(text)?.let { return "📊 Poll: " + it.question }
    parseVote(text)?.let { return "🗳️ Voted “" + it.second + "”" }
    parseEvent(text)?.let { return "📅 " + it.title }
    parseRps(text)?.let { return "✊✋✌️ Rock paper scissors" }
    parseGameLink(text)?.let { return "🎮 ${it.game} on ${it.site}" }
    parseRandom(text)?.let { return text.substringBefore('\n') }
    parseEdit(text)?.let { return "✏️ " + plainPreview(it.second) }
    if (parseEffect(text) != null || text.contains("||")) return plainPreview(text).takeIf { it != text }
    parseGame(text)?.let { return "${it.kind.icon} ${it.kind.title} · " + text.substringAfter(" · ").substringBefore('\n') }
    parseMoney(text)?.let { return "💸 " + (if (it.sent) "Sent " else "Requested ") + it.amount + " " + it.asset.substringBefore(" on ").substringBefore(" via ") }
    return null
}
