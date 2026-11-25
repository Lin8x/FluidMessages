package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Meeting links: which service is offered and picked, what counts as a real link, and the message that carries it. */
class MeetingServicesTest {
    private val zoom = MeetingService.ZOOM
    private val meet = MeetingService.MEET
    private val teams = MeetingService.TEAMS
    private val jitsi = MeetingService.JITSI

    @Test fun zoomIsFirstAndZoomAndJitsiAreOnAtFirst() {
        val fresh = MeetingSettings.from(emptyMap<String, Any>())
        assertEquals(listOf(zoom, meet, teams, jitsi), MeetingService.entries.toList())
        assertEquals(listOf(zoom, jitsi), fresh.enabled)
        assertEquals(zoom, fresh.preselected)
        assertEquals("meet.jit.si", fresh.jitsiServer)
        assertTrue(fresh.ready(jitsi))
        assertFalse(fresh.ready(zoom))
    }

    @Test fun theLastOneUsedIsPickedWhileItIsStillOn() {
        assertEquals(jitsi, MeetingSettings.from(mapOf("meeting.last" to "jitsi")).preselected)
        assertEquals(meet, MeetingSettings.from(mapOf("meeting.last" to "meet", "meeting.on.meet" to true)).preselected)
        // Google Meet was used last but is off now, so it's back to the first one that's on.
        assertEquals(zoom, MeetingSettings.from(mapOf("meeting.last" to "meet")).preselected)
        assertEquals(jitsi, MeetingSettings.from(mapOf("meeting.last" to "zoom", "meeting.on.zoom" to false)).preselected)
        assertEquals(zoom, MeetingSettings.from(mapOf("meeting.last" to "nonsense")).preselected)
    }

    @Test fun eachServiceTurnsOnOrOffAndOneAlwaysStaysOn() {
        val all = MeetingSettings.from(mapOf("meeting.on.meet" to true, "meeting.on.teams" to true))
        assertEquals(listOf(zoom, meet, teams, jitsi), all.enabled)
        val onlyTeams = MeetingSettings.from(mapOf("meeting.on.zoom" to false, "meeting.on.jitsi" to false, "meeting.on.teams" to true))
        assertEquals(listOf(teams), onlyTeams.enabled)
        assertFalse(onlyTeams.canTurnOff(teams))
        assertTrue(all.canTurnOff(zoom))
        // Everything off (from an edited backup, say) still leaves something to pick.
        val none = MeetingSettings.from(mapOf("meeting.on.zoom" to false, "meeting.on.jitsi" to false))
        assertEquals(listOf(zoom, jitsi), none.enabled)
    }

    @Test fun someoneWhoSetUpJitsiBeforeKeepsIt() {
        // Before there was a choice, the only setting was the Jitsi server.
        val before = MeetingSettings.from(mapOf("meetingServer" to "meet.example.org"))
        assertEquals("meet.example.org", before.jitsiServer)
        assertEquals(listOf(zoom, jitsi), before.enabled)
        assertEquals(jitsi, before.preselected)
        assertEquals("Makes a new room each time on meet.example.org. No account needed.", before.detail(jitsi))
        // Once they pick something else, that choice wins.
        assertEquals(zoom, MeetingSettings.from(mapOf("meetingServer" to "meet.example.org", "meeting.last" to "zoom")).preselected)
        // The usual server, or a broken value, changes nothing.
        assertEquals(zoom, MeetingSettings.from(mapOf("meetingServer" to "meet.jit.si")).preselected)
        assertEquals("meet.jit.si", MeetingSettings.from(mapOf("meetingServer" to "not a server", "meeting.on.zoom" to "yes")).jitsiServer)
    }

