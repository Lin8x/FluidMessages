package org.fluidmessages

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Muting for a while or for good: a chat, every address of one person, or every channel, room, and DM on a server.
 * Muted chats don't notify, sound, or vibrate and don't count on the tabs; their messages still arrive and show unread.
 *
 * Storage stays what older versions read: "muted" is the JSON list of muted keys, a chat's address or
 * "server:<account id>" for a whole server (AccountKeys ids, the same as FluidMessages Web's). "mutedUntil" is a JSON
 * object of when each timed mute ends, in epoch milliseconds; a key without one is muted forever, as every mute from
 * before was. A mute whose time is up simply stops counting, so nothing has to run when it ends, and it's tidied
 * away the next time the list is read ([active]). The same rules as FluidMessages Web (server/chats.js).
 */
object Mutes {
    const val LIST = "muted"
    const val UNTIL = "mutedUntil"
    const val SERVER = "server:"
    /** What [ends] returns for a key that isn't muted (or whose time is up). 0 means forever. */
    const val NOT_MUTED = -1L
    const val HOUR = 3_600_000L

    /** The choices on the mute page, as (label, milliseconds); 0 is forever. */
    @JvmField val CHOICES: List<Pair<String, Long>> =
        listOf("1 hour" to HOUR, "6 hours" to 6 * HOUR, "1 day" to 24 * HOUR, "1 week" to 7 * 24 * HOUR, "Forever" to 0L)

    @JvmStatic fun serverKey(accountId: String) = SERVER + accountId

    // The rules, on plain values so they can be tested.

    /** When [key]'s own mute ends: 0 forever, the end time, or [NOT_MUTED]. */
    fun ends(muted: Collection<String>, until: Map<String, Long>, key: String, now: Long): Long {
        if (key.isEmpty() || key !in muted) return NOT_MUTED
        val end = until[key]?.takeIf { it > 0 } ?: return 0L
        return if (end <= now) NOT_MUTED else end
    }

    /** The longer of two mutes: forever beats any time, and [NOT_MUTED] is no mute. */
    fun longer(a: Long, b: Long): Long = when {
        a < 0 -> b
        b < 0 -> a
        a == 0L || b == 0L -> 0L
        else -> maxOf(a, b)
    }

    /** A chat's mute, counting its server's ([server] is its account id, or ""). */
    fun chatEnds(muted: Collection<String>, until: Map<String, Long>, address: String, server: String, now: Long): Long =
        longer(ends(muted, until, address, now), if (server.isEmpty()) NOT_MUTED else ends(muted, until, serverKey(server), now))

    /**
     * Several keys, such as all of one person's addresses, are muted when every one of them is; the soonest end is
     * what shows. [serverOf] gives each address's server, so a muted server counts.
     */
    fun allEnds(muted: Collection<String>, until: Map<String, Long>, keys: Collection<String>, now: Long,
                serverOf: (String) -> String = { "" }): Long {
        if (keys.isEmpty()) return NOT_MUTED
        var soonest = 0L
        for (key in keys) {
            val end = if (key.startsWith(SERVER)) ends(muted, until, key, now) else chatEnds(muted, until, key, serverOf(key), now)
            if (end < 0) return NOT_MUTED
            if (end > 0 && (soonest == 0L || end < soonest)) soonest = end
        }
        return soonest
    }

    /** The mutes still running: those whose time is up are taken out, and ends for keys no longer muted. */
    fun pruned(muted: Collection<String>, until: Map<String, Long>, now: Long): Pair<Set<String>, Map<String, Long>> {
        val keep = muted.filterTo(LinkedHashSet()) { key -> until[key]?.let { it <= 0 || it > now } ?: true }
        return keep to until.filter { (key, end) -> key in keep && end > now }
    }

    /** After muting [keys] for [duration] milliseconds, or forever with 0. */
    fun muted(muted: Collection<String>, until: Map<String, Long>, keys: Collection<String>, duration: Long, now: Long):
        Pair<Set<String>, Map<String, Long>> {
        val (list, ends) = pruned(muted, until, now)
        val nextList = LinkedHashSet(list).apply { addAll(keys) }
        val nextEnds = ends.toMutableMap()
        for (key in keys) if (duration > 0) nextEnds[key] = now + duration else nextEnds.remove(key)
        return nextList to nextEnds
    }

    fun unmuted(muted: Collection<String>, until: Map<String, Long>, keys: Collection<String>, now: Long):
        Pair<Set<String>, Map<String, Long>> {
        val (list, ends) = pruned(muted, until, now)
        return list.filterTo(LinkedHashSet()) { it !in keys } to ends.filterKeys { it !in keys }
    }

    /**
     * "Muted" (forever), "Muted until 3:40 PM" (later today), or "Muted for 6 hours" / "1 day" / "1 week".
     * Empty when not muted. [clock] writes a time of day; [sameDay] says whether two times fall on the same day.
     */
    fun label(end: Long, now: Long, clock: (Long) -> String, sameDay: (Long, Long) -> Boolean): String {
        if (end < 0) return ""
        if (end == 0L) return "Muted"
        if (sameDay(end, now)) return "Muted until " + clock(end)
        val hours = ((end - now + HOUR - 1) / HOUR).toInt()
        if (hours < 24) return "Muted for $hours hour" + if (hours == 1) "" else "s"
        val days = maxOf(1, Math.round((end - now) / (24.0 * HOUR)).toInt())
        return "Muted for " + when (days) { 7 -> "1 week"; 1 -> "1 day"; else -> "$days days" }
    }

    // This phone's settings

    @JvmStatic fun until(prefs: SharedPreferences): Map<String, Long> = runCatching {
        val json = JSONObject(prefs.getString(UNTIL, "{}") ?: "{}")
        json.keys().asSequence().associateWith { json.optLong(it) }.filterValues { it > 0 }
    }.getOrDefault(emptyMap())

    /** Writes [state] into [edit] as the "muted" list and "mutedUntil" ends. */
    @JvmStatic fun write(edit: SharedPreferences.Editor, state: Pair<Set<String>, Map<String, Long>>): SharedPreferences.Editor {
        edit.putString(LIST, JSONArray(state.first).toString())
        return if (state.second.isEmpty()) edit.remove(UNTIL) else edit.putString(UNTIL, JSONObject(state.second).toString())
    }

    /** Every running mute, key → end (0 forever). Mutes whose time is up are tidied away here. */
    @JvmStatic fun active(prefs: SharedPreferences, now: Long = System.currentTimeMillis()): Map<String, Long> {
        val list = MainActivity.addressList(prefs, LIST)
        val ends = until(prefs)
        val (keep, keepEnds) = pruned(list, ends, now)
        if (keep.size != list.size || keepEnds.size != ends.size) write(prefs.edit(), keep to keepEnds).apply()
        return keep.associateWith { keepEnds[it] ?: 0L }
    }

    /** The server a chat belongs to, or "". */
    @JvmStatic fun serverOf(prefs: SharedPreferences, address: String): String =
        runCatching { GatewayClient.serverOf(prefs, address) }.getOrDefault("")

    /** When a chat's mute ends, counting its server's: 0 forever, a time, or [NOT_MUTED]. */
    @JvmStatic @JvmOverloads
    fun endsFor(prefs: SharedPreferences, address: String, now: Long = System.currentTimeMillis()): Long {
        val running = active(prefs, now)
        if (running.isEmpty()) return NOT_MUTED
        var end = chatEnds(running.keys, running, address, if (running.keys.any { it.startsWith(SERVER) }) serverOf(prefs, address) else "", now)
        // A number muted from a contact page may be written differently from how the carrier sends it.
        if (end < 0 && SmsStore.validRecipient(address)) for ((key, value) in running) {
            if (!key.startsWith(SERVER) && SmsStore.validRecipient(key) && android.telephony.PhoneNumberUtils.compare(key, address)) end = longer(end, value)
        }
        return end
    }

    /** Whether a chat is muted right now, by itself or its server. Every notification checks this. */
    @JvmStatic fun isMuted(prefs: SharedPreferences, address: String?): Boolean =
        !address.isNullOrEmpty() && endsFor(prefs, address) >= 0

    /** When [keys] (one chat, all of a person's addresses, or a server key) are muted until, or [NOT_MUTED]. */
    @JvmStatic fun endsForAll(prefs: SharedPreferences, keys: Collection<String>): Long {
        val now = System.currentTimeMillis()
        val running = active(prefs, now)
        return allEnds(running.keys, running, keys, now) { serverOf(prefs, it) }
    }

    /**
     * The addresses muted right now, for the tab badge and the Unread folder: muted chats, plus every chat on a muted
     * server.
     */
    @JvmStatic fun mutedAddresses(prefs: SharedPreferences): Set<String> {
        val running = active(prefs)
        val result = running.keys.filterTo(HashSet()) { !it.startsWith(SERVER) }
        val servers = running.keys.filter { it.startsWith(SERVER) }.map { it.removePrefix(SERVER) }.toSet()
        if (servers.isNotEmpty()) for (route in GatewayClient.allRoutes(prefs)) {
            if (route.optString("account") in servers) result += route.optString("address")
        }
        return result
    }

    @JvmStatic fun mute(prefs: SharedPreferences, keys: Collection<String>, duration: Long) {
        val now = System.currentTimeMillis()
        write(prefs.edit(), muted(MainActivity.addressList(prefs, LIST), until(prefs), keys, duration, now)).apply()
    }

    @JvmStatic fun unmute(prefs: SharedPreferences, keys: Collection<String>) {
        val now = System.currentTimeMillis()
        write(prefs.edit(), unmuted(MainActivity.addressList(prefs, LIST), until(prefs), keys, now)).apply()
    }

    @JvmStatic fun label(end: Long): String = label(end, System.currentTimeMillis(),
        { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }, ::sameDay)

    /** When a choice on the mute page would end, for its second line: "Until 3:40 PM", "Until Tue 9:00 AM". */
    @JvmStatic fun choiceDetail(duration: Long): String {
        if (duration <= 0) return "Until you unmute"
        val now = System.currentTimeMillis()
        val end = now + duration
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(end))
        return if (sameDay(end, now)) "Until $time" else "Until " + java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(end)) + " " + time
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val one = Calendar.getInstance().apply { timeInMillis = a }
        val two = Calendar.getInstance().apply { timeInMillis = b }
        return one.get(Calendar.YEAR) == two.get(Calendar.YEAR) && one.get(Calendar.DAY_OF_YEAR) == two.get(Calendar.DAY_OF_YEAR)
    }
}
