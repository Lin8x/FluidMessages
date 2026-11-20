package org.fluidmessages;

import android.Manifest;
import android.app.*;
import android.app.role.RoleManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Telephony;
import android.provider.DocumentsContract;
import android.provider.ContactsContract;
import android.telephony.SubscriptionManager;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.*;

public final class MainActivity extends androidx.activity.ComponentActivity {
    private static final int ROLE = 10, EXPORT = 11, IMPORT = 12, SCHEDULE_BACKUP = 15,
        CONTACT_PERMISSION = 16, CONTACT_EDITOR = 17;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private InboxScreen inboxScreen;
    private ConversationScreen conversationScreen;
    private String address;
    private String filter = "";
    private String folder = "Inbox";
    private String page = "inbox";
    private int setupStep;
    private String sampleSearch = "";
    private String sampleFolder = "Inbox";
    private final Map<String, List<ChatLine>> sampleReplies = new HashMap<>();
    private String contactToEdit;
    private final Map<String, Set<String>> hiddenSampleMessages = new HashMap<>();
    private static final String[][] SAMPLES = {
        {"Maya Chen", "MC", "Coffee at ten?", "See you there!", "9:41 AM", "SMS"},
        {"Jordan Ellis", "JE", "I sent the route for Saturday.", "That looks like a great walk.", "Yesterday", "RCS"},
        {"Avery Brooks", "AB", "Are we still meeting Sunday?", "Yes, let’s meet at the park.", "Tuesday", "SMS"},
        {"Sam Rivera", "SR", "I saved you a seat!", "On my way.", "Monday", "RCS"},
        {"Weekend Plans", "WP", "Picnic by the lake on Saturday?", "I’ll bring dessert.", "Today", "Group"},
        {"Trail Alerts", "TA", "Trail update: the south entrance is open.", "", "Today", "SMS"}
    };
    private static final List<String> WEEKEND_MEMBERS = List.of("Maya Chen", "Jordan Ellis", "Avery Brooks");

    private int samplePhoto(String name) {
        return switch (name) {
            case "Maya Chen" -> R.drawable.sample_maya;
            case "Jordan Ellis" -> R.drawable.sample_jordan;
            default -> 0;
        };
    }

    private List<String> sampleMembers(String[] contact) {
        return "Group".equals(contact[5]) ? WEEKEND_MEMBERS : List.of(contact[0]);
    }

