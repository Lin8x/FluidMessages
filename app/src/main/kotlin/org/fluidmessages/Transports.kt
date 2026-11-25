package org.fluidmessages

import android.app.role.RoleManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject

/**
 * Shared message layer. Every protocol implements [MessageTransport] so replies, reactions, forwards,
 * attachments, and the read-only rule work the same way in every chat. Transports that have a native
 * reply or reaction feature receive the structured [MessageRelation]. All of them also get a readable
 * fallback body that FluidMessages can turn back into a quote card or folded reaction.
 */
enum class Encryption(val label: String) {
    NONE("Not encrypted"),
    IN_TRANSIT("Encrypted in transit · not end-to-end"),
    END_TO_END("End-to-end encrypted"),
    PREVIEW("RCS isn’t available in FluidMessages yet"),
    COMPANION_E2EE("End-to-end encrypted · decrypted on FluidMessages Web"),
    FLUIDMESSAGES("Encrypted by FluidMessages with the key you share"),
}

internal data class TransportCapabilities(
    /** Sends files (photos, video, audio, voice, documents, contact cards). Locations work everywhere as map links. */
    val attachments: Boolean,
    val groups: Boolean,
    val nativeReplies: Boolean,
    val nativeReactions: Boolean,
    val encryption: Encryption,
)

internal enum class RelationKind { REPLY, REACTION, FORWARD }

/** What an outgoing message refers to. [targetId] is the local message id when one is known. */
internal data class MessageRelation(
    val kind: RelationKind,
    val targetId: String?,
    val targetText: String,
    val targetAuthor: String = "",
    val emoji: String = "",
    val ownMessage: Boolean = false,
    /** Taking back [emoji]: sent again on the same message. */
    val removed: Boolean = false,
    /** The sender's earlier reaction that [emoji] replaces, or "". */
    val previous: String = "",
    /** For a reaction to a picture or file sent without words: what it was, named in place of a quote. */
    val targetMedia: ReactionMedia? = null,
) {
    /** A removal or a change of an earlier reaction. */
    val revises: Boolean get() = kind == RelationKind.REACTION && (removed || previous.isNotEmpty())

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name.lowercase()).put("target", targetId ?: "")
        .put("emoji", emoji).put("own", ownMessage).put("removed", removed).put("previous", previous)

    companion object {
        @JvmStatic fun reply(target: ChatLine) = MessageRelation(RelationKind.REPLY, target.id, target.text, target.author.orEmpty())
        @JvmStatic fun reaction(target: ChatLine, emoji: String, ownMessage: Boolean) =
            MessageRelation(RelationKind.REACTION, target.id, target.text, target.author.orEmpty(), emoji, ownMessage,
                targetMedia = target.wordlessMedia())
        @JvmStatic fun forward(text: String, author: String) = MessageRelation(RelationKind.FORWARD, null, text, author)
    }
}

/** A message to send. [text] is what the person typed. The relation is kept separate from it. */
internal data class OutgoingMessage @JvmOverloads constructor(
    val conversation: String,
    val members: List<String>,
    val text: String,
    val attachment: Attachment? = null,
    val sim: Int = -1,
    val relation: MessageRelation? = null,
    /** Typed with a leading //: a line that starts with / goes out as written, never as a command or action. */
    val literal: Boolean = false,
) {
    val photo: Uri? get() = attachment?.takeIf { it.kind == AttachmentKind.PHOTO }?.uri

    /**
     * What the transport sends. Matrix and XMPP remove and change reactions natively; every other protocol
     * sends the readable text, so people on other apps know what changed.
     */
    fun forTransport(protocol: MessageProtocol): OutgoingMessage = when {
        relation?.revises != true || protocol == MessageProtocol.MATRIX || protocol == MessageProtocol.XMPP -> this
        // Texts get the usual "Loved “…”" for a change too: iPhones and other apps replace the earlier reaction with it.
        !relation.removed && (protocol == MessageProtocol.SMS || protocol == MessageProtocol.MMS || protocol == MessageProtocol.RCS) ->
            copy(relation = relation.copy(previous = ""))
        else -> copy(text = fallbackBody(), relation = null)
    }

    /** The attachment travels as a file, rather than as text such as a location link. */
    val hasFile: Boolean get() = attachment?.isFile == true

    /** Readable form for protocols without the feature, and for the local copy of every message. */
    fun fallbackBody(): String {
        val caption = when {
            attachment?.kind == AttachmentKind.LOCATION ->
                locationText(attachment.latitude, attachment.longitude) + if (text.isBlank()) "" else "\n$text"
            attachment != null -> text.ifBlank { attachment.caption }
            else -> text
        }
        return when (relation?.kind) {
            RelationKind.REPLY -> "Reply to “${messageExcerpt(relation.targetText, 240)}”\n\n$caption"
            RelationKind.REACTION -> when {
                relation.removed -> reactionRemovedSms(relation.emoji, relation.targetText, relation.ownMessage, relation.targetMedia)
                relation.previous.isNotEmpty() ->
                    reactionChangedSms(relation.previous, relation.emoji, relation.targetText, relation.ownMessage)
                else -> emojiReactionSms(relation.emoji, relation.targetText, relation.ownMessage, relation.targetMedia)
            }
            RelationKind.FORWARD -> forwardAsSms(relation.targetText, relation.targetAuthor)
            null -> caption
        }
    }
}

