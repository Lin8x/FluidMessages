package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same rules as FluidMessages Web's omemo_trust.py, with fictional addresses. */
class OmemoRulesTest {
    private val key = "05" + "ab12cd34".repeat(8)

    @Test fun fingerprintsAreEightGroupsOfEight() {
        assertEquals(List(8) { "ab12cd34" }, OmemoRules.fingerprintGroups(key))
        assertEquals(7, OmemoRules.fingerprintText("AB12 CD34".repeat(8)).count { it == ' ' })
        assertTrue(OmemoRules.sameFingerprint(key, "AB12CD34".repeat(8)))
        assertFalse(OmemoRules.sameFingerprint("", ""))
    }

    @Test fun xmppLinksUseConversationsFormat() {
        val uri = OmemoRules.xmppUri("maya@example.test", listOf(1234 to "ab12cd34".repeat(8)))
        assertEquals("xmpp:maya@example.test?omemo-sid-1234=" + "ab12cd34".repeat(8), uri)
        assertEquals("maya@example.test" to mapOf(1234 to "ab12cd34".repeat(8)),
            OmemoRules.parseXmppUri("xmpp:Maya@Example.test?message;omemo-sid-1234=" + "AB12CD34".repeat(8) + ";omemo-sid-x=1"))
        assertNull(OmemoRules.parseXmppUri("https://example.test"))
        assertNull(OmemoRules.parseXmppUri("xmpp:no-at-sign"))
        assertEquals(listOf(1234) to emptyList<Int>(), OmemoRules.verifyFromUri(uri, "maya@example.test", mapOf(1234 to key, 99 to key)))
        assertEquals(emptyList<Int>() to listOf(1234), OmemoRules.verifyFromUri(uri, "maya@example.test", mapOf(1234 to "05" + "00".repeat(32))))
    }

    @Test fun blindTrustBeforeVerification() {
        assertEquals(OmemoRules.BLIND, OmemoRules.firstUse(emptyList()))
        assertEquals(OmemoRules.BLIND, OmemoRules.firstUse(listOf(OmemoRules.BLIND)))
        assertEquals(OmemoRules.UNCONFIRMED, OmemoRules.firstUse(listOf(OmemoRules.VERIFIED, OmemoRules.BLIND)))
        assertTrue(OmemoRules.trusted(OmemoRules.BLIND) && OmemoRules.trusted(OmemoRules.VERIFIED))
        assertFalse(OmemoRules.trusted(OmemoRules.UNCONFIRMED) || OmemoRules.trusted(OmemoRules.DISTRUSTED))
    }

    @Test fun greenOnlyWhenLockedForAllTrustedDevices() {
        val green = OmemoRules.LockState(available = true, devices = 2, trusted = 2)
        assertEquals("green", OmemoRules.lock(green).first)
        assertTrue("only you and the people you're talking to" in OmemoRules.lock(green).second)
        for (state in listOf(OmemoRules.LockState(), green.copy(available = false), green.copy(devices = 0), green.copy(plain = true),
                green.copy(trusted = 0), green.copy(failed = 1), green.copy(group = true, groupOk = false))) {
            assertEquals(state.toString(), "yellow", OmemoRules.lock(state).first)
        }
        assertEquals("green", OmemoRules.lock(green.copy(group = true, groupOk = true)).first)
    }
}
