package org.fluidmessages

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Nearby's community layer, kept pure for the JVM tests: channels anyone nearby can join, rooms with a passphrase,
 * and messages that phones carry for each other (store and forward) until they reach someone out of range.
 *
 * Everything that travels between phones this way is an [Envelope]. Each has a random id (so every phone keeps it
 * once), its time, and a hop count that each phone passing it along lowers by one ([NearbyRelay]). Three kinds:
 * - [Envelope.CHANNEL]: an open channel like "Neighborhood". The words are readable by anyone nearby, and signed with
 *   the sender's key ([NearbySign]), so no phone along the way can change them or put words in someone's mouth.
 * - [Envelope.ROOM]: a channel with a passphrase. The passphrase makes the room's key ([NearbyRoom]); only phones that
 *   know it can read the words or even the room's name. The sender's signature is inside.
 * - [Envelope.DIRECT]: a one-to-one message sealed to the other person's key with Noise_X ([NearbySeal]). Phones that
 *   carry it can't read it or tell who sent it; they only see a short tag for whom it's for.
 */
internal data class Envelope(
    val id: String,
    val kind: Int,
    val created: Long,
    val hops: Int,
    /** The channel, room, or recipient tag (32 hex). */
    val tag: String,
    /** An open channel's name; "" for rooms and direct messages. */
    val channel: String = "",
    /** An open channel message's sender: signing key, name, and signature. Empty for the other kinds. */
    val sender: ByteArray = ByteArray(0),
    val name: String = "",
    val signature: ByteArray = ByteArray(0),
    /** An open channel's words (UTF-8), a room's sealed content, or a direct message's Noise_X message. */
    val body: ByteArray,
) {
    val size: Int get() = body.size + sender.size + signature.size + channel.length + name.length + 64

    override fun equals(other: Any?) = other is Envelope && NearbyMesh.encode(other).contentEquals(NearbyMesh.encode(this))
    override fun hashCode() = id.hashCode()

    companion object {
        const val CHANNEL = 1
        const val ROOM = 2
        const val DIRECT = 3
    }
}

internal object NearbyMesh {
    const val MAX_WORDS = 4000
    const val MAX_ENVELOPE = 48_000

    fun newId(random: java.util.Random = SecureRandom()) = NoiseCrypto.hex(ByteArray(16).also(random::nextBytes))

    private val ID = Regex("[0-9a-f]{32}")

    /** A channel's name as people type it: trimmed, inner spaces folded, up to 40 letters. Null when unusable. */
    fun cleanChannel(name: String): String? {
        val folded = name.trim().removePrefix("#").trim().replace(Regex("\\s+"), " ")
        return NearbyWire.cleanName(folded)
    }

    /** An open channel's tag, the same for everyone who types the name, whatever the capitals. */
    fun channelTag(name: String): String =
        NoiseCrypto.hex(NoiseCrypto.hash(("fluidnearby:channel:" + name.lowercase(Locale.ROOT)).toByteArray()).copyOf(16))