internal interface MessageTransport {
    val protocol: MessageProtocol
    val capabilities: TransportCapabilities

    /** Why this conversation can't send right now, or "" if it can. Disabled protocols stay readable. */
    fun unavailable(context: Context, prefs: SharedPreferences, conversation: String): String

    /** Blocking. Call off the main thread. Throws with a readable message when sending fails. */
    @Throws(Exception::class)
    fun send(context: Context, message: OutgoingMessage)
}

private fun holdsSmsRole(context: Context) =
    context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

private object SmsTransport : MessageTransport {
    override val protocol = MessageProtocol.SMS
    override val capabilities = TransportCapabilities(attachments = false, groups = false,
        nativeReplies = false, nativeReactions = false, encryption = Encryption.NONE)

    override fun unavailable(context: Context, prefs: SharedPreferences, conversation: String) = when {
        !protocol.enabled(prefs) -> protocol.disabledReason(prefs)
        !holdsSmsRole(context) -> "Make FluidMessages your texting app to send texts."
        else -> ""
    }

    override fun send(context: Context, message: OutgoingMessage) {
        // A file makes this an MMS, the same way carriers upgrade a text with an attachment. Locations stay SMS.
        if (message.hasFile) return MmsCarrierTransport.send(context, message)
        for (member in message.members) SmsStore.send(context, member, message.fallbackBody(), message.sim, message.conversation)
    }
}

private object MmsCarrierTransport : MessageTransport {
    override val protocol = MessageProtocol.MMS
    override val capabilities = TransportCapabilities(attachments = true, groups = true,
        nativeReplies = false, nativeReactions = false, encryption = Encryption.NONE)

    override fun unavailable(context: Context, prefs: SharedPreferences, conversation: String) = when {
        !protocol.enabled(prefs) -> protocol.disabledReason(prefs)
        !holdsSmsRole(context) -> "Make FluidMessages your texting app to send texts."
        else -> ""
    }

    override fun send(context: Context, message: OutgoingMessage) =
        MmsTransport.send(context, message.members, message.fallbackBody(), message.attachment?.takeIf { it.isFile }, message.sim)
}

private object RcsPreviewTransport : MessageTransport {
    override val protocol = MessageProtocol.RCS
    override val capabilities = TransportCapabilities(attachments = false, groups = true,
        nativeReplies = false, nativeReactions = false, encryption = Encryption.PREVIEW)

    override fun unavailable(context: Context, prefs: SharedPreferences, conversation: String) =
        protocol.disabledReason(prefs).ifEmpty { "RCS is only a preview here. It needs carrier support." }

    override fun send(context: Context, message: OutgoingMessage) =
        throw IllegalStateException(unavailable(context, context.getSharedPreferences("fluid", Context.MODE_PRIVATE), message.conversation))
}

