package org.fluidmessages

import org.fluidmessages.BackgroundStatus.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Running now: which group each thing goes in, and its words. */
class BackgroundStatusTest {
    private val hour = 3_600_000L
    private val now = 10 * hour
    /** Hours and minutes since midnight of the test day, e.g. "9:41". */
    private val clock: (Long) -> String = { "%d:%02d".format(it / hour, it % hour / 60_000) }

    private fun rows(s: BackgroundStatus.Snapshot) = BackgroundStatus.rows(s, clock)
    private fun row(s: BackgroundStatus.Snapshot, id: String) = rows(s).single { it.id == id }
    private val irc = BackgroundStatus.Connection("IRC", on = true, stayConnected = true, live = true, state = "connected",
        since = 9 * hour + 41 * 60_000)

    @Test fun nothingSetUpMeansNothingRunning() {
        val rows = rows(BackgroundStatus.Snapshot(now))
        assertEquals(0, BackgroundStatus.running(rows))
        assertEquals("Nothing is running in the background", BackgroundStatus.summary(rows))
        assertTrue(rows.all { it.group == Group.OFF })
    }

    @Test fun stayConnectedIsAlwaysRunningWithItsTime() {
        val row = row(BackgroundStatus.Snapshot(now, connections = listOf(irc)), "direct-irc")
        assertEquals(Group.ALWAYS, row.group)
        assertEquals("Connected · since 9:41", row.state)
        assertTrue(row.costly)
        assertEquals(BackgroundStatus.Opens.ACCOUNTS, row.opens)
    }

    @Test fun withoutStayConnectedItsOnlyWhileOpen() {
        val row = row(BackgroundStatus.Snapshot(now, connections = listOf(irc.copy(stayConnected = false))), "direct-irc")
        assertEquals(Group.OPEN, row.group)
        assertFalse(row.costly)
    }

    @Test fun reconnectingSaysWhen() {
        val c = irc.copy(state = "reconnecting", retryAt = 10 * hour + 2 * 60_000)
        assertEquals("Reconnecting · next try at 10:02", BackgroundStatus.connectionWords(c, clock))
        assertEquals("Disconnected · tap Reconnect in its account",
            BackgroundStatus.connectionWords(c.copy(state = "disconnected", manual = true), clock))
    }

    @Test fun ircOnTheWebsConnectionIsNotRunningHere() {
        val row = row(BackgroundStatus.Snapshot(now, connections = listOf(irc.copy(usesWeb = true))), "direct-irc")
        assertEquals(Group.OFF, row.group)
    }

    @Test fun nearbyOnlyPausesInternetConnections() {
        val s = BackgroundStatus.Snapshot(now, connections = listOf(irc), nearbyOnly = true, nearbyOn = true, discoverable = true)
        assertEquals(Group.OFF, row(s, "direct-irc").group)
        assertTrue(row(s, "nearby").state.endsWith("Nearby only"))
    }

    @Test fun backgroundCheckShowsIntervalNextWifiAndBatterySaver() {
        val s = BackgroundStatus.Snapshot(now, checkScheduled = true, backgroundMinutes = 15, lastCheck = 9 * hour + 45 * 60_000,
            wifiOnly = true, paired = true)
        val row = row(s, "check")
        assertEquals(Group.SCHEDULE, row.group)
        assertEquals("Checks every 15 minutes · next at about 10:00 · Wi-Fi only", row.state)
        assertTrue(row.why.contains("syncs with FluidMessages Web"))
        assertEquals("Checks every 15 minutes · paused for Battery Saver · Wi-Fi only", row(s.copy(saverOn = true), "check").state)
        assertEquals(Group.OFF, row(s.copy(backgroundMinutes = 0), "check").group)
    }

    @Test fun androidsFifteenMinuteFloorShows() {
        val s = BackgroundStatus.Snapshot(now, checkScheduled = true, backgroundMinutes = 5)
        assertEquals("Checks every 15 minutes", row(s, "check").state)
    }

