package org.fluidmessages;

import android.content.Context;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.search.FromStringTerm;
import javax.mail.search.SearchTerm;
import org.json.JSONObject;

/**
 * Email from the phone over IMAP and SMTP, limited to correspondents you link, the same as on the companion.
 * Mail is checked on the account's interval while FluidMessages is open and on the background schedule
 * otherwise, without keeping an IMAP connection open. Sent mail threads with In-Reply-To, carries one
 * attachment, ends with "Sent with FluidMessages", and reactions follow RFC 9078.
 */
final class DirectEmail implements DirectProtocols.DirectClient {
    private static final String FOOTER = "\n\nSent with FluidMessages";
    private final String signature;
    private volatile boolean stopped;
    private Thread thread;
    private Context context;
    private JSONObject account;

    DirectEmail(String signature) { this.signature = signature; }

    @Override public String toString() { return signature; }
    @Override public boolean persistent() { return false; }

    @Override public void load(Context context, JSONObject account) {
        this.context = context;
        this.account = account;
    }

    @Override public void start(Context context, JSONObject account) {
        load(context, account);
        thread = new Thread(() -> {
            while (!stopped) {
                try { poll(context); } catch (Exception ignored) {}
                try { Thread.sleep(Math.max(1, account.optInt("poll_minutes", 5)) * 60_000L); }
                catch (InterruptedException interrupted) { return; }
            }
        }, "fluid-direct-email");
        thread.start();
    }

    @Override public void stop() {
        stopped = true;
        if (thread != null) thread.interrupt();
    }

    private Session session() throws java.io.IOException {
        var properties = new Properties();
        properties.put("mail.imaps.ssl.checkserveridentity", "true");
        properties.put("mail.imaps.connectiontimeout", "15000");
        properties.put("mail.imaps.timeout", "30000");
        // IMAP on 143 must upgrade with STARTTLS; any other port uses TLS from the start (EmailPorts).
        properties.put("mail.imap.starttls.enable", "true");
        properties.put("mail.imap.starttls.required", "true");
        properties.put("mail.imap.ssl.checkserveridentity", "true");
        properties.put("mail.imap.connectiontimeout", "15000");
        properties.put("mail.imap.timeout", "30000");
        properties.put("mail.smtp.connectiontimeout", "15000");
        properties.put("mail.smtp.timeout", "30000");
        if (!EmailPorts.smtpStartTls(smtpPort())) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        } else {
            // Other ports, like 587, must upgrade with STARTTLS; FluidMessages never sends a password over plain text.
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
            properties.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        properties.put("mail.smtp.auth", "true");
        TorRoute.mail(properties, account.optString("imap_host"), account.optString("smtp_host"));
        return Session.getInstance(properties);
    }

    /** Saved ports, or the defaults for accounts saved before ports could be changed. */
    private int imapPort() { return EmailPorts.orDefault(account.optInt(EmailPorts.IMAP_KEY, EmailPorts.IMAP), EmailPorts.IMAP); }

    private int smtpPort() { return EmailPorts.orDefault(account.optInt(EmailPorts.SMTP_KEY, EmailPorts.SMTP), EmailPorts.SMTP); }

    @Override public synchronized void poll(Context context) throws Exception {
        var routes = DirectProtocols.routes(DirectProtocols.prefs(context));
        int imapPort = imapPort();
        var store = session().getStore(EmailPorts.imapStartTls(imapPort) ? "imap" : "imaps");
        store.connect(account.getString("imap_host"), imapPort,
            account.optString("imap_user", account.getString("address")), account.getString("imap_password"));
        try {
            Folder inbox = store.getFolder(account.optString("folder", "INBOX"));
            inbox.open(Folder.READ_ONLY);
            try {
                var uids = (UIDFolder) inbox;
                long validity = uids.getUIDValidity();
                var prefs = DirectProtocols.prefs(context);
                for (int i = 0; i < routes.length(); i++) {
                    var route = routes.optJSONObject(i);
                    if (route == null || MessageProtocol.parse(route.optString("protocol")) != MessageProtocol.EMAIL) continue;
                    String key = "direct.mail." + validity + "." + route.getString("target");
                    long highest = prefs.getLong(key, 0);
                    SearchTerm from = new FromStringTerm(route.getString("target"));
                    Message[] found = inbox.search(from);
                    var fresh = new java.util.ArrayList<Message>();
                    for (Message message : found) if (uids.getUID(message) > highest) fresh.add(message);
                    fresh.sort(java.util.Comparator.comparingLong(message -> {
                        try { return uids.getUID(message); } catch (MessagingException error) { return 0L; }
                    }));
                    var batch = highest == 0 ? fresh.subList(Math.max(0, fresh.size() - 25), fresh.size()) : fresh.subList(0, Math.min(25, fresh.size()));
                    for (Message message : batch) {
                        long uid = uids.getUID(message);
                        if (message.getSize() <= 35 * 1024 * 1024) accept(route, validity, uid, (MimeMessage) message);
                        prefs.edit().putLong(key, uid).apply();
                    }
                }
            } finally { inbox.close(false); }
        } finally { store.close(); }
    }

