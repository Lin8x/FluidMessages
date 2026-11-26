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
import org.jivesoftware.smack.util.PacketParserUtils;
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
 *
 * OMEMO (PhoneOmemo): the phone is one OMEMO device of the account. One-to-one chats are locked when the other
 * person has OMEMO devices, with a notice in the chat when a message goes without it. Group chats from the phone
 * aren't locked yet; FluidMessages Web locks members-only groups.
 */
final class DirectXmpp implements DirectProtocols.DirectClient {
    private static final String REPLY = "urn:xmpp:reply:0", REACTIONS = "urn:xmpp:reactions:0", OOB = "jabber:x:oob";
    private static final Pattern URL_TAG = Pattern.compile("<url>(https://[^<]{1,2000})</url>");
    private final String signature;
    private volatile boolean stopped, manual;
    private final Object wake = new Object();
    private Thread thread;
    private Context context;
    private JSONObject account;
    private XMPPTCPConnection connection;
    private volatile PhoneOmemo omemo;
    /** Locked messages are opened one at a time, in order, off Smack's threads. */
    private final java.util.concurrent.ExecutorService lockedQueue = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.Set<String> toldPlain = java.util.concurrent.ConcurrentHashMap.newKeySet();

    DirectXmpp(String signature) { this.signature = signature; }

    @Override public String toString() { return signature; }
    @Override public boolean persistent() { return true; }

    @Override public void start(Context context, JSONObject account) {
        this.context = context;
        this.account = account;
        thread = new Thread(this::connect, "fluid-direct-xmpp");
        thread.start();
    }

    /** The Reconnect button: connects now, also while Reconnect automatically is off. */
    @Override public void reconnectNow() {
        synchronized (wake) { manual = true; wake.notifyAll(); }
        var current = connection;
        if (current != null && !current.isConnected()) new Thread(() -> {
            try { status("connecting", ""); current.connect().login(); status("connected", ""); }
            catch (Exception error) { status("disconnected", error.getMessage()); }
        }, "fluid-xmpp-reconnect").start();
    }

    /** Smack's own reconnecting follows the switch at once, not only when the connection is next built. */
    @Override public void autoReconnectChanged() {
        XMPPTCPConnection open = connection;
        if (open == null) return;
        if (autoReconnect()) ReconnectionManager.getInstanceFor(open).enableAutomaticReconnection();
        else ReconnectionManager.getInstanceFor(open).disableAutomaticReconnection();
    }

    private boolean autoReconnect() { return DirectProtocols.autoReconnect(DirectProtocols.prefs(context), MessageProtocol.XMPP); }

    /** The inbox's connection mark and the bar read this (DirectProtocols.status). */
    private void status(String state, String error) {
        try {
            DirectProtocols.saveStatus(context, MessageProtocol.XMPP, new JSONObject().put("state", state)
                .put("network", account.optString("jid")).put("error", error == null ? "" : error)
                .put("manual", !autoReconnect() && !state.equals("connected") && !state.equals("connecting")));
        } catch (Exception ignored) {}
    }

    /** Waits for Reconnect while Reconnect automatically is off. False when stopped. */
    private boolean waitForManual() {
        synchronized (wake) {
            while (!manual && !stopped) {
                try { wake.wait(); } catch (InterruptedException interrupted) { return false; }
            }
            manual = false;
        }
        return !stopped;
    }

    @Override public void stop() {
        stopped = true;
        synchronized (wake) { wake.notifyAll(); }
        var current = connection;
        if (current != null) new Thread(current::disconnect, "fluid-xmpp-close").start();
        if (thread != null) thread.interrupt();
    }

