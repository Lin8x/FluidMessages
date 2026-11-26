package org.fluidmessages

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Noise Protocol Framework (revision 34, noiseprotocol.org/noise.html), written from the specification for
 * Nearby. Only Curve25519, ChaChaPoly and SHA-256 are supported: Noise_XX_25519_ChaChaPoly_SHA256 is what Nearby
 * uses for connections and Noise_X for messages sealed to one person that other phones carry; the other patterns are here so the official test vectors check every token.
 *
 * Every primitive comes from the platform (the JDK in unit tests, Conscrypt on Android 13+): X25519 is the "XDH"
 * key agreement, ChaCha20-Poly1305 is a Cipher, and SHA-256 and HMAC-SHA256 are MessageDigest and Mac. Nothing
 * here is Android-specific, so the JVM tests run the same code the phone does.
 */
internal object NoiseCrypto {
    const val DHLEN = 32
    const val HASHLEN = 32
    const val TAGLEN = 16
    const val MAX_MESSAGE = 65535

    private val random = SecureRandom()
    // DER prefixes for a raw X25519 key (RFC 8410), so the platform's key factory accepts it.
    private val PRIVATE_PREFIX = hex("302e020100300506032b656e04220420")
    private val PUBLIC_PREFIX = hex("302a300506032b656e032100")
    private val BASE_POINT = ByteArray(32).also { it[0] = 9 }

    class KeyPair(val private: ByteArray, val public: ByteArray)

    fun generateKeyPair(): KeyPair {
        val private = ByteArray(32).also(random::nextBytes)
        return KeyPair(private, publicKey(private))
    }

    fun publicKey(private: ByteArray): ByteArray = dh(private, BASE_POINT)

    /**
     * Where X25519 comes from. The platform's "XDH" (JDK 11+, Conscrypt on Android 13+) is the only one shipped. If a
     * phone's system lacks it, [selfCheck] says so and Nearby stays off. A fallback would plug in here, such as
     * BouncyCastle's lightweight X25519 (MIT): implement [X25519] and set [x25519]; NoiseTest's vectors then check it.
     */
    fun interface X25519 { fun agree(private: ByteArray, public: ByteArray): ByteArray }

    val platformX25519 = X25519 { private, public ->
        val factory = KeyFactory.getInstance("XDH")
        val priv = factory.generatePrivate(PKCS8EncodedKeySpec(PRIVATE_PREFIX + private))
        val pub = factory.generatePublic(X509EncodedKeySpec(PUBLIC_PREFIX + public))
        val agreement = KeyAgreement.getInstance("XDH")
        agreement.init(priv)
        agreement.doPhase(pub, true)
        agreement.generateSecret()
    }

    @Volatile var x25519: X25519 = platformX25519

    /** X25519(private, public). Throws for a low-order public key (an all-zero result). */
    fun dh(private: ByteArray, public: ByteArray): ByteArray {
        require(private.size == 32 && public.size == 32) { "X25519 keys are 32 bytes" }
        val shared = x25519.agree(private, public)
        if (shared.size != 32 || shared.all { it == 0.toByte() }) throw GeneralSecurityException("Bad public key")
        return shared
    }

    const val MISSING = "This phone’s system doesn’t have the encryption Nearby needs (X25519 and ChaCha20-Poly1305), so Nearby stays off."

    /**
     * Checks that X25519 and ChaCha20-Poly1305 work, against RFC 7748 and RFC 8439 values, before Nearby starts.
     * Returns "" when they do, or [MISSING]. Never throws.
     */
    fun selfCheck(dh: X25519 = x25519): String = try {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = dh.agree(alice, bob)
        val key = ByteArray(32) { it.toByte() }
        val sealed = cipher(true, key, 7, byteArrayOf(1), byteArrayOf(2, 3))
        val ok = hex(shared) == "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742" &&
            cipher(false, key, 7, byteArrayOf(1), sealed).contentEquals(byteArrayOf(2, 3))
        if (ok) "" else MISSING
    } catch (error: Throwable) { MISSING }

    private fun chachaPoly(): Cipher = try {
        Cipher.getInstance("ChaCha20-Poly1305")  // The JDK's name.
    } catch (missing: NoSuchAlgorithmException) {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding")  // Conscrypt's name on Android.
    } catch (missing: javax.crypto.NoSuchPaddingException) {
        Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
    }

    /** ChaCha20-Poly1305 with the nonce as 32 zero bits and the little-endian 64-bit counter, as the spec says. */
    fun cipher(encrypt: Boolean, key: ByteArray, n: Long, ad: ByteArray, data: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (n ushr (8 * i)).toByte()
        return cipherWithNonce(encrypt, key, nonce, ad, data)
    }

