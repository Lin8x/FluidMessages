package org.fluidmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic setup in the account forms, and the guide shown before them. The table matches FluidMessages Web's. */
class AccountSetupTest {
    private fun servers(address: String) = EmailProviders.detect(address)!!.let { listOf(it.imapHost, it.imapPort, it.smtpHost, it.smtpPort) }

    @Test fun knownEmailProvidersFillInTheirServers() {
        val table = mapOf(
            listOf("gmail.com", "googlemail.com") to listOf("imap.gmail.com", 993, "smtp.gmail.com", 465),
            listOf("outlook.com", "hotmail.com", "live.com", "msn.com") to listOf("outlook.office365.com", 993, "smtp-mail.outlook.com", 587),
            listOf("yahoo.com", "ymail.com", "rocketmail.com") to listOf("imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465),
            listOf("aol.com") to listOf("imap.aol.com", 993, "smtp.aol.com", 465),
            listOf("icloud.com", "me.com", "mac.com") to listOf("imap.mail.me.com", 993, "smtp.mail.me.com", 587),
            listOf("fastmail.com", "fastmail.fm") to listOf("imap.fastmail.com", 993, "smtp.fastmail.com", 465),
            listOf("zoho.com", "zohomail.com") to listOf("imap.zoho.com", 993, "smtp.zoho.com", 465),
            listOf("gmx.com", "gmx.us", "gmx.net", "gmx.de") to listOf("imap.gmx.com", 993, "mail.gmx.com", 587),
            listOf("mail.com") to listOf("imap.mail.com", 993, "smtp.mail.com", 587),
        )
        for ((domains, expected) in table) for (domain in domains) {
            assertEquals(domain, expected, servers("sam@$domain"))
            val setup = EmailProviders.detect("sam@$domain")!!
            assertFalse(domain, setup.guess || setup.blocked)
            assertTrue(setup.title, setup.title.startsWith("Set up for "))
        }
        assertEquals(table.keys.flatten().toSet() + setOf("proton.me", "protonmail.com", "pm.me"), EmailProviders.domains)
    }

    @Test fun onlyPortsTheMailCodeSupportsAreFilledIn() {
        for (domain in EmailProviders.domains) {
            val setup = EmailProviders.detect("sam@$domain")!!
            if (setup.blocked) continue
            // TLS from the start on 993 and 465; 587 upgrades with STARTTLS. Never plain text.
            assertEquals(domain, 993, setup.imapPort)
            assertFalse(EmailPorts.imapStartTls(setup.imapPort))
            assertTrue(domain, setup.smtpPort == 465 || setup.smtpPort == 587)
            assertEquals(setup.smtpPort == 587, EmailPorts.smtpStartTls(setup.smtpPort))
            assertEquals(setOf("imap_host", EmailPorts.IMAP_KEY, "smtp_host", EmailPorts.SMTP_KEY), setup.fields.keys)
            assertTrue(setup.summary, setup.summary.contains(if (setup.smtpPort == 587) "STARTTLS" else "port 465 (TLS)"))
        }
    }

    @Test fun appPasswordsAreExplainedWithTheProvidersOwnPage() {
        val links = mapOf(
            "gmail.com" to "https://myaccount.google.com/apppasswords",
            "outlook.com" to "https://support.microsoft.com/account-billing/5896ed9b-4263-e681-128a-a6f2979a7944",
            "yahoo.com" to "https://login.yahoo.com/account/security",
            "aol.com" to "https://login.aol.com/account/security",
            "icloud.com" to "https://account.apple.com/account/manage",
            "fastmail.com" to "https://app.fastmail.com/settings/security/apps",
            "zoho.com" to "https://accounts.zoho.com/home#security/app_password",
        )
        for ((domain, link) in links) {
            val setup = EmailProviders.detect("sam@$domain")!!
            assertEquals(domain, link, setup.helpUrl)
            assertTrue(domain, setup.note.isNotEmpty() && setup.helpLabel.isNotEmpty())
            assertEquals(domain, domain != "outlook.com", setup.appPassword)
        }
        assertTrue(EmailProviders.detect("sam@gmail.com")!!.note.contains("2-Step Verification"))
        assertEquals("Outlook may not allow sign-in from other apps.", EmailProviders.detect("sam@hotmail.com")!!.note)
        for (domain in listOf("gmx.net", "mail.com")) assertEquals("", EmailProviders.detect("sam@$domain")!!.helpUrl)
    }

    @Test fun protonIsExplainedAndNotFilledIn() {
        for (domain in listOf("proton.me", "protonmail.com", "pm.me")) {
            val setup = EmailProviders.detect("sam@$domain")!!
            assertTrue(setup.blocked)
            assertTrue(setup.fields.isEmpty())
            assertEquals("Proton Mail needs its Bridge app on a computer, so set it up in FluidMessages Web on that computer instead.", setup.note)
        }
    }

    @Test fun otherDomainsGetAnEditableGuess() {
        val setup = EmailProviders.detect(" Sam@Example.ORG ")!!
        assertTrue(setup.guess)
        assertEquals(listOf("imap.example.org", 993, "smtp.example.org", 465), servers("sam@example.org"))
        assertTrue(setup.title, setup.title.contains("guess"))
        assertTrue(setup.note, setup.note.contains("guess"))
        // Capitals and a trailing dot don't matter; half-typed addresses wait.
        assertEquals("Gmail", EmailProviders.detect("Sam@GMAIL.com.")!!.provider)
        for (early in listOf("", "sam", "sam@", "sam@gmail", "sam@gmail.", "@gmail.com", "sam @gmail.com", "sam@exa mple.org")) {
            assertNull(early, EmailProviders.detect(early))
        }
    }

    @Test fun aMatrixIdFillsInItsHomeserver() {
        assertEquals("https://example.org", AccountSetup.matrixHomeserver("@sam:example.org"))
        assertEquals("https://example.org", AccountSetup.matrixHomeserver(" sam:Example.org "))
        assertEquals("https://example.org:8448", AccountSetup.matrixHomeserver("@sam:example.org:8448"))
        // Known public homeservers sign in at their real address.
        assertEquals("https://matrix-client.matrix.org", AccountSetup.matrixHomeserver("@sam:matrix.org"))
        assertEquals("@sam:example.org", AccountSetup.matrixId("sam:EXAMPLE.org"))
        for (early in listOf("", "@sam", "@sam:", "@sam:example", "sam@example.org", "@sam:exa mple.org")) assertNull(early, AccountSetup.matrixHomeserver(early))
        assertEquals("https://example.org", AccountSetup.homeserverUrl(" example.org/ "))
        assertEquals("https://example.org", AccountSetup.homeserverUrl("https://example.org"))
        assertEquals("", AccountSetup.homeserverUrl("  "))
    }

    @Test fun anXmppAddressNamesItsServer() {
        assertEquals("example.org", AccountSetup.xmppServer("sam@Example.org"))
        assertEquals("example.org", AccountSetup.xmppServer("sam@example.org/phone"))
        for (early in listOf("", "sam", "sam@", "@example.org", "sam@example", "a@b@example.org")) assertNull(early, AccountSetup.xmppServer(early))
        assertEquals("Sam@example.org", AccountSetup.lowerDomain(" Sam@EXAMPLE.org "))
        assertEquals("sam", AccountSetup.lowerDomain("sam"))
    }

    @Test fun ircHelpersTidyWhatWasTyped() {
        assertEquals("irc.libera.chat", AccountSetup.ircServer(" Libera.Chat "))
        assertEquals("irc.example.org", AccountSetup.ircServer("IRC.Example.org"))
        // A network without TLS is never filled in.
        assertEquals("quakenet", AccountSetup.ircServer("QuakeNet"))
        assertEquals(listOf("#pond", "#garden", "&local"), AccountSetup.channels("pond, #garden  #POND,&local,"))
        assertEquals(emptyList<String>(), AccountSetup.channels("  "))
        assertEquals("SamRivera", AccountSetup.nick("Sam Rivera"))
        assertEquals("samrivera", AccountSetup.nick("sam.rivera@example.org"))
        assertEquals("sam", AccountSetup.nick("@sam:example.org"))
        assertEquals("Zoe", AccountSetup.nick("42 Zoë"))
        assertEquals("", AccountSetup.nick("123"))
        assertTrue(AccountSetup.nick("a".repeat(40)).length <= 16)
        assertEquals("sam@example.org", AccountSetup.tidy("address", " sam@ example.org "))
        assertEquals("two words", AccountSetup.tidy("imap_password", "two words"))
        assertEquals("imap.example.org", AccountSetup.saved("imap_host", " IMAP.Example.org "))
        assertEquals("https://example.org", AccountSetup.saved("homeserver", "example.org"))
    }

    @Test fun guidesUseTheSharedWordsAndRealServers() {
        assertEquals("Modern group chat that no single company owns. Used by communities, schools, and workplaces.",
            ServiceGuides.of(MessageProtocol.MATRIX).about)
        assertEquals("Simple, private one-to-one and group chat. It works like email: pick any provider and talk to people on all the others.",
            ServiceGuides.of(MessageProtocol.XMPP).about)
        assertEquals("Your normal email, shown as chats. Only the conversations you choose show up.", ServiceGuides.of(MessageProtocol.EMAIL).about)
        assertEquals("Big public chat rooms for hobbies and open-source projects. No sign-up needed: just pick a nickname.",
            ServiceGuides.of(MessageProtocol.IRC).about)
        assertEquals(mapOf("homeserver" to "https://matrix-client.matrix.org"), ServiceGuides.of(MessageProtocol.MATRIX).examplePreset)
        assertEquals(mapOf("server" to "irc.libera.chat", "port" to "6697"), ServiceGuides.of(MessageProtocol.IRC).examplePreset)
        assertTrue(ServerDirectory.xmpp.any { it.preset == ServiceGuides.of(MessageProtocol.XMPP).examplePreset })
        assertEquals("Use the email you already have", ServiceGuides.of(MessageProtocol.EMAIL).exampleTitle)
        assertTrue(ServiceGuides.of(MessageProtocol.EMAIL).examplePreset.isEmpty())
        for (protocol in listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)) {
            val guide = ServiceGuides.of(protocol)
            assertEquals(3, guide.steps.size)
            val words = (listOf(guide.title, guide.about, guide.exampleTitle, guide.exampleLine, guide.exampleUrlLabel) + guide.steps).joinToString(" ").lowercase()
            // No brand-name chat apps, and short sentences a first-timer can follow.
            for (brand in listOf("discord", "slack", "whatsapp", "telegram", "signal", "element", "teams")) assertFalse(brand, Regex("\\b$brand\\b").containsMatchIn(words))
            for (step in guide.steps) assertTrue(step, step.length <= 110)
        }
    }
}
