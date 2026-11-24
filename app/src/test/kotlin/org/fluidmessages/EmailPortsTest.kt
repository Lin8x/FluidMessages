package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Email ports: defaults that match how FluidMessages connected before, a 1–65535 range, and TLS on every port. */
class EmailPortsTest {
    @Test fun defaultsMatchTheEarlierImplicitTls() {
        assertEquals(993, EmailPorts.IMAP)
        assertEquals(465, EmailPorts.SMTP)
        assertFalse(EmailPorts.imapStartTls(EmailPorts.IMAP))
        assertFalse(EmailPorts.smtpStartTls(EmailPorts.SMTP))
    }

    @Test fun otherPortsUpgradeWithStartTlsAndNeverPlainText() {
        assertTrue(EmailPorts.smtpStartTls(587))
        assertTrue(EmailPorts.smtpStartTls(25))
        assertTrue(EmailPorts.imapStartTls(143))
        assertFalse(EmailPorts.imapStartTls(1993))
    }

    @Test fun portsAreWholeNumbersFrom1To65535() {
        assertEquals(587, EmailPorts.parse(" 587 "))
        assertEquals(1, EmailPorts.parse("1"))
        assertEquals(65535, EmailPorts.parse("65535"))
        for (bad in listOf("", "0", "65536", "-1", "58.7", "abc", "993a", "999999")) assertNull(bad, EmailPorts.parse(bad))
    }

    @Test fun accountsSavedWithoutPortsKeepTheDefaults() {
        // DirectEmail reads optInt(key, default); 0 or an out-of-range value also falls back.
        assertEquals(993, EmailPorts.orDefault(993, EmailPorts.IMAP))
        assertEquals(465, EmailPorts.orDefault(0, EmailPorts.SMTP))
        assertEquals(465, EmailPorts.orDefault(70000, EmailPorts.SMTP))
        assertEquals(2525, EmailPorts.orDefault(2525, EmailPorts.SMTP))
    }
}
