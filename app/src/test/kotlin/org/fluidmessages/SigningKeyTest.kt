package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** This app's signing key (SigningKey): the fingerprint, its readable forms, and the official-key check. */
class SigningKeyTest {
    private val official = SigningKey.OFFICIAL

    @Test fun fingerprintIsSha256InLowercaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SigningKey.fingerprint("abc".toByteArray()))
    }

    @Test fun colonFormIsUppercasePairs() {
        val colons = SigningKey.colons(official)
        assertEquals(32, colons.split(":").size)
        assertTrue(colons.startsWith("2D:09:80:EE:"))
        assertTrue(colons.endsWith(":5D:F5:BC"))
    }

    @Test fun groupedFormIsFourLinesOfEightPairs() {
        val lines = SigningKey.grouped(official).lines()
        assertEquals(4, lines.size)
        lines.forEach { assertEquals(8, it.split(":").size) }
        assertEquals(SigningKey.colons(official), lines.joinToString(":"))
    }

    @Test fun officialKeyMatchesInAnyForm() {
        assertTrue(SigningKey.matchesOfficial(official))
        assertTrue(SigningKey.matchesOfficial(SigningKey.colons(official)))
        assertTrue(SigningKey.matchesOfficial(SigningKey.grouped(official)))
        assertTrue(SigningKey.matchesOfficial(official.uppercase()))
    }

    @Test fun otherKeysDoNotMatch() {
        assertFalse(SigningKey.matchesOfficial(""))
        assertFalse(SigningKey.matchesOfficial(SigningKey.fingerprint("a made-up certificate".toByteArray())))
        // One digit off, or cut short, is someone else's key.
        assertFalse(SigningKey.matchesOfficial(official.dropLast(1) + "0"))
        assertFalse(SigningKey.matchesOfficial(official.take(40)))
        // Extra digits never sneak through the cleanup.
        assertFalse(SigningKey.matchesOfficial(official + "00"))
    }

    @Test fun statusSaysWhoSignedIt() {
        val other = SigningKey.fingerprint("a made-up certificate".toByteArray())
        assertEquals(SigningKey.Status.OFFICIAL, SigningKey.status(official, false))
        assertEquals(SigningKey.Status.OFFICIAL, SigningKey.status(official, true))
        assertEquals(SigningKey.Status.SOMEONE_ELSE, SigningKey.status(other, false))
        // F-Droid's own build may carry F-Droid's key; that isn't a warning.
        assertEquals(SigningKey.Status.FDROID, SigningKey.status(other, true))
        assertEquals(SigningKey.Status.STORE, SigningKey.status(other, false, true))
        assertEquals(SigningKey.Status.SOMEONE_ELSE, SigningKey.status(other, false, false))
        assertEquals(SigningKey.Status.SOMEONE_ELSE, SigningKey.status("", true))
    }
}
