package org.fluidmessages

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Channels, rooms, sealed one-to-one messages, and the rules for passing them along, with fictional people. */
class NearbyMeshTest {
    private val now = 1_800_000_000_000L
    private val maya = NearbySign.generate()
    private val sam = NearbySign.generate()

    @Test fun openChannelMessagesAreSignedAndSurviveTheTrip() {
        val sent = NearbyMesh.channelMessage(NearbyMesh.newId(), now, "Neighborhood", maya, "Maya", "Water at the school until six.")
        val arrived = NearbyMesh.decode(NearbyMesh.encode(NearbyRelay.next(sent)!!))
        assertEquals("Water at the school until six.", NearbyMesh.channelWords(arrived))
        assertEquals(NearbyRelay.MAX_HOPS - 1, arrived.hops)
        assertEquals(NearbyMesh.channelTag("neighborhood"), arrived.tag)
    }

    @Test fun changedWordsOrAnotherSignerAreCaught() {
        val sent = NearbyMesh.channelMessage(NearbyMesh.newId(), now, "Neighborhood", maya, "Maya", "Meet at noon")
        assertNull(NearbyMesh.channelWords(sent.copy(body = "Meet at midnight".toByteArray())))
        assertNull(NearbyMesh.channelWords(sent.copy(name = "Sam")))
        assertNull(NearbyMesh.channelWords(sent.copy(created = now + 1)))
        // Someone else's key with Maya's signature doesn't pass, and neither does re-signing under Maya's name with Sam's.
        assertNull(NearbyMesh.channelWords(sent.copy(sender = sam.public)))
        assertNotEquals(NearbySign.mark(maya.public), NearbySign.mark(sam.public))
    }