    private SenderStyle sampleSender(String name) {
        return switch (name) {
            case "Trail Alerts" -> SenderStyle.AUTOMATED;
            default -> SenderStyle.SMS;
        };
    }
    private String password = "";
    private Backup.Options backupOptions;
    private int backupMinutes;
    private Runnable backupBack;
    private BackupJob.FolderSchedule pendingFolder;
    private CloudBackup.Target pendingFileTarget;
    private boolean backupWifi;
    private boolean sharingBackup;
    private boolean syncing;
    private boolean unlocked;
    private CancellationSignal lockPrompt;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            sync(false);
            handler.postDelayed(this, 20_000);
        }
    };
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            if (page.equals("inbox")) fillInbox();
            if (page.equals("chat")) fillChat();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("fluid", MODE_PRIVATE);
        if (state != null && state.containsKey("setupStep")) onboarding(state.getInt("setupStep"));
        else if (prefs.getBoolean("onboarded", false)) open(getIntent());
        else onboarding(0);
        getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (prefs.getBoolean("onboarded", false)) open(intent);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (page.equals("setup")) state.putInt("setupStep", setupStep);
    }
    @Override protected void onStart() {
        super.onStart();
        if (prefs.getBoolean("lock", false) && !unlocked) authenticate();
        handler.post(poll);
    }
    @Override protected void onStop() {
        handler.removeCallbacks(poll);
        unlocked = false;
        if (lockPrompt != null) {
            lockPrompt.cancel();
            lockPrompt = null;
        }
        super.onStop();
    }
    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        switch (page) {
            case "inbox" -> super.onBackPressed();
            case "setup" -> { if (setupStep > 0) onboarding(setupStep - 1); else finish(); }
            case "sampleChat" -> sampleInbox();
            case "contact", "chatAppearance" -> contactBack.run();
            case "sample" -> inbox();
            case "theme", "backups", "messaging" -> settings();
            case "cloud" -> backups();
            case "backupForm", "backupReview", "companion" -> backupBack.run();
            default -> inbox();
        }
    }

    private void open(Intent intent) {
        String number = intent.getStringExtra("address");
        if (intent.getData() != null && Intent.ACTION_SENDTO.equals(intent.getAction())) {
            number = intent.getData().getSchemeSpecificPart().split("\\?", 2)[0];
        }
        if (number != null && !number.isBlank() && hasRole()) {
            chat(number);
            String body = intent.getStringExtra("sms_body");
            if (body != null) conversationScreen.setDraft(body);
        } else inbox();
    }

    private boolean hasRole() { return getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_SMS); }
    private boolean dark() { return prefs.getBoolean("dark", false); }
    private int accent() { return MessageStyle.controls(prefs); }
    private int canvas() { return MessageStyle.canvas(prefs); }
    private int ink() { return dark() ? 0xfff4f5f7 : 0xff171923; }
    private int subtle() { return dark() ? 0xffaeb2bc : 0xff616674; }
    private int incoming() { return MessageStyle.incoming(prefs); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private TextView title(String value, int size) {
        TextView label = text(value, size, ink());
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextColor(primary ? MessageStyle.filledControlInk(accent()) : ink());
        view.setMinHeight(dp(44));
        view.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x227a78a8), shape(primary ? MessageStyle.filledControl(accent()) : incoming(), 22), null));
        view.setPadding(dp(12), dp(6), dp(12), dp(6));
        view.setOnClickListener(ignored -> action.run());
        return view;
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private void render(String screen) {
        page = screen;
        inboxScreen = null;
        conversationScreen = null;
        boolean fullWidth = switch (screen) {
            case "inbox", "chat", "sample", "sampleChat" -> true;
            default -> false;
        };
        int side = dp(fullWidth ? 0 : 18);
        int top = dp(fullWidth ? 0 : 10);
        int bottom = dp(fullWidth ? 0 : 12);
        root = column();
        root.setPadding(side, top, side, bottom);
        root.setBackgroundColor(canvas());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            view.setPadding(side + safe.left, top + safe.top, side + safe.right, bottom + safe.bottom);
            return insets;
        });
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setNavigationBarContrastEnforced(false);
        setContentView(root);
        recolorWindow(canvas());
        if (prefs.getBoolean("lock", false) && !unlocked) root.setVisibility(View.INVISIBLE);
    }

    private void recolorWindow(int background) {
        root.setBackgroundColor(background);
        getWindow().setNavigationBarColor(background);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(Color.luminance(background) > .27f ? bars : 0, bars);
        }
    }
    private void heading(String label, String action, Runnable run) {
        LinearLayout bar = row();
        bar.addView(title(label, 29), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button(action, false, run), new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(bar);
    }
    private EditText field(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setTextColor(ink());
        view.setHintTextColor(subtle());
        view.setTextSize(16);
        view.setPadding(dp(13), dp(9), dp(13), dp(9));
        view.setBackground(shape(incoming(), 22));
        return view;
    }
    private void item(LinearLayout parent, View child) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(8);
        parent.addView(child, layout);
    }

    private void onboarding(int step) {
        setupStep = step;
        render("setup");
        OnboardingScreen.mount(root, prefs, step, () -> onboarding(step + 1), () -> onboarding(step - 1),
            () -> finishSetup(false), () -> finishSetup(true), () -> finishSetup(false), this::recolorWindow);
    }

    private void finishSetup(boolean demo) {
        boolean replay = prefs.getBoolean("onboarded", false);
        prefs.edit().putBoolean("onboarded", true).apply();
        if (demo) sampleInbox();
        else if (replay) inbox();
        else open(getIntent());
    }

    private void chooseFolder(String folder) {
        this.folder = folder;
        inbox();
    }

    private String initials(String value) {
        return value.codePoints().filter(Character::isLetter).limit(2)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString().toUpperCase(Locale.ROOT);
    }

    private void sampleInbox() {
        render("sample");
        inboxScreen = new InboxScreen(root, prefs, true, false, sampleFolder, sampleSearch,
            query -> { sampleSearch = query; fillSampleInbox(); }, this::settings,
            selected -> { sampleFolder = selected; sampleInbox(); }, this::inbox, this::requestSmsRole, this::openSampleThread, ignored -> {});
        fillSampleInbox();
    }

    private void fillSampleInbox() {
        String search = sampleSearch.trim().toLowerCase(Locale.ROOT);
        List<ThreadRow> threads = new ArrayList<>();
        for (int index = 0; index < SAMPLES.length; index++) {
            String[] contact = SAMPLES[index];
            String folder = index == 2 ? "Archived" : index == 3 ? "Blocked" : "Inbox";
            if (!sampleFolder.equals(folder)) continue;
            List<ChatLine> replies = sampleReplies.getOrDefault(contact[0], List.of());
            ChatLine latest = null;
            Set<String> hidden = hiddenSampleMessages.getOrDefault("demo:" + contact[0], Set.of());
            for (int reply = replies.size() - 1; reply >= 0; reply--) {
                ChatLine candidate = replies.get(reply);
                if (candidate.getReactionTarget() == null && !hidden.contains(candidate.getId())) {
                    latest = candidate;
                    break;
                }
            }
            String preview = latest == null ? (contact[3].isEmpty() ? contact[2] : contact[3]) : latest.getText();
            if ("Group".equals(contact[5]) && latest != null && latest.getAuthor() != null) {
                preview = latest.getAuthor().split(" ", 2)[0] + ": " + preview;
            }
            if (!(contact[0] + " " + preview).toLowerCase(Locale.ROOT).contains(search)) continue;
            threads.add(new ThreadRow(contact[0], contact[0], contact[1],
                preview, latest == null ? withTime(contact[4], sampleTime(contact)) : latest.getTime(),
                (contact == SAMPLES[0] || "Group".equals(contact[5])) && latest == null, false, samplePhoto(contact[0])));
        }
        inboxScreen.setThreads(threads);
    }

    private void openSampleThread(String name) {
        for (String[] contact : SAMPLES) if (contact[0].equals(name)) {
            sampleChat(contact);
            return;
        }
    }

    private void sampleChat(String[] contact) {
        render("sampleChat");
        List<ChatLine> replies = sampleReplies.computeIfAbsent(contact[0], ignored -> new ArrayList<>());
        String key = "demo:" + contact[0];
        ChatProfile profile = ChatProfile.read(prefs, key);
        conversationScreen = new ConversationScreen(root, prefs, profile, contact[0], contact[1], contact[5], true,
            SenderStyle.forAddress(prefs, key, sampleSender(contact[0])),
            null, this::sampleInbox, () -> contactDetails(key, contact[0], () -> sampleChat(contact)), null, null,
            this::forwardSample, line -> confirmDelete(() -> {
                hiddenSampleMessages.computeIfAbsent(key, ignored -> new HashSet<>()).add(line.getId());
                showSampleLines(contact, replies);
            }), null, sampleMembers(contact),
            (body, author, target) -> addSampleMessage(contact, replies, body, author, target),
            samplePhoto(contact[0]));
        showSampleLines(contact, replies);
        animateIn(root.getChildAt(0), 10);
    }

    private boolean addSampleMessage(String[] contact, List<ChatLine> replies, String body, String author, String target) {
        if (body.isBlank()) return false;
        String id = "reply-" + replies.size();
        replies.add(new ChatLine(id, body, "", author == null, "",
            DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()), author, target));
        conversationScreen.setAnimatedId(id);
        showSampleLines(contact, replies);
        if (author == null) feedback(root);
        else MessageFeedback.play(this, prefs, true);
        return true;
    }

    private void forwardSample(String message) {
        String[] names = Arrays.stream(SAMPLES).map(contact -> contact[0]).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Forward to a sample conversation")
            .setItems(names, (dialog, which) -> {
                sampleChat(SAMPLES[which]);
                conversationScreen.setDraft(message);
            }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(Runnable delete) {
        new AlertDialog.Builder(this).setTitle("Delete this message?")
            .setMessage("This removes the message from this device.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete for me", (dialog, which) -> delete.run()).show();
    }

    private String sampleTime(String[] contact) {
        return contact == SAMPLES[0] ? "9:41 AM" : contact == SAMPLES[1] ? "2:00 PM" : "6:26 PM";
    }

    private String withTime(String date, String clock) {
        return prefs.getBoolean("exactTimes", false) && !date.equals(clock) ? date + " " + clock : date;
    }

    private void showSampleLines(String[] contact, List<ChatLine> replies) {
        List<ChatLine> messages = new ArrayList<>();
        var members = sampleMembers(contact);
        messages.add(new ChatLine("incoming", contact[2], contact[4], false, "", sampleTime(contact), members.get(0)));
        if (members.size() > 1) {
            messages.add(new ChatLine("group-answer", "I’ll pack a blanket.", "", false, "", "6:28 PM", members.get(1)));
            messages.add(new ChatLine("group-time", "Let’s meet at noon.", "", false, "", "6:31 PM", members.get(2)));
        }
        if (!contact[3].isEmpty()) {
            messages.add(new ChatLine("sent", contact[3], "", true, "", members.size() > 1 ? "6:34 PM" : "2:01 PM"));
        }
        messages.addAll(replies);
        Set<String> hidden = hiddenSampleMessages.getOrDefault("demo:" + contact[0], Set.of());
        messages.removeIf(line -> hidden.contains(line.getId()));
        conversationScreen.setLines(messages);
    }

    private void animateIn(View view, int offset) {
        int speed = bounded("speed", 220, 0, 800);
        if (speed == 0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        view.setTranslationY(dp(offset));
        view.setAlpha(.35f);
        view.animate().translationY(0).alpha(1).setDuration(speed)
            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void requestSmsRole() {
        startActivityForResult(getSystemService(RoleManager.class).createRequestRoleIntent(RoleManager.ROLE_SMS), ROLE);
    }

    private void inbox() {
        render("inbox");
        boolean connected = hasRole();
        inboxScreen = new InboxScreen(root, prefs, false, connected, folder, filter,
            query -> { filter = query; fillInbox(); }, this::settings, this::chooseFolder,
            connected ? this::compose : this::sampleInbox, this::requestSmsRole, this::chat, this::conversationMenu);
        if (connected) fillInbox();
    }

    private Set<String> list(String key) { return addressList(prefs, key); }

    static Set<String> addressList(SharedPreferences prefs, String key) {
        Set<String> values = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        } catch (Exception ignored) {}
        return values;
    }
    private void toggle(String key, String number) {
        toggleAddress(key, number);
        inbox();
    }

    private void toggleAddress(String key, String number) {
        Set<String> values = list(key);
        if (!values.remove(number)) values.add(number);
        prefs.edit().putString(key, new JSONArray(values).toString()).apply();
    }
    private void conversationMenu(String number) {
        String[] keys = {"pinned", "archived", "blocked"};
        String[] added = {"Pin", "Archive", "Hide as blocked"};
        String[] removed = {"Unpin", "Unarchive", "Show again"};
        String[] options = new String[3];
        for (int i = 0; i < 3; i++) options[i] = list(keys[i]).contains(number) ? removed[i] : added[i];
        new AlertDialog.Builder(this).setTitle(number).setItems(options, (dialog, index) -> toggle(keys[index], number)).show();
    }
    private void fillInbox() {
        if (inboxScreen == null || !hasRole()) return;
        try {
            JSONArray data = SmsStore.read(this, 0);
            Set<String> pinned = list("pinned"), archived = list("archived"), blocked = list("blocked");
            Map<String, JSONObject> threads = new LinkedHashMap<>();
            String query = filter.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < data.length(); i++) {
                JSONObject message = data.getJSONObject(i);
                String number = message.getString("address");
                boolean visible = switch (folder) {
                    case "Archived" -> archived.contains(number) && !blocked.contains(number);
                    case "Blocked" -> blocked.contains(number);
                    default -> !archived.contains(number) && !blocked.contains(number);
                };
                if (visible && (number + " " + message.getString("body")).toLowerCase(Locale.ROOT).contains(query)) {
                    threads.putIfAbsent(number, message);
                }
            }
            var numbers = new ArrayList<>(threads.keySet());
            numbers.sort(Comparator.comparing(number -> !pinned.contains(number)));
            List<ThreadRow> entries = new ArrayList<>();
            for (String number : numbers) {
                JSONObject message = threads.get(number);
                entries.add(new ThreadRow(number, number, initials(number), message.getString("body"),
                    inboxDate(displayedTimestamp(message)), message.optInt("read", 1) == 0,
                    pinned.contains(number)));
            }
            inboxScreen.setError(null);
            inboxScreen.setThreads(entries);
        } catch (Exception error) {
            inboxScreen.setThreads(List.of());
            inboxScreen.setError("Unable to load the Android SMS inbox");
        }
    }

    private long displayedTimestamp(JSONObject message) throws org.json.JSONException {
        long sent = message.optLong("sentAt", 0);
        return sent > 0 && prefs.getBoolean("exactTimes", false) ? sent : message.getLong("date");
    }

    private String inboxDate(long date) {
        Date message = new Date(date);
        if (android.text.format.DateUtils.isToday(date)) {
            return DateFormat.getTimeInstance(DateFormat.SHORT).format(message);
        }
        if (prefs.getBoolean("exactTimes", false)) {
            var zone = java.time.ZoneId.systemDefault();
            var dateTime = java.time.Instant.ofEpochMilli(date).atZone(zone);
            long age = java.time.temporal.ChronoUnit.DAYS.between(dateTime.toLocalDate(), java.time.LocalDate.now(zone));
            String day = new java.text.SimpleDateFormat(age >= 0 && age < 7 ? "EEEE" : "MMM d", Locale.getDefault()).format(message);
            return withTime(day, DateFormat.getTimeInstance(DateFormat.SHORT).format(message));
        }
        return new java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(message);
    }

    private void compose() { compose(""); }

    private void compose(String body) {
        EditText entry = field("Recipient phone number");
        entry.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(this).setTitle(body.isEmpty() ? "New SMS" : "Forward message").setView(entry)
            .setNegativeButton("Cancel", null).setPositiveButton("Continue", (dialog, which) -> {
                String number = entry.getText().toString().trim();
                if (!number.isBlank()) {
                    chat(number);
                    if (!body.isEmpty()) conversationScreen.setDraft(body);
                }
            }).show();
    }
    private void chat(String number) {
        address = number;
        render("chat");
        ChatProfile profile = ChatProfile.read(prefs, number);
        ContactMatch match = findContact(number);
        String name = match == null ? number : match.name();
        conversationScreen = new ConversationScreen(root, prefs, profile, name, initials(name), "SMS", false,
            SenderStyle.forAddress(prefs, number, SenderStyle.infer(number)),
            simLabel(), this::inbox, () -> contactDetails(number, number, () -> chat(number)),
            () -> conversationMenu(number), this::chooseSim, this::compose,
            line -> confirmDelete(() -> {
                try {
                    SmsStore.delete(this, Long.parseLong(line.getId()), number);
                    fillChat();
                } catch (RuntimeException error) { toast("Could not delete the message"); }
            }), this::sendSms, List.of(), null, 0);
        fillChat();
        animateIn(root.getChildAt(0), 10);
    }

    private boolean sendSms(String body) {
        try {
            SmsStore.send(this, address, body, prefs.getInt("sim", -1));
            feedback(root);
            animateNextMessage = true;
            fillChat();
            sync(false);
            return true;
        } catch (Exception error) { toast(error.getMessage()); return false; }
    }

    private String simLabel() {
        int sim = prefs.getInt("sim", -1);
        if (sim < 0) return "SIM";
        try {
            var info = getSystemService(SubscriptionManager.class).getActiveSubscriptionInfo(sim);
            return info == null ? "SIM" : "SIM " + (info.getSimSlotIndex() + 1);
        } catch (SecurityException error) { return "SIM"; }
    }
    private void chooseSim() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 13);
            return;
        }
        var active = getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();
        labels.add("System default");
        ids.add(-1);
        if (active != null) for (var sim : active) {
            ids.add(sim.getSubscriptionId());
            labels.add("SIM " + (sim.getSimSlotIndex() + 1) + " · " + sim.getDisplayName());
        }
        new AlertDialog.Builder(this).setTitle("Send with").setItems(labels.toArray(new String[0]), (dialog, index) -> {
            prefs.edit().putInt("sim", ids.get(index)).apply();
            if (conversationScreen != null) conversationScreen.setSim(simLabel());
        }).show();
    }
    private boolean animateNextMessage;
    private void fillChat() {
        if (conversationScreen == null) return;
        try {
            JSONArray all = SmsStore.read(this, 0);
            DateFormat day = DateFormat.getDateInstance(DateFormat.MEDIUM);
            DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
            List<ChatLine> entries = new ArrayList<>();
            for (int i = all.length() - 1; i >= 0; i--) {
                JSONObject entry = all.getJSONObject(i);
                if (!address.equals(entry.getString("address"))) continue;
                int kind = entry.getInt("type");
                long date = displayedTimestamp(entry);
                String status = switch (kind) {
                    case Telephony.Sms.MESSAGE_TYPE_FAILED -> "Couldn’t send";
                    case Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Sending…";
                    default -> "";
                };
                entries.add(new ChatLine(entry.getString("id"), entry.getString("body"),
                    messageDay(day, date), kind != Telephony.Sms.MESSAGE_TYPE_INBOX, status, time.format(new Date(date))));
            }
            if (animateNextMessage && !entries.isEmpty()) {
                conversationScreen.setAnimatedId(entries.get(entries.size() - 1).getId());
            }
            animateNextMessage = false;
            conversationScreen.setError(null);
            conversationScreen.setLines(entries);
        } catch (Exception error) {
            conversationScreen.setError("Unable to open conversation");
        }
    }

    private static String messageDay(DateFormat format, long date) {
        return android.text.format.DateUtils.isToday(date) ? "Today" : format.format(new Date(date));
    }

    private int bounded(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, prefs.getInt(key, fallback)));
    }
    private void feedback(View target) {
        MessageFeedback.vibrate(target, prefs);
        MessageFeedback.play(this, prefs, false);
    }

    private void contactDetails(String key, String original, Runnable done) {
        String draft = conversationScreen == null ? "" : conversationScreen.getDraft();
        Runnable back = () -> {
            done.run();
            if (conversationScreen != null) conversationScreen.setDraft(draft);
        };
        showContact(key, original, back);
    }

    private void showContact(String key, String original, Runnable back) {
        boolean sample = key.startsWith("demo:");
        ContactMatch contact = sample ? null : findContact(key);
        String name = contact == null ? original : contact.name();
        render("contact");
        contactBack = back;
        heading("Conversation details", "Done", back);
        Runnable returnToDetails = () -> showContact(key, original, back);
        List<SettingsAction> actions = new ArrayList<>();
        ChatProfile profile = ChatProfile.read(prefs, key);
        String themeLabel = Arrays.stream(MessageStyle.Theme.values()).filter(theme -> theme.name().equals(profile.theme()))
            .map(theme -> theme.label).findFirst().orElse("Global theme");
        actions.add(action("Theme and wallpaper", themeLabel, () -> {
            render("chatAppearance");
            contactBack = returnToDetails;
            heading("Chat appearance", "Back", returnToDetails);
            ProfileEditor.mount(root, prefs, key);
        }));
        if (!sample || Arrays.stream(SAMPLES).anyMatch(row -> row[0].equals(original) && row[5].equals("SMS"))) {
            var fallback = sample ? sampleSender(original) : SenderStyle.infer(key);
            var style = SenderStyle.setting(prefs, key);
            actions.add(action("Sender color", style == SenderStyle.AUTO ? "Automatic · " + fallback.label : style.label,
                () -> chooseSenderColor(key, style, returnToDetails)));
        }
        if (!sample) {
            for (String setting : List.of("muted", "pinned", "archived")) {
                boolean active = list(setting).contains(key);
                String label = switch (setting) {
                    case "muted" -> active ? "Unmute notifications" : "Mute notifications";
                    case "pinned" -> active ? "Unpin conversation" : "Pin conversation";
                    default -> active ? "Unarchive conversation" : "Archive conversation";
                };
                String detail = switch (setting) {
                    case "muted" -> active ? "Alerts are off" : "Alerts are on";
                    case "pinned" -> active ? "Shown at the top of your inbox" : "Keep this chat at the top";
                    default -> active ? "In Archived" : "Move this chat out of your inbox";
                };
                actions.add(action(label, detail, () -> {
                    toggleAddress(setting, key);
                    returnToDetails.run();
                }));
            }
        }
        refreshContact = returnToDetails;
        String subtitle = sample ? "Sample contact" : key.equals(name) ? "SMS" : key;
        if (sample && original.equals("Weekend Plans")) subtitle = "Sample group · " + (WEEKEND_MEMBERS.size() + 1) + " members";
        ConversationDetails.mount(root, prefs, name, initials(name), subtitle, sample ? samplePhoto(name) : 0,
            sample ? null : () -> dialContact(key), sample ? null : () -> editContact(key), actions);
    }

    private Runnable contactBack;
    private Runnable refreshContact;

    private void chooseSenderColor(String key, SenderStyle current, Runnable back) {
        var styles = SenderStyle.values();
        String[] labels = Arrays.stream(styles).map(style -> style.label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Sender color")
            .setSingleChoiceItems(labels, current.ordinal(), (dialog, index) -> {
                styles[index].save(prefs, key);
                dialog.dismiss();
                back.run();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private record ContactMatch(String name, Uri uri) {}

    private ContactMatch findContact(String number) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        var columns = new String[]{ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY,
            ContactsContract.PhoneLookup.DISPLAY_NAME};
        var uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (var cursor = getContentResolver().query(uri, columns, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                Uri lookup = ContactsContract.Contacts.getLookupUri(cursor.getLong(0), cursor.getString(1));
                String name = cursor.getString(2);
                return new ContactMatch(name == null || name.isBlank() ? number : name, lookup);
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private void editContact(String number) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("askedContactLookup", false)) {
            contactToEdit = number;
            prefs.edit().putBoolean("askedContactLookup", true).apply();
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACT_PERMISSION);
        } else openContactEditor(number);
    }

    private void openContactEditor(String number) {
        ContactMatch contact = findContact(number);
        Intent editor = contact != null && contact.uri() != null ? new Intent(Intent.ACTION_EDIT, contact.uri())
            : new Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, number);
        try { startActivityForResult(editor, CONTACT_EDITOR); }
        catch (ActivityNotFoundException error) { toast("No Android Contacts editor is available"); }
    }

    private void dialContact(String number) {
        try { startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))); }
        catch (ActivityNotFoundException error) { toast("No Android dialer is available"); }
    }

    private SettingsAction action(String label, String detail, Runnable run) {
        return new SettingsAction(label, detail, run);
    }

    private void settings() {
        render("settings");
        heading("Settings", "Inbox", this::inbox);
        boolean locked = prefs.getBoolean("lock", false);
        SettingsPages.mount(root, prefs, List.of(
            new SettingsSection("Personalize", List.of(
                action("Themes and appearance", "Global themes, wallpapers, colors, and motion", this::theme),
                action("Messaging", "Timestamps, replies, and reactions", this::messagingSettings),
                action("Sample inbox", "Try changes with fictional conversations", this::sampleInbox))),
            new SettingsSection("Data and devices", List.of(
                action("Backups", "Local files, cloud destinations, and restore", this::backups),
                action("Web companion", CloudBackup.paired(this) ? "Set up" : "Not set up", this::companion),
                action(locked ? "Turn off app lock" : "Turn on app lock", "Protect FluidMessages with your device lock",
                    () -> toggleLock(locked)))),
            new SettingsSection("Help", List.of(action("Replay setup guide", "Review SMS setup and privacy",
                () -> onboarding(0))))));
    }

    private void toggleLock(boolean locked) {
        if (!locked && !getSystemService(KeyguardManager.class).isDeviceSecure()) {
            toast("Set a device screen lock before enabling app lock");
            return;
        }
        prefs.edit().putBoolean("lock", !locked).apply();
        if (locked) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            settings();
        } else authenticate();
    }

    private void messagingSettings() {
        render("messaging");
        heading("Messaging", "Settings", this::settings);
        SettingsPages.mountMessaging(root, prefs, this::sampleInbox);
    }

    private String lastRun(long timestamp, String fallback) {
        return timestamp == 0 ? fallback : "Last backup " + DateFormat.getDateTimeInstance().format(new Date(timestamp));
    }

    private void backups() {
        render("backups");
        heading("Backups", "Settings", this::settings);
        List<SettingsAction> local = new ArrayList<>();
        local.add(action("Export a file", lastRun(prefs.getLong("lastBackup", 0), "Save through Android’s file picker"),
            () -> backupDialog(true)));
        local.add(action("Share to a storage app", "Send a backup to a compatible app on your phone", this::shareBackup));
        local.add(action("Restore from a file", "Choose an archive and review its contents", () -> backupDialog(false)));
        var localFolder = BackupJob.FolderSchedule.LOCAL;
        boolean scheduled = !localFolder.tree(prefs).isEmpty();
        local.add(action("Local folder schedule", scheduled ? intervalLabel(localFolder.minutes(prefs))
            : "Schedule a device or storage-app folder", () -> scheduleFolder(localFolder, false)));
        if (scheduled) {
            local.add(action("Change backup folder", "Pick a different destination", () -> scheduleFolder(localFolder, true)));
            local.add(action("Disable local schedule", lastRun(localFolder.last(prefs), "No local backup run yet"),
                () -> { BackupJob.cancelFolder(this, localFolder); backups(); }));
        }
        List<SettingsAction> cloud = new ArrayList<>();
        List<Runnable> scheduledCloud = new ArrayList<>();
        for (CloudBackup.Target target : CloudBackup.Target.values()) {
            int minutes = BackupJob.interval(prefs, target);
            var phoneFolder = new BackupJob.FolderSchedule(target);
            boolean phone = backupFromPhone(target);
            boolean hasPhoneSchedule = !phoneFolder.tree(prefs).isEmpty();
            String detail = phone ? "Phone" : "Companion";
            if (phone && hasPhoneSchedule) detail += " · " + intervalLabel(phoneFolder.minutes(prefs));
            else if (!phone && minutes > 0) detail += " · " + intervalLabel(minutes);
            cloud.add(action(target.label, detail, () -> cloudDestination(target)));
            if (minutes > 0 && CloudBackup.paired(this)) scheduledCloud.add(() -> runCloudNow(target));
            if (hasPhoneSchedule) scheduledCloud.add(() -> runPhoneBackup(phoneFolder));
        }
        if (scheduledCloud.size() > 1) {
            cloud.add(action("Back up all cloud schedules", "Run each configured phone and companion schedule", () -> {
                toast("Starting " + scheduledCloud.size() + " cloud backups");
                scheduledCloud.forEach(Runnable::run);
            }));
        }
        SettingsPages.mount(root, prefs, List.of(
            new SettingsSection("Device and storage apps", local),
            new SettingsSection("Cloud destinations", cloud)));
    }

    private String phoneRouteKey(CloudBackup.Target target) { return "backupFromPhone." + target.id; }

    private boolean backupFromPhone(CloudBackup.Target target) {
        return prefs.getBoolean(phoneRouteKey(target), BackupJob.interval(prefs, target) == 0 && !CloudBackup.paired(this));
    }

    private void cloudDestination(CloudBackup.Target target) {
        render("cloud");
        heading(target.label, "Backups", this::backups);
        boolean phone = backupFromPhone(target);
        var folder = new BackupJob.FolderSchedule(target);
        String description = "";
        List<SettingsSection> sections;
        if (phone) {
            boolean scheduled = !folder.tree(prefs).isEmpty();
            description = "Save through Android’s file picker, share to a storage app, or schedule backups to a writable folder.";
            if (CloudBackup.paired(this) && BackupJob.interval(prefs, target) > 0) {
                description += " A companion schedule is also active.";
            }
            List<SettingsAction> entries = new ArrayList<>();
            long last = Math.max(folder.last(prefs), prefs.getLong(folder.key("lastManual", ""), 0));
            entries.add(action("Back up now", lastRun(last, "Choose a location on your phone or in a storage app"),
                () -> backupDialog(true, target)));
            entries.add(action("Share to a storage app", "Choose an app from Android’s share menu", () -> shareBackup(target)));
            entries.add(action("Restore from a file", "Choose a FluidMessages archive", () -> backupDialog(false, target)));
            entries.add(action("Backup schedule", scheduled ? intervalLabel(folder.minutes(prefs)) : "Choose a writable folder and interval",
                () -> scheduleFolder(folder, false)));
            if (scheduled) {
                entries.add(action("Run saved schedule now", "Use the saved folder and categories", () -> runPhoneBackup(folder)));
                entries.add(action("Change backup folder", "Choose a different folder", () -> scheduleFolder(folder, true)));
                entries.add(action("Disable this schedule", "", () -> {
                    BackupJob.cancelFolder(this, folder);
                    cloudDestination(target);
                }));
            }
            sections = List.of(new SettingsSection("Phone backup", entries));
        } else if (CloudBackup.paired(this)) {
            if (!folder.tree(prefs).isEmpty()) description = "A phone schedule is also active.";
            sections = List.of(new SettingsSection("Companion backup", companionBackupActions(target)));
        } else {
            description = "Web companion · Not set up";
            sections = List.of(new SettingsSection("Companion backup", List.of(
                action("Set up companion", "", () -> companion(() -> cloudDestination(target), true)))));
        }
        SettingsPages.mountBackupProvider(root, prefs, phone, description, sections, selected -> {
            prefs.edit().putBoolean(phoneRouteKey(target), selected).apply();
            cloudDestination(target);
        });
    }

    private List<SettingsAction> companionBackupActions(CloudBackup.Target target) {
        int minutes = BackupJob.interval(prefs, target);
        List<SettingsAction> entries = new ArrayList<>();
        entries.add(action("Back up now", lastRun(prefs.getLong(BackupJob.cloudKey(target, "last"), 0),
            "Choose what to upload for this backup"), () -> checkCloud(target, () -> cloudBackupNow(target))));
        entries.add(action("Backup schedule", minutes == 0 ? "Choose an independent interval" : intervalLabel(minutes),
            () -> checkCloud(target, () -> scheduleBackup(target))));
        if (minutes > 0) {
            entries.add(action("Run saved schedule now", "Use the saved categories and encryption",
                () -> checkCloud(target, () -> runCloudNow(target))));
            entries.add(action("Disable this schedule", "Other destination schedules continue",
                () -> { BackupJob.cancelCloud(this, target); cloudDestination(target); }));
        }
        entries.add(action("Restore newest backup", "Download the newest archive and review before importing",
            () -> checkCloud(target, () -> restoreCloud(target))));
        return entries;
    }

    private void checkCloud(CloudBackup.Target target, Runnable configured) {
        if (!CloudBackup.paired(this)) {
            companion(() -> cloudDestination(target));
            return;
        }
        View currentPage = root;
        new Thread(() -> {
            try {
                JSONArray targets = CloudBackup.targets(this);
                boolean found = false;
                for (int index = 0; index < targets.length(); index++) {
                    if (target.id.equals(targets.getString(index))) { found = true; break; }
                }
                boolean available = found;
                runOnUiThread(() -> {
                    if (root != currentPage || isFinishing()) return;
                    if (available) configured.run();
                    else toast("Configure " + target.label + " on your companion server first");
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast("Could not check the companion’s backup destinations"));
            }
        }, "fluid-check-cloud").start();
    }

    private void runCloudNow(CloudBackup.Target target) {
        runCloudNow(target, () -> BackupJob.runCloud(this, target));
    }

    private void runPhoneBackup(BackupJob.FolderSchedule folder) {
        runProviderBackup(folder.target(), "Backup saved to the chosen folder", () -> BackupJob.runFolder(this, folder));
    }

    private void cloudBackupNow(CloudBackup.Target target) {
        openBackupForm("Back up to " + target.label, () -> cloudDestination(target),
            BackupFormMode.CLOUD_NOW, backupDefaults(), selection -> {
                String origin = prefs.getString("server", "");
                runCloudNow(target, () -> BackupJob.runCloud(this, target, backupOptions(selection),
                    selection.getPassword(), origin));
            });
    }

    private interface CloudTransfer { void run() throws Exception; }

    private void runCloudNow(CloudBackup.Target target, CloudTransfer transfer) {
        runProviderBackup(target, "Backup saved to " + target.label, transfer);
    }

    private void runProviderBackup(CloudBackup.Target target, String success, CloudTransfer transfer) {
        View currentPage = root;
        toast("Backing up to " + target.label);
        new Thread(() -> {
            try {
                transfer.run();
                runOnUiThread(() -> {
                    toast(success);
                    if (root == currentPage && !isFinishing() && (page.equals("cloud") || page.equals("backupForm"))) {
                        cloudDestination(target);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast(error.getMessage()));
            }
        }, "fluid-cloud-backup").start();
    }

    private void restoreCloud(CloudBackup.Target target) {
        openBackupForm("Restore from " + target.label, () -> cloudDestination(target),
            BackupFormMode.CLOUD_RESTORE, backupDefaults(), selection -> {
                Backup.Options options = backupOptions(selection);
                View currentPage = root;
                toast("Downloading the latest backup");
                new Thread(() -> {
                    java.io.File file = null;
                    try {
                        file = java.io.File.createTempFile("fluid-restore-", ".fmb", getCacheDir());
                        CloudBackup.newest(this, target, file);
                        restorePreview(Backup.read(this, Uri.fromFile(file), selection.getPassword()), options, currentPage,
                            () -> cloudDestination(target));
                    } catch (Exception error) {
                        runOnUiThread(() -> toast("Could not download or unlock the newest backup"));
                    } finally { if (file != null) file.delete(); }
                }, "fluid-cloud-restore").start();
            });
    }

    private void authenticate() {
        if (lockPrompt != null) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        root.setVisibility(View.INVISIBLE);
        lockPrompt = new CancellationSignal();
        CancellationSignal prompt = lockPrompt;
        var biometric = new android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle("Unlock FluidMessages")
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build();
        biometric.authenticate(prompt, getMainExecutor(), new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                if (prompt != lockPrompt) return;
                lockPrompt = null;
                unlocked = true;
                if (page.equals("settings")) settings();
                root.setVisibility(View.VISIBLE);
            }
            @Override public void onAuthenticationError(int code, CharSequence message) {
                if (prompt != lockPrompt) return;
                lockPrompt = null;
                finish();
            }
        });
    }
    private void theme() {
        render("theme");
        heading("Appearance", "Done", this::settings);
        ThemeEditor.mount(root, prefs, this::recolorWindow);
    }

    private String intervalLabel(int minutes) {
        int hours = minutes / 60;
        int remainder = minutes % 60;
        if (hours == 24 && remainder == 0) return "Daily";
        String duration = hours == 0 ? "" : hours + (hours == 1 ? " hour" : " hours");
        if (remainder > 0) duration += (duration.isEmpty() ? "" : " ") + remainder + " min";
        return duration.isEmpty() ? "Choose an interval" : "Every " + duration;
    }

    private Backup.Options backupOptions(BackupFormSelection selection) {
        return new Backup.Options(selection.getCategories());
    }

    private BackupFormDefaults backupDefaults() {
        return new BackupFormDefaults(Arrays.stream(BackupCategory.values()).mapToInt(BackupCategory::getFlag).sum(),
            1440, false, false);
    }

    private void openBackupForm(String title, Runnable back, BackupFormMode mode, BackupFormDefaults defaults,
                                java.util.function.Consumer<BackupFormSelection> submit) {
        render("backupForm");
        backupBack = back;
        heading(title, "Back", back);
        BackupForms.mount(root, prefs, mode, hasRole(), defaults, submit);
    }

    private void backupDialog(boolean export) {
        backupDialog(export, null);
    }

    private void backupDialog(boolean export, CloudBackup.Target target) {
        String title = export ? (target == null ? "Export backup" : "Back up from phone") : "Restore from a file";
        openBackupForm(title, backupDestinationBack(target),
            export ? BackupFormMode.EXPORT : BackupFormMode.RESTORE, backupDefaults(), selection -> {
                password = selection.getPassword();
                backupOptions = backupOptions(selection);
                pendingFileTarget = target;
                Intent picker = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                    .setType(export ? "application/octet-stream" : "*/*").addCategory(Intent.CATEGORY_OPENABLE);
                if (export) picker.putExtra(Intent.EXTRA_TITLE, target == null ? "FluidMessages.fmb" : "FluidMessages_" + target.id + ".fmb");
                startActivityForResult(picker, export ? EXPORT : IMPORT);
            });
    }

    private Runnable backupDestinationBack(CloudBackup.Target target) {
        return target == null ? this::backups : () -> cloudDestination(target);
    }

    private void shareBackup() { shareBackup(null); }

    private void shareBackup(CloudBackup.Target target) {
        openBackupForm("Share a backup", backupDestinationBack(target), BackupFormMode.SHARE, backupDefaults(), selection -> {
            if (sharingBackup) return;
            sharingBackup = true;
            View currentPage = root;
            new Thread(() -> {
                java.io.File file = null;
                try {
                    var folder = new java.io.File(getCacheDir(), "shared-backups");
                    if (!folder.mkdirs() && !folder.isDirectory()) throw new java.io.IOException();
                    var expired = folder.listFiles(old -> old.isFile()
                        && old.lastModified() < System.currentTimeMillis() - 24L * 60 * 60 * 1000);
                    if (expired != null) for (var old : expired) old.delete();
                    file = java.io.File.createTempFile("FluidMessages-", ".fmb", folder);
                    Backup.write(this, Uri.fromFile(file), backupOptions(selection), selection.getPassword());
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".backup-files",
                        file, "FluidMessages.fmb");
                    Intent share = new Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    share.setClipData(ClipData.newUri(getContentResolver(), "FluidMessages backup", uri));
                    var archive = file;
                    runOnUiThread(() -> {
                        if (root == currentPage && !isFinishing() && !isDestroyed()) {
                            startActivity(Intent.createChooser(share, "Share backup"));
                        } else archive.delete();
                    });
                } catch (Exception error) {
                    if (file != null) file.delete();
                    runOnUiThread(() -> toast("Could not prepare the backup to share"));
                } finally {
                    runOnUiThread(() -> sharingBackup = false);
                }
            }, "fluid-share-backup").start();
        });
    }

    private void scheduleBackup(CloudBackup.Target target) {
        int saved = BackupJob.interval(prefs, target);
        BackupFormDefaults defaults = saved > 0 ? new BackupFormDefaults(BackupJob.categories(prefs, target), saved,
            prefs.getBoolean(BackupJob.cloudKey(target, "wifi"), false), BackupJob.hasSavedPassword(prefs, target)) : backupDefaults();
        openBackupForm("Schedule " + target.label, backupDestinationBack(target), BackupFormMode.CLOUD, defaults, selection -> {
            String schedulePassword = selection.getKeepPassword() ? null : selection.getPassword();
            try {
                BackupJob.configureCloud(this, target, backupOptions(selection), schedulePassword,
                    selection.getIntervalMinutes(), selection.getWifi());
                cloudDestination(target);
                toast("Backup scheduled for " + target.label);
            } catch (Exception error) { toast(error.getMessage()); }
        });
    }

    private void scheduleFolder(BackupJob.FolderSchedule folder, boolean replaceFolder) {
        String existing = folder.tree(prefs);
        int saved = folder.minutes(prefs);
        boolean keepFolder = !existing.isEmpty() && !replaceFolder;
        Runnable back = backupDestinationBack(folder.target());
        BackupFormDefaults defaults = saved > 0 ? new BackupFormDefaults(folder.categories(prefs), saved,
            folder.wifiOnly(prefs), folder.savedPassword(prefs)) : backupDefaults();
        openBackupForm(folder.target() == null ? "Local folder schedule" : "Phone backup schedule", back,
            keepFolder ? BackupFormMode.LOCAL_EXISTING : BackupFormMode.LOCAL, defaults, selection -> {
                Backup.Options options = backupOptions(selection);
                String schedulePassword = selection.getKeepPassword() ? null : selection.getPassword();
                if (keepFolder) {
                    try {
                        BackupJob.configureFolder(this, folder, Uri.parse(existing), options, schedulePassword,
                            selection.getIntervalMinutes(), selection.getWifi());
                        back.run();
                        toast("Backup schedule updated");
                    } catch (Exception error) { toast(error.getMessage()); }
                } else {
                    pendingFolder = folder;
                    backupOptions = options;
                    password = schedulePassword;
                    backupMinutes = selection.getIntervalMinutes();
                    backupWifi = selection.getWifi();
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), SCHEDULE_BACKUP);
                }
            });
    }

    private CheckBox option(LinearLayout parent, String label, boolean selected) {
        CheckBox view = new CheckBox(this);
        view.setText(label);
        view.setChecked(selected);
        parent.addView(view);
        return view;
    }
    private void backupResult(Uri uri, boolean export, Backup.Options options, String secret, CloudBackup.Target target) {
        View currentPage = root;
        Runnable back = backupDestinationBack(target);
        new Thread(() -> {
            try {
                if (export) {
                    Backup.write(this, uri, options, secret);
                    String last = target == null ? "lastBackup" : new BackupJob.FolderSchedule(target).key("lastManual", "");
                    prefs.edit().putLong(last, System.currentTimeMillis()).apply();
                    runOnUiThread(() -> {
                        if (root == currentPage && !isFinishing() && !isDestroyed()) back.run();
                        toast("Backup saved");
                    });
                } else {
                    restorePreview(Backup.read(this, uri, secret), options, currentPage, back);
                }
            } catch (Exception error) {
                runOnUiThread(() -> toast(export ? "Unable to write the backup" : "Unable to read the file or unlock the backup"));
            }
        }, "fluid-backup").start();
    }
    private void saveFolderSchedule(Intent data, BackupJob.FolderSchedule folder, Backup.Options options,
                                    String secret, int minutes, boolean wifiOnly) {
        Uri tree = data.getData();
        int granted = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        int access = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        View currentPage = root;
        new Thread(() -> {
            try {
                if (!DocumentsContract.isTreeUri(tree) || (granted & access) != access) {
                    throw new java.io.IOException("Choose a writable backup folder");
                }
                var resolver = getContentResolver();
                var directory = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                try (var cursor = resolver.query(directory, new String[]{DocumentsContract.Document.COLUMN_FLAGS},
                    null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst() ||
                        (cursor.getInt(0) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
                        throw new java.io.IOException("This storage app does not let FluidMessages write to that folder");
                    }
                }
                resolver.takePersistableUriPermission(tree, granted);
                runOnUiThread(() -> {
                    try {
                        BackupJob.configureFolder(this, folder, tree, options, secret, minutes, wifiOnly);
                        if (root == currentPage && !isFinishing() && !isDestroyed()) backupDestinationBack(folder.target()).run();
                        toast("Backup scheduled");
                    } catch (Exception error) {
                        BackupJob.release(this, tree);
                        toast("Unable to schedule backups for this folder");
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast(error instanceof java.io.IOException ? error.getMessage() :
                    "Unable to schedule backups for this folder"));
            }
        }, "fluid-backup-folder").start();
    }
    private void restorePreview(JSONObject archive, Backup.Options options, View currentPage, Runnable back) {
        List<String> summary = Backup.summary(archive, options);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || root != currentPage) return;
            render("backupReview");
            backupBack = back;
            heading("Review backup", "Cancel", back);
            BackupForms.review(root, prefs, summary, () -> new Thread(() -> {
                try {
                    int count = Backup.restore(this, archive, options);
                    runOnUiThread(() -> { back.run(); toast(count > 0 ? "Restored · " + count + " new texts" : "Backup restored"); });
                } catch (Exception error) {
                    runOnUiThread(() -> { back.run(); toast("Restore could not finish"); });
                }
            }, "fluid-restore").start());
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CONTACT_EDITOR) {
            if (page.equals("contact") && refreshContact != null) refreshContact.run();
        } else if (request == ROLE) {
            inbox();
            if (hasRole() && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 14);
            }
        } else if (request == SCHEDULE_BACKUP) {
            if (result == RESULT_OK && data != null && data.getData() != null && backupOptions != null && pendingFolder != null) {
                saveFolderSchedule(data, pendingFolder, backupOptions, password, backupMinutes, backupWifi);
            }
            pendingFolder = null;
            backupOptions = null;
            password = "";
        } else if (request == EXPORT || request == IMPORT) {
            if (result == RESULT_OK && data != null && data.getData() != null && backupOptions != null) {
                backupResult(data.getData(), request == EXPORT, backupOptions, password, pendingFileTarget);
            }
            pendingFileTarget = null;
            backupOptions = null;
            password = "";
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == CONTACT_PERMISSION && contactToEdit != null) {
            String number = contactToEdit;
            contactToEdit = null;
            if (page.equals("contact")) openContactEditor(number);
        }
    }

    private void companion() { companion(this::settings); }

    private void companion(Runnable back) { companion(back, false); }

    private void companion(Runnable back, boolean setup) {
        render("companion");
        backupBack = back;
        heading("Web companion", "Done", back);
        boolean connected = CloudBackup.paired(this);
        if (!connected && !setup) {
            SettingsPages.mount(root, prefs, List.of(new SettingsSection("Not set up", List.of(
                action("Set up companion", "", () -> companion(back, true))))));
            return;
        }
        item(root, text("Pair over HTTPS for cloud backups. You can also enable browser messaging to sync the newest 2,000 texts and send queued SMS.", 16, subtle()));
        EditText url = field("https://messages.example.net");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("server", ""));
        item(root, url);
        EditText token = field("Server pairing token");
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.getString("token", ""));
        item(root, token);
        CheckBox syncMessages = option(root, "Enable browser messaging",
            prefs.getBoolean("syncSms", CloudBackup.paired(this)));
        syncMessages.setTextColor(ink());
        syncMessages.setMinHeight(dp(48));
        item(root, button("Save connection", true, () -> {
            if (syncMessages.isChecked() && !hasRole()) {
                toast("Browser messaging needs the default SMS role. Turn it off to pair for backups.");
                return;
            }
            try {
                var endpoint = new java.net.URI(url.getText().toString().trim());
                String pairingToken = token.getText().toString().trim();
                if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || endpoint.getPath() != null && !endpoint.getPath().matches("/?") || pairingToken.length() < 32) {
                    throw new IllegalArgumentException();
                }
                prefs.edit().putString("server", "https://" + endpoint.getRawAuthority())
                    .putString("token", pairingToken)
                    .putBoolean("syncSms", syncMessages.isChecked()).apply();
                SyncJob.configure(this, syncMessages.isChecked());
                if (syncMessages.isChecked()) sync(true);
                back.run();
                toast("Companion connection saved");
            } catch (Exception error) { toast("Enter an HTTPS server origin and its pairing token"); }
        }));
        if (connected) item(root, button("Disconnect phone", false, () -> {
            prefs.edit().remove("server").remove("token").apply();
            SyncJob.configure(this, false);
            back.run();
        }));
    }
    private void sync(boolean report) {
        if (syncing || !CloudBackup.paired(this) || !prefs.getBoolean("syncSms", true) || !hasRole()) return;
        syncing = true;
        new Thread(() -> {
            try {
                SyncJob.sync(getApplicationContext());
                if (report) runOnUiThread(() -> toast("Companion synced"));
            } catch (Exception error) {
                if (report) runOnUiThread(() -> toast("Could not sync. Check the HTTPS certificate, address, and token."));
            } finally {
                runOnUiThread(() -> syncing = false);
            }
        }, "fluid-sync-now").start();
    }
    private void toast(String message) { Toast.makeText(this, message == null ? "Operation failed" : message, Toast.LENGTH_LONG).show(); }
}
