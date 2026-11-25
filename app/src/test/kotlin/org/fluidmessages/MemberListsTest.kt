package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long member lists fold to the first five; ten or fewer always show in full (MemberLists). */
class MemberListsTest {
    private fun people(count: Int) = (1..count).map { "Fixture person $it" }

    @Test fun tenOrFewerShowInFull() {
        for (count in listOf(0, 1, 5, 6, 10)) {
            assertFalse(MemberLists.collapsible(count))
            assertEquals(people(count), MemberLists.shown(people(count), expanded = false))
            assertEquals("", MemberLists.toggleLabel(count, expanded = false))
        }
    }

    @Test fun moreThanTenShowTheFirstFive() {
        val eleven = people(11)
        assertTrue(MemberLists.collapsible(11))
        assertEquals(eleven.take(5), MemberLists.shown(eleven, expanded = false))
        assertEquals("Show all 11 members", MemberLists.toggleLabel(11, expanded = false))
        val channel = people(214)
        assertEquals(5, MemberLists.shown(channel, expanded = false).size)
        assertEquals("Show all 214 members", MemberLists.toggleLabel(214, expanded = false))
    }

    @Test fun expandingShowsEveryoneAndCollapsesAgain() {
        val many = people(40)
        assertEquals(many, MemberLists.shown(many, expanded = true))
        assertEquals("Show fewer", MemberLists.toggleLabel(40, expanded = true))
        assertEquals(many.take(5), MemberLists.shown(many, expanded = false))
    }
}