    /** The bytes a sender signs: everything about the message except the hop count, which changes on the way. */
    fun signed(id: String, kind: Int, created: Long, tag: String, channel: String, name: String, words: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF("fluidnearby:signed:1"); out.writeUTF(id); out.writeByte(kind); out.writeLong(created)
            out.writeUTF(tag); out.writeUTF(channel); out.writeUTF(name); writeBytes(out, words.toByteArray(Charsets.UTF_8))
        }
        return bytes.toByteArray()
    }

    fun encode(envelope: Envelope): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(1)
            out.writeUTF(envelope.id); out.writeByte(envelope.kind); out.writeLong(envelope.created); out.writeByte(envelope.hops)
            out.writeUTF(envelope.tag); out.writeUTF(envelope.channel); writeBytes(out, envelope.sender); out.writeUTF(envelope.name)
            writeBytes(out, envelope.signature); writeBytes(out, envelope.body)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_ENVELOPE) { "Too long to pass along" } }
    }

    /** Throws IOException for anything malformed. */
    fun decode(bytes: ByteArray): Envelope {
        if (bytes.size > MAX_ENVELOPE) throw IOException("Too long")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            if (input.readUnsignedByte() != 1) throw IOException("Unknown version")
            val envelope = Envelope(id = input.readUTF(), kind = input.readUnsignedByte(), created = input.readLong(),
                hops = input.readUnsignedByte(), tag = input.readUTF(), channel = input.readUTF(), sender = readBytes(input),
                name = input.readUTF(), signature = readBytes(input), body = readBytes(input))
            if (input.available() > 0) throw IOException("Extra bytes")
            if (!envelope.id.matches(ID) || !envelope.tag.matches(ID) || envelope.kind !in 1..3) throw IOException("Bad envelope")
            if (envelope.kind == Envelope.CHANNEL && (cleanChannel(envelope.channel) == null || NearbyWire.cleanName(envelope.name) == null ||
                    channelTag(envelope.channel) != envelope.tag)) throw IOException("Bad channel")
            return envelope
        } catch (short: EOFException) { throw IOException("Cut off") }
    }

    private fun writeBytes(out: DataOutputStream, value: ByteArray) { out.writeShort(value.size); out.write(value) }

    private fun readBytes(input: DataInputStream): ByteArray {
        val bytes = ByteArray(input.readUnsignedShort())
        input.readFully(bytes)
        return bytes
    }

    // Open channels

    fun channelMessage(id: String, created: Long, channel: String, signer: NearbySign.Keys, name: String, words: String): Envelope {
        require(words.length <= MAX_WORDS) { "That message is too long for a channel" }
        val tag = channelTag(channel)
        val signature = NearbySign.sign(signer.private, signed(id, Envelope.CHANNEL, created, tag, channel, name, words))
        return Envelope(id, Envelope.CHANNEL, created, NearbyRelay.MAX_HOPS, tag, channel, signer.public, name, signature,
            words.toByteArray(Charsets.UTF_8))
    }

    /** A channel message's words, once its signature checks out; null when it doesn't. */
    fun channelWords(envelope: Envelope): String? {
        if (envelope.kind != Envelope.CHANNEL) return null
        val words = String(envelope.body, Charsets.UTF_8)
        val ok = NearbySign.verify(envelope.sender, signed(envelope.id, envelope.kind, envelope.created, envelope.tag, envelope.channel,
            envelope.name, words), envelope.signature)
        return if (ok && words.length <= MAX_WORDS) words else null
    }

    // Rooms

    data class RoomMessage(val name: String, val words: String, val sender: ByteArray)

    private fun roomHeader(id: String, created: Long, tag: String) = "fluidnearby:room:1:$id:$created:$tag".toByteArray()

    fun roomMessage(id: String, created: Long, key: ByteArray, signer: NearbySign.Keys, name: String, words: String): Envelope {
        require(words.length <= MAX_WORDS) { "That message is too long for a channel" }
        val tag = NearbyRoom.tag(key)
        val signature = NearbySign.sign(signer.private, signed(id, Envelope.ROOM, created, tag, "", name, words))
        val inner = ByteArrayOutputStream()
        DataOutputStream(inner).use { out ->
            out.writeUTF(name); writeBytes(out, words.toByteArray(Charsets.UTF_8)); writeBytes(out, signer.public); writeBytes(out, signature)
        }
        return Envelope(id, Envelope.ROOM, created, NearbyRelay.MAX_HOPS, tag,
            body = NearbyRoom.seal(key, roomHeader(id, created, tag), inner.toByteArray()))
    }

    /** A room message opened with the room's key and its signature checked; null for the wrong key or anything off. */
    fun openRoom(envelope: Envelope, key: ByteArray): RoomMessage? = try {
        if (envelope.kind != Envelope.ROOM || envelope.tag != NearbyRoom.tag(key)) null else {
            val input = DataInputStream(ByteArrayInputStream(NearbyRoom.open(key, roomHeader(envelope.id, envelope.created, envelope.tag), envelope.body)))
            val name = input.readUTF()
            val words = String(readBytes(input), Charsets.UTF_8)
            val sender = readBytes(input)
            val signature = readBytes(input)
            val ok = NearbyWire.cleanName(name) != null && words.length <= MAX_WORDS &&
                NearbySign.verify(sender, signed(envelope.id, Envelope.ROOM, envelope.created, envelope.tag, "", name, words), signature)
            if (ok) RoomMessage(name, words, sender) else null
        }
    } catch (error: Exception) { null }

    // One-to-one, carried by others

    fun directMessage(id: String, created: Long, sender: NoiseCrypto.KeyPair, recipient: ByteArray, text: NearbyWire.Packet.Text): Envelope =
        Envelope(id, Envelope.DIRECT, created, NearbyRelay.MAX_HOPS, NearbySeal.toTag(recipient),
            body = NearbySeal.seal(sender, recipient, NearbyWire.encode(text)))

    data class Direct(val sender: String, val text: NearbyWire.Packet.Text)

    /** A direct message for this phone, opened; null when it isn't for this phone or doesn't open. */
    fun openDirect(envelope: Envelope, me: NoiseCrypto.KeyPair): Direct? = try {
        if (envelope.kind != Envelope.DIRECT || envelope.tag != NearbySeal.toTag(me.public)) null else {
            val opened = NearbySeal.open(me, envelope.body)
            (NearbyWire.decode(opened.plaintext) as? NearbyWire.Packet.Text)?.let { Direct(NoiseCrypto.hex(opened.sender), it) }
        }
    } catch (error: Exception) { null }
}