    @Test fun nextCountsOnPastSkippedWindows() {
        assertEquals("next at about 10:15", BackgroundStatus.nextAt(9 * hour + 30 * 60_000, 15, now + 60_000, clock))
        assertEquals(null, BackgroundStatus.nextAt(0, 15, now, clock))
    }

    @Test fun webSyncSaysLastAndNext() {
        val s = BackgroundStatus.Snapshot(now, paired = true, checkScheduled = true, lastCheck = 9 * hour + 50 * 60_000,
            lastWebSync = 9 * hour + 50 * 60_000)
        val row = row(s, "web")
        assertEquals(Group.SCHEDULE, row.group)
        assertEquals("Last synced at 9:50 · next at about 10:05", row.state)
        assertEquals(Group.OPEN, row(s.copy(checkScheduled = false), "web").group)
        assertEquals("Not synced yet", row(s.copy(checkScheduled = false, lastWebSync = 0), "web").state)
    }

    @Test fun backupsNotScheduledWithAndroidSaySo() {
        val backup = BackgroundStatus.Backup("local", "Backup to a folder", 1440, false, now - 2 * hour, scheduled = true)
        val s = BackgroundStatus.Snapshot(now, backups = listOf(backup))
        assertEquals("Every day · last at 8:00 · next in about 22 hours", row(s, "backup-local").state)
        assertTrue(row(s.copy(backups = listOf(backup.copy(scheduled = false))), "backup-local").state.contains("doesn’t have it scheduled"))
    }

    @Test fun nearbyDiscoverableIsAlwaysAndCostly() {
        val s = BackgroundStatus.Snapshot(now, nearbyOn = true, discoverable = true, nearbyLive = true, helping = true, bluetooth = true)
        val row = row(s, "nearby")
        assertEquals(Group.ALWAYS, row.group)
        assertEquals("Discoverable · looking now · passing messages along · Bluetooth scanning", row.state)
        assertTrue(row.costly)
        assertEquals(Group.OPEN, row(s.copy(discoverable = false), "nearby").group)
    }

    @Test fun torOnSaysWhoRunsIt() {
        val s = BackgroundStatus.Snapshot(now, torOn = true, torWords = "Connected through Tor")
        assertEquals("Connected through Tor · through Orbot", row(s, "tor").state)
        assertEquals("Connected through Tor · built in", row(s.copy(torEngine = "built-in"), "tor").state)
        assertEquals(Group.OFF, row(s.copy(torOn = false), "tor").group)
    }

    @Test fun sipAlwaysIsBackgroundUnlessTorHoldsCalls() {
        val s = BackgroundStatus.Snapshot(now, sipReady = true, sipMode = "always", sipStatus = "Ready for calls")
        assertEquals(Group.ALWAYS, row(s, "sip").group)
        assertEquals(Group.OFF, row(s.copy(callsHeldByTor = true), "sip").group)
        assertEquals(Group.OPEN, row(s.copy(sipMode = "open"), "sip").group)
    }

    @Test fun oneOffJobsAndAlarms() {
        val s = BackgroundStatus.Snapshot(now, disappearingAlarm = now + 12 * 60_000, outboxWaiting = true, otherJobs = listOf(999))
        assertEquals("Next one goes at 10:12", row(s, "disappearing").state)
        assertEquals(Group.SCHEDULE, row(s, "outbox").group)
        assertEquals("Android job 999", row(s, "job-999").state)
        assertEquals("3 things running", BackgroundStatus.summary(rows(s)))
    }

    @Test fun groupsComeInOrder() {
        val s = BackgroundStatus.Snapshot(now, connections = listOf(irc, irc.copy(label = "Matrix", stayConnected = false)),
            checkScheduled = true, torOn = true)
        val order = rows(s).map { it.group }
        assertEquals(order.sortedBy { it.ordinal }, order)
        assertEquals("3 things running", BackgroundStatus.summary(rows(s)))
    }
}
