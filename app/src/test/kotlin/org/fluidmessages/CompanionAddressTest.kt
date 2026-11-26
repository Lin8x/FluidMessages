package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The FluidMessages Web address the phone saves (MainActivity.saveCompanion, SyncGuideScreen). */
class CompanionAddressTest {
    @Test fun httpsSavesWithoutAWarning() {
        assertEquals(CompanionAddress.Checked("https://messages.example.net", false), CompanionAddress.check("https://messages.example.net"))
        assertEquals(CompanionAddress.Checked("https://messages.example.net:8443", false), CompanionAddress.check(" https://messages.example.net:8443/ "))
        assertEquals("https://messages.example.net", CompanionAddress.check("HTTPS://messages.example.net")!!.origin)
    }

    @Test fun httpSavesWithItsSchemeAndAWarning() {
        assertEquals(CompanionAddress.Checked("http://192.168.1.20:8080", true), CompanionAddress.check("http://192.168.1.20:8080"))
        assertEquals(CompanionAddress.Checked("http://192.168.1.20:8080", true), CompanionAddress.check("http://192.168.1.20:8080/"))
        assertEquals(CompanionAddress.Checked("http://fluid.local", true), CompanionAddress.check("HTTP://fluid.local"))
        assertEquals(CompanionAddress.Checked("http://[fd00::20]:8080", true), CompanionAddress.check("http://[fd00::20]:8080"))
        assertTrue(CompanionAddress.plain("http://192.168.1.20:8080"))
        assertFalse(CompanionAddress.plain("https://192.168.1.20:8080"))
        assertTrue(CompanionAddress.PLAIN_WARNING.contains("same Wi-Fi"))
    }

    @Test fun noSchemeMeansHttps() {
        assertEquals(CompanionAddress.Checked("https://messages.example.net", false), CompanionAddress.check("messages.example.net"))
        assertEquals(CompanionAddress.Checked("https://192.168.1.20:8080", false), CompanionAddress.check("192.168.1.20:8080"))
        assertFalse(CompanionAddress.plain("192.168.1.20:8080"))
    }

    @Test fun badAddressesAreRefused() {
        for (bad in listOf("", "   ", "ftp://messages.example.net", "ws://192.168.1.20:8080", "file:///sdcard/x",
            "javascript:alert(1)", "https://", "http://", "https://user:secret@messages.example.net",
            "https://messages.example.net/path", "https://messages.example.net/?token=abc", "https://messages.example.net/#part",
            "http://192.168.1.20:99999", "http://192.168.1.20:0", "http://192.168.1.20:port", "https://messages example.net",
            "content://org.fluidmessages/x")) {
            assertNull("Accepted: $bad", CompanionAddress.check(bad))
            assertFalse(CompanionAddress.plain(bad))
        }
    }

    @Test fun savedAddressesTheConnectionsUse() {
        assertTrue(CompanionAddress.usable("https://messages.example.net"))
        assertTrue(CompanionAddress.usable("http://192.168.1.20:8080"))
        assertTrue(CompanionAddress.usable("HTTP://192.168.1.20:8080"))
        assertFalse(CompanionAddress.usable(""))
        assertFalse(CompanionAddress.usable(null))
        assertFalse(CompanionAddress.usable("ftp://messages.example.net"))
        assertFalse(CompanionAddress.usable("messages.example.net"))
    }
}