    /** ChaCha20-Poly1305 with a 12-byte nonce given as is (Nearby rooms use a random one per message). */
    fun cipherWithNonce(encrypt: Boolean, key: ByteArray, nonce: ByteArray, ad: ByteArray, data: ByteArray): ByteArray {
        require(nonce.size == 12)
        // A fresh Cipher each time: the JDK refuses to reuse one instance with the same key and nonce.
        val cipher = chachaPoly()
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(ad)
        return cipher.doFinal(data)
    }

    fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** The spec's HKDF: two or three outputs from a chaining key and input key material. */
    fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val one = hmac(temp, byteArrayOf(1))
        val two = hmac(temp, one + byteArrayOf(2))
        if (outputs == 2) return listOf(one, two)
        return listOf(one, two, hmac(temp, two + byteArrayOf(3)))
    }

    fun hex(text: String): ByteArray {
        require(text.length % 2 == 0 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Not hex" }
        return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** A CipherState: a key (or none) and a 64-bit nonce counter. */
internal class NoiseCipher {
    private var key: ByteArray? = null
    private var nonce = 0L

    fun initializeKey(k: ByteArray?) { key = k?.copyOf(); nonce = 0 }
    fun hasKey(): Boolean = key != null
    fun setNonce(n: Long) { nonce = n }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        // 2^64-1 is reserved; as an unsigned counter that is -1 here.
        if (nonce == -1L) throw GeneralSecurityException("Nonce exhausted")
        return NoiseCrypto.cipher(true, k, nonce, ad, plaintext).also { nonce++ }
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        if (nonce == -1L) throw GeneralSecurityException("Nonce exhausted")
        // The nonce only moves on after a successful decryption, as the spec says.
        return NoiseCrypto.cipher(false, k, nonce, ad, ciphertext).also { nonce++ }
    }

    fun rekey() {
        val k = key ?: return
        key = NoiseCrypto.cipher(true, k, -1L, ByteArray(0), ByteArray(32)).copyOf(32)
    }
}

/** A SymmetricState: the chaining key, the handshake hash, and its CipherState. */
internal class NoiseSymmetric(protocolName: String) {
    val cipher = NoiseCipher()
    private var ck: ByteArray
    var h: ByteArray
        private set

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= NoiseCrypto.HASHLEN) name.copyOf(NoiseCrypto.HASHLEN) else NoiseCrypto.hash(name)
        ck = h.copyOf()
    }

    fun mixKey(ikm: ByteArray) {
        val (next, temp) = NoiseCrypto.hkdf(ck, ikm, 2)
        ck = next
        cipher.initializeKey(temp.copyOf(32))
    }

    fun mixHash(data: ByteArray) { h = NoiseCrypto.hash(h + data) }

    fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encryptWithAd(h, plaintext).also(::mixHash)

    fun decryptAndHash(ciphertext: ByteArray): ByteArray = cipher.decryptWithAd(h, ciphertext).also { mixHash(ciphertext) }

    fun split(): Pair<NoiseCipher, NoiseCipher> {
        val (one, two) = NoiseCrypto.hkdf(ck, ByteArray(0), 2)
        return NoiseCipher().apply { initializeKey(one.copyOf(32)) } to NoiseCipher().apply { initializeKey(two.copyOf(32)) }
    }
}

/** The interactive handshake patterns (section 7.4 and 7.5): pre-messages for each side, then the message tokens. */
internal enum class NoisePattern(val initiatorPre: List<String>, val responderPre: List<String>, val messages: List<List<String>>) {
    // One-way patterns (section 7.4): the initiator only sends. X seals a message to someone's static key and proves who sent it.
    N(listOf(), listOf("s"), listOf(listOf("e", "es"))),
    K(listOf("s"), listOf("s"), listOf(listOf("e", "es", "ss"))),
    X(listOf(), listOf("s"), listOf(listOf("e", "es", "s", "ss"))),
    NN(listOf(), listOf(), listOf(listOf("e"), listOf("e", "ee"))),
    NK(listOf(), listOf("s"), listOf(listOf("e", "es"), listOf("e", "ee"))),
    NX(listOf(), listOf(), listOf(listOf("e"), listOf("e", "ee", "s", "es"))),
    XN(listOf(), listOf(), listOf(listOf("e"), listOf("e", "ee"), listOf("s", "se"))),
    XK(listOf(), listOf("s"), listOf(listOf("e", "es"), listOf("e", "ee"), listOf("s", "se"))),
    XX(listOf(), listOf(), listOf(listOf("e"), listOf("e", "ee", "s", "es"), listOf("s", "se"))),
    KN(listOf("s"), listOf(), listOf(listOf("e"), listOf("e", "ee", "se"))),
    KK(listOf("s"), listOf("s"), listOf(listOf("e", "es", "ss"), listOf("e", "ee", "se"))),
    KX(listOf("s"), listOf(), listOf(listOf("e"), listOf("e", "ee", "se", "s", "es"))),
    IN(listOf(), listOf(), listOf(listOf("e", "s"), listOf("e", "ee", "se"))),
    IK(listOf(), listOf("s"), listOf(listOf("e", "es", "s", "ss"), listOf("e", "ee", "se"))),
    IX(listOf(), listOf(), listOf(listOf("e", "s"), listOf("e", "ee", "se", "s", "es")));

