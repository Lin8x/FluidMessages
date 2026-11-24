package org.fluidmessages

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundsAndNotificationsTest {
    @Test
    fun volumeStaysBetweenZeroAndOneHundred() {
        assertEquals(0, SoundPrefs.volume(-20))
        assertEquals(100, SoundPrefs.volume(140))
        assertEquals(0.4f, SoundPrefs.gain(40), 0.0001f)
        assertEquals(0f, SoundPrefs.gain(0), 0f)
    }

    @Test
    fun mediaChoiceUsesMediaVolumeAndEverythingElseNotificationVolume() {
        assertEquals(AudioAttributes.USAGE_MEDIA, SoundPrefs.usage(SoundPrefs.MEDIA))
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_EVENT, SoundPrefs.usage(SoundPrefs.NOTIFICATION))
        // Before this setting existed, in-app sounds played as notifications.
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_EVENT, SoundPrefs.usage(null))
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_EVENT, SoundPrefs.usage("something old"))
    }

    @Test
    fun notificationsAreAllowedOnlyWhenPermissionAppAndChannelAllAgree() {
        assertTrue(NotificationStatus(true, true, true).allowed)
        assertEquals("Notifications: allowed", NotificationStatus(true, true, true).label)
        val noPermission = NotificationStatus(false, true, true)
        assertFalse(noPermission.allowed)
        assertTrue(noPermission.canAsk)
        assertEquals("Notifications: blocked", noPermission.label)
        val appOff = NotificationStatus(true, false, true)
        assertFalse(appOff.allowed)
        assertFalse(appOff.canAsk)
        assertFalse(NotificationStatus(true, true, false).allowed)
    }

    @Test
    fun onlyRecentUnreadReceivedMessagesNotify() {
        val now = 10L * IncomingAlerts.RECENT_MS
        assertTrue(IncomingAlerts.shouldNotify(1, unread = true, event = false, date = now - 1000, now = now))
        assertFalse("sent by me", IncomingAlerts.shouldNotify(2, true, false, now, now))
        assertFalse("already read", IncomingAlerts.shouldNotify(1, false, false, now, now))
        assertFalse("join or topic event", IncomingAlerts.shouldNotify(1, true, true, now, now))
        assertFalse("backlog from days ago", IncomingAlerts.shouldNotify(1, true, false, now - 2 * IncomingAlerts.RECENT_MS, now))
    }

    @Test
    fun oneNotificationPerChatForItsNewestMessage() {
        data class Row(val address: String, val date: Long)
        val rows = listOf(Row("chat-a", 1), Row("chat-b", 5), Row("chat-a", 9), Row("chat-a", 3))
        val picked = IncomingAlerts.newestPerChat(rows, { it.address }, { it.date })
        assertEquals(listOf(Row("chat-a", 9), Row("chat-b", 5)), picked)
    }
}
