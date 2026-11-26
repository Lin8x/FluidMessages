package org.fluidmessages

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.Locale

/**
 * Nearby's pure parts, kept free of Android so the JVM tests cover them: the code people scan to add each other, the
 * safety code they compare without a camera, how bytes are framed on the connection, the packets inside the Noise
 * transport, and the rules for queueing, acknowledging, and deduplicating messages.
 *
 * On the wire every Noise message is sent as a 2-byte big-endian length and then the bytes (the spec's suggested
 * framing for TCP and Bluetooth streams). The handshake is Noise_XX_25519_ChaChaPoly_SHA256 with [PROLOGUE]; the
 * second and third handshake payloads carry each side's [Packet.Hello].
 */
internal object NearbyWire {
    const val SERVICE_TYPE = "_fluidnearby._tcp"
    val PROLOGUE: ByteArray = "FluidMessages Nearby 1".toByteArray(Charsets.US_ASCII)
    const val MAX_TEXT_BYTES = 60_000
    const val MAX_NAME = 40
    const val MAX_HAVE = 1000

    // The code people scan

    /** What a Nearby code holds: the person's static X25519 public key, the name they chose, and a random ID. */
    data class Card(val publicKey: ByteArray, val name: String, val id: String) {
        val keyHex: String get() = NoiseCrypto.hex(publicKey)
        override fun equals(other: Any?) = other is Card && other.publicKey.contentEquals(publicKey) && other.name == name && other.id == id
        override fun hashCode() = publicKey.contentHashCode() * 31 + name.hashCode() * 7 + id.hashCode()
    }

    private const val CARD_PREFIX = "fluidnearby:1:"

    /** "fluidnearby:1:<key, 64 hex>:<id, 16 hex>:<name, base64url>". */
    fun encodeCard(card: Card): String = CARD_PREFIX + card.keyHex + ":" + card.id + ":" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(card.name.toByteArray(Charsets.UTF_8))

