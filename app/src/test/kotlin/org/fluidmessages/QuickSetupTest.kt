package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick setup: each choice's settings, how the current one is worked out, and Only send encrypted messages. */
class QuickSetupTest {
    private fun applied(id: String, start: Map<String, Any> = emptyMap(), ready: QuickSetup.Ready = QuickSetup.Ready()): Map<String, Any> {
        val plan = QuickSetup.plan(QuickSetup.choice(id)!!, start, ready)
        // What "Use this setup" saves: the settings, and the card as your choice.
        return start + plan.set + (QuickSetup.CHOSEN to id)
    }

    @Test fun aFreshInstallIsStandard() {
        assertEquals("standard", QuickSetup.current(emptyMap<String, Any>()))
        assertEquals("Standard", QuickSetup.title(QuickSetup.current(emptyMap<String, Any>())))
    }

    @Test fun eachChoiceShowsAfterItIsUsed() {
        for (choice in QuickSetup.CHOICES) assertEquals(choice.id, QuickSetup.current(applied(choice.id)))
    }

    @Test fun choicesAreUsedInEveryOrder() {
        for (first in QuickSetup.CHOICES) for (second in QuickSetup.CHOICES)
            assertEquals("${first.id} then ${second.id}", second.id, QuickSetup.current(applied(second.id, applied(first.id))))
    }

    @Test fun changingOneSwitchShowsCustom() {
        val encrypted = applied("encrypted")
        assertEquals("encrypted", QuickSetup.current(encrypted))
        assertEquals(QuickSetup.CUSTOM, QuickSetup.current(encrypted + (QuickSetup.LINK_PREVIEWS to true)))
        assertEquals(QuickSetup.CUSTOM, QuickSetup.current(applied("lock") + (NotificationContent.PREF to true)))
        assertEquals(QuickSetup.CUSTOM, QuickSetup.current(applied("standard") + (PrivateKeyboard.PREF to false)))
        assertEquals("Custom", QuickSetup.title(QuickSetup.CUSTOM))
    }

    @Test fun everythingOnIsToldApartFromStandard() {
        val everything = applied("everything")
        assertEquals("everything", QuickSetup.current(everything))
        // Turning one service off is a change of your own.
        assertEquals(QuickSetup.CUSTOM, QuickSetup.current(everything + ("protocolIRC" to false)))
        // Choosing Standard afterwards shows Standard and leaves Nearby, services, and calls as they were.
        val standard = applied("standard", everything)
        assertEquals("standard", QuickSetup.current(standard))
        assertEquals(true, standard[QuickSetup.NEARBY_ON])
        // With no card chosen yet, the first choice that fits shows.
        assertEquals("everything", QuickSetup.current(everything - QuickSetup.CHOSEN))
        assertEquals(true, everything[QuickSetup.NEARBY_ON])
        assertEquals("everyone", everything["nearby.visibility"] ?: "everyone")
        QuickSetup.CALLS.forEach { assertEquals(it, true, everything[it] ?: true) }
    }

    @Test fun whatEachChoiceSets() {
        val lock = applied("lock")
        assertEquals(true, lock[QuickSetup.LOCK])
        assertEquals(false, lock[QuickSetup.LINK_PREVIEWS])
        assertEquals(false, lock[QuickSetup.LINK_PICTURES])
        assertEquals(false, lock[NotificationContent.PREF])
        assertEquals("none", lock[QuickSetup.GIF_SEARCH] ?: "none")
        assertFalse(lock[QuickSetup.ENCRYPTED_ONLY] == true)
        assertEquals(true, applied("encrypted")[QuickSetup.ENCRYPTED_ONLY])
        val tor = applied("tor")
        assertEquals(true, tor["tor"])
        assertEquals(true, tor[QuickSetup.ENCRYPTED_ONLY])
        assertFalse(tor["tor.callsDirect"] == true)
        val nearby = applied("nearby")
        assertEquals(true, nearby[NetworkGate.NEARBY_ONLY])
        assertEquals(true, nearby[QuickSetup.LOCK])
        // Standard never touches app lock, services, or GIF search someone set up.
        val standard = QuickSetup.plan(QuickSetup.choice("standard")!!, lock + (QuickSetup.GIF_SEARCH to "tenor"), QuickSetup.Ready()).set
        assertFalse(QuickSetup.LOCK in standard)
        assertFalse(QuickSetup.GIF_SEARCH in standard)
        assertTrue(QuickSetup.SERVICES.none { it in standard })
    }

    @Test fun onlyChangedSettingsAreSaved() {
        assertTrue(QuickSetup.plan(QuickSetup.choice("standard")!!, emptyMap<String, Any>(), QuickSetup.Ready()).set.isEmpty())
    }

    @Test fun stepsInsteadOfThingsThatNeedSetup() {
        val noLock = QuickSetup.plan(QuickSetup.choice("lock")!!, emptyMap<String, Any>(), QuickSetup.Ready(screenLock = false))
        assertFalse(QuickSetup.LOCK in noLock.set)
        assertTrue(QuickSetup.NEEDS_SCREEN_LOCK in noLock.steps)
        val noTor = QuickSetup.plan(QuickSetup.choice("tor")!!, emptyMap<String, Any>(), QuickSetup.Ready(tor = false))
        assertFalse("tor" in noTor.set)
        assertTrue(QuickSetup.NEEDS_TOR in noTor.steps)
        assertEquals(true, noTor.set[QuickSetup.ENCRYPTED_ONLY])
        val noName = QuickSetup.plan(QuickSetup.choice("everything")!!, emptyMap<String, Any>(), QuickSetup.Ready(nearbyName = false))
        assertFalse(QuickSetup.NEARBY_ON in noName.set)
        assertTrue(QuickSetup.NEEDS_NEARBY_NAME in noName.steps)
        assertTrue(QuickSetup.GIF_KEY in noName.notes)
    }