    private void accept(JSONObject route, long validity, long uid, MimeMessage message) throws Exception {
        var from = message.getFrom();
        String sender = from != null && from.length > 0 && from[0] instanceof InternetAddress address ? address.getAddress().toLowerCase(Locale.ROOT) : "";
        if (!sender.equals(route.getString("target")) || message.getHeader("List-Id") != null
                || !"no".equalsIgnoreCase(header(message, "Auto-Submitted", "no"))) return;
        String address = route.getString("address");
        String id = "mail-" + hash(route.getString("target") + ":" + validity + ":" + uid);
        long date = Math.min(System.currentTimeMillis(), message.getSentDate() == null ? System.currentTimeMillis() : message.getSentDate().getTime());
        String text = "";
        String reaction = null;
        BodyPart attached = null;
        Object content = message.getContent();
        if (content instanceof String plain) text = plain;
        else if (content instanceof Multipart parts) {
            for (int i = 0; i < parts.getCount(); i++) {
                var part = parts.getBodyPart(i);
                if ("reaction".equalsIgnoreCase(part.getDisposition())) reaction = String.valueOf(part.getContent()).strip();
                else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) && attached == null) attached = part;
                else if (part.isMimeType("text/plain") && text.isEmpty()) text = String.valueOf(part.getContent());
                else if (part.isMimeType("multipart/alternative") && text.isEmpty()) {
                    var alternative = (Multipart) part.getContent();
                    for (int j = 0; j < alternative.getCount(); j++) {
                        if (alternative.getBodyPart(j).isMimeType("text/plain")) text = String.valueOf(alternative.getBodyPart(j).getContent());
                    }
                }
            }
        }
        text = text.replace("\r\n", "\n");
        if (text.endsWith(FOOTER + "\n")) text = text.substring(0, text.length() - FOOTER.length() - 1);
        text = MessageInteractionsKt.stripQuotedEmail(text);
        JSONObject record;
        String replyTo = header(message, "In-Reply-To", "").strip();
        // The quoted history is gone; a reply card stands in when they answer an older email than the latest.
        var answered = reaction != null || replyTo.isEmpty() ? null : DirectProtocols.findBy(context, address, "mail_id", replyTo);
        if (answered != null && !text.isEmpty() && !answered.getString("id").equals(latestId(address))) {
            text = "Reply to “" + MessageInteractionsKt.messageExcerpt(answered.optString("body"), 240) + "”\n\n" + text;
        } else answered = null;
        var target = reaction == null ? null : DirectProtocols.findBy(context, address, "mail_id", replyTo);
        if (target != null) {
            record = DirectProtocols.record(route, id, MessageInteractionsKt.emojiReactionSms(reaction, target.optString("body"), false),
                false, date, route.optString("title")).put("reaction_target", target.getString("id"));
        } else {
            if (text.isEmpty() && attached == null) return;
            String name = attached == null ? "" : MediaSanitizer.safeName(attached.getFileName() == null ? "attachment" : attached.getFileName());
            record = DirectProtocols.record(route, id, text.isEmpty() ? name : text, false, date, route.optString("title"));
            if (attached != null) {
                String mime = attached.getContentType().split(";")[0].strip().toLowerCase(Locale.ROOT);
                try (var input = attached.getInputStream()) { ProtocolStore.get(context).putFile(id, input, MediaSanitizer.MAX_FILE); }
                long size = ProtocolStore.get(context).attachmentFile(id).length();
                record.put("attachment", new Attachment(Attachment.kindFor(mime), null, name, mime, size).toJson());
            }
        }
        if (answered != null) record.put("reply_target", answered.getString("id"));
        record.put("mail_id", message.getMessageID() == null ? "" : message.getMessageID())
            .put("mail_subject", message.getSubject() == null ? "" : message.getSubject());
        DirectProtocols.incoming(context, record);
    }

    /** The newest message in a conversation, on any protocol. */
    private String latestId(String address) throws Exception {
        for (JSONObject item : ProtocolStore.get(context).read()) {
            if (address.equals(item.optString("address"))) return item.optString("id");
        }
        return "";
    }

    /** Once: removes quoted history from emails received before FluidMessages stripped it. */
    static void stripStoredQuotes(Context context) {
        var prefs = DirectProtocols.prefs(context);
        if (prefs.getBoolean("emailQuotesStripped", false)) return;
        try {
            var store = ProtocolStore.get(context);
            for (JSONObject item : store.read()) {
                if (!"email".equals(item.optString("protocol")) || item.optInt("type") != 1 || item.has("reaction_target")) continue;
                String body = item.optString("body");
                String cleaned = MessageInteractionsKt.stripQuotedEmail(body);
                if (!cleaned.isEmpty() && !cleaned.equals(body)) store.put(item.put("body", cleaned));
            }
            prefs.edit().putBoolean("emailQuotesStripped", true).apply();
        } catch (Exception ignored) {}
    }

    private static String header(Message message, String name, String fallback) throws MessagingException {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? fallback : values[0];
    }

    @Override public JSONObject send(Context context, JSONObject route, JSONObject message, MediaSanitizer.Prepared file) throws Exception {
        var session = session();
        var mail = new MimeMessage(session);
        mail.setFrom(new InternetAddress(account.getString("address")));
        mail.setRecipient(Message.RecipientType.TO, new InternetAddress(route.getString("target")));
        mail.setSentDate(new Date());
        var relation = message.optJSONObject("relation");
        String inReplyTo = relation == null ? "" : relation.optString("mail_id");
        String subject = relation == null ? "" : relation.optString("mail_subject");
        if (inReplyTo.isEmpty()) {
            // Keep the conversation in one thread: answer the latest email with this person.
            for (JSONObject item : ProtocolStore.get(context).read()) {
                if (route.getString("address").equals(item.optString("address")) && !item.optString("mail_id").isEmpty()) {
                    inReplyTo = item.getString("mail_id");
                    subject = item.optString("mail_subject");
                    break;
                }
            }
        }
        if (!inReplyTo.isEmpty()) {
            mail.setHeader("In-Reply-To", inReplyTo);
            mail.setHeader("References", inReplyTo);
        }
        mail.setSubject(subject.isEmpty() ? "FluidMessages conversation" : subject.toLowerCase(Locale.ROOT).startsWith("re:") ? subject : "Re: " + subject, "UTF-8");
        boolean reaction = relation != null && "reaction".equals(relation.optString("kind")) && !relation.optString("mail_id").isEmpty();
        String text = file != null || relation != null && "reply".equals(relation.optString("kind")) && !relation.optString("mail_id").isEmpty()
            ? message.optString("text") : message.getString("body");
        if (reaction) {
            // RFC 9078: the only text part is the emoji, marked as a reaction; no footer.
            mail.setText(relation.optString("emoji"), "UTF-8");
            mail.setDisposition("reaction");
        } else if (file == null) {
            mail.setText(text + FOOTER, "UTF-8");
        } else {
            var body = new MimeBodyPart();
            body.setText(text + FOOTER, "UTF-8");
            var attachment = new MimeBodyPart();
            attachment.setDataHandler(new DataHandler(new FileDataSource(file.file()) {
                @Override public String getContentType() { return file.mime(); }
            }));
            attachment.setFileName(MimeUtility.encodeText(file.name(), "UTF-8", null));
            attachment.setDisposition(Part.ATTACHMENT);
            var parts = new MimeMultipart();
            parts.addBodyPart(body);
            parts.addBodyPart(attachment);
            mail.setContent(parts);
        }
        mail.saveChanges();
        String messageId = mail.getMessageID();
        var transport = session.getTransport("smtp");
        try {
            transport.connect(account.getString("smtp_host"), smtpPort(),
                account.optString("smtp_user", account.optString("imap_user", account.getString("address"))),
                account.optString("smtp_password", account.getString("imap_password")));
            transport.sendMessage(mail, mail.getAllRecipients());
        } finally { transport.close(); }
        return new JSONObject().put("mail_id", messageId == null ? "" : messageId).put("mail_subject", mail.getSubject());
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return hex.toString();
        } catch (Exception error) { return Integer.toHexString(value.hashCode()); }
    }
}
