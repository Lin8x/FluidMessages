package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Muting for a while or for good (Mutes): expiry, forever, people and servers, and the older muted list. */
class MutesTest {
    private val now = 1_800_000_000_000L
    private val hour = Mutes.HOUR
    private val server = "irc:fixture@irc.example.test"

    @Test fun olderEntriesWithoutAnEndAreForever() {
        // Every mute from before timed mutes is just a key in the list.
        assertEquals(0L, Mutes.ends(setOf("+12025550123"), emptyMap(), "+12025550123", now))
        assertEquals(Mutes.NOT_MUTED, Mutes.ends(setOf("+12025550123"), emptyMap(), "+12025550199", now))
        // An end of 0 or below also means forever.
        assertEquals(0L, Mutes.ends(setOf("maya"), mapOf("maya" to 0L), "maya", now))
    }

    @Test fun timedMutesEndOnTheirOwn() {
        val (list, until) = Mutes.muted(emptySet(), emptyMap(), listOf("maya"), 6 * hour, now)
        assertEquals(now + 6 * hour, Mutes.ends(list, until, "maya", now))
        assertEquals(now + 6 * hour, Mutes.ends(list, until, "maya", now + 6 * hour - 1))
        assertEquals(Mutes.NOT_MUTED, Mutes.ends(list, until, "maya", now + 6 * hour))
        // Lazily tidied: the ended mute and its end are gone; a forever one stays.
        val (kept, ends) = Mutes.pruned(list + "sam", until, now + 7 * hour)
        assertEquals(setOf("sam"), kept)
        assertTrue(ends.isEmpty())
    }

    @Test fun everyChoiceAndForever() {
        for ((label, duration) in Mutes.CHOICES) {
            val (list, until) = Mutes.muted(emptySet(), emptyMap(), listOf("leo"), duration, now)
            assertEquals(label, if (duration == 0L) 0L else now + duration, Mutes.ends(list, until, "leo", now))
        }
        assertEquals(listOf("1 hour", "6 hours", "1 day", "1 week", "Forever"), Mutes.CHOICES.map { it.first })
        // Forever replaces a time; a time replaces forever.
        var state = Mutes.muted(emptySet(), emptyMap(), listOf("leo"), hour, now)
        state = Mutes.muted(state.first, state.second, listOf("leo"), 0, now)
        assertEquals(0L, Mutes.ends(state.first, state.second, "leo", now + 10 * hour))
        state = Mutes.muted(state.first, state.second, listOf("leo"), hour, now)
        assertEquals(now + hour, Mutes.ends(state.first, state.second, "leo", now))
        state = Mutes.unmuted(state.first, state.second, listOf("leo"), now)
        assertEquals(Mutes.NOT_MUTED, Mutes.ends(state.first, state.second, "leo", now))
        assertFalse("leo" in state.second)
    }

    @Test fun aServerCoversAllItsChats() {
        val (list, until) = Mutes.muted(emptySet(), emptyMap(), listOf(Mutes.serverKey(server)), hour, now)
        assertEquals("server:$server", Mutes.serverKey(server))
        assertEquals(now + hour, Mutes.chatEnds(list, until, "#radio", server, now))
        assertEquals(Mutes.NOT_MUTED, Mutes.chatEnds(list, until, "#radio", "", now))
        assertEquals(Mutes.NOT_MUTED, Mutes.chatEnds(list, until, "#radio", server, now + hour))
        // The chat's own mute and its server's: the longer one counts, and forever beats any time.
        val withChat = Mutes.muted(list, until, listOf("#radio"), 0, now)
        assertEquals(0L, Mutes.chatEnds(withChat.first, withChat.second, "#radio", server, now))
        assertEquals(5L, Mutes.longer(3L, 5L))
        assertEquals(0L, Mutes.longer(0L, 5L))
        assertEquals(5L, Mutes.longer(Mutes.NOT_MUTED, 5L))
    }

    @Test fun aPersonCoversAllTheirAddresses() {
        val addresses = listOf("+12025550123", "@maya:example.org", "maya@example.org")
        val (list, until) = Mutes.muted(emptySet(), emptyMap(), addresses, 24 * hour, now)
        for (address in addresses) assertEquals(now + 24 * hour, Mutes.ends(list, until, address, now))
        assertEquals(now + 24 * hour, Mutes.allEnds(list, until, addresses, now))
        // Muted only while every address is; the soonest end shows.
        assertEquals(Mutes.NOT_MUTED, Mutes.allEnds(list, until, addresses + "+12025550199", now))
        val longer = Mutes.muted(list, until, listOf("@maya:example.org"), 0, now)
        assertEquals(now + 24 * hour, Mutes.allEnds(longer.first, longer.second, addresses, now))
        val forever = Mutes.muted(emptySet(), emptyMap(), addresses, 0, now)
        assertEquals(0L, Mutes.allEnds(forever.first, forever.second, addresses, now))
        // A muted server counts for an address on it.
        val onServer = Mutes.muted(emptySet(), emptyMap(), listOf(Mutes.serverKey(server)), 0, now)
        assertEquals(0L, Mutes.allEnds(onServer.first, onServer.second, listOf("kestrel"), now) { server })
    }

    @Test fun labels() {
        val clock = { time: Long -> "T" + (time - now) / 60_000 }
        val sameDay = { a: Long, b: Long -> a / (24 * hour) == b / (24 * hour) }
        val start = (now / (24 * hour)) * (24 * hour) + 21 * hour  // 9 PM on a fictional day
        fun label(end: Long) = Mutes.label(end, start, clock, sameDay)
        assertEquals("", label(Mutes.NOT_MUTED))
        assertEquals("Muted", label(0))
        assertEquals("Muted until " + clock(start + hour), label(start + hour))
        assertEquals("Muted for 6 hours", label(start + 6 * hour))
        assertEquals("Muted for 1 day", label(start + 24 * hour))
        assertEquals("Muted for 1 week", label(start + 7 * 24 * hour))
    }
}