    private void connect() {
        long delay = 5_000;
        // Reconnect automatically off: connect only once you tap Reconnect.
        if (!autoReconnect()) {
            status("disconnected", "");
            if (!waitForManual()) return;
        }
        while (!stopped) {
            status("connecting", "");
            try {
                AndroidSmackInitializer.initialize(context);
                var jid = JidCreate.entityBareFrom(account.getString("jid"));
                var builder = XMPPTCPConnectionConfiguration.builder()
                    .setUsernameAndPassword(jid.getLocalpart().toString(), account.getString("password"))
                    .setXmppDomain(jid.asDomainBareJid())
                    .setResource("FluidMessages")
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.required);
                if (!account.optString("host").isEmpty()) builder.setHost(account.getString("host")).setPort(account.optInt("port", 5222));
                TorRoute.xmpp(builder, jid.getDomain().toString(), account.optString("host"));
                var next = new XMPPTCPConnection(builder.build());
                next.setUseStreamManagement(true);
                next.setUseStreamManagementResumption(true);
                if (autoReconnect()) ReconnectionManager.getInstanceFor(next).enableAutomaticReconnection();
                else ReconnectionManager.getInstanceFor(next).disableAutomaticReconnection();
                next.addConnectionListener(new org.jivesoftware.smack.ConnectionListener() {
                    @Override public void authenticated(org.jivesoftware.smack.XMPPConnection connected, boolean resumed) { status("connected", ""); }
                    @Override public void connectionClosedOnError(Exception error) {
                        if (!stopped) status(autoReconnect() ? "reconnecting" : "disconnected", error.getMessage());
                    }
                });
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
                status("connected", "");
                startOmemo(next, jid.toString());
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
                if (stopped) return;
                if (!autoReconnect()) {
                    status("disconnected", error.getMessage());
                    if (!waitForManual()) return;
                    continue;
                }
                status("reconnecting", error.getMessage());
                synchronized (wake) {
                    try { if (!manual) wake.wait(delay); } catch (InterruptedException interrupted) { return; }
                    manual = false;
                }
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
        accept(conversation, author, message, messageId, outgoing, null, null);
    }

    /** [unlocked] is the text of an OMEMO message (or a plain notice when it couldn't be opened). */
    private void accept(String conversation, String author, Message message, String messageId, boolean outgoing,
                        String unlocked, PhoneOmemo.Opened opened) {
        var encrypted = element(message, "encrypted", OmemoCore.NAMESPACE);
        if (unlocked == null && encrypted != null) {
            boolean group = message.getType() == Message.Type.groupchat;
            if (group) {
                accept(conversation, author, message, messageId, outgoing,
                    "🔒 Locked group messages open on FluidMessages Web; this phone doesn't lock group chats yet.", new PhoneOmemo.Opened());
                return;
            }
            String self = account.optString("jid").toLowerCase(Locale.ROOT);
            String sender = outgoing ? self : conversation;
            lockedQueue.execute(() -> {
                var current = omemo;
                PhoneOmemo.Opened result;
                if (current == null) { result = new PhoneOmemo.Opened(); result.error = "unavailable"; }
                else result = current.decrypt(sender, encrypted);
                if (result.error.isEmpty() && result.text == null) return;  // A key transport or heartbeat.
                if (!result.error.isEmpty() && outgoing) return;  // Your own copy from another device that left this phone out.
                if (result.error.isEmpty() && !PhoneOmemo.prefs(context).contains("lock." + conversation.toLowerCase(Locale.ROOT))) {
                    PhoneOmemo.rememberLock(context, conversation, "locked");  // They use OMEMO, so your next message is locked.
                }
                String text = result.error.isEmpty() ? result.text : lockedNotice(result.error);
                accept(conversation, author, message, messageId, outgoing, text, result);
            });
            return;
        }
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
                String body = unlocked != null ? unlocked : message.getBody();
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
                String url = unlocked != null ? (body.strip().startsWith("aesgcm://") && !body.strip().contains(" ") ? body.strip() : null) : oob(message);
                record = DirectProtocols.record(route, id, body, outgoing, date, author);
                if (opened != null) {
                    record.put("omemo", true);
                    if (!opened.error.isEmpty()) record.put("omemo_error", opened.error);
                    else if (!OmemoRules.trusted(opened.trust)) record.put("omemo_untrusted", true);
                }
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

    /** Downloads a shared file (HTTPS only, up to 25 MB) so it opens like other attachments. An aesgcm:// link
     *  (XEP-0454, from an OMEMO chat) is fetched over HTTPS and decrypted with the key in its fragment. */
    private void fetch(JSONObject record, String link) {
        if (link.startsWith("aesgcm://")) {
            fetchLocked(record, link);
            return;
        }
        try {
            NetworkGate.requireInternet();
            var url = new URL(link);
            var connection = (HttpsURLConnection) TorRoute.open(url);
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

    private void fetchLocked(JSONObject record, String link) {
        try {
            int hash = link.indexOf('#');
            byte[] secret = hexBytes(link.substring(hash + 1));
            if (hash < 0 || (secret.length != 44 && secret.length != 48)) return;
            var url = new URL("https" + link.substring("aesgcm".length(), hash));
            var connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            byte[] data;
            try (var input = connection.getInputStream()) { data = input.readNBytes((int) MediaSanitizer.MAX_FILE + 16); }
            if (data.length > (int) MediaSanitizer.MAX_FILE + 16) return;
            byte[] iv = java.util.Arrays.copyOf(secret, secret.length - 32);
            byte[] key = java.util.Arrays.copyOfRange(secret, secret.length - 32, secret.length);
            byte[] plain = OmemoCore.aesGcm(javax.crypto.Cipher.DECRYPT_MODE, key, iv, data);
            String id = record.getString("id");
            ProtocolStore.get(context).putFile(id, new java.io.ByteArrayInputStream(plain), MediaSanitizer.MAX_FILE);
            String name = MediaSanitizer.safeName(url.getPath().substring(url.getPath().lastIndexOf('/') + 1));
            String mime = java.net.URLConnection.guessContentTypeFromName(name);
            if (mime == null) mime = "application/octet-stream";
            record.put("attachment", new Attachment(Attachment.kindFor(mime), null, name, mime,
                ProtocolStore.get(context).attachmentFile(id).length()).toJson());
            record.put("body", name);
        } catch (Exception ignored) {}
    }

    private static byte[] hexBytes(String hex) {
        if (hex.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16), low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) return new byte[0];
            out[i] = (byte) (high * 16 + low);
        }
        return out;
    }

    private static String lockedNotice(String error) {
        return switch (error) {
            case "not_for_us" -> "🔒 This message was locked for your other devices only, so this phone can't open it.";
            case "unavailable" -> "🔒 This message is locked with OMEMO, which isn't ready on this phone yet.";
            default -> "🔒 This locked message couldn't be opened. Ask them to send it again.";
        };
    }

    // OMEMO

    private void startOmemo(XMPPTCPConnection open, String ownJid) {
        if (omemo != null) return;
        new Thread(() -> {
            try {
                var created = new PhoneOmemo(context, ownJid, new Pep(open), (jid, text) -> notice(jid, text));
                created.start();
                omemo = created;
            } catch (Exception ignored) {
                // XMPP keeps working without OMEMO; chats show the yellow lock and say so.
            }
        }, "fluid-xmpp-omemo").start();
    }

    /** A line in the chat about its encryption (a key change, a new device, or a message without OMEMO). */
    private void notice(String jid, String text) {
        try {
            var route = DirectProtocols.routeForTarget(context, MessageProtocol.XMPP, jid.toLowerCase(Locale.ROOT));
            if (route == null) return;
            long now = System.currentTimeMillis();
            DirectProtocols.incoming(context, DirectProtocols.record(route, "xmpp-" + hash("omemo:" + jid + ":" + text + ":" + now / 60_000),
                text, false, now, "").put("event", true).put("read", 1));
        } catch (Exception ignored) {}
    }

    /** This phone's OMEMO device, for the chat's Security page; null while it isn't ready. */
    PhoneOmemo omemo() { return omemo; }

    /** The XMPP work OMEMO needs: your PEP nodes, other people's, and key transport messages. */
    private static final class Pep implements PhoneOmemo.Network {
        private final XMPPTCPConnection connection;
        Pep(XMPPTCPConnection connection) { this.connection = connection; }

        @Override public void publish(String node, String payloadXml) throws Exception {
            // Readable by anyone (OMEMO needs that); a server without publish options takes a plain publish.
            String xml = "<iq type='set' id='" + org.jivesoftware.smack.util.StringUtils.randomString(12) + "'>"
                + "<pubsub xmlns='http://jabber.org/protocol/pubsub'><publish node='" + node + "'><item id='current'>" + payloadXml
                + "</item></publish><publish-options><x xmlns='jabber:x:data' type='submit'>"
                + "<field var='FORM_TYPE' type='hidden'><value>http://jabber.org/protocol/pubsub#publish-options</value></field>"
                + "<field var='pubsub#access_model'><value>open</value></field></x></publish-options></pubsub></iq>";
            try {
                connection.sendIqRequestAndWaitForResponse((org.jivesoftware.smack.packet.IQ) PacketParserUtils.parseStanza(xml));
            } catch (Exception error) {
                org.jivesoftware.smackx.pep.PepManager.getInstanceFor(connection).publish(node,
                    new org.jivesoftware.smackx.pubsub.PayloadItem<>("current", new org.jivesoftware.smackx.pubsub.SimplePayload(payloadXml)));
            }
        }

        @Override public StandardExtensionElement fetch(String bareJid, String node) throws Exception {
            var request = new org.jivesoftware.smackx.pubsub.packet.PubSub(JidCreate.bareFrom(bareJid),
                org.jivesoftware.smack.packet.IQ.Type.get, org.jivesoftware.smackx.pubsub.packet.PubSubNamespace.basic);
            request.addExtension(new org.jivesoftware.smackx.pubsub.ItemsExtension(
                org.jivesoftware.smackx.pubsub.ItemsExtension.ItemsElementType.items, node, java.util.Collections.emptyList()));
            org.jivesoftware.smack.packet.IQ response;
            try {
                response = connection.sendIqRequestAndWaitForResponse(request);
            } catch (org.jivesoftware.smack.XMPPException.XMPPErrorException error) {
                var condition = error.getStanzaError().getCondition();
                if (condition == org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found) return null;
                throw error;
            }
            String xml = response.toXML().toString();
            int start = xml.indexOf("<pubsub"), end = xml.lastIndexOf("</pubsub>");
            if (start < 0 || end < 0) return null;
            var parser = PacketParserUtils.getParserFor(xml.substring(start, end + "</pubsub>".length()));
            var pubsub = org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE.parse(parser);
            var items = pubsub.getFirstElement("items");
            if (items == null) return null;
            for (var item : items.getElements("item")) {
                if (!item.getElements().isEmpty()) return item.getElements().get(0);
            }
            return null;
        }

        @Override public void send(String bareJid, StandardExtensionElement encrypted) throws Exception {
            var builder = StanzaBuilder.buildMessage().to(JidCreate.bareFrom(bareJid)).ofType(Message.Type.chat);
            builder.addExtension(encrypted);
            builder.addExtension(StandardExtensionElement.builder("store", "urn:xmpp:hints").build());
            connection.sendStanza(builder.build());
        }
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

    /** /join, /part, /nick, /topic, /invite, and /msg from the phone's own XMPP connection. */
    @Override public JSONObject command(Context context, JSONObject route, ChatCommand command) throws Exception {
        var current = connection;
        if (current == null || !current.isAuthenticated()) throw new IllegalStateException("XMPP isn’t connected yet");
        var argv = command.getArgv();
        String args = command.getArgs();
        var result = new JSONObject().put("notice", "");
        java.util.function.Supplier<MultiUserChat> here = () -> {
            try { return MultiUserChatManager.getInstanceFor(current).getMultiUserChat(JidCreate.entityBareFrom(route.getString("target"))); }
            catch (Exception error) { throw new IllegalStateException("Use this in a group chat"); }
        };
        switch (command.getName()) {
            case "join", "msg", "query" -> {
                boolean group = command.getName().equals("join");
                String target = argv.get(0).toLowerCase(Locale.ROOT), text = group ? "" : args.substring(argv.get(0).length()).trim();
                var existing = DirectProtocols.routeForTarget(context, MessageProtocol.XMPP, target);
                if (existing == null) {
                    DirectProtocols.link(context, "xmpp-" + hash(target), MessageProtocol.XMPP, target, group ? target.split("@", 2)[0] : target, group);
                    existing = DirectProtocols.routeForTarget(context, MessageProtocol.XMPP, target);
                }
                if (!text.isEmpty()) DirectProtocols.send(context, existing.getString("address"), MessageProtocol.XMPP, text, text, null, null);
                result.put("open", existing.getString("address"));
            }
            case "part" -> {
                if (!route.optBoolean("group")) throw new IllegalArgumentException("Use /part in a group chat");
                try { here.get().leave(); } catch (Exception ignored) {}
                DirectProtocols.unlink(context, route.getString("address"), MessageProtocol.XMPP);
                result.put("notice", "Left " + route.optString("title"));
            }
            case "nick" -> {
                if (!route.optBoolean("group")) throw new IllegalArgumentException("Use /nick in a group chat");
                here.get().changeNickname(Resourcepart.from(argv.get(0)));
                result.put("notice", "Your nickname here is now " + argv.get(0));
            }
            case "topic" -> {
                if (!route.optBoolean("group")) throw new IllegalArgumentException("Use /topic in a group chat");
                if (args.isEmpty()) {
                    String subject = here.get().getSubject();
                    result.put("notice", subject == null || subject.isEmpty() ? "This group chat has no topic" : "Topic: " + subject);
                } else here.get().changeSubject(args);
            }
            case "invite" -> { changeMember(context, route, true, argv.get(0)); result.put("notice", "Invited " + argv.get(0)); }
            default -> throw new IllegalArgumentException("/" + command.getName() + " isn’t available on XMPP.");
        }
        return result;
    }

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
            String target = route.getString("target").toLowerCase(Locale.ROOT);
            var current_omemo = omemo;
            boolean locked = false;
            if (!group && current_omemo != null) {
                try { locked = current_omemo.canLock(java.util.Set.of(target)); } catch (Exception ignored) {}
            }
            if (locked) return sendLocked(current, route, message, relation, reply, body, file, current_omemo);
            if (!group && !toldPlain.contains(target)) {
                toldPlain.add(target);
                notice(target, current_omemo == null
                    ? "Not locked with OMEMO: it isn't ready on this phone yet. Your XMPP server can read this message."
                    : "Not locked with OMEMO: their app doesn't use it, so your XMPP server can read this message.");
            }
            if (!group) PhoneOmemo.rememberLock(context, target, "plain");
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

    /** One-to-one, locked with OMEMO: text and files (XEP-0454: the file is encrypted before upload). */
    private JSONObject sendLocked(XMPPTCPConnection current, JSONObject route, JSONObject message, JSONObject relation,
                                  String reply, String body, MediaSanitizer.Prepared file, PhoneOmemo locked) throws Exception {
        String target = route.getString("target").toLowerCase(Locale.ROOT);
        String sent = null;
        String caption = file != null ? message.optString("text") : body;
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (caption != null && !caption.isEmpty()) parts.add(caption);
        if (file != null) {
            byte[] data = java.nio.file.Files.readAllBytes(file.file().toPath());
            byte[] key = OmemoCore.random(32), iv = OmemoCore.random(12);
            byte[] sealed = OmemoCore.aesGcm(javax.crypto.Cipher.ENCRYPT_MODE, key, iv, data);
            URL uploaded = HttpFileUploadManager.getInstanceFor(current).uploadFile(new java.io.ByteArrayInputStream(sealed),
                file.file().getName(), sealed.length);
            String link = uploaded.toString();
            if (!link.startsWith("https://")) throw new IllegalStateException("The upload service gave a link FluidMessages can't lock");
            parts.add("aesgcm" + link.substring("https".length()) + "#" + OmemoCore.hex(OmemoCore.concat(iv, key)));
        }
        int failed = 0;
        for (int index = 0; index < parts.size(); index++) {
            PhoneOmemo.Sealed result;
            try {
                result = locked.encrypt(java.util.Set.of(target), parts.get(index).getBytes(StandardCharsets.UTF_8));
            } catch (PhoneOmemo.NotTrusted error) {
                throw new IllegalStateException(error.getMessage());
            }
            failed += result.failed;
            MessageBuilder builder = StanzaBuilder.buildMessage().to(route.getString("target")).ofType(Message.Type.chat)
                .setBody(PhoneOmemo.FALLBACK);
            builder.addExtension(result.element);
            builder.addExtension(StandardExtensionElement.builder("encryption", "urn:xmpp:eme:0")
                .addAttribute("namespace", OmemoCore.NAMESPACE).addAttribute("name", "OMEMO").build());
            builder.addExtension(StandardExtensionElement.builder("store", "urn:xmpp:hints").build());
            if (index == 0 && !reply.isEmpty() && relation != null && "reply".equals(relation.optString("kind"))) {
                builder.addExtension(StandardExtensionElement.builder("reply", REPLY).addAttribute("to", route.getString("target"))
                    .addAttribute("id", reply).build());
            }
            Message stanza = builder.build();
            current.sendStanza(stanza);
            sent = stanza.getStanzaId();
        }
        PhoneOmemo.rememberLock(context, target, failed > 0 ? "failed" : "locked");
        return new JSONObject().put("xmpp_id", sent).put("omemo", true);
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
