package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPairingTest {
    private val print = "ab".repeat(32)
    private val secret = "0123456789abcdef".repeat(3)

    @Test
    fun codeMatchesTheComputers() {
        // The same digits server/nearby_pair.py code() makes for this fingerprint and secret (scripts/test-local checks it too).
        assertEquals("779 835", LanPairing.code(print, secret))
        assertEquals("779 835", LanPairing.code("AB:".repeat(31) + "AB", secret))
        assertNotEquals(LanPairing.code(print, secret), LanPairing.code("cd".repeat(32), secret))
        assertNotEquals(LanPairing.code(print, secret), LanPairing.code(print, secret.reversed()))
    }

    @Test
    fun secretsAreRandomHex() {
        val one = LanPairing.secret()
        assertEquals(48, one.length)
        assertTrue(one.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(one, LanPairing.secret())
    }

    @Test
    fun onlyPrivateAddressesAreTried() {
        assertTrue(LanPairing.privateIpv4("192.168.1.20"))
        assertTrue(LanPairing.privateIpv4("10.0.0.5"))
        assertTrue(LanPairing.privateIpv4("172.20.1.1"))
        assertTrue(LanPairing.privateIpv4("100.101.1.2"))
        assertFalse(LanPairing.privateIpv4("8.8.8.8"))
        assertFalse(LanPairing.privateIpv4("172.32.0.1"))
        assertFalse(LanPairing.privateIpv4("127.0.0.1"))
        assertFalse(LanPairing.privateIpv4("192.168.1.300"))
        assertFalse(LanPairing.privateIpv4("example.org"))
        assertEquals(listOf("https://192.168.1.20:8485", "https://10.0.0.5:8485"),
            LanPairing.origins("192.168.1.20", "192.168.1.20, 8.8.8.8,10.0.0.5", 8485))
        assertEquals(emptyList<String>(), LanPairing.origins("192.168.1.20", null, 0))
    }

    @Test
    fun foundServicesNeedAFingerprintAndAnAddress() {
        val found = LanPairing.computer("studio", mapOf("fp" to print, "a" to "192.168.1.20", "name" to "Studio"), null, 8485)
        assertEquals("Studio", found?.name)
        assertEquals(print, found?.fingerprint)
        assertEquals(listOf("https://192.168.1.20:8485"), found?.origins)
        assertNull(LanPairing.computer("studio", mapOf("a" to "192.168.1.20"), null, 8485))
        assertNull(LanPairing.computer("studio", mapOf("fp" to print, "a" to "8.8.8.8"), null, 8485))
        assertEquals("studio", LanPairing.computer("studio", mapOf("fp" to print), "192.168.1.9", 8485)?.name)
    }

    @Test
    fun phoneNames() {
        assertEquals("Pixel 8", LanPairing.phoneName("Google", "Pixel 8"))
        assertEquals("Samsung SM-S918B", LanPairing.phoneName("samsung", "SM-S918B"))
        assertEquals("OnePlus 12", LanPairing.phoneName("OnePlus", "OnePlus 12"))
        assertEquals("An Android phone", LanPairing.phoneName("", ""))
    }
}