    @Test fun zoomLinks() {
        assertEquals("https://zoom.us/j/1234567890", MeetingLinks.valid(zoom, " zoom.us/j/1234567890 "))
        assertEquals("https://us02web.zoom.us/j/12345678901?pwd=Fictional1", MeetingLinks.valid(zoom, "https://us02web.zoom.us/j/12345678901?pwd=Fictional1"))
        assertEquals("https://example.zoom.us/my/river.room", MeetingLinks.valid(zoom, "http://example.zoom.us/my/river.room"))
        for (wrong in listOf("", "https://zoom.us/", "https://zoom.us/profile", "https://zoom.us/j/12", "https://notzoom.us/j/1234567890",
            "https://zoom.us.example.org/j/1234567890", "https://example.org/?next=https://zoom.us/j/1234567890", "ftp://zoom.us/j/1234567890",
            "https://zoom.us/j/1234567890 and more", "https://meet.google.com/abc-defg-hij")) assertNull(wrong, MeetingLinks.valid(zoom, wrong))
    }

    @Test fun googleMeetLinks() {
        assertEquals("https://meet.google.com/abc-defg-hij", MeetingLinks.valid(meet, "meet.google.com/abc-defg-hij"))
        assertEquals("https://meet.google.com/abc-defg-hij?authuser=0", MeetingLinks.valid(meet, "https://meet.google.com/abc-defg-hij?authuser=0"))
        for (wrong in listOf("https://meet.google.com/", "https://meet.google.com/abcdefghij", "https://meet.google.com/abc-defg-hi",
            "https://meet.google.com.example.org/abc-defg-hij", "https://google.com/abc-defg-hij", "https://zoom.us/j/1234567890"))
            assertNull(wrong, MeetingLinks.valid(meet, wrong))
    }

    @Test fun teamsLinks() {
        val long = "https://teams.microsoft.com/l/meetup-join/19%3ameeting_Fictional%40thread.v2/0?context=%7b%22Tid%22%3a%22fictional%22%7d"
        assertEquals(long, MeetingLinks.valid(teams, long))
        assertEquals("https://teams.live.com/meet/9312345678901?p=Fictional", MeetingLinks.valid(teams, "teams.live.com/meet/9312345678901?p=Fictional"))
        assertEquals("https://teams.microsoft.com/meet/1234567890", MeetingLinks.valid(teams, "https://teams.microsoft.com/meet/1234567890"))
        for (wrong in listOf("https://teams.microsoft.com/", "https://teams.microsoft.com/v2/", "https://teams.example.org/meet/123",
            "https://teams.microsoft.com.example.org/meet/123", "https://teams.live.com/l/other/1")) assertNull(wrong, MeetingLinks.valid(teams, wrong))
    }

    @Test fun jitsiLinksAreOnTheKnownServers() {
        assertEquals("https://meet.jit.si/FluidMessages-0011223344556677", MeetingLinks.newJitsiRoom("meet.jit.si", "0011223344556677"))
        assertEquals(jitsi, MeetingLinks.serviceOf("https://meet.jit.si/FluidMessages-0011223344556677"))
        assertEquals(jitsi, MeetingLinks.serviceOf("https://meet.example.org/TrailClub", "meet.example.org"))
        assertNull(MeetingLinks.serviceOf("https://meet.example.org/TrailClub"))
        assertNull(MeetingLinks.serviceOf("https://example.org/article"))
        assertEquals(zoom, MeetingLinks.serviceOf("https://zoom.us/j/1234567890"))
        assertEquals("meet.example.org:8443", MeetingLinks.cleanServer("https://meet.example.org:8443/"))
        assertNull(MeetingLinks.cleanServer("meet example"))
    }

    @Test fun aSavedLinkIsSentAndABrokenOneCountsAsMissing() {
        val saved = MeetingSettings.from(mapOf("meeting.link.zoom" to "https://zoom.us/j/1234567890", "meeting.link.meet" to "https://example.org/x",
            "meeting.on.meet" to true))
        assertEquals("https://zoom.us/j/1234567890", saved.link(zoom))
        assertTrue(saved.ready(zoom))
        assertEquals("Sends your saved link", saved.detail(zoom))
        assertNull(saved.link(meet))
        assertEquals("Save your own Google Meet link first", saved.detail(meet))
    }

