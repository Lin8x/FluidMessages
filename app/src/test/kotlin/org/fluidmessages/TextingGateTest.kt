package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Texting needs the default messages app: when the full-page ask shows, and when texts are shown at all. */
class TextingGateTest {
    @Test fun asksOnlyWithTextingOnAndNoRole() {
        assertTrue(TextingGate.mustAsk(textingOn = true, roleHeld = false))
        assertFalse(TextingGate.mustAsk(textingOn = false, roleHeld = false))
        assertFalse(TextingGate.mustAsk(textingOn = true, roleHeld = true))
        assertFalse(TextingGate.mustAsk(textingOn = false, roleHeld = true))
    }

    @Test fun textsShowOnlyWithTextingOnAndTheRole() {
        assertTrue(TextingGate.showsTexts(textingOn = true, roleHeld = true))
        assertFalse(TextingGate.showsTexts(textingOn = true, roleHeld = false))
        assertFalse(TextingGate.showsTexts(textingOn = false, roleHeld = true))
        assertFalse(TextingGate.showsTexts(textingOn = false, roleHeld = false))
    }

    @Test fun theAskAndTextsNeverShowTogether() {
        for (on in listOf(true, false)) for (held in listOf(true, false))
            assertFalse(TextingGate.mustAsk(on, held) && TextingGate.showsTexts(on, held))
    }

    @Test fun aTextSwitchTurnsOnOnlyWithTheRole() {
        assertFalse(TextingGate.maySwitch(wanted = true, roleHeld = false))
        assertTrue(TextingGate.maySwitch(wanted = true, roleHeld = true))
        // Turning off always works.
        assertTrue(TextingGate.maySwitch(wanted = false, roleHeld = false))
        assertTrue(TextingGate.maySwitch(wanted = false, roleHeld = true))
    }

    @Test fun textingIsTheThreeCarrierSwitches() {
        assertEquals(setOf("protocolRCS", "protocolSMS", "protocolMMS"), TextingGate.keys.toSet())
    }

    @Test fun textRowsHideAndOtherServicesStay() {
        // Rows from Android's message store carry no protocol: they are texts.
        for (text in listOf("sms", "mms", "rcs", "", null)) {
            assertTrue("$text", TextingGate.hidesRow(text, showsTexts = false))
            assertFalse("$text", TextingGate.hidesRow(text, showsTexts = true))
        }
        for (other in listOf("matrix", "xmpp", "email", "irc")) {
            assertFalse(other, TextingGate.hidesRow(other, showsTexts = false))
            assertFalse(other, TextingGate.hidesRow(other, showsTexts = true))
        }
    }

    @Test fun onlyTheSetupGuideStaysOpen() {
        assertTrue(TextingGate.staysOpen("setup"))
        for (page in listOf("inbox", "chat", "phone", "settings", "protocols", "sample", "sampleChat", "texting"))
            assertFalse(page, TextingGate.staysOpen(page))
    }

    @Test fun thePageSaysWhyInPlainWords() {
        assertTrue(TextingGate.WHY.startsWith("Android lets only one app send and receive your texts."))
        assertTrue(TextingGate.TURN_OFF.startsWith("Turn off texting"))
    }
}

/** The setup guide asks for what the app really uses, and nothing else. */
class OnboardingPermissionsTest {
    private val asks = basicPermissions + laterPermissions

    @Test fun everyAskIsDeclaredInTheManifest() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (ask in asks) for (permission in ask.permissions)
            assertTrue(permission, manifest.contains("android:name=\"$permission\""))
    }

    @Test fun eachPermissionIsAskedOnceWithAPlainReason() {
        val all = asks.flatMap { it.permissions }
        assertEquals(all.size, all.toSet().size)
        assertEquals(asks.size, asks.map { it.key }.toSet().size)
        for (ask in asks) {
            assertTrue(ask.key, ask.permissions.isNotEmpty())
            // What it is for, then what stops working without it.
            assertTrue(ask.key, ask.why.contains("Without it,") && ask.why.endsWith("."))
        }
    }

    @Test fun noPermissionTheAppDoesNotUse() {
        val all = asks.flatMap { it.permissions }
        assertTrue(all.none { it.contains("BLUETOOTH") || it.contains("NEARBY") || it.contains("BODY_SENSORS") || it.contains("BACKGROUND_LOCATION") })
        // Texts themselves come with the default messages app role, not a separate request.
        assertTrue(all.none { it.endsWith("_SMS") || it.endsWith("_MMS") })
    }

    @Test fun allowedNeedsEveryPermissionExceptLocation() {
        val contacts = basicPermissions.first { it.key == "contacts" }
        assertTrue(contacts.allowed(listOf(true, true)))
        assertFalse(contacts.allowed(listOf(true, false)))
        assertFalse(contacts.allowed(listOf(false, false)))
        // Approximate location alone is enough to share a place.
        val location = laterPermissions.first { it.key == "location" }
        assertTrue(location.allowed(listOf(false, true)))
        assertFalse(location.allowed(listOf(false, false)))
    }

    @Test fun theGuideHasTheAskPagesRightAfterWelcome() {
        assertEquals(listOf("welcome", "texting", "permissions", "later"), onboardingSteps.take(4).map { it.first })
        assertEquals(TextingGate.TITLE, onboardingSteps[1].second)
    }
}
