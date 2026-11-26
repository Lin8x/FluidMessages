package org.fluidmessages

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The phone's legacy OMEMO core (OmemoCore.java) against saved messages from an independent implementation
 * (resources/omemo, fictional keys), and against itself: key exchanges, answers, skipped and replayed messages,
 * tampering, and the skipped-key limit.
 */
class OmemoCoreTest {
    private val vectors: Map<String, String> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("omemo/legacy-vectors.txt")!!
        stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.substringBefore("=") to it.substringAfter("=") }
    }
    private fun b64(name: String): ByteArray = Base64.getDecoder().decode(vectors.getValue(name))
    private val original = OmemoCore.RANDOM

    @After fun restore() { OmemoCore.RANDOM = original }

    private fun ownFromVectors(): OmemoCore.OwnKeys {
        val own = OmemoCore.OwnKeys()
        own.identity = OmemoCore.Identity.fromPrivate(b64("own.identity_priv"))
        own.signedPreKeyId = vectors.getValue("own.spk_id").toInt()
        own.signedPreKeyPriv = b64("own.spk_priv")
        vectors.keys.filter { it.startsWith("own.prekey.") }.forEach {
            own.preKeys[it.removePrefix("own.prekey.").toInt()] = b64(it)
        }
        return own
    }

    private fun oracleBundle(): OmemoCore.Bundle {
        val bundle = OmemoCore.Bundle()
        bundle.identity = OmemoCore.decodeKey(b64("oracle.ik"))
        bundle.signedPreKey = OmemoCore.decodeKey(b64("oracle.spk"))
        bundle.signedPreKeyId = vectors.getValue("oracle.spk_id").toInt()
        bundle.signature = b64("oracle.sig")
        bundle.preKeys[vectors.getValue("oracle.pk_id").toInt()] = OmemoCore.decodeKey(b64("oracle.pk"))
        return bundle
    }

    @Test fun savedKeyExchangesFromAnotherImplementationOpen() {
        val own = ownFromVectors()
        var session: OmemoCore.Session? = null
        for (index in 0 until vectors.getValue("messages").toInt()) {
            assertEquals("true", vectors.getValue("m$index.prekey"))
            val exchange = OmemoCore.parseKeyExchange(b64("m$index.key"))
            if (!OmemoCore.sameExchange(session, exchange)) {
                assertTrue("only the first message starts the session", index == 0)
                session = OmemoCore.accept(own, exchange)
            }
            val result = OmemoCore.decrypt(session!!, exchange.message)
            session = result[0] as OmemoCore.Session
            val text = OmemoCore.openPayload(result[1] as ByteArray, b64("m$index.iv"), b64("m$index.payload"))
            assertArrayEquals(b64("m$index.text"), text)
        }
    }

    @Test fun anotherImplementationsSignedPrekeyVerifies() {
        val bundle = oracleBundle()
        assertTrue(OmemoCore.xeddsaVerify(bundle.identity, OmemoCore.encodeKey(bundle.signedPreKey), bundle.signature))
        val forged = bundle.signature.clone().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertFalse(OmemoCore.xeddsaVerify(bundle.identity, OmemoCore.encodeKey(bundle.signedPreKey), forged))
    }

    /** The phone starts a session with the other implementation's device. Its output was checked by that
     *  implementation once (repeatable randomness), and that implementation's answer is saved as phone.reply. */
    @Test fun phoneStartsASessionTheOtherImplementationAccepts() {
        OmemoCore.RANDOM = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(20261125L) }
        val phone = OmemoCore.OwnKeys.create()
        assertTrue(OmemoCore.xeddsaVerify(phone.identity.pub, OmemoCore.encodeKey(phone.bundle().signedPreKey),
            phone.signedPreKeySignature))
        val session = OmemoCore.start(phone, oracleBundle())
        val sealed = OmemoCore.sealPayload("Hello from the phone".toByteArray())
        val exchange = OmemoCore.keyExchange(session, phone.registrationId, OmemoCore.encrypt(session, sealed[2]))
        val bundle = phone.bundle()
        val out = buildString {
            appendLine("phone.ik=" + OmemoCore.b64(OmemoCore.encodeKey(bundle.identity)))
            appendLine("phone.spk_id=" + bundle.signedPreKeyId)
            appendLine("phone.spk=" + OmemoCore.b64(OmemoCore.encodeKey(bundle.signedPreKey)))
            appendLine("phone.sig=" + OmemoCore.b64(bundle.signature))
            bundle.preKeys.forEach { (id, key) -> appendLine("phone.pk.$id=" + OmemoCore.b64(OmemoCore.encodeKey(key))) }
            appendLine("phone.key=" + OmemoCore.b64(exchange))
            appendLine("phone.iv=" + OmemoCore.b64(sealed[1]))
            appendLine("phone.payload=" + OmemoCore.b64(sealed[0]))
        }
        File("build").mkdirs()
        File("build/omemo-phone-out.txt").writeText(out)
        if (vectors.containsKey("phone.reply.key")) {
            assertEquals("the saved check matches this run", vectors["phone.key"], OmemoCore.b64(exchange))
            val result = OmemoCore.decrypt(session, b64("phone.reply.key"))
            val text = OmemoCore.openPayload(result[1] as ByteArray, b64("phone.reply.iv"), b64("phone.reply.payload"))
            assertEquals(String(b64("phone.reply.text")), String(text))
            assertTrue((result[0] as OmemoCore.Session).confirmed)
        }
    }

    private fun pair(): Triple<OmemoCore.OwnKeys, OmemoCore.OwnKeys, Pair<OmemoCore.Session, OmemoCore.Session>> {
        val alice = OmemoCore.OwnKeys.create()
        val bob = OmemoCore.OwnKeys.create()
        var a = OmemoCore.start(alice, bob.bundle())
        val first = OmemoCore.keyExchange(a, alice.registrationId, OmemoCore.encrypt(a, "hello".toByteArray()))
        val exchange = OmemoCore.parseKeyExchange(first)
        val result = OmemoCore.decrypt(OmemoCore.accept(bob, exchange), exchange.message)
        assertEquals("hello", String(result[1] as ByteArray))
        return Triple(alice, bob, a to (result[0] as OmemoCore.Session))
    }

    @Test fun twoPhonesTalkBothWaysWithSkippedAndReplayedMessages() {
        var (_, _, sessions) = pair()
        var (a, b) = sessions
        val reply = OmemoCore.encrypt(b, "hi".toByteArray())
        var opened = OmemoCore.decrypt(a, reply)
        a = opened[0] as OmemoCore.Session
        assertEquals("hi", String(opened[1] as ByteArray))
        assertTrue(a.confirmed)
        val batch = (0 until 5).map { OmemoCore.encrypt(a, "m$it".toByteArray()) }
        for (index in listOf(3, 0, 4, 1, 2)) {
            opened = OmemoCore.decrypt(b, batch[index])
            b = opened[0] as OmemoCore.Session
            assertEquals("m$index", String(opened[1] as ByteArray))
        }
        try {
            OmemoCore.decrypt(b, batch[2])
            fail("a replayed message opened")
        } catch (expected: OmemoCore.OmemoException) {}
        val tampered = OmemoCore.encrypt(a, "x".toByteArray()).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val before = b.nr
        try {
            OmemoCore.decrypt(b, tampered)
            fail("a tampered message opened")
        } catch (expected: OmemoCore.OmemoException) {}
        assertEquals("a failed message doesn't change the session", before, b.nr)
    }

    @Test fun tooManySkippedMessagesAreRefused() {
        var (_, _, sessions) = pair()
        var (a, b) = sessions
        b = OmemoCore.decrypt(b, OmemoCore.encrypt(a, "0".toByteArray()))[0] as OmemoCore.Session
        repeat(OmemoCore.MAX_SKIP + 1) { OmemoCore.encrypt(a, "skip".toByteArray()) }
        try {
            OmemoCore.decrypt(b, OmemoCore.encrypt(a, "far".toByteArray()))
            fail("a message skipping more keys than allowed opened")
        } catch (expected: OmemoCore.OmemoException) {}
    }

    @Test fun payloadsUseAes128GcmWithKeyAndTagTogether() {
        val sealed = OmemoCore.sealPayload("Coffee at ten?".toByteArray())
        assertEquals(32, sealed[2].size)
        assertEquals(12, sealed[1].size)
        assertEquals("Coffee at ten?", String(OmemoCore.openPayload(sealed[2], sealed[1], sealed[0])))
        // Older apps: a 16-byte key, with the tag at the end of the payload.
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12)
        val old = OmemoCore.aesGcm(Cipher.ENCRYPT_MODE, key, iv, "old".toByteArray())
        assertEquals("old", String(OmemoCore.openPayload(key, iv, old)))
        assertNotNull(OmemoCore.hex(key))
    }

    @Test fun usedPrekeysAreReplaced() {
        val keys = OmemoCore.OwnKeys.create()
        assertEquals(100, keys.preKeys.size)
        val used = keys.preKeys.keys.first()
        keys.preKeys.remove(used)
        keys.refill(100)
        assertEquals(100, keys.preKeys.size)
        assertFalse(used in keys.preKeys.keys)
    }
}