    fun protocolName() = "Noise_${name}_25519_ChaChaPoly_SHA256"
    val oneWay: Boolean get() = messages.size == 1
}

/** The two CipherStates after a handshake: [send] for messages this side writes, [receive] for the other side's. */
internal class NoiseTransport(val send: NoiseCipher, val receive: NoiseCipher, val handshakeHash: ByteArray, val remoteStatic: ByteArray?) {
    fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= NoiseCrypto.MAX_MESSAGE - NoiseCrypto.TAGLEN) { "Message too long" }
        return send.encryptWithAd(ByteArray(0), plaintext)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray = receive.decryptWithAd(ByteArray(0), ciphertext)
}

/**
 * A HandshakeState. [ephemeral] is only for test vectors; a real handshake makes a fresh one. [remoteStatic] is the
 * other side's static key when the pattern has it as a pre-message.
 */
internal class NoiseHandshake(
    private val pattern: NoisePattern,
    val initiator: Boolean,
    prologue: ByteArray,
    private val static: NoiseCrypto.KeyPair?,
    private var ephemeral: NoiseCrypto.KeyPair? = null,
    remoteStatic: ByteArray? = null,
) {
    private val symmetric = NoiseSymmetric(pattern.protocolName())
    var remoteStatic: ByteArray? = remoteStatic
        private set
    private var remoteEphemeral: ByteArray? = null
    private var index = 0
    private var result: NoiseTransport? = null

    init {
        symmetric.mixHash(prologue)
        for (token in pattern.initiatorPre) symmetric.mixHash(if (initiator) static!!.public else this.remoteStatic!!)
        for (token in pattern.responderPre) symmetric.mixHash(if (initiator) this.remoteStatic!! else static!!.public)
    }

    val finished: Boolean get() = result != null
    /** Whether this side writes the next handshake message. */
    val writes: Boolean get() = !finished && (if (pattern.oneWay) initiator else (index % 2 == 0) == initiator)
    val handshakeHash: ByteArray get() = symmetric.h

    fun transport(): NoiseTransport = result ?: throw IllegalStateException("Handshake not finished")

    fun writeMessage(payload: ByteArray): ByteArray {
        check(writes) { "Not this side's turn" }
        val out = java.io.ByteArrayOutputStream()
        for (token in pattern.messages[index]) when (token) {
            "e" -> {
                val e = ephemeral ?: NoiseCrypto.generateKeyPair()
                ephemeral = e
                out.write(e.public)
                symmetric.mixHash(e.public)
            }
            "s" -> out.write(symmetric.encryptAndHash(static!!.public))
            else -> mixDh(token)
        }
        out.write(symmetric.encryptAndHash(payload))
        next()
        return out.toByteArray().also { require(it.size <= NoiseCrypto.MAX_MESSAGE) { "Handshake message too long" } }
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!finished && !writes) { "Not the other side's turn" }
        require(message.size <= NoiseCrypto.MAX_MESSAGE) { "Handshake message too long" }
        var at = 0
        fun take(count: Int): ByteArray {
            if (at + count > message.size) throw GeneralSecurityException("Handshake message too short")
            return message.copyOfRange(at, at + count).also { at += count }
        }
        for (token in pattern.messages[index]) when (token) {
            "e" -> {
                val re = take(NoiseCrypto.DHLEN)
                remoteEphemeral = re
                symmetric.mixHash(re)
            }
            "s" -> remoteStatic = symmetric.decryptAndHash(take(NoiseCrypto.DHLEN + if (symmetric.cipher.hasKey()) NoiseCrypto.TAGLEN else 0))
            else -> mixDh(token)
        }
        val payload = symmetric.decryptAndHash(message.copyOfRange(at, message.size))
        next()
        return payload
    }

    private fun mixDh(token: String) {
        val e = ephemeral
        val s = static
        val shared = when (token) {
            "ee" -> NoiseCrypto.dh(e!!.private, remoteEphemeral!!)
            "es" -> if (initiator) NoiseCrypto.dh(e!!.private, remoteStatic!!) else NoiseCrypto.dh(s!!.private, remoteEphemeral!!)
            "se" -> if (initiator) NoiseCrypto.dh(s!!.private, remoteEphemeral!!) else NoiseCrypto.dh(e!!.private, remoteStatic!!)
            "ss" -> NoiseCrypto.dh(s!!.private, remoteStatic!!)
            else -> throw IllegalArgumentException("Unknown token $token")
        }
        symmetric.mixKey(shared)
    }

    private fun next() {
        index++
        if (index < pattern.messages.size) return
        val (one, two) = symmetric.split()
        result = if (initiator) NoiseTransport(one, two, symmetric.h, remoteStatic)
        else NoiseTransport(two, one, symmetric.h, remoteStatic)
    }
}
