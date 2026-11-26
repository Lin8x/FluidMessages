package org.fluidmessages

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "What is Tor?" shows once, on the way to turning Tor on; and Settings → Battery and sync's words and keys. */
class TorIntroAndBatteryTest {
    @Test
    fun torIntroShowsOnlyTheFirstTimeTorIsTurnedOn() {
        assertTrue(TorIntro.due(seen = false, turningOn = true))
        assertFalse(TorIntro.due(seen = true, turningOn = true))
        assertFalse("Turning Tor off never shows it", TorIntro.due(seen = false, turningOn = false))
        val prefs = TorBatteryPrefs()
        assertTrue(TorIntro.due(prefs, true))
        TorIntro.markSeen(prefs)
        assertEquals(true, prefs.values[TorIntro.SEEN_PREF])
        assertFalse("Once seen, Tor turns on straight to the warning", TorIntro.due(prefs, true))
    }

    @Test
    fun torIntroSaysWhatItIsAndIsNot() {
        assertTrue(TorIntro.ABOUT.contains("three computers"))
        assertTrue(TorIntro.not.any { it.contains("doesn’t lock your messages") })
        assertTrue(TorIntro.not.any { it.contains("Texts and phone calls never use Tor") })
        assertEquals(3, TorIntro.downsides.size)
        assertTrue(TorIntro.steps.last().contains("onion"))
        assertTrue(TorIntro.steps.any { it.contains("each chat’s details") })
    }

    @Test
    fun checkChoicesArePlainWords() {
        assertEquals(listOf("Every 10 seconds", "Every 20 seconds", "Every minute", "Only when I send something"),
            SyncPolicy.FOREGROUND_CHOICES.map { BatteryPage.foregroundLabel(it) })
        assertEquals(listOf("Every 15 minutes", "Every 30 minutes", "Every hour", "Every 6 hours", "Never"),
            SyncPolicy.BACKGROUND_CHOICES.map { BatteryPage.backgroundLabel(it) })
        assertEquals("Checks every 15 minutes when closed", BatteryPage.summary(15))
        assertEquals("Checks every hour when closed", BatteryPage.summary(60))
        assertEquals("Checks only while FluidMessages is open", BatteryPage.summary(0))
    }

    @Test
    fun batteryKeepsItsKeysForSettingsAndBackups() {
        assertEquals(listOf("syncForegroundSeconds", "syncBackgroundMinutes", "syncWifiOnly", "syncPauseOnBatterySaver", "attachmentDownloads"),
            listOf(SyncPolicy.FOREGROUND, SyncPolicy.BACKGROUND, SyncPolicy.WIFI_ONLY, SyncPolicy.BATTERY_SAVER, SyncPolicy.DOWNLOADS))
        val prefs = TorBatteryPrefs()
        assertEquals(20, SyncPolicy.foregroundSeconds(prefs))
        assertEquals(15, SyncPolicy.backgroundMinutes(prefs))
        assertEquals("wifi", SyncPolicy.downloads(prefs))
        prefs.edit().putInt(SyncPolicy.BACKGROUND, 360).apply()
        assertEquals("Checks every 6 hours when closed", BatteryPage.summary(prefs))
    }

    @Test
    fun theOpenCheckSaysItOnlyMattersWithFluidMessagesWeb() {
        assertTrue(BatteryPage.openDetail(true).contains("FluidMessages Web"))
        assertTrue(BatteryPage.openDetail(false).contains("isn’t set up"))
    }

    @Test
    fun whatKeepsAConnectionOpen() {
        val off = BatteryPage.uses(BatteryPage.Now(paired = false, stayConnected = emptyList(), discoverable = false, tor = false))
        assertEquals(listOf("FluidMessages Web", "Stay connected", "Nearby: Stay discoverable", "Connect through Tor"), off.map { it.title })
        assertTrue(off.all { it.state == "Off" })
        val on = BatteryPage.uses(BatteryPage.Now(paired = true, stayConnected = listOf("Matrix", "XMPP", "IRC"), discoverable = true, tor = true))
        assertEquals("On for Matrix, XMPP, and IRC", on[1].state)
        assertTrue(on[1].detail.contains("most battery"))
        assertEquals(listOf("On", "On", "On"), listOf(on[0], on[2], on[3]).map { it.state })
        assertEquals("On for Email and IRC",
            BatteryPage.uses(BatteryPage.Now(false, listOf("Email", "IRC"), false, false))[1].state)
    }
}

/** SharedPreferences in memory. */
private class TorBatteryPrefs : SharedPreferences {
    val values = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val changes = HashMap<String, Any?>()
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes.remove(key) }
        override fun clear() = apply { }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { values.putAll(changes) }
    }
}