    @Test fun theMessageNamesTheServiceAndReadsBackAsACallCard() {
        assertEquals("📹 Join my video call: https://meet.jit.si/Room-1", MeetingLinks.text(jitsi, true, "https://meet.jit.si/Room-1"))
        assertEquals("📞 Join my call: https://meet.jit.si/Room-1", MeetingLinks.text(jitsi, false, "https://meet.jit.si/Room-1"))
        assertEquals("📹 Join my Zoom video call: https://zoom.us/j/1234567890", MeetingLinks.text(zoom, true, "https://zoom.us/j/1234567890"))
        assertEquals("📞 Join my Teams call: https://teams.live.com/meet/931?p=a", MeetingLinks.text(teams, false, "https://teams.live.com/meet/931?p=a"))
        for (service in MeetingService.entries) for (video in listOf(true, false)) {
            val link = if (service == jitsi) "https://meet.jit.si/Room-1" else service.example
            val card = MeetingLinks.parse(MeetingLinks.text(service, video, link))!!
            assertEquals(service, card.service)
            assertEquals(video, card.video)
            assertEquals(link, card.link)
        }
        // A Jitsi room on someone else's own server, as older versions sent it, is still a card.
        val older = parseMeeting("📹 Join my video call: https://meet.example.org/FluidMessages-00112233")!!
        assertNull(older.service)
        assertEquals("meet.example.org", older.place)
        assertEquals("Zoom", parseMeeting("Join my Zoom call: https://zoom.us/my/river.room")!!.place)
        assertEquals("📹 Video call · tap to join", meetingPreview("📹 Join my Google Meet video call: https://meet.google.com/abc-defg-hij"))
        // Not a card: an everyday sentence, or a link to somewhere that isn't a meeting.
        assertNull(parseMeeting("Join my call: https://example.org/a/b?c=d"))
        assertNull(parseMeeting("Can you join my call later?"))
    }

    @Test fun aMeetingLinkInAnEverydayMessageIsFound() {
        assertEquals(zoom to "https://zoom.us/j/1234567890?pwd=Fictional1", MeetingLinks.find("Trail club at 6: https://zoom.us/j/1234567890?pwd=Fictional1."))
        assertEquals(meet to "https://meet.google.com/abc-defg-hij", MeetingLinks.find("See https://example.org first, then (https://meet.google.com/abc-defg-hij)"))
        assertEquals(jitsi to "https://meet.jit.si/TrailClub", MeetingLinks.find("https://meet.jit.si/TrailClub"))
        assertNull(MeetingLinks.find("The map is at https://example.org/zoom.us/j/1234567890"))
        assertNull(MeetingLinks.find("No link here"))
    }

    @Test fun joiningAVoiceCallOnlyChangesJitsiLinks() {
        assertEquals("https://meet.jit.si/Room-1#config.startWithVideoMuted=true", MeetingLinks.joinLink(jitsi, false, "https://meet.jit.si/Room-1"))
        assertEquals("https://meet.jit.si/Room-1", MeetingLinks.joinLink(jitsi, true, "https://meet.jit.si/Room-1"))
        assertEquals("https://zoom.us/j/1234567890", MeetingLinks.joinLink(zoom, false, "https://zoom.us/j/1234567890"))
    }

    @Test fun thePrivacyLineIsStraight() {
        assertEquals("Zoom can see who joins and may record. For a private call, use an encrypted call instead.", zoom.privacy)
        assertEquals("The meeting server can access the call. For a private call, use an encrypted call instead.", jitsi.privacy)
        for (service in MeetingService.entries.filter { it.needsSavedLink }) {
            assertEquals(3, service.steps.size)
            assertTrue(service.home.startsWith("https://"))
            assertEquals(service.example, MeetingLinks.valid(service, service.example))
        }
    }
}
