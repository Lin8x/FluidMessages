package org.fluidmessages

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NearbyWireTest {
    private val key = NoiseCrypto.hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")

    @Test fun cardsRoundTrip() {
        val card = NearbyWire.Card(key, "Riley ☀️ Park", "0011223344556677")
        val text = NearbyWire.encodeCard(card)
        assertTrue(text.startsWith("fluidnearby:1:"))
        assertEquals(card, NearbyWire.parseCard(text))
        assertEquals(card, NearbyWire.parseCard("  $text\n"))
    }

    @Test fun badCardsAreRefused() {
        val good = NearbyWire.encodeCard(NearbyWire.Card(key, "Sam", "0011223344556677"))
        assertNull(NearbyWire.parseCard("https://example.org"))
        assertNull(NearbyWire.parseCard(good.replace("fluidnearby:1:", "fluidnearby:2:")))
        assertNull(NearbyWire.parseCard(good + ":extra"))
        assertNull(NearbyWire.parseCard(good.replaceRange(14, 16, "zz")))  // not hex
        assertNull(NearbyWire.parseCard("fluidnearby:1:" + "00".repeat(32) + ":0011223344556677:U2Ft"))  // zero key
        assertNull(NearbyWire.parseCard("fluidnearby:1:" + NoiseCrypto.hex(key) + ":0011:U2Ft"))  // short id
        assertNull(NearbyWire.parseCard("fluidnearby:1:" + NoiseCrypto.hex(key) + ":0011223344556677:"))  // no name
        val controlName = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("Sam\u0007".toByteArray())
        assertNull(NearbyWire.parseCard("fluidnearby:1:" + NoiseCrypto.hex(key) + ":0011223344556677:" + controlName))
        val longName = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("x".repeat(41).toByteArray())
        assertNull(NearbyWire.parseCard("fluidnearby:1:" + NoiseCrypto.hex(key) + ":0011223344556677:" + longName))
    }

    @Test fun safetyCodesAreSixDigits() {
        assertEquals("000 000", NearbyWire.safetyCode(ByteArray(32)))
        val hash = ByteArray(32).also { it[0] = 0xff.toByte(); it[1] = 0xff.toByte(); it[2] = 0xff.toByte(); it[3] = 0xff.toByte() }
        assertEquals("967 295", NearbyWire.safetyCode(hash))  // 4294967295 mod 1000000
    }

    @Test fun framesSurviveAStream() {
        val stream = NearbyWire.frame(byteArrayOf(1, 2, 3)) + NearbyWire.frame(ByteArray(0)) + NearbyWire.frame(ByteArray(300) { 7 })
        val input = ByteArrayInputStream(stream)
        assertEquals(listOf<Byte>(1, 2, 3), NearbyWire.readFrame(input)!!.toList())
        assertEquals(0, NearbyWire.readFrame(input)!!.size)
        assertEquals(300, NearbyWire.readFrame(input)!!.size)
        assertNull(NearbyWire.readFrame(input))
    }

    @Test fun aCutOffFrameIsAnError() {
        val cut = NearbyWire.frame(ByteArray(10)).copyOf(6)
        try { NearbyWire.readFrame(ByteArrayInputStream(cut)); fail() } catch (expected: IOException) {}
        try { NearbyWire.frame(ByteArray(65536)); fail() } catch (expected: IllegalArgumentException) {}
    }

    @Test fun packetsRoundTrip() {
        for (packet in listOf(NearbyWire.Packet.Hello("Maya", "0123456789abcdef"),
                NearbyWire.Packet.Text("6f1c-22", 1_700_000_000_000, "Picnic at noon 🧺\nBring a blanket"),
                NearbyWire.Packet.Ack("6f1c-22"), NearbyWire.Packet.Ping)) {
            assertEquals(packet, NearbyWire.decode(NearbyWire.encode(packet)))
        }
    }

    @Test fun malformedPacketsAreRejected() {
        for (bad in listOf(byteArrayOf(), byteArrayOf(9), byteArrayOf(3, 0, 5, 65), NearbyWire.encode(NearbyWire.Packet.Ping) + 0,
                NearbyWire.encode(NearbyWire.Packet.Ack("ok")).also { it[it.size - 1] = '/'.code.toByte() })) {
            try { NearbyWire.decode(bad); fail("decoded ${bad.toList()}") } catch (expected: IOException) {}
        }
    }

    @Test fun incomingIdsAreStablePerSenderAndMessage() {
        val peer = NoiseCrypto.hex(key)
        assertEquals("nearby-8520f0098930a754-abc", NearbyWire.incomingId(peer, "abc"))
        assertEquals(NearbyWire.incomingId(peer, "abc"), NearbyWire.incomingId(peer.uppercase(), "abc"))
        assertEquals("abc-1", NearbyWire.wireId("direct-abc-1"))
    }

    @Test fun repeatsAreAckedButStoredOnce() {
        assertEquals(NearbyWire.Receipt(store = true, ack = true), NearbyWire.receive(alreadyStored = false, fromContact = true))
        assertEquals(NearbyWire.Receipt(store = false, ack = true), NearbyWire.receive(alreadyStored = true, fromContact = true))
        // Not added yet: neither kept nor confirmed, so the sender keeps it waiting.
        assertEquals(NearbyWire.Receipt(store = false, ack = false), NearbyWire.receive(alreadyStored = false, fromContact = false))
    }

    @Test fun waitingMessagesGoOldestFirstToPeopleInRange() {
        val waiting = listOf(NearbyWire.Waiting("c", "sam", 30), NearbyWire.Waiting("a", "sam", 10),
            NearbyWire.Waiting("b", "maya", 20))
        assertEquals(listOf("a", "c"), NearbyWire.sendable(waiting, setOf("sam")).map { it.id })
        assertEquals(listOf("a", "b", "c"), NearbyWire.sendable(waiting, setOf("sam", "maya")).map { it.id })
        assertTrue(NearbyWire.sendable(waiting, emptySet()).isEmpty())
    }

    @Test fun datesNeverComeFromTheFuture() {
        assertEquals(500L, NearbyWire.incomingDate(500, 1000))
        assertEquals(1000L, NearbyWire.incomingDate(5000, 1000))
        assertEquals(1000L, NearbyWire.incomingDate(0, 1000))
    }

    @Test fun bothPhonesKeepTheSameConnection() {
        val small = "11"
        val big = "99"
        assertTrue(NearbyWire.replaces(existingStarter = big, newStarter = small))
        assertFalse(NearbyWire.replaces(existingStarter = small, newStarter = big))
        assertFalse(NearbyWire.replaces(existingStarter = small, newStarter = small))
    }

    @Test fun statusesArePlain() {
        assertEquals("Waiting for Maya to be nearby", NearbyWire.waiting("Maya"))
        assertEquals("Sent nearby", NearbyWire.SENT)
    }
}