/** Signatures for channel messages: ECDSA on P-256 with SHA-256, which every Android version and the JDK have. */
internal object NearbySign {
    class Keys(val private: ByteArray, val public: ByteArray)

    fun generate(): Keys {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        return Keys(pair.private.encoded, pair.public.encoded)
    }

    fun sign(private: ByteArray, data: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(private)))
        signer.update(data)
        return signer.sign()
    }

    fun verify(public: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(public)))
        verifier.update(data)
        verifier.verify(signature)
    } catch (error: Exception) { false }

    /** A short, readable mark for a signing key, so two people with the same name look different: "3F9A". */
    fun mark(public: ByteArray): String = NoiseCrypto.hex(NoiseCrypto.hash(public).copyOf(2)).uppercase(Locale.ROOT)
}

/** Rooms with a passphrase: the passphrase and room name make a key, which seals each message (ChaCha20-Poly1305). */
internal object NearbyRoom {
    const val ITERATIONS = 120_000

    fun key(name: String, passphrase: String, iterations: Int = ITERATIONS): ByteArray {
        require(passphrase.length >= 4) { "Use a passphrase of at least 4 letters" }
        val salt = ("fluidnearby:room:" + name.lowercase(Locale.ROOT)).toByteArray()
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** What phones carrying a room's messages see instead of its name. Different passphrases make different rooms. */
    fun tag(key: ByteArray): String = NoiseCrypto.hex(NoiseCrypto.hmac(key, "fluidnearby:room tag".toByteArray()).copyOf(16))

    private fun messageKey(key: ByteArray) = NoiseCrypto.hmac(key, "fluidnearby:room messages".toByteArray())

    fun seal(key: ByteArray, header: ByteArray, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        return nonce + NoiseCrypto.cipherWithNonce(true, messageKey(key), nonce, header, plain)
    }

    fun open(key: ByteArray, header: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < 12 + NoiseCrypto.TAGLEN) throw IOException("Too short")
        return NoiseCrypto.cipherWithNonce(false, messageKey(key), sealed.copyOf(12), header, sealed.copyOfRange(12, sealed.size))
    }
}

/**
 * One-way sealed messages with Noise_X_25519_ChaChaPoly_SHA256 (Noise.kt): sealed to the recipient's static key, with
 * a fresh ephemeral key each time, and carrying the sender's static key encrypted, so only the recipient learns who
 * sent it and can check it. Phones carrying it see only [toTag].
 */
