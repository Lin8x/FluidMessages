package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a text sender is classified: the address detection, then the chat's own sender type from Conversation details. */
class SenderStyleTest {
    @Test fun automaticKeepsTheDetection() {
        assertEquals(SenderStyle.AUTOMATED, SenderStyle.forAddress("72345", ""))
        assertEquals(SenderStyle.AUTOMATED, SenderStyle.forAddress("TrailAlerts", ""))
        assertEquals(SenderStyle.SMS, SenderStyle.forAddress("+15555550134", ""))
        assertEquals(SenderStyle.SMS, SenderStyle.forAddress("555-0134", ""))
    }

    @Test fun automatedSenderWinsOverTheDetection() {
        assertEquals(SenderStyle.AUTOMATED, SenderStyle.forAddress("+15555550134", SenderStyle.AUTOMATED_CHOICE))
        assertEquals(SenderStyle.AUTOMATED, SenderStyle.forAddress("72345", SenderStyle.AUTOMATED_CHOICE))
        assertEquals(SenderStyle.AUTOMATED, SenderStyle.resolve(SenderStyle.RCS, SenderStyle.AUTOMATED_CHOICE))
    }

    @Test fun personShowsADetectedAutomatedSenderAsARegularText() {
        assertEquals(SenderStyle.SMS, SenderStyle.forAddress("72345", SenderStyle.PERSON_CHOICE))
        assertEquals(SenderStyle.SMS, SenderStyle.forAddress("TrailAlerts", SenderStyle.PERSON_CHOICE))
        assertEquals(SenderStyle.SMS, SenderStyle.forAddress("+15555550134", SenderStyle.PERSON_CHOICE))
        // A sample RCS chat stays RCS; Person only removes the automated style.
        assertEquals(SenderStyle.RCS, SenderStyle.resolve(SenderStyle.RCS, SenderStyle.PERSON_CHOICE))
    }

    @Test fun unknownChoicesFallBackToAutomatic() {
        assertEquals("", SenderStyle.clean("robot"))
        assertEquals("", SenderStyle.clean(null))
        assertEquals(SenderStyle.AUTOMATED, SenderStyle.forAddress("72345", "robot"))
        assertEquals(SenderStyle.SMS, SenderStyle.forAddress("+15555550134", "AUTOMATED"))
    }

    @Test fun choicesHaveTheirDetailsLabels() {
        assertEquals("Automatic", SenderStyle.label(""))
        assertEquals("Automated sender", SenderStyle.label(SenderStyle.AUTOMATED_CHOICE))
        assertEquals("Person (SMS)", SenderStyle.label(SenderStyle.PERSON_CHOICE))
    }
}