/** Internet protocols reached through the paired companion. */
private class CompanionTransport(
    override val protocol: MessageProtocol,
    override val capabilities: TransportCapabilities,
) : MessageTransport {
    // "Connect from this phone" sends directly; otherwise the companion relays the message.
    override fun unavailable(context: Context, prefs: SharedPreferences, conversation: String) =
        protocol.disabledReason(prefs).ifEmpty {
            if (DirectProtocols.on(prefs, protocol)) DirectProtocols.unavailable(prefs, conversation, protocol)
            else GatewayClient.unavailable(prefs, conversation, protocol)
        }

    override fun send(context: Context, message: OutgoingMessage) {
        // FluidMessages encryption, when this chat or protocol uses it, scrambles the text before it leaves the phone.
        val text = SmsCrypto.outgoing(context, message.conversation, protocol, message.text)
        val body = SmsCrypto.outgoing(context, message.conversation, protocol, message.fallbackBody())
        if (DirectProtocols.on(DirectProtocols.prefs(context), protocol)) return DirectProtocols.send(context, message.conversation,
            protocol, text, body, message.relation, message.attachment?.takeIf { it.isFile }, message.literal)
        GatewayClient.send(context, message.conversation, protocol, text, body, message.relation,
            message.attachment?.takeIf { it.isFile }, capabilities.attachments, message.literal)
    }
}

internal object Transports {
    private val companion = mapOf(
        MessageProtocol.MATRIX to CompanionTransport(MessageProtocol.MATRIX, TransportCapabilities(
            attachments = true, groups = true, nativeReplies = true, nativeReactions = true, encryption = Encryption.IN_TRANSIT)),
        MessageProtocol.XMPP to CompanionTransport(MessageProtocol.XMPP, TransportCapabilities(
            attachments = true, groups = true, nativeReplies = true, nativeReactions = true, encryption = Encryption.IN_TRANSIT)),
        MessageProtocol.EMAIL to CompanionTransport(MessageProtocol.EMAIL, TransportCapabilities(
            attachments = true, groups = false, nativeReplies = true, nativeReactions = true, encryption = Encryption.IN_TRANSIT)),
        // IRC has no reliable file transfer, so files go out as a link from the companion's sharing destination.
        MessageProtocol.IRC to CompanionTransport(MessageProtocol.IRC, TransportCapabilities(
            attachments = true, groups = true, nativeReplies = false, nativeReactions = false, encryption = Encryption.IN_TRANSIT)),
    )

    @JvmStatic
    fun of(protocol: MessageProtocol): MessageTransport = when (protocol) {
        MessageProtocol.SMS -> SmsTransport
        MessageProtocol.MMS -> MmsCarrierTransport
        MessageProtocol.RCS -> RcsPreviewTransport
        else -> companion.getValue(protocol)
    }
}

/**
 * A group lives on one protocol, as with every messaging service: protocols can't relay one group
 * between them. This picks the first protocol in the usual order that every member can be reached
 * on and that supports groups.
 */
internal object GroupRouting {
    data class Choice(val protocol: MessageProtocol?, val reason: String)

    @JvmStatic
    fun pick(prefs: SharedPreferences, members: List<String>, routes: java.util.function.Function<String, List<MessageProtocol>>): Choice {
        val reachable = members.associateWith { member ->
            routes.apply(member).flatMap { if (it == MessageProtocol.SMS) listOf(it, MessageProtocol.MMS) else listOf(it) }
        }
        for (protocol in MessageProtocol.values()) {
            val transport = protocol.transport()
            if (!transport.capabilities.groups || !protocol.enabled(prefs)) continue
            if (protocol == MessageProtocol.RCS) continue // RCS groups wait for carrier RCS support.
            if (reachable.values.all { protocol in it }) {
                // New Matrix, XMPP, and IRC groups are created on the companion, not from the phone yet.
                return if (protocol == MessageProtocol.MMS) Choice(protocol, "")
                else Choice(null, "Everyone has ${protocol.label}, but new ${protocol.label} groups are created on FluidMessages Web for now.")
            }
        }
        val phones = members.all { SmsStore.validRecipient(it) }
        return Choice(null, when {
            phones && !MessageProtocol.MMS.enabled(prefs) -> "Allow MMS in Settings → Message protocols to text everyone in one group."
            phones -> "SMS is turned off, so this group can’t be created."
            else -> "No single protocol reaches everyone: " + reachable.filterValues { it.isEmpty() }.keys.joinToString()
        })
    }
}
