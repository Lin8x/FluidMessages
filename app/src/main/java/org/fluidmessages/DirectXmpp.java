package org.fluidmessages;

import android.content.Context;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.android.AndroidSmackInitializer;
import org.jivesoftware.smack.chat2.ChatManager;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.MessageBuilder;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.packet.StanzaBuilder;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.carbons.packet.CarbonExtension;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.sid.element.StanzaIdElement;
import org.json.JSONObject;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;

/**
 * XMPP from the phone with Smack: one-to-one chats and group chats you link, TLS required, message carbons,
 * delivery receipts, replies (XEP-0461), reactions (XEP-0444), and files with HTTP File Upload and an
 * out-of-band link, the same behavior as the companion. It needs an open connection, so it runs while
 * FluidMessages is open, or always with "Stay connected".
 */
final class DirectXmpp implements DirectProtocols.DirectClient {
    private static final String REPLY = "urn:xmpp:reply:0", REACTIONS = "urn:xmpp:reactions:0", OOB = "jabber:x:oob";
    private static final Pattern URL_TAG = Pattern.compile("<url>(https://[^<]{1,2000})</url>");
    private final String signature;
    private volatile boolean stopped;
    private Thread thread;
    private Context context;
    private JSONObject account;
    private XMPPTCPConnection connection;

    DirectXmpp(String signature) { this.signature = signature; }

    @Override public String toString() { return signature; }
    @Override public boolean persistent() { return true; }

    @Override public void start(Context context, JSONObject account) {
        this.context = context;
        this.account = account;
        thread = new Thread(this::connect, "fluid-direct-xmpp");
        thread.start();
    }

    @Override public void stop() {
        stopped = true;
        var current = connection;
        if (current != null) new Thread(current::disconnect, "fluid-xmpp-close").start();
        if (thread != null) thread.interrupt();
    }

