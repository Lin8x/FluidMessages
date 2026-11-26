package org.fluidmessages

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Noise against the official test vectors (cacophony's, public domain) for every one-way and interactive pattern with
 * 25519, ChaChaPoly and SHA-256, then a real XX handshake between two in-memory peers like Nearby runs.
 */
class NoiseTest {
    private fun hex(text: String): ByteArray = NoiseCrypto.hex(text)
    private fun hex(bytes: ByteArray): String = NoiseCrypto.hex(bytes)

    @Suppress("UNCHECKED_CAST")
    private val vectors = (MiniJson(javaClass.getResource("/noise-vectors.json")!!.readText()).read() as Map<String, Any?>)["vectors"]
        as List<Map<String, Any?>>

    private fun Map<String, Any?>.bytes(key: String): ByteArray? = (this[key] as String?)?.let { hex(it) }
    private fun pair(private: ByteArray?) = private?.let { NoiseCrypto.KeyPair(it, NoiseCrypto.publicKey(it)) }

    @Test fun everyPatternMatchesTheOfficialVectors() {
        assertEquals(15, vectors.size)
        for (vector in vectors) {
            val name = vector["protocol_name"] as String
            val pattern = NoisePattern.valueOf(name.removePrefix("Noise_").substringBefore('_'))
            assertEquals(name, pattern.protocolName())
            val initiator = NoiseHandshake(pattern, true, vector.bytes("init_prologue")!!, pair(vector.bytes("init_static")),
                pair(vector.bytes("init_ephemeral")), vector.bytes("init_remote_static"))
            val responder = NoiseHandshake(pattern, false, vector.bytes("resp_prologue")!!, pair(vector.bytes("resp_static")),
                pair(vector.bytes("resp_ephemeral")), vector.bytes("resp_remote_static"))
            @Suppress("UNCHECKED_CAST") val messages = vector["messages"] as List<Map<String, Any?>>
            var initiatorTransport: NoiseTransport? = null
            var responderTransport: NoiseTransport? = null
            messages.forEachIndexed { index, message ->
                val payload = message.bytes("payload")!!
                val expected = message.bytes("ciphertext")!!
                val fromInitiator = pattern.oneWay || index % 2 == 0
                val written: ByteArray
                val read: ByteArray
                if (!initiator.finished) {
                    val (writer, reader) = if (fromInitiator) initiator to responder else responder to initiator
                    written = writer.writeMessage(payload)
                    read = reader.readMessage(written)
                    if (initiator.finished) {
                        assertTrue("$name: both finish together", responder.finished)
                        assertArrayEquals("$name handshake hash", vector.bytes("handshake_hash"), initiator.handshakeHash)
                        assertArrayEquals(initiator.handshakeHash, responder.handshakeHash)
                        initiatorTransport = initiator.transport()
                        responderTransport = responder.transport()
                    }
                } else {
                    val (writer, reader) = if (fromInitiator) initiatorTransport!! to responderTransport!! else responderTransport!! to initiatorTransport!!
                    written = writer.encrypt(payload)
                    read = reader.decrypt(written)
                }
                assertArrayEquals("$name message $index ciphertext", expected, written)
                assertArrayEquals("$name message $index payload", payload, read)
            }
        }
    }

    @Test fun theSelfCheckPassesHereAndReportsAMissingPrimitive() {
        assertEquals("", NoiseCrypto.selfCheck())
        assertEquals(NoiseCrypto.MISSING, NoiseCrypto.selfCheck { _, _ -> throw java.security.NoSuchAlgorithmException("XDH") })
        assertEquals(NoiseCrypto.MISSING, NoiseCrypto.selfCheck { _, _ -> ByteArray(32) { 1 } })
    }

    @Test fun x25519MatchesRfc7748() {
        // RFC 7748 section 6.1.
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(NoiseCrypto.publicKey(alice)))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hex(NoiseCrypto.publicKey(bob)))
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            hex(NoiseCrypto.dh(alice, NoiseCrypto.publicKey(bob))))
    }

    /** Two peers as Nearby runs them: XX with fresh keys, Hello packets in the payloads, then messages both ways. */
    @Test fun twoPeersHandshakeAndTalk() {
        val riley = NoiseCrypto.generateKeyPair()
        val sam = NoiseCrypto.generateKeyPair()
        val a = NoiseHandshake(NoisePattern.XX, true, NearbyWire.PROLOGUE, riley)
        val b = NoiseHandshake(NoisePattern.XX, false, NearbyWire.PROLOGUE, sam)
        b.readMessage(a.writeMessage(ByteArray(0)))
        val samHello = NearbyWire.Packet.Hello("Sam", "0123456789abcdef")
        assertEquals(samHello, NearbyWire.decode(a.readMessage(b.writeMessage(NearbyWire.encode(samHello)))))
        // The initiator learns who answered before it says who it is.
        assertArrayEquals(sam.public, a.remoteStatic)
        val rileyHello = NearbyWire.Packet.Hello("Riley", "fedcba9876543210")
        assertEquals(rileyHello, NearbyWire.decode(b.readMessage(a.writeMessage(NearbyWire.encode(rileyHello)))))
        assertArrayEquals(riley.public, b.remoteStatic)
        assertTrue(a.finished && b.finished)
        assertEquals(NearbyWire.safetyCode(a.handshakeHash), NearbyWire.safetyCode(b.handshakeHash))

        val ta = a.transport()
        val tb = b.transport()
        for (i in 0 until 5) {
            val text = NearbyWire.Packet.Text("m$i", 1000L + i, "Trail meetup at $i")
            assertEquals(text, NearbyWire.decode(tb.decrypt(ta.encrypt(NearbyWire.encode(text)))))
            val ack = NearbyWire.Packet.Ack("m$i")
            assertEquals(ack, NearbyWire.decode(ta.decrypt(tb.encrypt(NearbyWire.encode(ack)))))
        }
    }

    @Test fun tamperedOrReplayedMessagesFail() {
        val a = NoiseHandshake(NoisePattern.XX, true, NearbyWire.PROLOGUE, NoiseCrypto.generateKeyPair())
        val b = NoiseHandshake(NoisePattern.XX, false, NearbyWire.PROLOGUE, NoiseCrypto.generateKeyPair())
        b.readMessage(a.writeMessage(ByteArray(0)))
        a.readMessage(b.writeMessage(ByteArray(0)))
        b.readMessage(a.writeMessage(ByteArray(0)))
        val sealed = a.transport().encrypt("hello".toByteArray())
        val bent = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try { b.transport().decrypt(bent); fail("tampered message opened") } catch (expected: Exception) {}
        assertEquals("hello", String(b.transport().decrypt(sealed)))
        // The same bytes again use the next nonce, so a replay doesn't open.
        try { b.transport().decrypt(sealed); fail("replayed message opened") } catch (expected: Exception) {}
    }

    @Test fun aDifferentPrologueOrMiddlemanBreaksTheHandshake() {
        val a = NoiseHandshake(NoisePattern.XX, true, NearbyWire.PROLOGUE, NoiseCrypto.generateKeyPair())
        val b = NoiseHandshake(NoisePattern.XX, false, "other".toByteArray(), NoiseCrypto.generateKeyPair())
        b.readMessage(a.writeMessage(ByteArray(0)))
        val second = b.writeMessage(ByteArray(0))
        try { a.readMessage(second); fail("handshake with another prologue finished") } catch (expected: Exception) {}
        assertFalse(a.finished)
    }

    /** Just enough JSON for the vectors file: objects, arrays, and strings. */
    private class MiniJson(private val text: String) {
        private var at = 0
        private fun skip() { while (at < text.length && text[at].isWhitespace()) at++ }
        fun read(): Any? {
            skip()
            return when (text[at]) {
                '{' -> buildMap {
                    at++; skip()
                    while (text[at] != '}') {
                        val key = read() as String
                        skip(); at++ // ':'
                        put(key, read())
                        skip(); if (text[at] == ',') at++
                        skip()
                    }
                    at++
                }
                '[' -> buildList {
                    at++; skip()
                    while (text[at] != ']') {
                        add(read())
                        skip(); if (text[at] == ',') at++
                        skip()
                    }
                    at++
                }
                '"' -> {
                    val end = text.indexOf('"', at + 1)
                    text.substring(at + 1, end).also { at = end + 1 }
                }
                else -> throw IllegalStateException("Unexpected ${text[at]} at $at")
            }
        }
    }
}
