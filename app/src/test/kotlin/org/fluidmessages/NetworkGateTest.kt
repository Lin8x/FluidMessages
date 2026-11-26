package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGateTest {
    private val use = NetworkGate.Use.values()

    @Test fun offByDefaultEverythingIsAllowed() {
        for (texts in listOf(true, false)) for (each in use) assertTrue("$each", NetworkGate.allows(false, texts, each))
    }

    @Test fun nearbyOnlyBlocksTheInternetButNeverNearby() {
        assertFalse(NetworkGate.allows(true, true, NetworkGate.Use.INTERNET))
        assertFalse(NetworkGate.allows(true, false, NetworkGate.Use.INTERNET))
        assertTrue(NetworkGate.allows(true, true, NetworkGate.Use.NEARBY))
        assertTrue(NetworkGate.allows(true, false, NetworkGate.Use.NEARBY))
    }

    @Test fun textsFollowTheirOwnSwitchOnlyWhileNearbyOnlyIsOn() {
        assertTrue(NetworkGate.allows(true, true, NetworkGate.Use.TEXTS))
        assertFalse(NetworkGate.allows(true, false, NetworkGate.Use.TEXTS))
        assertTrue(NetworkGate.allows(false, false, NetworkGate.Use.TEXTS))
    }

    @Test fun theBannerSaysSoPlainly() {
        assertEquals("Nearby only: internet is off in FluidMessages", NetworkGate.banner(true))
        assertEquals("", NetworkGate.banner(false))
    }
}