    @Test fun roomsOpenOnlyWithTheSamePassphrase() {
        val key = NearbyRoom.key("Garden Club", "tomato season", iterations = 1000)
        val sent = NearbyMesh.roomMessage(NearbyMesh.newId(), now, key, maya, "Maya", "Seeds are in the shed")
        val arrived = NearbyMesh.decode(NearbyMesh.encode(sent))
        val opened = NearbyMesh.openRoom(arrived, key)!!
        assertEquals("Seeds are in the shed", opened.words)
        assertEquals("Maya", opened.name)
        assertArrayEquals(maya.public, opened.sender)
        // Carriers see neither the room's name nor the words.
        assertEquals("", arrived.channel)
        assertFalse(String(arrived.body, Charsets.ISO_8859_1).contains("Seeds"))
        assertNull(NearbyMesh.openRoom(arrived, NearbyRoom.key("Garden Club", "potato season", iterations = 1000)))
        assertNotEquals(NearbyRoom.tag(key), NearbyRoom.tag(NearbyRoom.key("Garden Club", "potato season", iterations = 1000)))
        val bent = arrived.copy(body = arrived.body.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })
        assertNull(NearbyMesh.openRoom(bent, key))
    }

    @Test fun sealedMessagesOpenOnlyForTheirPersonAndSayWhoSentThem() {
        val rosa = NoiseCrypto.generateKeyPair()
        val amir = NoiseCrypto.generateKeyPair()
        val carrier = NoiseCrypto.generateKeyPair()
        val text = NearbyWire.Packet.Text("6f1c-22", now, "I left the radio on your porch")
        val sealed = NearbyMesh.decode(NearbyMesh.encode(NearbyMesh.directMessage(NearbyMesh.newId(), now, rosa, amir.public, text)))
        assertEquals(NearbySeal.toTag(amir.public), sealed.tag)
        assertNull(NearbyMesh.openDirect(sealed, carrier))
        assertFalse(String(sealed.body, Charsets.ISO_8859_1).contains("radio"))
        val opened = NearbyMesh.openDirect(sealed, amir)!!
        assertEquals(NoiseCrypto.hex(rosa.public), opened.sender)
        assertEquals(text, opened.text)
        // Each seal uses a fresh ephemeral key, so two copies of the same words look unrelated.
        val again = NearbyMesh.directMessage(NearbyMesh.newId(), now, rosa, amir.public, text)
        assertFalse(again.body.contentEquals(sealed.body))
    }

    @Test fun envelopesRejectNonsense() {
        val good = NearbyMesh.encode(NearbyMesh.channelMessage(NearbyMesh.newId(), now, "Neighborhood", maya, "Maya", "Hi"))
        for (bad in listOf(byteArrayOf(), byteArrayOf(2), good.copyOf(good.size - 3), good + 0)) {
            try { NearbyMesh.decode(bad); fail("decoded a broken envelope") } catch (expected: IOException) {}
        }
        // A channel name that doesn't match its tag is refused.
        val wrong = NearbyMesh.channelMessage(NearbyMesh.newId(), now, "Neighborhood", maya, "Maya", "Hi").copy(channel = "Market")
        try { NearbyMesh.decode(NearbyMesh.encode(wrong)); fail() } catch (expected: IOException) {}
        assertEquals("Neighborhood Watch", NearbyMesh.cleanChannel("  #Neighborhood   Watch "))
        assertNull(NearbyMesh.cleanChannel("   "))
    }

    @Test fun relayingStopsAtHopsAgeAndRepeats() {
        val fresh = NearbyMesh.channelMessage(NearbyMesh.newId(), now, "Neighborhood", maya, "Maya", "Hi")
        assertEquals(NearbyRelay.Intake(keep = true, passOn = true), NearbyRelay.intake(fresh, now, false, true, false, 0))
        assertEquals(NearbyRelay.Intake(keep = true, passOn = false), NearbyRelay.intake(fresh, now, false, false, false, 0))
        assertEquals(NearbyRelay.Intake(false, false), NearbyRelay.intake(fresh, now, true, true, false, 0))
        assertEquals(NearbyRelay.Intake(true, false), NearbyRelay.intake(fresh, now, false, true, true, 0))
        assertEquals(NearbyRelay.Intake(false, false), NearbyRelay.intake(fresh, now, false, true, false, NearbyRelay.PER_CONNECTION))
        val old = fresh.copy(created = now - NearbyRelay.MAX_AGE - 1)
        assertEquals(NearbyRelay.Intake(false, false), NearbyRelay.intake(old, now, false, true, false, 0))
        val ahead = fresh.copy(created = now + NearbyRelay.MAX_AHEAD + 1)
        assertEquals(NearbyRelay.Intake(false, false), NearbyRelay.intake(ahead, now, false, true, false, 0))
        assertEquals(NearbyRelay.Intake(true, false), NearbyRelay.intake(fresh.copy(hops = 0), now, false, true, false, 0))
        assertNull(NearbyRelay.next(fresh.copy(hops = 0)))
        var hops = 0
        var travelling: Envelope? = fresh
        while (travelling != null) { travelling = NearbyRelay.next(travelling); hops++ }
        assertEquals(NearbyRelay.MAX_HOPS + 1, hops)
    }

    @Test fun storageIsCappedButYourOwnMessagesStay() {
        val items = (0 until NearbyRelay.MAX_ITEMS + 10).map { NearbyRelay.Stored("id$it", now - 1000L * (NearbyRelay.MAX_ITEMS + 10 - it), 100, own = it < 5) }
        val removed = NearbyRelay.prune(items + NearbyRelay.Stored("ancient", now - NearbyRelay.MAX_AGE, 100, own = true), now)
        assertTrue("ancient" in removed)
        assertEquals(10 + 1, removed.size)
        assertTrue(removed.none { it in setOf("id0", "id1", "id2", "id3", "id4") })
        val big = listOf(NearbyRelay.Stored("a", now - 10, (NearbyRelay.MAX_BYTES - 10).toInt(), false), NearbyRelay.Stored("b", now, 100, false))
        assertEquals(listOf("a"), NearbyRelay.prune(big, now))
    }

    @Test fun onlyMissingEnvelopesThatMayTravelAreSent() {
        val mine = listOf(NearbyRelay.Stored("a", 1, 10, false), NearbyRelay.Stored("b", 3, 10, false), NearbyRelay.Stored("c", 2, 10, true))
        assertEquals(listOf("c", "a"), NearbyRelay.toSend(mine, setOf("b"), mapOf("a" to 2, "b" to 5, "c" to 8)))
        assertEquals(listOf("c"), NearbyRelay.toSend(mine, emptySet(), mapOf("a" to 0, "b" to 0, "c" to 8)))
    }

    @Test fun newPacketsRoundTrip() {
        val id = NearbyMesh.newId()
        for (packet in listOf(NearbyWire.Packet.Hello("Maya", "0123456789abcdef", NoiseCrypto.hex(maya.public), true),
                NearbyWire.Packet.Mode(true), NearbyWire.Packet.Mode(false), NearbyWire.Packet.Have(listOf(id, NearbyMesh.newId())),
                NearbyWire.Packet.Have(emptyList()), NearbyWire.Packet.Carry(byteArrayOf(1, 2, 3)))) {
            assertEquals(packet, NearbyWire.decode(NearbyWire.encode(packet)))
        }
    }

    @Test fun helloFromAnOlderPhoneStillReads() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(1)
            for (value in listOf("Sam", "fedcba9876543210")) { out.writeShort(value.length); out.write(value.toByteArray()) }
        }
        val hello = NearbyWire.decode(bytes.toByteArray()) as NearbyWire.Packet.Hello
        assertEquals("Sam", hello.name)
        assertEquals("", hello.signKey)
        assertFalse(hello.pairing)
        assertNotNull(hello)
    }

    @Test fun statusesArePlain() {
        assertEquals("Kept here · shares with the next phone nearby", NearbyText.shared(0))
        assertEquals("Shared with 1 phone nearby", NearbyText.shared(1))
        assertEquals("Shared with 4 phones nearby", NearbyText.shared(4))
        assertTrue(NearbyText.OPEN_NOTICE.startsWith("Anyone nearby can read this channel."))
    }
}
