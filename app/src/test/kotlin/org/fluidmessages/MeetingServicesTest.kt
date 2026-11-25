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

    @Test fun zoomIsFirstAndAllFourAreOnAtFirst() {
        val fresh = MeetingSettings.from(emptyMap<String, Any>())
        assertEquals(listOf(zoom, meet, teams, jitsi), MeetingService.entries.toList())
        assertEquals(listOf(zoom, meet, teams, jitsi), fresh.enabled)
        assertEquals(zoom, fresh.preselected)
        assertEquals("meet.jit.si", fresh.jitsiServer)
        assertTrue(fresh.ready(jitsi))
        assertFalse(fresh.ready(zoom))
        assertFalse(fresh.ready(meet))
        // Picking one with no saved link leads to its setup page, which this line points to.
        assertEquals("Save your own Google Meet link first", fresh.detail(meet))
        assertEquals("Save your own Microsoft Teams link first", fresh.detail(teams))
    }

    @Test fun aChoiceMadeBeforeGoogleMeetAndTeamsWereOnAtFirstStays() {
        // Only a switch you flipped is stored. Someone who turned Teams off, or Google Meet on, keeps that.
        val before = MeetingSettings.from(mapOf("meeting.on.teams" to false, "meeting.on.meet" to true, "meeting.on.jitsi" to false))
        assertEquals(listOf(zoom, meet), before.enabled)
        // Someone who never touched them now sees both.
        assertEquals(listOf(zoom, meet, teams, jitsi), MeetingSettings.from(mapOf("meeting.on.zoom" to true)).enabled)
    }

    @Test fun theLastOneUsedIsPickedWhileItIsStillOn() {
        assertEquals(jitsi, MeetingSettings.from(mapOf("meeting.last" to "jitsi")).preselected)
        assertEquals(meet, MeetingSettings.from(mapOf("meeting.last" to "meet")).preselected)
        // Google Meet was used last but is off now, so it's back to the first one that's on.
        assertEquals(zoom, MeetingSettings.from(mapOf("meeting.last" to "meet", "meeting.on.meet" to false)).preselected)
        assertEquals(meet, MeetingSettings.from(mapOf("meeting.last" to "zoom", "meeting.on.zoom" to false)).preselected)
        assertEquals(zoom, MeetingSettings.from(mapOf("meeting.last" to "nonsense")).preselected)
    }

    @Test fun eachServiceTurnsOnOrOffAndOneAlwaysStaysOn() {
        val all = MeetingSettings.from(emptyMap<String, Any>())
        assertEquals(listOf(zoom, meet, teams, jitsi), all.enabled)
        val onlyTeams = MeetingSettings.from(mapOf("meeting.on.zoom" to false, "meeting.on.jitsi" to false, "meeting.on.meet" to false))
        assertEquals(listOf(teams), onlyTeams.enabled)
        assertFalse(onlyTeams.canTurnOff(teams))
        assertTrue(all.canTurnOff(zoom))
        // Everything off (from an edited backup, say) still leaves something to pick.
        val none = MeetingSettings.from(mapOf("meeting.on.zoom" to false, "meeting.on.jitsi" to false, "meeting.on.meet" to false, "meeting.on.teams" to false))
        assertEquals(listOf(zoom, meet, teams, jitsi), none.enabled)
    }

    @Test fun someoneWhoSetUpJitsiBeforeKeepsIt() {
        // Before there was a choice, the only setting was the Jitsi server.
        val before = MeetingSettings.from(mapOf("meetingServer" to "meet.example.org"))
        assertEquals("meet.example.org", before.jitsiServer)
        assertEquals(listOf(zoom, meet, teams, jitsi), before.enabled)
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

    // Your own services, with fictional names and links.
    private val work = MeetingService.custom("c0a1b2c3d", "Work video", "https://video.example.org/rooms/team?pin=1234", "video.example.org")

    @Test fun aNameForYourOwnServiceIsCheckedAndTidied() {
        assertEquals("Work video", MeetingLinks.cleanName("  Work   video "))
        assertEquals("A".repeat(32), MeetingLinks.cleanName("A".repeat(32)))
        for (wrong in listOf("", "   ", "A".repeat(33), "Zoom", "google meet", "Teams", "Microsoft Teams", "Jitsi Meet", "Work‮video"))
            assertNull(wrong, MeetingLinks.cleanName(wrong))
        assertEquals("Give your service a name.", MeetingLinks.nameProblem(" "))
        assertEquals("Use a shorter name, 32 letters or fewer.", MeetingLinks.nameProblem("A".repeat(33)))
        assertEquals("Zoom is already on the list. Use another name.", MeetingLinks.nameProblem("Zoom"))
        assertEquals("You already have a service named work video.", MeetingLinks.nameProblem("work video", listOf("Work video")))
        assertNull(MeetingLinks.nameProblem("Book club"))
    }

    @Test fun aLinkForYourOwnServiceIsAnHttpsLinkOnItsHost() {
        assertEquals("https://video.example.org/rooms/team", MeetingLinks.validCustom(" video.example.org/rooms/team "))
        assertEquals("https://video.example.org/x", MeetingLinks.validCustom("http://video.example.org/x"))
        assertEquals("https://eu.video.example.org/x", MeetingLinks.validCustom("https://eu.video.example.org/x", "video.example.org"))
        assertEquals("https://video.example.org:8443/x?y=1#z", MeetingLinks.validCustom("https://video.example.org:8443/x?y=1#z", "video.example.org"))
        for (wrong in listOf("", "ftp://video.example.org/x", "javascript:alert(1)", "https://video.example.org/a b", "https://localhost/x",
            "https://video.example.org@other.example.net/x", "https://" + "a".repeat(2000) + ".org", "mailto:someone@example.org"))
            assertNull(wrong, MeetingLinks.validCustom(wrong))
        // The host catches a typo, and a look-alike host.
        for (wrong in listOf("https://vidoe.example.org/x", "https://video.example.org.example.net/x", "https://badvideo.example.org/x"))
            assertNull(wrong, MeetingLinks.validCustom(wrong, "video.example.org"))
        assertEquals("video.example.org", MeetingLinks.cleanHost(" https://Video.Example.org/ "))
        assertEquals("", MeetingLinks.cleanHost(""))
        assertNull(MeetingLinks.cleanHost("video example"))
        assertNull(MeetingLinks.cleanHost("video.example.org/rooms"))
        assertEquals("That link isn’t on video.example.org. Check it for a typo.", MeetingLinks.linkProblem("https://vidoe.example.org/x", "video.example.org"))
        assertEquals("Paste your link first.", MeetingLinks.linkProblem("", ""))
        assertNull(MeetingLinks.linkProblem("https://video.example.org/x", "video.example.org"))
    }

    @Test fun yourOwnServiceIsSavedShownAndRemoved() {
        val empty = MeetingSettings.from(emptyMap<String, Any>())
        assertEquals("Give your service a name.", empty.customProblem(null, "", "https://video.example.org/x", ""))
        assertEquals("Enter just the web address, like video.example.org.", empty.customProblem(null, "Work video", "https://video.example.org/x", "not a host"))
        assertNull(empty.customProblem(null, "Work video", "video.example.org/rooms/team", "video.example.org"))
        val stored = HashMap<String, Any?>()
        fun apply(writes: Map<String, Any?>) { for ((key, value) in writes) if (value == null) stored.remove(key) else stored[key] = value }
        apply(empty.customWrites(null, "c0a1b2c3d", " Work video ", "video.example.org/rooms/team", "video.example.org"))
        var now = MeetingSettings.from(stored)
        val added = now.custom.single()
        assertEquals(listOf("c0a1b2c3d", "Work video", "https://video.example.org/rooms/team", "video.example.org"),
            listOf(added.id, added.label, added.customLink, added.customHost))
        assertEquals(listOf(zoom, meet, teams, jitsi, added), now.enabled)
        assertTrue(now.ready(added))
        assertEquals("Your own service · sends your saved link", now.detail(added))
        assertEquals("https://video.example.org/rooms/team", now.link(added))
        // The same name can't be added twice; the service itself can keep its name.
        assertEquals("You already have a service named Work video.", now.customProblem(null, "Work video", "https://video.example.org/y", ""))
        assertNull(now.customProblem(added.id, "Work video", "https://video.example.org/y", ""))
        // Edited, turned off, picked last, and removed.
        apply(now.customWrites(added.id, "unused", "Team room", "https://meet.example.net/r/1", ""))
        stored[MeetingSettings.onKey(added)] = false
        stored[MeetingSettings.LAST] = added.id
        now = MeetingSettings.from(stored)
        assertEquals(listOf("Team room", "https://meet.example.net/r/1", ""), now.custom.map { listOf(it.label, it.customLink, it.customHost) }.single())
        assertFalse(now.isOn(now.custom.single()))
        assertEquals(listOf(zoom, meet, teams, jitsi), now.enabled)
        apply(now.customRemoval(added.id))
        assertEquals(emptyMap<String, Any?>(), stored)
        assertEquals(emptyList<MeetingService>(), MeetingSettings.from(stored).custom)
        // Broken stored values are left out instead of failing.
        assertEquals(emptyList<MeetingService>(), MeetingSettings.from(mapOf("meeting.custom" to "c0a1b2c3d,../x,cffffffff",
            "meeting.name.c0a1b2c3d" to "Zoom", "meeting.link.c0a1b2c3d" to "https://video.example.org/x", "meeting.name.cffffffff" to "No link")).custom)
    }

    @Test fun yourOwnServiceReadsBackAsACallCardOnTheOtherSide() {
        assertEquals("📹 Join my Work video call: https://video.example.org/rooms/team?pin=1234", MeetingLinks.text(work, true, work.customLink!!))
        assertEquals("📞 Join my Work video call: https://video.example.org/rooms/team?pin=1234", MeetingLinks.text(work, false, work.customLink!!))
        for (service in listOf(work, MeetingService.custom("c1", "Video", "https://v.example.org/a"), MeetingService.custom("c2", "Book club 📚", "https://club.example.org/"),
            MeetingService.custom("c3", "A".repeat(32), "https://v.example.org/a"))) for (video in listOf(true, false)) {
            // The other phone doesn't know the name: it reads it from the message.
            val card = parseMeeting(MeetingLinks.text(service, video, service.customLink!!))!!
            // A name ending in "video" ("Work video") reads back without it on a video call, since only one "video" is sent.
            assertEquals(if (video) service.label.removeSuffix(" video") else service.label, card.service!!.label)
            assertTrue(card.service!!.custom)
            assertEquals(video, card.video)
            assertEquals(service.customLink, card.link)
            assertEquals(service.customLink, MeetingLinks.joinLink(card.service, video, card.link))
        }
        assertEquals("Work · video.example.org", parseMeeting(MeetingLinks.text(work, true, work.customLink!!))!!.place)
        // A built-in name still needs that service's link, and a name needs a real https link.
        assertNull(parseMeeting("📹 Join my Zoom video call: https://example.org/a/b"))
        assertNull(parseMeeting("📹 Join my Work video video call: http://video.example.org/x"))
        assertNull(parseMeeting("📹 Join my Work‮ video call: https://video.example.org/x"))
        assertNull(parseMeeting("📹 Join my " + "A".repeat(40) + " video call: https://video.example.org/x"))
        // Older messages read the same as before.
        assertEquals(zoom, parseMeeting("📹 Join my Zoom video call: https://zoom.us/j/1234567890")!!.service)
        assertTrue(parseMeeting("📹 Join my video call: https://meet.jit.si/Room-1")!!.video)
        assertFalse(parseMeeting("📞 Join my call: https://meet.jit.si/Room-1")!!.video)
    }

    @Test fun thePrivacyLineIsStraight() {
        assertEquals("Whoever runs Work video can see who joins and may record. For a private call, use an encrypted call instead.", work.privacy)
        assertEquals("Zoom can see who joins and may record. For a private call, use an encrypted call instead.", zoom.privacy)
        assertEquals("The meeting server can access the call. For a private call, use an encrypted call instead.", jitsi.privacy)
        for (service in MeetingService.entries.filter { it.needsSavedLink }) {
            assertEquals(3, service.steps.size)
            assertTrue(service.home.startsWith("https://"))
            assertEquals(service.example, MeetingLinks.valid(service, service.example))
        }
    }
}