    private void connect() {
        long delay = 5_000;
        while (!stopped) {
            try {
                AndroidSmackInitializer.initialize(context);
                var jid = JidCreate.entityBareFrom(account.getString("jid"));
                var builder = XMPPTCPConnectionConfiguration.builder()
                    .setUsernameAndPassword(jid.getLocalpart().toString(), account.getString("password"))
                    .setXmppDomain(jid.asDomainBareJid())
                    .setResource("FluidMessages")
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.required);
                if (!account.optString("host").isEmpty()) builder.setHost(account.getString("host")).setPort(account.optInt("port", 5222));
                var next = new XMPPTCPConnection(builder.build());
                next.setUseStreamManagement(true);
                next.setUseStreamManagementResumption(true);
                ReconnectionManager.getInstanceFor(next).enableAutomaticReconnection();
                DeliveryReceiptManager.getInstanceFor(next).setAutoReceiptMode(DeliveryReceiptManager.AutoReceiptMode.ifIsSubscribed);
                DeliveryReceiptManager.getInstanceFor(next).autoAddDeliveryReceiptRequests();
                ChatManager.getInstanceFor(next).addIncomingListener((from, message, chat) -> received(from, message, false));
                CarbonManager.getInstanceFor(next).addCarbonCopyReceivedListener((direction, carbon, wrapper) ->
                    received(direction == CarbonExtension.Direction.sent ? carbon.getTo().asEntityBareJidIfPossible()
                        : carbon.getFrom().asEntityBareJidIfPossible(), carbon, direction == CarbonExtension.Direction.sent));
                // XMPP calls: Jingle session requests and call ringing (XEP-0353) go to the call screen.
                next.registerIQRequestHandler(new org.jivesoftware.smack.iqrequest.AbstractIqRequestHandler("jingle", "urn:xmpp:jingle:1",
                        org.jivesoftware.smack.packet.IQ.Type.set, org.jivesoftware.smack.iqrequest.IQRequestHandler.Mode.async) {
                    @Override public org.jivesoftware.smack.packet.IQ handleIQRequest(org.jivesoftware.smack.packet.IQ request) {
                        if (request.getFrom() != null) CallHub.xmppStanza(context, request.getFrom().toString(), request.toXML().toString());
                        return org.jivesoftware.smack.packet.IQ.createResultIQ(request);
                    }
                });
                next.addAsyncStanzaListener(stanza -> {
                    if (stanza.getFrom() != null) CallHub.xmppStanza(context, stanza.getFrom().toString(), stanza.toXML().toString());
                }, stanza -> stanza instanceof Message message && message.getExtensions().stream()
                    .anyMatch(extension -> CallHub.JINGLE_MESSAGE.equals(extension.getNamespace())));
                next.connect().login();
                connection = next;
                try { CarbonManager.getInstanceFor(next).enableCarbons(); } catch (Exception ignored) {}
                var mucs = MultiUserChatManager.getInstanceFor(next);
                for (String room : DirectProtocols.targets(context, MessageProtocol.XMPP, true)) {
                    try {
                        MultiUserChat muc = mucs.getMultiUserChat(JidCreate.entityBareFrom(room));
                        muc.addMessageListener(message -> grouped(muc, message));
                        muc.join(Resourcepart.from(jid.getLocalpart().toString()));
                    } catch (Exception ignored) {}
                }
                return;
            } catch (Exception error) {
                try { Thread.sleep(delay); } catch (InterruptedException interrupted) { return; }
                delay = Math.min(delay * 2, 300_000);
            }
        }
    }

    private void grouped(MultiUserChat muc, Message message) {
        var from = message.getFrom();
        if (from == null || from.getResourceOrNull() == null) return;
        String nick = from.getResourceOrNull().toString();
        boolean own = muc.getNickname() != null && nick.equals(muc.getNickname().toString());
        String id = StanzaIdElement.hasStanzaId(message) ? StanzaIdElement.getStanzaId(message).getId() : message.getStanzaId();
        accept(muc.getRoom().toString(), own ? "" : nick, message, id, own);
    }

    private void received(EntityBareJid other, Message message, boolean outgoing) {
        if (other == null) return;
        accept(other.toString(), outgoing ? "" : other.toString(), message, message.getStanzaId(), outgoing);
    }

    private void accept(String conversation, String author, Message message, String messageId, boolean outgoing) {
        try {
            var route = DirectProtocols.routeForTarget(context, MessageProtocol.XMPP, conversation.toLowerCase(Locale.ROOT));
            if (route == null || messageId == null) return; // Only linked chats appear.
            String address = route.getString("address");
            if (outgoing && DirectProtocols.findBy(context, address, "xmpp_id", messageId) != null) return;
            var delay = DelayInformation.from(message);
            long date = delay == null ? System.currentTimeMillis() : delay.getStamp().getTime();
            String id = "xmpp-" + hash(conversation + ":" + messageId + ":" + date);
            JSONObject record;
            var retract = element(message, "retract", RETRACT);
            if (retract != null) {
                // XEP-0424: the sender took the message back, so it goes here too.
                var target = DirectProtocols.findBy(context, address, "xmpp_id", retract.getAttributeValue("id"));
                if (target != null && (outgoing || author.equals(target.optString("author")))) {
                    ProtocolStore.get(context).remove(target.getString("id"));
                    MmsTransport.changed(context);
                }
                return;
            }
            var reactions = element(message, "reactions", REACTIONS);
            if (reactions != null) {
                var target = DirectProtocols.findBy(context, address, "xmpp_id", reactions.getAttributeValue("id"));
                if (target == null) return;
                var all = reactions.getElements("reaction");
                var last = all.isEmpty() ? null : all.get(all.size() - 1).getText();
                if (last == null || last.isBlank()) {
                    // XEP-0444 sends the sender's whole set; an empty set takes their reaction back.
                    String earlier = earlierReaction(address, target.getString("id"), author, outgoing);
                    if (earlier.isEmpty()) return;
                    record = DirectProtocols.record(route, id, MessageInteractionsKt.reactionRemovedSms(earlier, target.optString("body"), false),
                        outgoing, date, author).put("reaction_target", target.getString("id"));
                } else {
                    record = DirectProtocols.record(route, id, MessageInteractionsKt.emojiReactionSms(last, target.optString("body"), false),
                        outgoing, date, author).put("reaction_target", target.getString("id")).put("reaction", last);
                }
            } else {
                String body = message.getBody();
                if (body == null) return;
                var reply = element(message, "reply", REPLY);
                var target = reply == null ? null : DirectProtocols.findBy(context, address, "xmpp_id", reply.getAttributeValue("id"));
                if (target != null) {
                    // Drop the quoted fallback lines other clients add before the reply.
                    var lines = body.split("\n", -1);
                    int start = 0;
                    while (start < lines.length && lines[start].startsWith(">")) start++;
                    String rest = String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length)).strip();
                    body = "Reply to “" + MessageInteractionsKt.messageExcerpt(target.optString("body"), 240) + "”\n\n" + (rest.isEmpty() ? body : rest);
                }
                String url = oob(message);
                record = DirectProtocols.record(route, id, body, outgoing, date, author);
                if (target != null) record.put("reply_target", target.getString("id"));
                var disappear = element(message, "disappear", DISAPPEAR);
                if (disappear != null) {
                    try { record.put("expires", date + Math.max(0, Integer.parseInt(disappear.getAttributeValue("seconds"))) * 1000L); }
                    catch (NumberFormatException ignored) {}
                }
                if (url != null && SyncPolicy.autoDownload(context)) fetch(record, url);
            }
            DirectProtocols.incoming(context, record.put("xmpp_id", messageId));
        } catch (Exception ignored) {}
    }

    private static StandardExtensionElement element(Message message, String name, String namespace) {
        ExtensionElement element = message.getExtensionElement(name, namespace);
        return element instanceof StandardExtensionElement standard ? standard : null;
    }

    private static String oob(Message message) {
        ExtensionElement element = message.getExtensionElement("x", OOB);
        if (element == null) return null;
        var match = URL_TAG.matcher(element.toXML().toString());
        return match.find() ? match.group(1) : null;
    }

    /** Downloads a shared file (HTTPS only, up to 25 MB) so it opens like other attachments. */
    private void fetch(JSONObject record, String link) {
        try {
            var url = new URL(link);
            var connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            String mime = connection.getContentType() == null ? "application/octet-stream" : connection.getContentType().split(";")[0].strip();
            String id = record.getString("id");
            try (var input = connection.getInputStream()) { ProtocolStore.get(context).putFile(id, input, MediaSanitizer.MAX_FILE); }
            String name = MediaSanitizer.safeName(url.getPath().substring(url.getPath().lastIndexOf('/') + 1));
            record.put("attachment", new Attachment(Attachment.kindFor(mime), null, name, mime,
                ProtocolStore.get(context).attachmentFile(id).length()).toJson());
            String body = record.getString("body").replace(link, "").strip();
            record.put("body", body.isEmpty() ? name : body);
        } catch (Exception ignored) {}
    }

    /** The emoji this sender last reacted with on a message, so a removal can say which one. */
    private String earlierReaction(String address, String target, String author, boolean outgoing) {
        try {
            for (JSONObject item : ProtocolStore.get(context).read()) {
                if (address.equals(item.optString("address")) && target.equals(item.optString("reaction_target"))
                        && (outgoing ? item.optInt("type") == 2 : author.equals(item.optString("author")))) {
                    return item.optString("reaction");
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static final String RETRACT = "urn:xmpp:message-retract:1";

    /** Sends a call stanza the call screen built (Jingle IQ or a ringing message). */
    void sendRaw(String xml) throws Exception {
        var current = connection;
        if (current == null || !current.isAuthenticated()) throw new IllegalStateException("XMPP isn’t connected yet");
        current.sendStanza(org.jivesoftware.smack.util.PacketParserUtils.parseStanza(xml));
    }

    /** This device's full address, which Jingle sessions name as the caller or answerer. */
    String self() {
        var current = connection;
        return current == null || current.getUser() == null ? "" : current.getUser().toString();
    }
    /** Lets other FluidMessages apps remove the message on the same timer; other clients ignore it. */
    private static final String DISAPPEAR = "urn:fluidmessages:disappear:0";

    @Override public void changeMember(Context context, JSONObject route, boolean add, String member) throws Exception {
        var current = connection;
        if (current == null || !current.isAuthenticated()) throw new IllegalStateException("XMPP isn’t connected yet");
        if (!route.optBoolean("group")) throw new IllegalStateException("Only XMPP group chats have members to change");
        MultiUserChat muc = MultiUserChatManager.getInstanceFor(current).getMultiUserChat(JidCreate.entityBareFrom(route.getString("target")));
        // Adding invites an address; removing kicks someone by their nickname in the room, which needs moderator rights.
        if (add) muc.invite(JidCreate.entityBareFrom(member), "");
        else muc.kickParticipant(Resourcepart.from(member), "Removed from the group");
    }

    @Override public void retract(Context context, JSONObject route, JSONObject record) throws Exception {
        var current = connection;
        String target = record.optString("xmpp_id");
        if (current == null || !current.isAuthenticated() || target.isEmpty()) return;
        var builder = StanzaBuilder.buildMessage().to(route.getString("target"))
            .ofType(route.optBoolean("group") ? Message.Type.groupchat : Message.Type.chat)
            .setBody("This message was removed.");
        builder.addExtension(StandardExtensionElement.builder("retract", RETRACT).addAttribute("id", target).build());
        builder.addExtension(StandardExtensionElement.builder("fallback", "urn:xmpp:fallback:0").addAttribute("for", RETRACT).build());
        builder.addExtension(StandardExtensionElement.builder("store", "urn:xmpp:hints").build());
        current.sendStanza(builder.build());
    }

    @Override public JSONObject send(Context context, JSONObject route, JSONObject message, MediaSanitizer.Prepared file) throws Exception {
        var current = connection;
        long deadline = System.currentTimeMillis() + 15_000;
        while ((current == null || !current.isAuthenticated()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            current = connection;
        }
        if (current == null || !current.isAuthenticated()) throw new IllegalStateException("XMPP isn’t connected yet");
        boolean group = route.optBoolean("group");
        var relation = message.optJSONObject("relation");
        String reply = relation == null ? "" : relation.optString("xmpp_id");
        MessageBuilder builder = StanzaBuilder.buildMessage().to(route.getString("target"))
            .ofType(group ? Message.Type.groupchat : Message.Type.chat);
        if (relation != null && "reaction".equals(relation.optString("kind")) && !reply.isEmpty()) {
            // XEP-0444 replaces your whole set: none takes the reaction back, one emoji changes it.
            var reactions = StandardExtensionElement.builder("reactions", REACTIONS).addAttribute("id", reply);
            if (!relation.optBoolean("removed")) reactions.addElement("reaction", relation.optString("emoji"));
            builder.addExtension(reactions.build());
            builder.addExtension(StandardExtensionElement.builder("store", "urn:xmpp:hints").build());
        } else {
            String body = !reply.isEmpty() && "reply".equals(relation.optString("kind")) ? message.optString("text") : message.getString("body");
            if (file != null) {
                URL uploaded = HttpFileUploadManager.getInstanceFor(current).uploadFile(file.file());
                String caption = message.optString("text");
                body = caption.isEmpty() ? uploaded.toString() : caption + "\n" + uploaded;
                builder.addExtension(StandardExtensionElement.builder("x", OOB).addElement("url", uploaded.toString()).build());
            }
            if (!reply.isEmpty() && "reply".equals(relation.optString("kind"))) {
                String to = group && !relation.optString("author").isEmpty() ? route.getString("target") + "/" + relation.optString("author")
                    : route.getString("target");
                builder.addExtension(StandardExtensionElement.builder("reply", REPLY).addAttribute("to", to).addAttribute("id", reply).build());
            }
            builder.setBody(body);
            if (message.optInt("expire_seconds") > 0) builder.addExtension(StandardExtensionElement.builder("disappear", DISAPPEAR)
                .addAttribute("seconds", Integer.toString(message.optInt("expire_seconds"))).build());
        }
        Message stanza = builder.build();
        current.sendStanza(stanza);
        return new JSONObject().put("xmpp_id", stanza.getStanzaId());
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return hex.toString();
        } catch (Exception error) { return Integer.toHexString(value.hashCode()); }
    }
}