internal object NearbySeal {
    val PROLOGUE: ByteArray = "FluidMessages Nearby sealed 1".toByteArray(Charsets.US_ASCII)

    fun seal(sender: NoiseCrypto.KeyPair, recipient: ByteArray, plaintext: ByteArray): ByteArray =
        NoiseHandshake(NoisePattern.X, true, PROLOGUE, sender, remoteStatic = recipient).writeMessage(plaintext)

    class Opened(val sender: ByteArray, val plaintext: ByteArray)

    fun open(me: NoiseCrypto.KeyPair, sealed: ByteArray): Opened {
        val handshake = NoiseHandshake(NoisePattern.X, false, PROLOGUE, me)
        val plaintext = handshake.readMessage(sealed)
        return Opened(handshake.remoteStatic!!, plaintext)
    }

    fun toTag(recipient: ByteArray): String =
        NoiseCrypto.hex(NoiseCrypto.hash("fluidnearby:to:".toByteArray() + recipient).copyOf(16))
}

/** The rules for passing messages along: how far, how long, how much, and nothing twice. */
internal object NearbyRelay {
    const val MAX_HOPS = 8
    const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
    /** Phones without a network clock can be off; a message from a little in the future is still fine. */
    const val MAX_AHEAD = 24L * 60 * 60 * 1000
    const val MAX_ITEMS = 2000
    const val MAX_BYTES = 8L * 1024 * 1024
    /** How many envelopes one connection may hand over, so a busy or hostile phone can't flood this one. */
    const val PER_CONNECTION = 400
    /** How many ids an inventory lists, newest first. */
    const val INVENTORY = 600

    fun fresh(envelope: Envelope, now: Long) =
        envelope.hops in 0..MAX_HOPS && envelope.created > now - MAX_AGE && envelope.created < now + MAX_AHEAD

    /** What to do with an envelope that just arrived. */
    data class Intake(val keep: Boolean, val passOn: Boolean)

    fun intake(envelope: Envelope, now: Long, alreadyHave: Boolean, helping: Boolean, forThisPhone: Boolean, receivedHere: Int): Intake = when {
        alreadyHave || !fresh(envelope, now) || receivedHere >= PER_CONNECTION -> Intake(false, false)
        // A message for this phone stops here; it's kept only so a copy arriving later is recognized.
        forThisPhone -> Intake(true, false)
        else -> Intake(true, helping && envelope.hops > 0)
    }

    /** The copy handed to the next phone: one hop fewer, or null when it has gone as far as it may. */
    fun next(envelope: Envelope): Envelope? = if (envelope.hops > 0) envelope.copy(hops = envelope.hops - 1) else null

    data class Stored(val id: String, val created: Long, val size: Int, val own: Boolean)

    /**
     * Which stored envelopes to delete: anything past [MAX_AGE], then the oldest that others sent until both limits hold.
     * Your own messages go only by age.
     */
    fun prune(items: List<Stored>, now: Long): List<String> {
        val expired = items.filter { it.created <= now - MAX_AGE }
        val left = (items - expired.toSet()).sortedBy { it.created }.toMutableList()
        val removed = expired.map { it.id }.toMutableList()
        var bytes = left.sumOf { it.size.toLong() }
        val iterator = left.iterator()
        var count = left.size
        while ((count > MAX_ITEMS || bytes > MAX_BYTES) && iterator.hasNext()) {
            val item = iterator.next()
            if (item.own) continue
            removed += item.id
            bytes -= item.size
            count--
        }
        return removed
    }

    /** What to hand a phone that listed [theirs]: everything here it lacks that may still travel, newest first. */
    fun toSend(mine: List<Stored>, theirs: Set<String>, hops: Map<String, Int>): List<String> =
        mine.filter { it.id !in theirs && (hops[it.id] ?: 0) > 0 }.sortedByDescending { it.created }.take(PER_CONNECTION).map { it.id }
}
