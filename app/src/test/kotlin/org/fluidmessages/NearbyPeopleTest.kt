package org.fluidmessages

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPeopleTest {
    private fun ip(text: String) = text.split('.').map { it.toInt().toByte() }.toByteArray()
    private fun shown(list: List<ByteArray>) = list.map(NearbyPeople::show)

    // Looking on this Wi-Fi: subnet math and private ranges

    @Test fun privateRangesOnly() {
        for (good in listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.0.1", "192.168.255.254")) {
            assertTrue(good, NearbyPeople.isPrivate(ip(good)))
        }
        for (bad in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.64.0.1", "169.254.1.1", "127.0.0.1", "1.1.1.1", "11.0.0.1")) {
            assertFalse(bad, NearbyPeople.isPrivate(ip(bad)))
        }
        assertFalse(NearbyPeople.isPrivate(ByteArray(16)))  // IPv6
    }

    @Test fun aSlash24IsTriedWithoutNetworkBroadcastOrSelf() {
        val hosts = shown(NearbyPeople.addressesToTry(ip("192.168.1.20"), 24))
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertFalse("192.168.1.20" in hosts)
        assertTrue(hosts.all { it.startsWith("192.168.1.") })
    }

    @Test fun widerNetworksAreCutToThisPhonesSlash24() {
        val hosts = shown(NearbyPeople.addressesToTry(ip("10.4.7.9"), 8))
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.4.7.") })
        assertEquals(253, NearbyPeople.addressesToTry(ip("172.20.3.3"), 16).size)
    }

    @Test fun smallerNetworksStaySmall() {
        assertEquals(listOf("192.168.1.129", "192.168.1.130"), shown(NearbyPeople.addressesToTry(ip("192.168.1.131"), 29)).take(2))
        assertEquals(5, NearbyPeople.addressesToTry(ip("192.168.1.131"), 29).size)  // .129–.134 without .131
        assertEquals(listOf("192.168.1.6"), shown(NearbyPeople.addressesToTry(ip("192.168.1.5"), 30)))
        assertEquals(125, NearbyPeople.addressesToTry(ip("192.168.1.200"), 25).size)
        assertTrue(NearbyPeople.addressesToTry(ip("192.168.1.5"), 31).isEmpty())
        assertTrue(NearbyPeople.addressesToTry(ip("192.168.1.5"), 32).isEmpty())
    }

    @Test fun publicOrOddAddressesAreNeverTried() {
        assertTrue(NearbyPeople.addressesToTry(ip("8.8.8.8"), 24).isEmpty())
        assertTrue(NearbyPeople.addressesToTry(ip("100.64.1.2"), 24).isEmpty())  // carrier-grade NAT, as on mobile data
        assertTrue(NearbyPeople.addressesToTry(ip("192.168.1.5"), 0).isEmpty())
        assertTrue(NearbyPeople.addressesToTry(ip("192.168.1.5"), 33).isEmpty())
        assertTrue(NearbyPeople.addressesToTry(ByteArray(16), 64).isEmpty())
    }

    @Test fun onlyOnWifiAndSlowly() {
        assertTrue(NearbyPeople.mayLook(wifi = true, cellular = false, vpn = false))
        assertFalse(NearbyPeople.mayLook(wifi = false, cellular = true, vpn = false))
        assertFalse(NearbyPeople.mayLook(wifi = true, cellular = true, vpn = false))
        assertFalse(NearbyPeople.mayLook(wifi = true, cellular = false, vpn = true))
        assertFalse(NearbyPeople.mayLook(wifi = false, cellular = false, vpn = false))
        assertTrue(NearbyPeople.MAX_PER_SECOND <= 50)
        assertTrue(NearbyPeople.SPACING_MS * NearbyPeople.MAX_PER_SECOND >= 1000)
        assertTrue(NearbyPeople.TIMEOUT_MS <= 1000)
    }

    @Test fun lookingSaysHowManyAnswered() {
        assertEquals("Looking for FluidMessages on this Wi-Fi…", NearbyPeople.lookingLine(false, 0))
        assertEquals("Looking for FluidMessages on this Wi-Fi… found 2", NearbyPeople.lookingLine(false, 2))
        assertEquals("Found 1 phone with FluidMessages on this Wi-Fi.", NearbyPeople.lookingLine(true, 1))
        assertEquals("Found 3 phones with FluidMessages on this Wi-Fi.", NearbyPeople.lookingLine(true, 3))
    }

    // Who can see me nearby

    @Test fun visibilityRules() {
        val everyone = NearbyPeople.Visibility.EVERYONE
        val added = NearbyPeople.Visibility.ADDED
        val nobody = NearbyPeople.Visibility.NOBODY
        assertEquals(everyone, NearbyPeople.Visibility.parse(null))
        assertEquals(everyone, NearbyPeople.Visibility.parse("something else"))
        assertEquals(added, NearbyPeople.Visibility.parse("added"))
        assertEquals(nobody, NearbyPeople.Visibility.parse("nobody"))

        assertTrue(NearbyPeople.shown(everyone, peerAdded = false))
        assertTrue(NearbyPeople.shown(added, peerAdded = true))
        assertFalse(NearbyPeople.shown(added, peerAdded = false))
        assertFalse(NearbyPeople.shown(nobody, peerAdded = true))

        // Hidden phones still meet strangers when they help pass messages along or are in a channel.
        assertTrue(NearbyPeople.welcomesStrangers(everyone, community = false))
        assertFalse(NearbyPeople.welcomesStrangers(added, community = false))
        assertTrue(NearbyPeople.welcomesStrangers(nobody, community = true))

        // Requests only while seen by everyone, and only from phones that know about requests.
        assertTrue(NearbyPeople.takesRequests(everyone, theirsModern = true))
        assertFalse(NearbyPeople.takesRequests(everyone, theirsModern = false))
        assertFalse(NearbyPeople.takesRequests(added, theirsModern = true))
        assertFalse(NearbyPeople.takesRequests(nobody, theirsModern = true))
    }

    @Test fun whatOthersSeeIsSaidPlainly() {
        assertTrue(NearbyPeople.WHAT_THEY_SEE.contains("name"))
        assertTrue(NearbyPeople.WHAT_THEY_SEE.contains("tag"))
        assertTrue(NearbyPeople.WHAT_THEY_SEE.contains("Never your phone number"))
        assertTrue(NearbyPeople.HIDDEN_RELAY.contains("Help pass messages along"))
    }

    @Test fun tagsAreFourCharactersFromTheKey() {
        val one = NoiseCrypto.hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val two = NoiseCrypto.hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertTrue(NearbyPeople.tag(one).matches(Regex("[0-9A-F]{4}")))
        assertEquals(NearbyPeople.tag(one), NearbyPeople.tag(one.copyOf()))
        assertFalse(NearbyPeople.tag(one) == NearbyPeople.tag(two))
        assertEquals("Rosa · " + NearbyPeople.tag(one), NearbyPeople.label("Rosa", one))
        assertEquals("On your Wi-Fi", NearbyPeople.way("Wi-Fi"))
        assertEquals("Close by (Bluetooth)", NearbyPeople.way("Bluetooth"))
    }

    // Trust on first use and the key-change hold

    private val rosa = "aa".repeat(32)
    private val other = "bb".repeat(32)

    @Test fun peopleAddedInPersonAreVerifiedAndMetNearbyAreNot() {
        assertTrue(NearbyPeople.verified(NearbyPeople.Person(rosa)))  // added with a code or safety code, or before this
        val met = NearbyPeople.Person(rosa, "0011223344556677", tofu = true)
        assertFalse(NearbyPeople.verified(met))
        assertTrue(NearbyPeople.verified(met.copy(verified = true)))
        assertTrue(NearbyPeople.lockWords(met, "Rosa").startsWith("Not verified yet"))
        assertTrue(NearbyPeople.lockWords(met.copy(verified = true), "Rosa").startsWith("Verified"))
    }

    @Test fun aKnownIdWithAnotherKeyIsAKeyChange() {
        val person = NearbyPeople.Person(rosa, "0011223344556677", tofu = true)
        assertTrue(NearbyPeople.keyChanged(person, "0011223344556677", other))
        assertFalse(NearbyPeople.keyChanged(person, "0011223344556677", rosa.uppercase()))  // the pinned key itself
        assertFalse(NearbyPeople.keyChanged(person, "ffffffffffffffff", other))
        assertFalse(NearbyPeople.keyChanged(person.copy(id = ""), "", other))
    }

    @Test fun aKeyChangeHoldsMessagesBothWays() {
        val person = NearbyPeople.Person(rosa, "0011223344556677", tofu = true, verified = true)
        assertTrue(NearbyPeople.canSend(person))
        val changed = person.copy(changedKey = other)
        assertFalse(NearbyPeople.canSend(changed))
        assertTrue(NearbyPeople.lockWords(changed, "Rosa").contains("on hold"))
        // The phone with the new key can't write into Rosa's chat or start a request while it waits.
        val everyone = NearbyPeople.Visibility.EVERYONE
        assertEquals(NearbyPeople.Intake.REFUSE, NearbyPeople.intake(null, impersonates = true, mine = everyone, theirsModern = true))
        // Rosa's pinned key still gets through.
        assertEquals(NearbyPeople.Intake.STORE, NearbyPeople.intake(changed, impersonates = false, mine = everyone, theirsModern = true))
    }

    @Test fun blockedPeopleAreRefused() {
        val person = NearbyPeople.Person(rosa, blocked = true)
        assertFalse(NearbyPeople.canSend(person))
        assertFalse(NearbyPeople.added(person))
        assertEquals(NearbyPeople.Intake.REFUSE, NearbyPeople.intake(person, false, NearbyPeople.Visibility.EVERYONE, true))
    }

    // Request → accept → added

    @Test fun aFirstMessageFromSomeoneNewIsARequestUntilAccepted() {
        val everyone = NearbyPeople.Visibility.EVERYONE
        assertEquals(NearbyPeople.Intake.REQUEST, NearbyPeople.intake(null, false, everyone, theirsModern = true))
        assertEquals(NearbyPeople.Intake.REFUSE, NearbyPeople.intake(null, false, NearbyPeople.Visibility.ADDED, theirsModern = true))
        assertEquals(NearbyPeople.Intake.REFUSE, NearbyPeople.intake(null, false, NearbyPeople.Visibility.NOBODY, theirsModern = true))
        assertEquals(NearbyPeople.Intake.REFUSE, NearbyPeople.intake(null, false, everyone, theirsModern = false))

        // The chat it makes: their key pinned, not verified, a request.
        val request = NearbyPeople.Person(other, "8899aabbccddeeff", tofu = true, request = true)
        assertFalse(NearbyPeople.added(request))
        assertFalse(NearbyPeople.verified(request))
        assertEquals(NearbyPeople.Intake.STORE, NearbyPeople.intake(request, false, everyone, true))  // their next messages land too
        val settings = MessageRequests.Settings()
        val facts = MessageRequests.Facts(service = "NEARBY", stranger = true)
        assertTrue(MessageRequests.isRequest(facts, settings))

        // Accept (or reply): added, and no longer a request.
        val accepted = request.copy(decision = MessageRequests.ACCEPTED)
        assertTrue(NearbyPeople.added(accepted))
        assertFalse(MessageRequests.isRequest(facts.copy(decision = MessageRequests.ACCEPTED), settings))
        assertFalse(MessageRequests.isRequest(facts.copy(sent = true), settings))
        // Deleted: still not added, and a new message is a new request.
        assertFalse(NearbyPeople.added(request.copy(decision = MessageRequests.DELETED + "1700000000000")))
        assertTrue(MessageRequests.isRequest(facts.copy(decision = MessageRequests.DELETED + "1700000000000"), settings))
        // Starting the chat yourself from People nearby: added on your side, not verified.
        val started = NearbyPeople.Person(other, "8899aabbccddeeff", tofu = true)
        assertTrue(NearbyPeople.added(started))
        assertFalse(NearbyPeople.verified(started))
    }

    @Test fun ownerNamesBecomeAFirstName() {
        assertEquals("Rosa", NearbyPeople.firstName("Rosa Martín"))
        assertEquals("", NearbyPeople.firstName(null))
        assertEquals("", NearbyPeople.firstName("   "))
    }

    // The hello's flags and the Seen packet, without breaking phones from before

    @Test fun helloFlagsRoundTripAndOlderPhonesStillRead() {
        for (pairing in listOf(false, true)) for (visible in listOf(false, true)) {
            val hello = NearbyWire.Packet.Hello("Rosa", "0011223344556677", "ab".repeat(4), pairing, modern = true, visible = visible)
            assertEquals(hello, NearbyWire.decode(NearbyWire.encode(hello)))
        }
        // An older phone's hello: pairing 0 or 1, never modern or visible.
        val old = NearbyWire.Packet.Hello("Sam", "0011223344556677", "", pairing = true)
        assertEquals(1, NearbyWire.helloFlags(pairing = true, modern = false, visible = false))
        val decoded = NearbyWire.decode(NearbyWire.encode(old)) as NearbyWire.Packet.Hello
        assertTrue(decoded.pairing)
        assertFalse(decoded.modern)
        assertFalse(decoded.visible)
        // Older phones read "pairing" as byte == 1, so a newer hello is never mistaken for an open pairing page.
        assertFalse(NearbyWire.helloFlags(pairing = false, modern = true, visible = true) == 1)
        assertEquals(NearbyWire.Packet.Seen(true), NearbyWire.decode(NearbyWire.encode(NearbyWire.Packet.Seen(true))))
        assertEquals(NearbyWire.Packet.Seen(false), NearbyWire.decode(NearbyWire.encode(NearbyWire.Packet.Seen(false))))
    }

    // The guide

    @Test fun theGuideHasItsSections() {
        val titles = OfflineGuide.sections.map { it.title }
        assertEquals(listOf("If cell towers work but the internet is down", "If there’s no service at all", "Practical tips",
            "What it can’t do", "For longer range, other tools exist"), titles)
        val text = OfflineGuide.text().joinToString("\n")
        for (words in listOf("Texts and calls still work", "Keep messages short", "Texts often get through when calls don’t",
            "even when that Wi-Fi has no internet", "10–30 m", "meeting points", "before an emergency", "Help pass messages along",
            "relay for a building", "battery pack", "Stay discoverable costs battery", "Nearby only", "Neighborhood",
            "far away", "can’t call", "isn’t instant across a city", "LoRa", "Meshtastic", "GMRS", "FRS", "ham", "doesn’t connect to these yet")) {
            assertTrue(words, text.contains(words))
        }
    }

    @Test fun theGuideIsInTheDocsToo() {
        val file = listOf(File("../docs/offline-guide.md"), File("docs/offline-guide.md")).first { it.isFile }
        val doc = file.readText().replace("'", "’")
        assertTrue(doc.contains("# " + OfflineGuide.TITLE))
        for (section in OfflineGuide.sections) assertTrue(section.title, doc.contains("## " + section.title))
        for (words in listOf("Meshtastic", "GMRS", "ham", "doesn’t connect to these yet", "10–30 m")) assertTrue(words, doc.contains(words))
    }
}
