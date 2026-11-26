package org.fluidmessages

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLocksTest {
    private val rowan = Privacy.chatScope("+12025550146")
    private val texts = Privacy.protocolScope(MessageProtocol.SMS)

    @After fun closeAll() = Privacy.forget()

    @Test fun chatStaysOpenOnItsOwnPages() {
        val opened = setOf(rowan, texts)
        for (page in listOf("chat", "contact", "chatAppearance")) {
            assertEquals(page, opened, ChatLocks.stillOpen(opened, "+12025550146", page, null))
        }
        // Back from details to the same chat doesn't ask again.
        assertEquals(opened, ChatLocks.stillOpen(opened, "+12025550146", "chat", "+12025550146"))
    }

    @Test fun leavingTheChatLocksItAgain() {
        val opened = setOf(rowan, texts)
        assertEquals(emptySet<String>(), ChatLocks.stillOpen(opened, "+12025550146", "inbox", null))
        assertEquals(emptySet<String>(), ChatLocks.stillOpen(opened, "+12025550146", "settings", null))
        // A different chat locks the first one, and its kind's lock too.
        assertEquals(emptySet<String>(), ChatLocks.stillOpen(opened, "+12025550146", "chat", "+12025550188"))
        // Details opened from the inbox, with no chat open, keep nothing open.
        assertEquals(emptySet<String>(), ChatLocks.stillOpen(opened, null, "contact", null))
    }

    @Test fun onlyRealLeavingCounts() {
        assertTrue(ChatLocks.leftApp(screenOff = false, userLeaving = true, openedInside = false, prompting = false))
        assertTrue(ChatLocks.leftApp(screenOff = true, userLeaving = false, openedInside = true, prompting = true))
        // Android's PIN screen for a fingerprint or face prompt stops the app; that's not leaving (the old loop).
        assertFalse(ChatLocks.leftApp(screenOff = false, userLeaving = true, openedInside = false, prompting = true))
        assertFalse(ChatLocks.leftApp(screenOff = false, userLeaving = false, openedInside = false, prompting = true))
        // The photo picker or a call started from the chat.
        assertFalse(ChatLocks.leftApp(screenOff = false, userLeaving = true, openedInside = true, prompting = false))
    }

    @Test fun phoneUnlockLocksCountAsLocked() {
        val prefs = LockPrefs()
        assertFalse(Privacy.locked(prefs, "+12025550146", MessageProtocol.SMS))
        assertNull(Privacy.lockedPreview(prefs, "+12025550146", MessageProtocol.SMS))
        prefs.values["code.device.$rowan"] = true
        assertTrue(Privacy.usesScreenLock(prefs, rowan) && Privacy.hasLock(prefs, rowan))
        assertFalse("A screen-lock lock has no passcode, so the emergency erase check stays off", Privacy.hasCode(prefs, rowan))
        assertEquals(listOf(rowan), Privacy.lockedScopes(prefs, "+12025550146", MessageProtocol.SMS))
        assertEquals("Locked · unlock to read", Privacy.lockedPreview(prefs, "+12025550146", MessageProtocol.SMS))
        // Other chats aren't locked by it.
        assertFalse(Privacy.locked(prefs, "+12025550188", MessageProtocol.SMS))
        Privacy.markOpened(rowan)
        assertFalse(Privacy.locked(prefs, "+12025550146", MessageProtocol.SMS))
        Privacy.keepOpen(ChatLocks.stillOpen(Privacy.openedScopes(), "+12025550146", "inbox", null))
        assertTrue("Back in the inbox, it's locked again", Privacy.locked(prefs, "+12025550146", MessageProtocol.SMS))
    }

    @Test fun kindLocksAndPasscodesTogether() {
        val prefs = LockPrefs()
        prefs.values["code.device.$texts"] = true
        prefs.values["code.$rowan"] = "c2FsdA.aGFzaA"
        assertEquals(listOf(rowan, texts), Privacy.lockedScopes(prefs, "+12025550146", MessageProtocol.SMS))
        assertEquals("Locked · enter the passcode to read", Privacy.lockedPreview(prefs, "+12025550146", MessageProtocol.SMS))
        assertEquals(listOf(texts), Privacy.lockedScopes(prefs, "+12025550188", MessageProtocol.MMS))
        assertTrue(Privacy.lockedScopes(prefs, "web-kit", MessageProtocol.MATRIX).isEmpty())
        Privacy.removeLock(prefs, texts)
        assertFalse(Privacy.hasLock(prefs, texts))
        assertTrue(Privacy.hasCode(prefs, rowan))
        Privacy.setScreenLock(prefs, rowan)
        assertTrue(Privacy.usesScreenLock(prefs, rowan) && !Privacy.hasCode(prefs, rowan))
    }
}

/** SharedPreferences in memory, for the lock tests. */
private class LockPrefs : SharedPreferences {
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
        val removed = HashSet<String>()
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed += key; changes.remove(key) }
        override fun clear() = apply { removed += values.keys }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { for (key in removed) values.remove(key); values.putAll(changes) }
    }
}