    @Test fun nearbyOnlyNeverTurnsTextingOff() {
        val plan = QuickSetup.plan(QuickSetup.choice("nearby")!!, emptyMap<String, Any>(), QuickSetup.Ready())
        assertFalse(NetworkGate.TEXTS in plan.set)
        assertTrue(QuickSetup.TEXTS_ON in plan.notes)
        val textsOff = QuickSetup.plan(QuickSetup.choice("nearby")!!, mapOf(NetworkGate.TEXTS to false), QuickSetup.Ready())
        assertTrue(QuickSetup.TEXTS_OFF in textsOff.notes)
        QuickSetup.CHOICES.forEach { choice -> QuickSetup.SERVICES.forEach { assertFalse(choice.values[it] == false) } }
    }

    @Test fun theQuestionsReadTheSettings() {
        assertEquals(QuickSetup.Answers(QuickSetup.Send.ANYTHING, false, false), QuickSetup.answers(emptyMap<String, Any>()))
        assertEquals(QuickSetup.Answers(QuickSetup.Send.ENCRYPTED, true, true), QuickSetup.answers(applied("tor")))
        assertEquals(QuickSetup.Send.NEARBY, QuickSetup.answers(applied("nearby")).send)
        assertEquals(null, QuickSetup.answers(mapOf(QuickSetup.LINK_PREVIEWS to false)).fewerLeaks)
        // Answering the questions one at a time reaches the same choice.
        val answered = emptyMap<String, Any>() + QuickSetup.sendValues(QuickSetup.Send.ENCRYPTED) + QuickSetup.torValues(true) + QuickSetup.leakValues(true)
        assertEquals("tor", QuickSetup.current(answered))
    }

    @Test fun everyChoiceSaysWhoItIsForAndWhatItDoesntProtect() {
        for (choice in QuickSetup.CHOICES) {
            assertTrue(choice.forWho.isNotBlank() && choice.does.isNotBlank() && choice.doesnt.isNotBlank())
            assertTrue(choice.id, QuickSetup.lines(choice).isNotEmpty())
            for (word in listOf("private", "secure", "safe")) assertFalse(choice.title.lowercase().contains(word))
        }
    }

    @Test fun encryptedRoutesFollowTheLock() {
        assertTrue(QuickSetup.encrypted(Encryption.END_TO_END))
        assertTrue(QuickSetup.encrypted(Encryption.COMPANION_E2EE))
        assertTrue(QuickSetup.encrypted(Encryption.FLUIDMESSAGES))
        assertTrue(QuickSetup.encrypted(Encryption.UNVERIFIED))
        assertFalse(QuickSetup.encrypted(Encryption.NONE))
        assertFalse(QuickSetup.encrypted(Encryption.IN_TRANSIT))
        assertFalse(QuickSetup.encrypted(Encryption.PREVIEW))
    }

    @Test fun onlySendEncryptedMessagesBlocksWithAReason() {
        // Off: nothing is blocked.
        assertEquals("", QuickSetup.sendRefused(false, MessageProtocol.IRC, Encryption.IN_TRANSIT))
        for ((protocol, lock) in listOf(MessageProtocol.SMS to Encryption.NONE, MessageProtocol.IRC to Encryption.IN_TRANSIT,
            MessageProtocol.EMAIL to Encryption.IN_TRANSIT, MessageProtocol.MATRIX to Encryption.IN_TRANSIT,
            MessageProtocol.XMPP to Encryption.IN_TRANSIT, MessageProtocol.NEARBY to Encryption.NONE)) {
            val reason = QuickSetup.sendRefused(true, protocol, lock)
            assertTrue(protocol.name, reason.startsWith("Only send encrypted messages is on."))
            assertFalse(protocol.name, reason.contains("locked", ignoreCase = true))
        }
        assertTrue(QuickSetup.sendRefused(true, MessageProtocol.IRC, Encryption.IN_TRANSIT).contains("IRC can't encrypt messages, so sending here is off."))
        // An encrypted Matrix room, OMEMO, and FluidMessages encryption on a text all send.
        assertEquals("", QuickSetup.sendRefused(true, MessageProtocol.MATRIX, Encryption.COMPANION_E2EE))
        assertEquals("", QuickSetup.sendRefused(true, MessageProtocol.XMPP, Encryption.END_TO_END))
        assertEquals("", QuickSetup.sendRefused(true, MessageProtocol.SMS, Encryption.FLUIDMESSAGES))
        assertEquals("", QuickSetup.sendRefused(true, MessageProtocol.IRC, Encryption.FLUIDMESSAGES))
    }

    @Test fun servicesAreMarkedOnlyWhileTheSwitchIsOn() {
        assertEquals("", QuickSetup.serviceMark(false, MessageProtocol.IRC))
        assertTrue(QuickSetup.serviceMark(true, MessageProtocol.IRC).startsWith("Can't encrypt"))
        assertTrue(QuickSetup.serviceMark(true, MessageProtocol.EMAIL).startsWith("Can't encrypt"))
        assertEquals("Sends only in encrypted rooms", QuickSetup.serviceMark(true, MessageProtocol.MATRIX))
    }
}