    /** The card in a scanned code, or null when it isn't a Nearby code or anything in it looks wrong. */
    fun parseCard(text: String): Card? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(CARD_PREFIX, ignoreCase = true)) return null
        val parts = trimmed.substring(CARD_PREFIX.length).split(':')
        if (parts.size != 3) return null
        val (key, id, encodedName) = parts
        if (!key.matches(Regex("[0-9a-fA-F]{64}")) || !id.matches(Regex("[0-9a-fA-F]{16}"))) return null
        val name = try { String(Base64.getUrlDecoder().decode(encodedName), Charsets.UTF_8) } catch (bad: IllegalArgumentException) { return null }
        val clean = cleanName(name) ?: return null
        val publicKey = NoiseCrypto.hex(key)
        if (publicKey.all { it == 0.toByte() }) return null
        return Card(publicKey, clean, id.lowercase(Locale.ROOT))
    }

    /** A name as typed, trimmed, or null when it's empty, too long, or has control characters. */
    fun cleanName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > MAX_NAME) return null
        if (trimmed.any { it.isISOControl() || it == ' ' || it == ' ' }) return null
        return trimmed
    }

    fun newId(random: java.util.Random = java.security.SecureRandom()): String =
        NoiseCrypto.hex(ByteArray(8).also(random::nextBytes))

    // The safety code

    /**
     * Six digits both phones show after an encrypted connection with someone new, read from the handshake hash. Both
     * sides get the same hash only when no one is in the middle, so matching codes mean it really is the other phone.
     */
    fun safetyCode(handshakeHash: ByteArray): String {
        require(handshakeHash.size >= 4)
        val value = ((handshakeHash[0].toLong() and 0xff) shl 24) or ((handshakeHash[1].toLong() and 0xff) shl 16) or
            ((handshakeHash[2].toLong() and 0xff) shl 8) or (handshakeHash[3].toLong() and 0xff)
        val digits = "%06d".format(value % 1_000_000)
        return digits.substring(0, 3) + " " + digits.substring(3)
    }

    // Framing

    fun frame(bytes: ByteArray): ByteArray {
        require(bytes.size <= NoiseCrypto.MAX_MESSAGE) { "Frame too long" }
        return byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    /** The next frame, or null when the stream ends cleanly between frames. */
    fun readFrame(input: InputStream): ByteArray? {
        val high = input.read()
        if (high < 0) return null
        val low = input.read()
        if (low < 0) throw EOFException("Frame cut off")
        val length = (high shl 8) or low
        val bytes = ByteArray(length)
        var at = 0
        while (at < length) {
            val read = input.read(bytes, at, length - at)
            if (read < 0) throw EOFException("Frame cut off")
            at += read
        }
        return bytes
    }

    // Packets inside the encrypted connection

    sealed class Packet {
        /**
         * Who this side is, sent inside the handshake: the name, the random id, the key it signs channel messages with
         * (hex, "" if none), and one flags byte ([helloFlags]): whether its Add nearby person page is open, whether it
         * knows People nearby and message requests ([modern]), and whether it may be listed under People nearby.
         */
        data class Hello(val name: String, val id: String, val signKey: String = "", val pairing: Boolean = false,
                         val modern: Boolean = false, val visible: Boolean = false) : Packet()
        /** A message. [id] is the sender's own message id, so a message sent twice is stored once. */
        data class Text(val id: String, val date: Long, val text: String) : Packet()
        /** The message with this id is stored on the other phone. */
        data class Ack(val id: String) : Packet()
        /** Keeps the connection known to be alive. */
        object Ping : Packet()
        /** The Add nearby person page opened or closed on the other phone. */
        data class Mode(val pairing: Boolean) : Packet()
        /** The envelopes this phone already has (NearbyMesh), newest first, so the other sends only what's missing. */
        data class Have(val ids: List<String>) : Packet()
        /** Whether this phone may be listed under People nearby now. Sent only to phones whose Hello is [Hello.modern]. */
        data class Seen(val visible: Boolean) : Packet()
        /** An envelope to keep and maybe pass along (NearbyMesh.encode). */
        class Carry(val envelope: ByteArray) : Packet() {
            override fun equals(other: Any?) = other is Carry && other.envelope.contentEquals(envelope)
            override fun hashCode() = envelope.contentHashCode()
        }
    }

    private const val HELLO = 1
    private const val TEXT = 2
    private const val ACK = 3
    private const val PING = 4
    private const val MODE = 5
    private const val HAVE = 6
    private const val CARRY = 7
    private const val SEEN = 8

    private const val PAIRING_FLAG = 1
    private const val MODERN_FLAG = 2
    private const val VISIBLE_FLAG = 4

    /**
     * The Hello's last byte. Phones from before People nearby read it as "Add nearby person is open" only when it is
     * exactly 1, so a newer phone on that page also sends Mode(true) after the handshake (DirectNearby.adopt).
     */
    fun helloFlags(pairing: Boolean, modern: Boolean, visible: Boolean) =
        (if (pairing) PAIRING_FLAG else 0) or (if (modern) MODERN_FLAG else 0) or (if (visible) VISIBLE_FLAG else 0)

    fun encode(packet: Packet): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            when (packet) {
                is Packet.Hello -> {
                    out.writeByte(HELLO); writeString(out, packet.name); writeString(out, packet.id)
                    writeString(out, packet.signKey); out.writeByte(helloFlags(packet.pairing, packet.modern, packet.visible))
                }
                is Packet.Text -> {
                    out.writeByte(TEXT); writeString(out, packet.id); out.writeLong(packet.date); writeString(out, packet.text)
                }
                is Packet.Ack -> { out.writeByte(ACK); writeString(out, packet.id) }
                Packet.Ping -> out.writeByte(PING)
                is Packet.Mode -> { out.writeByte(MODE); out.writeByte(if (packet.pairing) 1 else 0) }
                is Packet.Have -> {
                    require(packet.ids.size <= MAX_HAVE) { "Too many" }
                    out.writeByte(HAVE); out.writeShort(packet.ids.size); for (id in packet.ids) out.write(NoiseCrypto.hex(id))
                }
                is Packet.Carry -> { out.writeByte(CARRY); out.writeShort(packet.envelope.size); out.write(packet.envelope) }
                is Packet.Seen -> { out.writeByte(SEEN); out.writeByte(if (packet.visible) 1 else 0) }
            }
        }
        return bytes.toByteArray()
    }

    /** Throws IOException for anything malformed, so a broken or hostile packet ends the connection. */
    fun decode(bytes: ByteArray): Packet {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            val packet = when (input.readUnsignedByte()) {
                HELLO -> {
                    val name = cleanName(readString(input)) ?: throw IOException("Bad name")
                    val id = readString(input)
                    if (!id.matches(Regex("[0-9a-f]{16}"))) throw IOException("Bad id")
                    // Phones from before channels send only the name and id.
                    if (input.available() == 0) Packet.Hello(name, id) else {
                        val signKey = readString(input)
                        if (!signKey.matches(Regex("([0-9a-f]{2}){0,200}"))) throw IOException("Bad key")
                        val flags = input.readUnsignedByte()
                        Packet.Hello(name, id, signKey, flags and PAIRING_FLAG != 0, flags and MODERN_FLAG != 0, flags and VISIBLE_FLAG != 0)
                    }
                }
                TEXT -> {
                    val id = readString(input)
                    if (!validMessageId(id)) throw IOException("Bad message id")
                    Packet.Text(id, input.readLong(), readString(input))
                }
                ACK -> Packet.Ack(readString(input).also { if (!validMessageId(it)) throw IOException("Bad message id") })
                PING -> Packet.Ping
                MODE -> Packet.Mode(input.readUnsignedByte() == 1)
                HAVE -> {
                    val count = input.readUnsignedShort()
                    if (count > MAX_HAVE) throw IOException("Too many")
                    Packet.Have(List(count) { ByteArray(16).also(input::readFully).let(NoiseCrypto::hex) })
                }
                CARRY -> Packet.Carry(ByteArray(input.readUnsignedShort()).also(input::readFully))
                SEEN -> Packet.Seen(input.readUnsignedByte() == 1)
                else -> throw IOException("Unknown packet")
            }
            if (input.available() > 0) throw IOException("Extra bytes")
            return packet
        } catch (short: EOFException) { throw IOException("Packet cut off") }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        require(utf8.size <= MAX_TEXT_BYTES) { "Too long" }
        out.writeShort(utf8.size)
        out.write(utf8)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        if (length > MAX_TEXT_BYTES) throw IOException("Too long")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun validMessageId(id: String) = id.matches(Regex("[A-Za-z0-9-]{1,64}"))

    // Delivery

    /** The status on a message waiting for its person. */
    fun waiting(name: String) = "Waiting for ${name.ifBlank { "them" }} to be nearby"
    const val SENT = "Sent nearby"

    /** The id sent on the wire for a stored outgoing message ("direct-<uuid>" → "<uuid>"). */
    fun wireId(localId: String): String = localId.removePrefix("direct-")

    /** Where an incoming message is stored: one id per sender and message, so a repeat is recognized and not stored twice. */
    fun incomingId(peerKeyHex: String, wireId: String) = "nearby-" + peerKeyHex.take(16).lowercase(Locale.ROOT) + "-" + wireId

    /** A message's date as stored: the sender's time, but never in the future of this phone's clock. */
    fun incomingDate(sent: Long, now: Long) = if (sent in 1..now) sent else now

    /** What happens to an incoming message: [store] it if it's new; always [ack], so the sender stops resending. */
    data class Receipt(val store: Boolean, val ack: Boolean)

    fun receive(alreadyStored: Boolean, fromContact: Boolean): Receipt = when {
        // Refused (NearbyPeople.intake): nothing is kept or confirmed, so it's sent again later.
        !fromContact -> Receipt(store = false, ack = false)
        else -> Receipt(store = !alreadyStored, ack = true)
    }

    data class Waiting(val id: String, val peer: String, val date: Long)

    /** The waiting messages to send now: oldest first, only to people connected right now. */
    fun sendable(waiting: List<Waiting>, nearby: Set<String>): List<Waiting> =
        waiting.filter { it.peer in nearby }.sortedBy { it.date }

    /**
     * Both phones may connect to each other at once. Each keeps the connection started by the phone with the smaller
     * key, and both pick the same one. True when [new] should replace [existing], given who started each.
     */
    fun replaces(existingStarter: String, newStarter: String): Boolean = newStarter < existingStarter
}
