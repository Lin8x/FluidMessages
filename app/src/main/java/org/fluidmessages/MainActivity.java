package org.fluidmessages;

import android.telephony.PhoneNumberUtils;

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
        CONTACT_PERMISSION = 16, CONTACT_EDITOR = 17, PICKER_CONTACT_PERMISSION = 18, INSTALL_SOURCE = 19;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private InboxScreen inboxScreen;
    private ConversationScreen conversationScreen;
    private ContactDirectory contacts;
    private RecipientPicker recipientPicker;
    private boolean observingContacts;
    private final java.util.concurrent.ExecutorService inboxWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService chatWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> chatLoad;
    private int chatVersion;
    // The inbox and every conversation read the same merged list; keep it until a text or protocol message changes.
    private final Object rowsLock = new Object();
    private List<JSONObject> rowsCache;
    private long rowsAt;
    private int rowsGeneration, rowsRevision = -1;
    private java.util.concurrent.Future<?> inboxLoad;
    private int inboxVersion;
    private final Runnable searchInbox = this::refreshVisibleInbox;
    private String address;
    private MessageProtocol activeProtocol = MessageProtocol.SMS;
    private final Map<String, List<String>> unsentGroups = new HashMap<>();
    private final BroadcastReceiver protocolChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            invalidateRows();
            // Decoy mode turned on from the quick settings tile: leave the private folder at once.
            if ("inbox".equals(page) && Privacy.decoyMode(prefs) && folder.equals(HIDDEN_FOLDER)) { folder = "Inbox"; inbox(); return; }
            if ("inbox".equals(page)) refreshVisibleInbox();
            if ("chat".equals(page)) fillChat();
        }
    };
    private String filter = "";
    private String folder = "Inbox";
    private String page = "inbox";
    private int setupStep;
    private String sampleSearch = "";
    private String sampleFolder = "Inbox";
    private final Map<String, List<ChatLine>> sampleReplies = new HashMap<>();
    private final Set<String> readSamples = new HashSet<>();
    private String contactToEdit;
    private final Map<String, Set<String>> hiddenSampleMessages = new HashMap<>();
    private static final String[][] SAMPLES = {
        {"Maya Chen", "MC", "Coffee at ten?", "See you there!", "9:41 AM", "SMS"},
        {"Jordan Ellis", "JE", "I sent the route for Saturday.", "That looks like a great walk.", "Yesterday", "RCS"},
        {"Avery Brooks", "AB", "Are we still meeting Sunday?", "Yes, let’s meet at the park.", "Tuesday", "SMS"},
        {"Sam Rivera", "SR", "I saved you a seat!", "On my way.", "Monday", "RCS"},
        {"Weekend Plans", "WP", "Picnic by the lake on Saturday?", "I’ll bring dessert.", "Today", "Group"},
        {"Trail Alerts", "TA", "Trail update: the south entrance is open. Reply STOP to stop receiving these messages.", "STOP", "Today", "SMS"},
        {"Nora Patel", "NP", "Did you want the window seat?", "That would be great.", "Yesterday", "SMS"},
        {"Cameron Reed", "CR", "The pottery studio finally fired our pieces.", "Did the blue glaze come out okay?", "Yesterday", "RCS"},
        {"Photo Walk", "PW", "Blue hour at the footbridge tomorrow?", "I just charged the camera batteries.", "Today", "Group"},
        {"Trail Crew", "TC", "North trail advisory: the footpath reopens at noon.", "Great, I’ll bring the spare water bottles.", "Today", "Group"},
        {"Garden Committee", "GC", "Here’s the sketch for the raised beds.", "Let’s keep the path four feet wide.", "Yesterday", "MMS"},
        {"Leah Morgan", "LM", "The glaze workshop moved to Thursday.", "I’ll update the signup form.", "Yesterday", "Email"},
        {"Design Studio", "DS", "I pinned the new type samples in the room.", "The narrower one fits the poster.", "Monday", "Matrix"},
        {"Amir Nasser", "AN", "I pushed the updated timetable to the shared drive.", "I’ll check the last train.", "Tuesday", "XMPP"},
        {"#radio-club", "RC", "The antenna tuning session starts at seven.", "I’ll bring the analyzer.", "Today", "IRC"}
    };
    private static final Map<String, List<String>> SAMPLE_GROUPS = Map.of(
        "Weekend Plans", List.of("Maya Chen", "Jordan Ellis", "Avery Brooks"),
        "Photo Walk", List.of("Jordan Ellis", "Cameron Reed", "Sam Rivera"),
        "Trail Crew", List.of("Trail Alerts", "Maya Chen", "Cameron Reed"),
        "Garden Committee", List.of("Avery Brooks", "Leah Morgan", "Amir Nasser"),
        "Design Studio", List.of("Nora Patel", "Maya Chen", "Amir Nasser"),
        "#radio-club", List.of("mosaic", "kestrel", "orbital"));

    private int samplePhoto(String name) {
        return switch (name) {
            case "Maya Chen" -> R.drawable.sample_maya;
            case "Jordan Ellis" -> R.drawable.sample_jordan;
            default -> 0;
        };
    }

    private List<String> sampleMembers(String[] contact) {
        return SAMPLE_GROUPS.getOrDefault(contact[0], List.of(contact[0]));
    }

    private SenderStyle sampleSender(String name) {
        var group = SAMPLE_GROUPS.get(name);
        if (group != null) return SenderStyle.group(group.stream().map(this::sampleSender).toList());
        if (name.equals("Trail Alerts")) return SenderStyle.AUTOMATED;
        for (String[] sample : SAMPLES) {
            if (sample[0].equals(name) && sample[5].equals("RCS")) return SenderStyle.RCS;
        }
        return SenderStyle.SMS;
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
    private boolean resumed;
    private boolean checkingUpdates;
    private int updateCheckVersion;
    private boolean downloadingUpdate;
    private String updateStatus = "";
    private ReleaseUpdates.Release pendingRelease;
    private java.io.File pendingInstaller;
    private boolean unlocked;
    private CancellationSignal lockPrompt;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            int seconds = SyncPolicy.foregroundSeconds(prefs);
            if (seconds > 0 && SyncPolicy.allowed(MainActivity.this)) sync(false);
            if (resumed) checkUpdates(false);
            int next = seconds > 0 ? seconds : 60;
            handler.postDelayed(this, next * 1000L);
        }
    };
    /**
     * Set for the next disappearing message among the loaded ones: it leaves the screen at its time and is deleted
     * from storage straight away. With the app closed, {@link DisappearingAlarm} deletes it instead.
     */
    private final Runnable expireOnScreen = new Runnable() {
        @Override public void run() {
            invalidateRows();
            refreshVisibleInbox();
            if (page.equals("chat")) fillChat();
            new Thread(() -> Disappearing.sweep(getApplicationContext()), "fluid-disappear").start();
        }
    };

    private void expireAt(long at) {
        handler.removeCallbacks(expireOnScreen);
        if (at > 0) handler.postDelayed(expireOnScreen, Math.max(0, at - System.currentTimeMillis()) + 50);
    }
    /** Companion-linked Matrix chats ring here while FluidMessages is open. */
    private final Runnable callPoll = new Runnable() {
        @Override public void run() {
            if (CloudBackup.paired(MainActivity.this) && GatewayClient.hasAccount(prefs, MessageProtocol.MATRIX)) {
                new Thread(() -> CallHub.pollCompanion(getApplicationContext()), "fluid-call-check").start();
            }
            handler.postDelayed(this, 3000);
        }
    };
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            invalidateRows();
            if (page.equals("inbox")) fillInbox();
            if (page.equals("chat")) fillChat();
        }
    };

    private final ContentObserver contactObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            contacts.invalidate();
            scheduleInboxSearch();
            if ("recipients".equals(page)) loadRecipients();
        }
    };

    private void refreshVisibleInbox() {
        if (page.equals("inbox")) fillInbox();
    }

    private void scheduleInboxSearch() {
        handler.removeCallbacks(searchInbox);
        handler.postDelayed(searchInbox, 150);
    }

    private void watchContacts() {
        contacts.watch();
        if (contacts.allowed() && !observingContacts) {
            try {
                getContentResolver().registerContentObserver(ContactsContract.AUTHORITY_URI, true, contactObserver);
                observingContacts = true;
                getContentResolver().registerContentObserver(ContactsContract.Data.CONTENT_URI, true, contactObserver);
                getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, contactObserver);
            } catch (SecurityException ignored) {}
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("fluid", MODE_PRIVATE);
        // A fresh install starts in the phone's light or dark mode; after that the choice is the user's.
        if (!prefs.contains("dark") && !prefs.getBoolean("onboarded", false)) {
            int night = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            prefs.edit().putBoolean("dark", night == android.content.res.Configuration.UI_MODE_NIGHT_YES).apply();
        }
        // Shared with the rest of the app and kept between visits; it clears itself when a contact changes.
        contacts = ContactDirectory.shared(this);
        new Thread(() -> {
            Privacy.migrate(prefs);
            DirectEmail.stripStoredQuotes(getApplicationContext());
        }, "fluid-email-cleanup").start();
        // Catches disappearing messages whose alarm Android dropped (a force stop clears alarms) and sets it again.
        // Waits until the inbox has loaded, so it doesn't slow the first screen.
        if (Disappearing.anyTimers(prefs) || Disappearing.alarmSet(this)) handler.postDelayed(() ->
            new Thread(() -> Disappearing.sweep(getApplicationContext()), "fluid-disappear-start").start(), 4000);
        if (state != null && state.containsKey("setupStep")) onboarding(state.getInt("setupStep"));
        else if (prefs.getBoolean("onboarded", false)) open(getIntent());
        else onboarding(0);
        getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer);
        registerReceiver(protocolChanges, new IntentFilter(MmsTransport.CHANGED), Context.RECEIVER_NOT_EXPORTED);
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
        // The first companion sync waits a moment, so it doesn't compete with the inbox for the message store.
        handler.postDelayed(poll, 1500);
        handler.post(callPoll);
        SipAgent.setAppVisible(this, true);
        // Phone-direct connections run while FluidMessages is visible, unless set to stay connected.
        new Thread(() -> DirectProtocols.foreground(this, true), "fluid-direct-start").start();
    }
    private boolean userLeaving;
    @Override protected void onUserLeaveHint() {
        userLeaving = true;
        super.onUserLeaveHint();
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // Texts, internet messages, and settings changes already refresh the shared message list as they happen,
        // and contacts clear themselves when they change, so coming back reuses what's loaded instead of reading
        // every message and contact again.
        if (page.equals("chat")) fillChat();
        if (contacts.allowed() && !contacts.watching()) contacts.invalidate();
        watchContacts();
        refreshVisibleInbox();
        checkUpdates(false);
        promptUpdate();
        if (pendingInstaller != null && canPromptUpdate()) installUpdate();
    }
    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }
    @Override protected void onStop() {
        handler.removeCallbacks(poll);
        handler.removeCallbacks(callPoll);
        SipAgent.setAppVisible(this, false);
        new Thread(() -> DirectProtocols.foreground(this, false), "fluid-direct-stop").start();
        unlocked = false;
        // Leaving the app or turning off the screen closes hidden chats and asks for chat codes again. Opening the
        // photo picker, camera, or a call from inside the app doesn't.
        if (userLeaving || !getSystemService(android.os.PowerManager.class).isInteractive()) {
            boolean wasOpen = Privacy.vaultOpen() || emptyHidden;
            emptyHidden = false;
            Privacy.forget();
            invalidateRows();
            if (wasOpen && !isChangingConfigurations()) inbox();
        }
        userLeaving = false;
        new Thread(() -> HiddenChats.sweep(getApplicationContext()), "fluid-hide-sweep").start();
        if (lockPrompt != null) {
            lockPrompt.cancel();
            lockPrompt = null;
        }
        super.onStop();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(searchInbox);
        handler.removeCallbacks(expireOnScreen);
        inboxWorker.shutdownNow();
        chatWorker.shutdownNow();
        if (observingContacts) getContentResolver().unregisterContentObserver(contactObserver);
        getContentResolver().unregisterContentObserver(observer);
        unregisterReceiver(protocolChanges);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        switch (page) {
            case "inbox" -> super.onBackPressed();
            case "setup" -> { if (setupStep > 0) onboarding(setupStep - 1); else finish(); }
            case "sampleChat" -> sampleInbox();
            case "contact", "chatAppearance", "decoy" -> contactBack.run();
            case "sample" -> inbox();
            case "theme", "backups", "messaging", "about", "protocols", "privacy", "reset", "calls", "gifs", "payments" -> settings();
            case "recording" -> callsSettings();
            case "recordings" -> recordingSettings();
            case "locks", "hiddenChats", "decoyMode", "encryption" -> privacySettings();
            case "phone" -> inbox();
            case "samplePhone" -> sampleInbox();
            case "cloud", "fileServer" -> backups();
            case "fileServerEdit" -> fileServerBack.run();
            case "backupForm", "backupReview", "companion" -> backupBack.run();
            default -> inbox();
        }
    }

    private void open(Intent intent) {
        if (DecoyTile.END.equals(intent.getAction())) { if (Privacy.decoyMode(prefs)) { inbox(); endDecoyMode(); return; } }
        if (QUICK_HIDE.equals(intent.getAction())) {
            // From the app icon's Quick hide shortcut: switch to decoy mode straight away.
            if (Privacy.vaultExists(prefs)) { startDecoyMode(); return; }
        }
        String number = intent.getStringExtra("address");
        if (intent.getData() != null && Intent.ACTION_SENDTO.equals(intent.getAction())) {
            number = intent.getData().getSchemeSpecificPart().split("\\?", 2)[0];
        }
        if (number != null && number.startsWith("mms:") && hasRole()) { chat(number, MessageProtocol.MMS); return; }
        if (number != null && !number.isBlank() && hasRole()) {
            String body = intent.getStringExtra("sms_body");
            if (Intent.ACTION_SENDTO.equals(intent.getAction()) && (number.contains(",") || number.contains(";"))) {
                openRecipients(number, body == null ? "" : body);
            } else {
                boolean mms = intent.getData() != null && List.of("mms", "mmsto").contains(intent.getData().getScheme());
                chat(number, mms ? MessageProtocol.MMS : MessageProtocol.SMS);
                if (body != null) conversationScreen.setDraft(body);
            }
        } else if ("phone".equals(prefs.getString("lastTab", "")) && hasRole()) phone();
        else inbox();
    }

    private boolean hasRole() { return getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_SMS); }
    private int accent() { return MessageStyle.controls(prefs); }
    private int canvas() { return MessageStyle.canvas(prefs); }
    private MessageStyle.UiColors uiColors() { return MessageStyle.uiColors(prefs); }
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
        TextView label = text(value, size, uiColors().ink());
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setMinHeight(dp(44));
        int fill = primary ? MessageStyle.filledControl(accent()) : uiColors().raised();
        tintButton(view, fill, primary ? MessageStyle.filledControlInk(accent()) : MessageStyle.textOn(fill));
        view.setPadding(dp(12), dp(6), dp(12), dp(6));
        view.setOnClickListener(ignored -> action.run());
        return view;
    }
    private void tintButton(Button view, int fill, int textColor) {
        view.setTextColor(textColor);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x22000000 | (textColor & 0xffffff)), shape(fill, 22), null));
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
        recipientPicker = null;
        boolean fullWidth = switch (screen) {
            case "inbox", "chat", "sample", "sampleChat", "phone", "samplePhone" -> true;
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
            controller.setSystemBarsAppearance(MessageStyle.textOn(background) == Color.BLACK ? bars : 0, bars);
        }
    }
    private java.util.function.IntConsumer heading(String label, String action, Runnable run) {
        LinearLayout bar = row();
        TextView heading = title(label, 29);
        Button control = button(action, false, run);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(control, new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(bar);
        return background -> {
            heading.setTextColor(MessageStyle.textOn(background));
            int fill = uiColors().raised();
            tintButton(control, fill, MessageStyle.textOn(fill));
        };
    }
    private EditText field(String hint) {
        var palette = uiColors();
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setTextColor(palette.ink());
        view.setHintTextColor(palette.muted());
        view.setHighlightColor(0x55000000 | (palette.action() & 0xffffff));
        var cursor = view.getTextCursorDrawable();
        if (cursor != null) {
            cursor.mutate().setTint(palette.action());
            view.setTextCursorDrawable(cursor);
        }
        view.setTextSize(16);
        view.setPadding(dp(13), dp(9), dp(13), dp(9));
        view.setBackground(shape(palette.surface(), 22));
        return view;
    }
    private AlertDialog.Builder dialogBuilder() {
        int style = MessageStyle.textOn(uiColors().raised()) == Color.WHITE
            ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
        return new AlertDialog.Builder(this, style);
    }
    private AlertDialog showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.show();
        var palette = uiColors();
        var window = dialog.getWindow();
        if (window != null) {
            var background = window.getDecorView().getBackground();
            if (background != null) background.mutate().setTint(palette.raised());
        }
        for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            var action = dialog.getButton(button);
            if (action != null) action.setTextColor(palette.action());
        }
        return dialog;
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
            () -> finishSetup(false), () -> finishSetup(true), () -> finishSetup(false), this::recolorWindow,
            () -> accounts(() -> onboarding(step)), () -> companion(() -> onboarding(step), true),
            () -> openWebsite(ReleaseUpdates.REPOSITORY + "#lan-web-companion"), () -> backupsFrom(() -> onboarding(step)));
    }

    private void finishSetup(boolean demo) {
        boolean replay = prefs.getBoolean("onboarded", false);
        prefs.edit().putBoolean("onboarded", true).apply();
        if (demo) sampleInbox();
        else if (replay) inbox();
        else open(getIntent());
    }

    static final String HIDDEN_FOLDER = "Private folder";

    static final String QUICK_HIDE_LABEL = "Quick hide (decoy mode)";

    private void chooseFolder(String folder) {
        if (folder.equals(QUICK_HIDE_LABEL)) { startDecoyMode(); return; }
        // Leaving the open private folder asks for the passcode again, then locks it.
        if (this.folder.equals(HIDDEN_FOLDER) && !folder.equals(HIDDEN_FOLDER) && Privacy.vaultOpen() && closeNeedsCode()) {
            confirmPrivate("Close the private folder", () -> {
                Privacy.closeVault();
                invalidateRows();
                emptyHidden = false;
                this.folder = folder;
                inbox();
            });
            return;
        }
        if (!folder.equals(HIDDEN_FOLDER)) emptyHidden = false;
        if (folder.equals(HIDDEN_FOLDER) && !Privacy.vaultOpen()) {
            openHiddenChats(() -> { this.folder = HIDDEN_FOLDER; inbox(); });
            return;
        }
        this.folder = folder;
        inbox();
    }

    /** The folders in the inbox filter menu. Hidden chats shows only when set up, shown in Settings, and not in decoy mode. */
    private List<String> inboxFolders() {
        // Every inbox has Hidden chats in this menu, set up or not, so its presence gives nothing away.
        var folders = new ArrayList<>(List.of("Inbox", "Archived", "Blocked", HIDDEN_FOLDER));
        if (Privacy.vaultExists(prefs) && !Privacy.decoyMode(prefs)) folders.add(QUICK_HIDE_LABEL);
        return folders;
    }

    /** True while the Hidden chats folder is showing empty after a passcode that didn't open anything. */
    private boolean emptyHidden;

    /**
     * Opens hidden chats with a fingerprint, face, or screen lock when that's on; otherwise with the passcode. A wrong
     * passcode opens an empty folder rather than an error, so nobody can tell whether anything is hidden.
     */
    private void openHiddenChats(Runnable opened) {
        if (!Privacy.vaultExists(prefs)) {
            showDialog(dialogBuilder().setTitle("Private folder")
                .setMessage("Move private chats here so they never show in your inbox. Only you can open this folder, with your "
                    + "fingerprint or a passcode.")
                .setNegativeButton("Not now", null).setPositiveButton("Set up", (dialog, which) -> hiddenChatsSettings()));
            return;
        }
        Runnable withPasscode = () -> SecurityDialogs.passcodeEntry(this, prefs, "Private folder", code -> new Thread(() -> {
            if (distress(code)) return;
            boolean match = Privacy.openVault(prefs, code);
            runOnUiThread(() -> {
                invalidateRows();
                if (match) { emptyHidden = false; opened.run(); }
                else { emptyHidden = true; folder = HIDDEN_FOLDER; inbox(); }
            });
        }, "fluid-hidden").start());
        if (!Privacy.deviceUnlockOn(prefs)) { withPasscode.run(); return; }
        deviceAuth("Open the private folder", () -> {
            if (Privacy.openVaultWithDevice(prefs)) { invalidateRows(); opened.run(); }
            else withPasscode.run();
        }, withPasscode);
    }

    private boolean closeNeedsCode() { return prefs.getBoolean("privateCloseCode", true); }

    /** Asks for the private folder passcode (or fingerprint, when that's on) and runs ok only if it matches. */
    private void confirmPrivate(String title, Runnable ok) {
        Runnable withPasscode = () -> SecurityDialogs.passcodeEntry(this, prefs, title, code -> new Thread(() -> {
            if (distress(code)) return;
            boolean match = Privacy.checkVaultCode(prefs, code);
            runOnUiThread(() -> { if (match) ok.run(); else toast("That passcode doesn't match"); });
        }, "fluid-private-check").start());
        if (!Privacy.deviceUnlockOn(prefs)) { withPasscode.run(); return; }
        deviceAuth(title, ok, withPasscode);
    }

    /** Ends decoy mode after the private folder passcode, leaving the private folder itself closed. */
    private void endDecoyMode() {
        if (!Privacy.decoyMode(prefs)) return;
        confirmPrivate("Show your real inbox", () -> {
            Privacy.setDecoyMode(prefs, false);
            Privacy.closeVault();
            invalidateRows();
            folder = "Inbox";
            DecoyTile.refresh(this);
            inbox();
            toast("Decoy mode is off");
        });
    }

    /** Asks for the fingerprint, face, or screen lock, then runs ok; failed runs when it's cancelled or unavailable. */
    private void deviceAuth(String title, Runnable ok, Runnable failed) {
        var prompt = new android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(title)
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build();
        prompt.authenticate(new CancellationSignal(), getMainExecutor(), new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) { ok.run(); }
            @Override public void onAuthenticationError(int code, CharSequence message) { if (failed != null) failed.run(); }
        });
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
            selected -> { sampleFolder = selected; sampleInbox(); }, this::inbox, this::requestSmsRole, this::openSampleThread, ignored -> {},
            name -> contactDetails("demo:" + name, name, this::sampleInbox), this::confirmSampleReset, this::sampleCalls, List.of("Inbox", "Archived", "Blocked"));
        fillSampleInbox();
    }

    private void fillSampleInbox() {
        var search = new MessageSearch(sampleSearch);
        List<ThreadRow> threads = new ArrayList<>();
        for (int index = 0; index < SAMPLES.length; index++) {
            String[] contact = SAMPLES[index];
            String folder = index == 2 ? "Archived" : index == 3 ? "Blocked" : "Inbox";
            if (!sampleFolder.equals(folder)) continue;
            List<ChatLine> lines = sampleLines(contact, sampleReplies.getOrDefault(contact[0], List.of()));
            if (!search.matches(contact[0]) && lines.stream().noneMatch(line ->
                    search.matches(contact[0], line.getText(), Objects.toString(line.getAuthor(), "")))) continue;
            ChatLine latest = null;
            for (ChatLine line : lines) if (line.getReactionTarget() == null) latest = line;
            String preview = latest == null ? "" : latest.getMedia() == null || latest.getText().equals(latest.getMedia().getCaption())
                ? latest.getText() : latest.getMedia().getKind().getLabel() + " · " + latest.getText();
            if ("Group".equals(contact[5]) && latest != null && latest.getAuthor() != null) {
                preview = latest.getAuthor().split(" ", 2)[0] + ": " + preview;
            }
            threads.add(new ThreadRow(contact[0], contact[0], contact[1],
                preview, sampleReplies.getOrDefault(contact[0], List.of()).isEmpty() || latest == null
                    ? withTime(contact[4], sampleTime(contact)) : latest.getTime(),
                (contact == SAMPLES[0] || "Group".equals(contact[5])) && !readSamples.contains(contact[0])
                    && sampleReplies.getOrDefault(contact[0], List.of()).isEmpty(), false,
                samplePhoto(contact[0]), null, 0, sampleFormats(contact).stream().map(format -> format.label)
                    .collect(java.util.stream.Collectors.joining(" · ")),
                sampleSender(contact[0]) == SenderStyle.AUTOMATED, sampleGroupMembers(contact[0])));
        }
        inboxScreen.setThreads(threads);
    }

    private void confirmSampleReset() {
        showDialog(dialogBuilder().setTitle("Reset the sample inbox?")
            .setMessage("Your practice replies, reactions, and deleted sample messages are cleared, and every sample "
                + "conversation is unread again. Your real messages and settings aren't touched.")
            .setPositiveButton("Reset", (dialog, which) -> {
                sampleReplies.clear();
                hiddenSampleMessages.clear();
                readSamples.clear();
                sampleSearch = "";
                sampleFolder = "Inbox";
                sampleInbox();
            })
            .setNegativeButton("Cancel", null));
    }

    private void openSampleThread(String name) {
        readSamples.add(name);
        for (String[] contact : SAMPLES) if (contact[0].equals(name)) {
            sampleChat(contact);
            return;
        }
    }

    private List<MessageProtocol> sampleFormats(String[] contact) {
        if (contact[0].equals("Maya Chen")) return List.of(MessageProtocol.RCS,
            MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL);
        return List.of("Group".equals(contact[5]) && sampleSender(contact[0]) == SenderStyle.RCS
            ? MessageProtocol.RCS : MessageProtocol.parse(contact[5]));
    }

    private void sampleChat(String[] contact) {
        var routes = sampleFormats(contact);
        sampleChat(contact, MessageProtocol.preferred(prefs, "demo:" + contact[0], routes));
    }

    private void sampleChat(String[] contact, MessageProtocol format) {
        render("sampleChat");
        List<ChatLine> replies = sampleReplies.computeIfAbsent(contact[0], ignored -> new ArrayList<>());
        String key = "demo:" + contact[0];
        ChatProfile profile = ChatProfile.read(prefs, key);
        var formats = sampleFormats(contact);
        boolean combined = prefs.getBoolean("combineProtocols", false) && formats.size() > 1;
        var routing = new ChatRouting(format, formats, combined, next -> sampleChat(contact, next), next -> {
            prefs.edit().putString("protocolRoute." + key, next.name()).apply();
            sampleChat(contact, next.family() == format.family() ? format : next);
        }, "", false, savedRoute(key));
        activeProtocol = format;
        conversationScreen = new ConversationScreen(root, prefs, profile, contact[0], contact[1], contact[5], true,
            sampleSender(contact[0]),
            null, this::sampleInbox, () -> contactDetails(key, contact[0], () -> sampleChat(contact, format)), null, null,
            this::forwardSample, line -> confirmDelete(() -> {
                hiddenSampleMessages.computeIfAbsent(key, ignored -> new HashSet<>()).add(line.getId());
                showSampleLines(contact, replies);
            }), null, sampleMembers(contact),
            (text, author, relation, attachment, via) -> addSampleMessage(contact, replies, author,
                new OutgoingMessage(key, List.of(), text, attachment, -1, relation), via),
            samplePhoto(contact[0]), null, routing, sampleGroupMembers(contact[0]));
        showSampleLines(contact, replies);
        animateIn(root.getChildAt(0), 10);
    }

    private MessageProtocol savedRoute(String conversation) {
        String saved = prefs.getString("protocolRoute." + conversation, "");
        return saved.isEmpty() ? null : MessageProtocol.parse(saved);
    }

    private boolean addSampleMessage(String[] contact, List<ChatLine> replies, String author, OutgoingMessage message,
                                     MessageProtocol via) {
        // Sample attachments stay on this device and in memory only.
        var attachment = message.getAttachment();
        Uri photo = message.getPhoto();
        var other = attachment != null && attachment.isFile() && photo == null ? attachment : null;
        // Samples use the same fallback text as live chats, so quotes and reactions render identically.
        String body = message.fallbackBody();
        if (body.isBlank()) return false;
        var relation = message.getRelation();
        String target = relation != null && relation.getKind() == RelationKind.REACTION ? relation.getTargetId() : null;
        String id = "reply-" + replies.size();
        replies.add(new ChatLine(id, body, "", author == null, "",
            DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()), author, target, photo, via, other));
        conversationScreen.setAnimatedId(id);
        showSampleLines(contact, replies);
        if (author == null) feedback(root);
        else MessageFeedback.play(this, prefs, true);
        return true;
    }

    private void forwardSample(String message, Attachment attachment) {
        String[] names = Arrays.stream(SAMPLES).map(contact -> contact[0]).toArray(String[]::new);
        showDialog(dialogBuilder().setTitle("Forward to a sample conversation")
            .setItems(names, (dialog, which) -> {
                sampleChat(SAMPLES[which]);
                conversationScreen.setDraft(message);
                conversationScreen.setDraftAttachment(attachment);
            }).setNegativeButton("Cancel", null));
    }

    private void confirmDelete(Runnable delete) {
        showDialog(dialogBuilder().setTitle("Delete this message?")
            .setMessage("This removes the message from this device.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete for me", (dialog, which) -> delete.run()));
    }

    private String sampleTime(String[] contact) {
        return contact == SAMPLES[0] ? "9:41 AM" : contact == SAMPLES[1] ? "2:00 PM" : "6:26 PM";
    }

    private String withTime(String date, String clock) {
        return prefs.getBoolean("exactTimes", false) && !date.equals(clock) ? date + " " + clock : date;
    }

    private void showSampleLines(String[] contact, List<ChatLine> replies) {
        var formats = sampleFormats(contact);
        boolean combined = prefs.getBoolean("combineProtocols", false);
        var lines = sampleLines(contact, replies);
        conversationScreen.setLines(formats.size() <= 1 || combined ? lines : lines.stream().filter(line ->
            (line.getFormat() == null ? MessageProtocol.SMS : line.getFormat()).family() == activeProtocol.family()).toList());
    }

    private List<ChatLine> sampleLines(String[] contact, List<ChatLine> replies) {
        List<ChatLine> messages = new ArrayList<>();
        var members = sampleMembers(contact);
        messages.add(new ChatLine("incoming", contact[2], contact[4], false, "", sampleTime(contact), members.get(0)));
        if (members.size() > 1) {
            String second = switch (contact[0]) {
                case "Weekend Plans" -> "I’ll grab a picnic blanket and some lemonade.";
                case "Photo Walk" -> "The fog should lift around seven. Bring the small tripod.";
                default -> "I can meet at the trailhead with the work gloves.";
            };
            String third = switch (contact[0]) {
                case "Weekend Plans" -> "I booked the tables by the willow tree.";
                case "Photo Walk" -> "I’ll take the bus to the east entrance.";
                default -> "I updated the volunteer sign-in sheet.";
            };
            messages.add(new ChatLine("group-answer", second, "", false, "", "6:28 PM", members.get(1)));
            messages.add(new ChatLine("group-time", third, "", false, "", "6:31 PM", members.get(2)));
        }
        if (!contact[3].isEmpty()) {
            messages.add(new ChatLine("sent", contact[3], "", true, "", members.size() > 1 ? "6:34 PM" : "2:01 PM"));
        }
        if (contact[0].equals("#radio-club")) {
            // IRC samples show channel events and /me actions the way the IRC layout draws them.
            messages.add(0, new ChatLine("irc-join", "orbital joined", contact[4], false, "", "6:58 PM", null, null, null,
                MessageProtocol.IRC, null, true));
            messages.add(new ChatLine("irc-action", "/me tunes the dipole for 20 meters", "", false, "", "7:03 PM", "kestrel"));
        }
        if (contact[0].equals("Nora Patel")) {
            messages.add(new ChatLine("demo-quote", "Reply to “Did you want the window seat?”\n\nOnly if you don’t mind.", "", true, "", "2:04 PM"));
            messages.add(new ChatLine("demo-reaction", "Loved “Only if you don’t mind.”", "", false, "", "2:05 PM", contact[0], "demo-quote"));
            messages.add(new ChatLine("demo-forward", "Forwarded “Boarding starts at 4 PM.” by “Station Updates”", "", false, "", "2:09 PM", contact[0]));
        }
        if (contact[0].equals("Cameron Reed") || contact[0].equals("Photo Walk")) {
            boolean groupPhoto = contact[0].equals("Photo Walk");
            int drawable = groupPhoto ? R.drawable.sample_lakeside : R.drawable.sample_pottery;
            Uri photo = Uri.parse("android.resource://" + getPackageName() + "/" + drawable);
            messages.add(new ChatLine("demo-photo", groupPhoto ? "The lake just before sunset." :
                "Both mugs made it home in one piece!", "", false, "", "6:37 PM", "Cameron Reed", null, photo));
        }
        if (contact[0].equals("Maya Chen")) {
            messages.add(0, new ChatLine("maya-rcs", "I can send you the event poster here.", "Yesterday", false,
                "", "4:20 PM", contact[0], null, null, MessageProtocol.RCS));
            messages.add(0, new ChatLine("maya-matrix", "The library has a quiet room we can borrow.", "Yesterday", false,
                "", "2:40 PM", contact[0], null, null, MessageProtocol.MATRIX));
            messages.add(0, new ChatLine("maya-xmpp", "I’m on the earlier train this week.", "Yesterday", false,
                "", "1:12 PM", contact[0], null, null, MessageProtocol.XMPP));
            messages.add(0, new ChatLine("maya-email", "I sent the confirmation to the whole team.", "Monday", false,
                "", "5:10 PM", contact[0], null, null, MessageProtocol.EMAIL));
        }
        messages.addAll(replies);
        Set<String> hidden = hiddenSampleMessages.getOrDefault("demo:" + contact[0], Set.of());
        messages.removeIf(line -> hidden.contains(line.getId()));
        return messages;
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

    /**
     * Android 15 and later block the default SMS role for apps installed from a downloaded APK
     * ("App was denied access to default SMS app") until the user allows restricted settings.
     */
    private void smsRoleBlocked() {
        showDialog(dialogBuilder().setTitle("Couldn't become the default SMS app")
            .setMessage("If Android said FluidMessages was denied access, it's because the app was installed from a "
                + "downloaded file. To allow it:\n\n1. Open App info.\n2. Tap ⋮ in the top corner, then "
                + "Allow restricted settings, and confirm.\n3. Come back and tap Set as default again.\n\n"
                + "If you don't see Allow restricted settings, try setting the default once first, then check again.")
            .setPositiveButton("Open App info", (dialog, which) -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException error) { toast("Open Settings → Apps → FluidMessages"); }
            })
            .setNegativeButton("Not now", null));
    }

    /** The emergency erase passcode: typed anywhere a passcode is asked for, it erases everything at once. */
    private boolean distress(String code) {
        if (!Privacy.hasCode(prefs, DISTRESS) || !Privacy.checkCode(prefs, DISTRESS, code)) return false;
        Resets.eraseEverything(this);
        return true;
    }

    static final String DISTRESS = "distress";

    private void inbox() {
        if (folder.equals(HIDDEN_FOLDER) && !Privacy.vaultOpen() && !emptyHidden) folder = "Inbox";
        render("inbox");
        prefs.edit().putString("lastTab", "messages").apply();
        // An internet account alone is enough to start chats, even without the SMS role.
        boolean connected = hasRole() || GatewayClient.hasRoutes(prefs) || !reachableProtocols(true).isEmpty();
        inboxScreen = new InboxScreen(root, prefs, false, connected, folder, filter,
            query -> { filter = query; scheduleInboxSearch(); }, this::settings, this::chooseFolder,
            connected ? this::compose : this::sampleInbox, this::requestSmsRole, this::openThread,
            key -> conversationMenu(threadAddress(key)), key -> contactDetails(threadAddress(key), threadAddress(key), this::inbox), null,
            connected ? this::phone : null, inboxFolders());
        if (Privacy.decoyMode(prefs)) inboxScreen.setTitleHold(this::endDecoyMode);
        if (connected) {
            inboxScreen.setResync(this::resync);
            seedInbox(inboxScreen);
            fillInbox();
        }
        // Ask once, for people who made FluidMessages their SMS app before group MMS used it.
        if (hasRole() && !prefs.getBoolean("askedPhoneNumbers", false)
                && checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("askedPhoneNumbers", true).apply();
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_NUMBERS}, 24);
        }
    }

    private String pendingCall;

    /** The Phone tab: favorites, recent calls, contacts, and the keypad, in the same window as Messages. */
    private void phone() {
        render("phone");
        prefs.edit().putString("lastTab", "phone").apply();
        List<String> restrict = null;
        List<String> decoyNames = new ArrayList<>();
        if (Privacy.decoyMode(prefs)) {
            restrict = new ArrayList<>(Privacy.decoyAllowed(prefs));
            for (var row : Privacy.decoys(prefs)) {
                String name = row.optString("address").substring(Privacy.DECOY.length());
                if (!decoyNames.contains(name)) decoyNames.add(name);
            }
        }
        PhoneTab.mount(root, prefs, new PhoneActions() {
            @Override public void messages() { inbox(); }
            @Override public void call(String number) { placeCall(number); }
            @Override public void internetCall(String address, String title, boolean video, String protocol) {
                startCall(address, title, video, protocol);
            }
            @Override public void openChat(String address, String protocol) { chat(address, MessageProtocol.parse(protocol)); }
            @Override public void callUsername(String id, boolean video) { MainActivity.this.callUsername(id, video); }
            @Override public void meeting(List<String> addresses, String title, boolean video) { startMeeting(addresses, title, video); }
            @Override public void allowCallHistory() { requestPermissions(new String[]{Manifest.permission.READ_CALL_LOG}, CALL_LOG_PERMISSION); }
            @Override public void allowContacts() { requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CALL_LOG_PERMISSION); }
            @Override public String preview(String address) {
                try {
                    for (JSONObject row : messageRows()) {
                        String other = row.optString("address");
                        boolean match = other.equals(address) || SmsStore.validRecipient(other) && SmsStore.validRecipient(address)
                            && PhoneNumberUtils.compare(other, address);
                        if (!match) continue;
                        var format = MessageProtocol.parse(row.optString("protocol"));
                        return Privacy.locked(prefs, address, format) ? "Locked" : row.optString("body");
                    }
                } catch (Exception ignored) {}
                return "";
            }
        }, false, restrict, decoyNames);
    }

    /** The sample inbox's Phone tab: fictional people and calls on several protocols. Nothing is dialed. */
    private void sampleCalls() {
        render("samplePhone");
        PhoneTab.mount(root, prefs, new PhoneActions() {
            @Override public void messages() { sampleInbox(); }
            // Sample calls open the real call screen as a pretend call: nothing is dialed, sent, or recorded.
            @Override public void call(String number) {
                String name = PhoneTab.sampleName(number);
                startActivity(CallHub.demo(MainActivity.this, "demo:" + name, name, false, "Phone", Encryption.NONE));
            }
            @Override public void internetCall(String address, String title, boolean video, String protocol) {
                startActivity(CallHub.demo(MainActivity.this, address.startsWith("demo:") ? address : "demo:" + title, title, video, protocol,
                    "SIP".equals(protocol) ? Encryption.IN_TRANSIT : Encryption.END_TO_END));
            }
            @Override public void openChat(String address, String protocol) {
                String name = address.startsWith("demo:") ? address.substring(5) : address;
                for (String[] contact : SAMPLES) {
                    if (contact[0].equals(name) || samplePhone(contact[0]).equals(address)) {
                        var format = MessageProtocol.parse(protocol);
                        var formats = sampleFormats(contact);
                        readSamples.add(contact[0]);
                        sampleChat(contact, formats.contains(format) ? format : formats.get(0));
                        return;
                    }
                }
                toast("Sample contact: there's no sample chat with them");
            }
            @Override public void callUsername(String id, boolean video) {
                startActivity(CallHub.demo(MainActivity.this, "demo:" + id, id, video, "Matrix", Encryption.END_TO_END));
            }
            @Override public void meeting(List<String> addresses, String title, boolean video) {
                startActivity(CallHub.demo(MainActivity.this, "demo:" + title, title, video, "Meeting link", Encryption.IN_TRANSIT));
            }
            @Override public void allowCallHistory() {}
            @Override public void allowContacts() {}
            @Override public String preview(String address) {
                String name = address.startsWith("demo:") ? address.substring(5) : address;
                for (String[] contact : SAMPLES) if (contact[0].equals(name) || samplePhone(contact[0]).equals(address)) return contact[3];
                return "";
            }
        }, true);
    }

    private String meetingServer() { return prefs.getString("meetingServer", "meet.jit.si"); }

    private void startMeeting(String key, boolean video) { startMeeting(List.of(key), threadTitle(key), video); }

    /**
     * Starts a Jitsi Meet call: you choose which chat gets the link (any way you can message them), or not to send
     * it at all, and then the meeting opens.
     */
    private void startMeeting(List<String> addresses, String name, boolean video) {
        if (callBlocked(Calling.Method.MEETING)) return;
        String room = "FluidMessages-" + java.util.HexFormat.of().formatHex(Privacy.randomBytes(8));
        String link = "https://" + meetingServer() + "/" + room;
        record Route(String address, MessageProtocol format) {}
        List<Route> routes = new ArrayList<>();
        List<String[]> items = new ArrayList<>();
        for (String address : addresses) for (var format : formats(address)) {
            routes.add(new Route(address, format));
            String target = address;
            for (var item : GatewayClient.links(prefs, address)) {
                if (MessageProtocol.parse(item.optString("protocol")) == format) target = item.optString("target", address);
            }
            items.add(new String[]{"Send with " + format.label, target});
        }
        items.add(new String[]{"Copy the link instead", "Paste it wherever you like"});
        items.add(new String[]{"Don't send the link", "Just open the meeting"});
        Runnable open = () -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(video ? link : link + "#config.startWithVideoMuted=true"))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
            } catch (ActivityNotFoundException error) { toast("No browser is available to join the call"); }
        };
        SecurityDialogs.chooseOne(this, prefs, (video ? "Video meeting with " : "Meeting with ") + name,
            "They join by tapping the link, in a browser or the Jitsi Meet app.", items, index -> {
                if (index < routes.size()) {
                    var route = routes.get(index);
                    sendRouted(route.format(), new OutgoingMessage(route.address(), route.address().startsWith("mms:")
                        ? groupNumbers(route.address()) : List.of(route.address()),
                        MeetingKt.meetingText(video, link), null, prefs.getInt("sim", -1), null));
                } else if (index == routes.size()) {
                    getSystemService(android.content.ClipboardManager.class)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Meeting link", link));
                    toast("Meeting link copied");
                }
                open.run();
            });
    }

    private boolean meetingLinks() { return Calling.allowed(prefs, Calling.Method.MEETING); }

    /** Settings → Accounts and calls → Calls, kept short: ways to call, SIP when it's on, recording, and recent calls. */
    private void callsSettings() {
        render("calls");
        heading("Calls", "Back", this::settings);
        List<SettingsAction> allowed = new ArrayList<>();
        for (var method : Calling.Method.values()) {
            boolean on = Calling.allowed(prefs, method);
            allowed.add(toggleRow(method.label, method.detail, on, () -> {
                Calling.set(prefs, method, !on);
                DirectProtocols.restart(this);
                SipAgent.sync(this);
                callsSettings();
            }));
        }
        List<SettingsSection> sections = new ArrayList<>();
        sections.add(new SettingsSection("Ways to call · separate from messaging", allowed));
        if (Calling.allowed(prefs, Calling.Method.SIP)) {
            var sip = Calling.sipAccount(prefs);
            List<SettingsAction> rows = new ArrayList<>();
            rows.add(action("SIP account", sip == null ? "Not set up · your SIP provider gives you the details"
                : sip.optString("uri").replaceFirst("^sip:", ""), this::editSipAccount));
            if (sip != null) rows.add(action("Receive SIP calls", receiveLabel() + " · " + SipAgent.status(), this::chooseSipReceive));
            sections.add(new SettingsSection("SIP", rows));
        }
        List<SettingsAction> more = new ArrayList<>();
        if (meetingLinks()) more.add(action("Meeting server", meetingServer(), this::chooseMeetingServer));
        more.add(action("Call recording", CallRecordings.allowed(prefs) ? "On · where recordings are saved" : "Off", this::recordingSettings));
        boolean phoneCalls = prefs.getBoolean("showPhoneCalls", true);
        more.add(toggleRow("Show regular phone calls in Recents", "Next to internet calls in the Phone tab", phoneCalls, () -> {
            prefs.edit().putBoolean("showPhoneCalls", !phoneCalls).apply();
            callsSettings();
        }));
        sections.add(new SettingsSection("More", more));
        SettingsPages.mount(root, prefs, sections);
    }

    private String receiveLabel() {
        return switch (SipAgent.mode(prefs)) {
            case SipAgent.OFF -> "Off";
            case SipAgent.ALWAYS -> "Always";
            default -> "While FluidMessages is open";
        };
    }

    private void chooseSipReceive() {
        String[] modes = {SipAgent.OFF, SipAgent.OPEN, SipAgent.ALWAYS};
        String[] labels = {"Off · you can still make SIP calls", "While FluidMessages is open",
            "Always · uses a little more battery and shows an ongoing notification"};
        showDialog(dialogBuilder().setTitle("Receive SIP calls").setItems(labels, (dialog, which) -> {
            prefs.edit().putString("sip.receive", modes[which]).apply();
            DirectProtocols.restart(this);
            SipAgent.sync(this);
            callsSettings();
        }));
    }

    private void recordingSettings() {
        render("recording");
        heading("Call recording", "Back", this::callsSettings);
        boolean recording = CallRecordings.allowed(prefs);
        List<SettingsAction> record = new ArrayList<>();
        record.add(toggleRow("Allow recording calls", "Adds a Record button on Matrix, XMPP, and SIP calls. Android doesn't let "
            + "apps record regular phone calls.", recording, () -> {
                prefs.edit().putBoolean("recordCalls", !recording).apply();
                recordingSettings();
            }));
        List<SettingsSection> sections = new ArrayList<>();
        sections.add(new SettingsSection("Recording", record));
        if (recording) {
            boolean announce = prefs.getBoolean("announceRecording", true);
            List<SettingsAction> saving = new ArrayList<>();
            saving.add(toggleRow("Tell them when you record", "Sends “I started recording this call” in the chat (Matrix and XMPP)",
                announce, () -> { prefs.edit().putBoolean("announceRecording", !announce).apply(); recordingSettings(); }));
            for (boolean video : new boolean[]{false, true}) {
                saving.add(action(video ? "Save video calls to" : "Save voice calls to",
                    CallRecordings.describe(prefs, video), () -> chooseRecordingPlace(video)));
            }
            int kept = CallRecordings.inApp(this).size();
            if (kept > 0) saving.add(action("Recordings kept in FluidMessages", kept + (kept == 1 ? " recording" : " recordings"),
                this::recordingsPage));
            sections.add(new SettingsSection("Saving", saving));
            sections.add(new SettingsSection("Be fair", List.of(new SettingsAction("Ask first",
                "Let everyone on the call know before you record. In some places it's against the law not to.", () -> {}, true, null))));
        }
        SettingsPages.mount(root, prefs, sections);
    }

    private static final int RECORDING_FOLDER = 43;
    private boolean pickingVideoFolder;

    /** Where recordings go: the phone's standard folder, only inside FluidMessages, or any folder you pick. */
    private void chooseRecordingPlace(boolean video) {
        String folder = video ? "Movies" : "Recordings";
        String current = CallRecordings.destination(prefs, video);
        List<String[]> items = List.of(
            new String[]{(CallRecordings.DEVICE.equals(current) ? "✓ " : "") + "Your phone's " + folder + " folder",
                folder + "/FluidMessages · other apps like Files and Gallery can open them"},
            new String[]{(CallRecordings.APP.equals(current) ? "✓ " : "") + "Only inside FluidMessages",
                "Private to this app · find them in Recordings kept in FluidMessages"},
            new String[]{(current.startsWith(CallRecordings.TREE) ? "✓ " : "") + "Choose a folder…",
                current.startsWith(CallRecordings.TREE) ? "Now: " + CallRecordings.describe(prefs, video)
                    : "Any folder, including an SD card or a folder your cloud app syncs"});
        SecurityDialogs.chooseOne(this, prefs, video ? "Save video calls to" : "Save voice calls to", "", items, index -> {
            if (index == 2) {
                pickingVideoFolder = video;
                try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), RECORDING_FOLDER); }
                catch (ActivityNotFoundException error) { toast("No file manager is available to choose a folder"); }
                return;
            }
            prefs.edit().putString(video ? "recordings.video" : "recordings.audio", index == 0 ? CallRecordings.DEVICE : CallRecordings.APP).apply();
            recordingSettings();
        });
    }

    /** Recordings kept only inside FluidMessages: tap to play, share, or delete. */
    private void recordingsPage() {
        render("recordings");
        heading("Call recordings", "Back", this::recordingSettings);
        List<SettingsAction> rows = new ArrayList<>();
        for (var file : CallRecordings.inApp(this)) {
            String size = android.text.format.Formatter.formatShortFileSize(this, file.length());
            rows.add(action(file.getName().replaceFirst("\\.webm$", ""), size + " · tap to play, share, or delete", () ->
                showDialog(dialogBuilder().setTitle(file.getName()).setItems(new String[]{"Play", "Share", "Delete"}, (dialog, which) -> {
                    var uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".backup-files", file);
                    String type = file.getName().startsWith("Video") ? "video/webm" : "audio/webm";
                    try {
                        if (which == 0) startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                        else if (which == 1) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(type)
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share recording"));
                        else showDialog(dialogBuilder().setTitle("Delete this recording?").setMessage("It can't be recovered.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (inner, button) -> {
                                if (!file.delete()) toast("Couldn't delete it");
                                recordingsPage();
                            }));
                    } catch (ActivityNotFoundException error) { toast("No app on this phone can open it"); }
                }))));
        }
        if (rows.isEmpty()) rows.add(new SettingsAction("No recordings", "", () -> {}, true, null));
        SettingsPages.mount(root, prefs, List.of(new SettingsSection("Kept inside FluidMessages", rows)));
    }

    /** The SIP account: from your provider, including a secure WebSocket address for calling from the app. */
    private void editSipAccount() {
        var current = Calling.sipAccount(prefs);
        String[][] fields = {{"uri", "SIP address", "name@sip.example.com"}, {"password", "Password", ""},
            {"ws", "WebSocket address", "wss://sip.example.com:7443"}, {"user", "Username (if different)", ""},
            {"domain", "Domain for calling numbers (optional)", "sip.example.com"}, {"display", "Your name (optional)", ""}};
        List<String[]> form = new ArrayList<>();
        for (String[] field : fields) form.add(new String[]{field[1], field[0].equals("password") || current == null ? "" : current.optString(field[0]),
            field[0].equals("password") && current != null ? "Leave blank to keep" : field[2], field[0].equals("password") ? "password"
            : field[0].equals("uri") || field[0].equals("ws") ? "uri" : "text"});
        SecurityDialogs.form(this, prefs, "SIP account", "Your SIP provider gives you these. Calls from the app need a provider that "
            + "accepts secure WebSocket connections (wss://).", form, "Save", current != null ? "Remove" : null, values -> {
            try {
                var account = new JSONObject();
                for (int i = 0; i < fields.length; i++) account.put(fields[i][0], values[i].trim());
                if (account.optString("password").isEmpty() && current != null) account.put("password", current.optString("password"));
                String uri = account.optString("uri");
                if (!uri.startsWith("sip:")) uri = "sip:" + uri;
                account.put("uri", uri);
                if (!uri.matches("sip:[^@\\s]+@[A-Za-z0-9.-]+(:[0-9]+)?") || !account.optString("ws").matches("wss://\\S+")) {
                    return "Enter a SIP address like name@sip.example.com and a wss:// address.";
                }
                if (account.optString("domain").isEmpty()) account.put("domain", uri.substring(uri.indexOf('@') + 1));
                Calling.saveSipAccount(prefs, account);
                callsSettings();
                return null;
            } catch (Exception error) { return "Couldn't save the SIP account."; }
        }, () -> { Calling.saveSipAccount(prefs, null); callsSettings(); });
    }

    private void chooseMeetingServer() {
        SecurityDialogs.form(this, prefs, "Meeting links", "Meeting-link calls use Jitsi Meet, which works in any browser and the free "
            + "Jitsi app. Use meet.jit.si, or your own Jitsi server. The meeting server can see who joins.",
            List.<String[]>of(new String[]{"Meeting server", meetingServer(), "meet.jit.si", "uri"}), "Save", null, values -> {
                String value = values[0].trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
                if (!value.matches("[A-Za-z0-9.-]{3,253}(:[0-9]{1,5})?")) return "Enter a server name like meet.jit.si.";
                prefs.edit().putString("meetingServer", value).apply();
                callsSettings();
                return null;
            }, null);
    }

    /** A username from the keypad: @name:server is Matrix, sip:name@host is SIP, and name@server is XMPP. */
    private void callUsername(String id, boolean video) {
        Calling.Method method = id.startsWith("sip:") ? Calling.Method.SIP : id.startsWith("@") ? Calling.Method.MATRIX : Calling.Method.XMPP;
        if (callBlocked(method)) return;
        if (id.startsWith("sip:")) { startCall(id, id.substring(4), false, "SIP"); return; }
        if (id.startsWith("@")) { callMatrixUser(id, video); return; }
        if (!DirectProtocols.on(prefs, MessageProtocol.XMPP)) {
            toast("XMPP calls need XMPP connected from this phone, in Settings → Accounts and calls → Accounts");
            return;
        }
        new Thread(() -> {
            try {
                var route = DirectProtocols.routeForTarget(this, MessageProtocol.XMPP, id.toLowerCase(java.util.Locale.ROOT));
                String address = route != null ? route.getString("address") : "xmpp-" + Integer.toHexString(id.toLowerCase(java.util.Locale.ROOT).hashCode());
                if (route == null) DirectProtocols.link(this, address, MessageProtocol.XMPP, id, id, false);
                runOnUiThread(() -> startCall(address, id, video, "XMPP"));
            } catch (Exception error) { runOnUiThread(() -> toast(error.getMessage() == null ? "Couldn't reach " + id : error.getMessage())); }
        }, "fluid-call-xmpp").start();
    }

    /** Calls a Matrix username from the keypad, linking a chat with them first when there isn't one yet. */
    private void callMatrixUser(String id, boolean video) {
        if (!DirectProtocols.on(prefs, MessageProtocol.MATRIX) && !GatewayClient.hasAccount(prefs, MessageProtocol.MATRIX)) {
            toast("Add a Matrix account in Settings → Accounts first");
            return;
        }
        toast("Connecting to " + id + "…");
        new Thread(() -> {
            String address = null;
            for (var route : GatewayClient.allRoutes(prefs)) {
                if (MessageProtocol.parse(route.optString("protocol")) == MessageProtocol.MATRIX
                        && (id.equalsIgnoreCase(route.optString("target")) || id.equalsIgnoreCase(route.optString("title")))) {
                    address = route.optString("address");
                }
            }
            try {
                if (address == null) {
                    address = "mx-" + Integer.toHexString(id.toLowerCase(java.util.Locale.ROOT).hashCode());
                    if (DirectProtocols.on(prefs, MessageProtocol.MATRIX)) DirectProtocols.link(this, address, MessageProtocol.MATRIX, id, id, false);
                    else {
                        GatewayClient.link(prefs, address, MessageProtocol.MATRIX, id, id, false);
                        GatewayClient.refresh(this, prefs);
                    }
                }
                String target = address;
                runOnUiThread(() -> startActivity(CallHub.screen(this, target, id, video, null)));
            } catch (Exception error) {
                runOnUiThread(() -> toast(error.getMessage() == null ? "Couldn't reach " + id : error.getMessage()));
            }
        }, "fluid-call-user").start();
    }

    /** Starts an internet call. For SIP, target is a phone number or SIP address dialed through your provider. */
    private void startCall(String target, String title, boolean video, String protocol) {
        if (callBlocked(Calling.method(protocol))) return;
        if ("SIP".equals(protocol)) {
            if (!Calling.sipReady(prefs)) { toast("Add a SIP account in Settings → Accounts and calls → Calls"); return; }
            startActivity(CallHub.screen(this, target, title, false, null, "SIP").putExtra("sipTarget", Calling.sipTarget(prefs, target)));
            return;
        }
        startActivity(CallHub.screen(this, target, title, video, null, protocol));
    }

    /** Says why a call can't start: every way to call is off, or just this one. */
    private boolean callBlocked(Calling.Method method) {
        if (Calling.allowed(prefs, method)) return false;
        if (!Calling.anyAllowed(prefs)) toast(Calling.CALLS_OFF);
        else toast(method.label + " calls are turned off in Settings → Calls");
        return true;
    }

    private void readOnlyNotice() {
        showDialog(dialogBuilder().setTitle("Sending is off")
            .setMessage("Every way to message is turned off, so FluidMessages is read-only: you can read your chats, but nothing is sent. "
                + "Turn a way to message back on in Settings → Ways to message.")
            .setNegativeButton("Close", null).setPositiveButton("Open settings", (dialog, which) -> protocolSettings()));
    }

    /** Places a phone-network call through Android's call screen, or opens the dialer if calling isn't allowed. */
    private void placeCall(String number) {
        if (number == null || number.isBlank()) return;
        if (!Calling.allowed(prefs, Calling.Method.PHONE)) {
            // Phone calls are turned off: use SIP for the number when it's set up.
            if (Calling.sipReady(prefs)) startCall(number, threadTitle(number), false, "SIP");
            else toast(Calling.anyAllowed(prefs) ? "Phone calls are turned off in Settings → Calls" : Calling.CALLS_OFF);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCall = number;
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, CALL_PERMISSION);
            return;
        }
        try { startActivity(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))); }
        catch (RuntimeException error) { dialContact(number); }
    }

    private static final int CALL_PERMISSION = 25;
    private static final int CALL_LOG_PERMISSION = 26;

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
    /** The ⋯ menu on an inbox row: the everyday actions first, then privacy, then the full details. */
    private void conversationMenu(String number) {
        List<String> labels = new ArrayList<>();
        List<Runnable> runs = new ArrayList<>();
        java.util.function.BiConsumer<String, Runnable> add = (label, run) -> { labels.add(label); runs.add(run); };
        String name = threadTitle(number);
        add.accept(list("pinned").contains(number) ? "Unpin" : "Pin", () -> toggle("pinned", number));
        add.accept(list("muted").contains(number) ? "Unmute" : "Mute", () -> toggle("muted", number));
        add.accept(list("archived").contains(number) ? "Unarchive" : "Archive", () -> toggle("archived", number));
        add.accept(list("blocked").contains(number) ? "Unblock" : "Block", () -> toggle("blocked", number));
        if (!number.startsWith(Privacy.DECOY) && !Privacy.decoyMode(prefs)) {
            if (Privacy.vaultExists(prefs)) {
                boolean hidden = HiddenChats.addressHidden(prefs, number);
                add.accept(hidden ? "Move out of private folder" : "Move to private folder", () -> setHidden(number, name, !hidden, this::inbox));
            }
            String scope = Privacy.chatScope(number);
            add.accept(Privacy.hasCode(prefs, scope) ? "Change chat lock" : "Lock this chat", () -> chooseCode(scope, name, this::inbox));
            add.accept("Disappearing messages · " + Disappearing.label(Disappearing.seconds(prefs, number)),
                () -> SecurityDialogs.disappearing(this, prefs, "Disappearing messages", prefs.contains("disappear." + number)
                    ? Disappearing.seconds(prefs, number) : -1, Disappearing.label(Disappearing.globalSeconds(prefs)), seconds -> {
                        Disappearing.set(prefs, number, seconds);
                        SyncJob.reschedule(this);
                        invalidateRows();
                        inbox();
                    }));
        }
        add.accept("Conversation details", () -> contactDetails(number, number, this::inbox));
        showDialog(dialogBuilder().setTitle(name).setItems(labels.toArray(new String[0]), (dialog, index) -> runs.get(index).run()));
    }

    /** Moves a chat into or out of hidden chats. Moving in takes its texts out of Android's shared message store. */
    private void setHidden(String key, String name, boolean hide, Runnable done) {
        if (!hide) {
            new Thread(() -> {
                try {
                    if (Privacy.vaultOpen()) HiddenChats.restore(this, key);
                    Privacy.setHidden(prefs, "hidden.addresses", key, false);
                    invalidateRows();
                    runOnUiThread(() -> { toast(name + " is back in the inbox"); done.run(); });
                } catch (Exception error) { runOnUiThread(() -> toast(error.getMessage())); }
            }, "fluid-unhide").start();
            return;
        }
        showDialog(dialogBuilder().setTitle("Move " + name + " to the private folder?")
            .setMessage("It leaves your inbox and is only in the private folder, which opens with your fingerprint or passcode. "
                + "Its texts are moved into FluidMessages' encrypted storage, so other apps and FluidMessages Web won't show them. "
                + "New messages from them alert without showing who they're from.")
            .setNegativeButton("Cancel", null).setPositiveButton("Move", (dialog, which) -> new Thread(() -> {
                Privacy.setHidden(prefs, "hidden.addresses", key, true);
                HiddenChats.sweep(this);
                invalidateRows();
                runOnUiThread(() -> { toast(name + " moved to the private folder"); done.run(); });
            }, "fluid-hide").start()));
    }

    private boolean inboxLoading, inboxAgain;
    private final Runnable inboxRefresh = this::loadInbox;

    /**
     * Refreshes the inbox. Bursts of changes (a sync, an MMS download) collapse into one load, and a load that's
     * running finishes and shows its results before the next starts, so the list always fills.
     */
    private void fillInbox() {
        handler.removeCallbacks(inboxRefresh);
        handler.postDelayed(inboxRefresh, inboxScreen != null && filledInbox != inboxScreen ? 0 : 90);
    }

    /** The inbox screen that has shown a full load, rather than only the remembered rows. */
    private InboxScreen filledInbox;

    /**
     * Shows the inbox as it last looked straight away, while the full list loads behind it. Rows are checked against
     * the current hidden, archived, blocked, locked, and disappearing state first. From memory this is ready for the
     * first frame; after a cold start it's read from disk off the main thread.
     */
    private void seedInbox(InboxScreen target) {
        String key = InboxSnapshot.key(prefs, folder, filter);
        if (key == null) return;
        int revision = contacts.revision();
        if (InboxSnapshot.inMemory(key)) {
            var rows = InboxSnapshot.recall(this, prefs, key, revision, this::inboxDate);
            if (!rows.isEmpty()) target.setThreads(rows);
            return;
        }
        inboxWorker.submit(() -> {
            var rows = InboxSnapshot.recall(getApplicationContext(), prefs, key, revision, this::inboxDate);
            if (rows.isEmpty()) return;
            runOnUiThread(() -> {
                if (inboxScreen == target && filledInbox != target && target.getThreads().isEmpty() && !isDestroyed()) {
                    target.setThreads(rows);
                }
            });
        });
    }

    private void loadInbox() {
        if (inboxLoading) { inboxAgain = true; return; }
        var target = inboxScreen;
        if (target == null || (!hasRole() && !GatewayClient.hasRoutes(prefs) && reachableProtocols(true).isEmpty())) return;
        inboxLoading = true;
        inboxAgain = false;
        int version = ++inboxVersion;
        var query = new MessageSearch(filter);
        String selectedFolder = folder;
        int contactRevision = contacts.revision();
        String typed = filter == null ? "" : filter.trim();
        inboxLoad = inboxWorker.submit(() -> {
            try { buildInbox(target, version, query, selectedFolder, contactRevision, typed); }
            finally {
                runOnUiThread(() -> {
                    inboxLoading = false;
                    if (inboxAgain && page.equals("inbox")) fillInbox();
                });
            }
        });
    }

    private void buildInbox(InboxScreen target, int version, MessageSearch query, String selectedFolder, int contactRevision,
                            String typed) {
            try {
                // Typing the hidden-chats code into search opens them until the app leaves the screen.
                if (typed.length() >= 6 && distress(typed)) return;
                if (!Privacy.vaultOpen() && typed.length() >= 4 && Privacy.vaultExists(prefs) && Privacy.openVault(prefs, typed)) {
                    invalidateRows();
                    // The passcode also ends decoy mode.
                    Privacy.setDecoyMode(prefs, false);
                    runOnUiThread(() -> {
                        filter = "";
                        folder = HIDDEN_FOLDER;
                        toast("The private folder is open until you leave the app");
                        inbox();
                    });
                    return;
                }
                var data = messageRows();
                Set<String> pinned = list("pinned"), archived = list("archived"), blocked = list("blocked");
                Map<String, JSONObject> threads = new LinkedHashMap<>();
                Map<String, String> identities = new HashMap<>();
                for (JSONObject message : data) {
                    if (Thread.currentThread().isInterrupted()) return;
                    String number = identities.computeIfAbsent(message.optString("address"), raw -> rowAddress(message));
                    var format = MessageProtocol.parse(message.optString("protocol"));
                    String thread = prefs.getBoolean("combineProtocols", false) ? number : number + "\u001f" + format.family().name();
                    // Hidden chats only ever show in their own folder, never mixed into the inbox.
                    boolean hiddenChat = Privacy.vaultOpen() && HiddenChats.rowHidden(prefs, message);
                    boolean visible = switch (selectedFolder) {
                        case HIDDEN_FOLDER -> hiddenChat;
                        case "Archived" -> !hiddenChat && archived.contains(number) && !blocked.contains(number);
                        case "Blocked" -> !hiddenChat && blocked.contains(number);
                        default -> !hiddenChat && !archived.contains(number) && !blocked.contains(number);
                    };
                    if (!visible || threads.containsKey(thread)) continue;
                    boolean codeLocked = Privacy.locked(prefs, number, format);
                    if (query.empty() || query.matches(number, threadTitle(number), codeLocked ? "" : message.getString("body"))) {
                        threads.put(thread, message);
                    }
                }
                var numbers = new ArrayList<>(threads.keySet());
                numbers.sort(Comparator.comparing(number -> !pinned.contains(threadAddress(number))));
                List<ThreadRow> entries = new ArrayList<>();
                List<InboxSnapshot.Entry> remembered = new ArrayList<>();
                for (String thread : numbers) {
                    if (Thread.currentThread().isInterrupted()) return;
                    JSONObject message = threads.get(thread);
                    String number = threadAddress(thread);
                    var contact = SmsStore.validRecipient(number) ? contacts.find(number) : null;
                    String name = threadTitle(number);
                    var format = MessageProtocol.parse(message.optString("protocol"));
                    String label = prefs.getBoolean("combineProtocols", false) ? formats(number).stream()
                        .map(item -> item.label).collect(java.util.stream.Collectors.joining(" · ")) : format.label;
                    String rowKey = prefs.getBoolean("combineProtocols", false) ? number + "\u001f" + format.family().name() : thread;
                    String meetingLine = MeetingKt.meetingPreview(message.getString("body"));
                    if (meetingLine == null) meetingLine = RichCardsKt.cardPreview(message.getString("body"));
                    String preview = Privacy.locked(prefs, number, format) ? "Locked · enter the passcode to read"
                        : meetingLine != null ? meetingLine : message.getString("body");
                    var row = new ThreadRow(rowKey, name, initials(name), preview,
                        inboxDate(displayedTimestamp(message)), message.optInt("read", 1) == 0,
                        pinned.contains(number), 0, contact == null ? null : contact.photo(), contactRevision, label,
                        (format == MessageProtocol.SMS || format == MessageProtocol.MMS) && !number.startsWith("mms:")
                            && SenderStyle.infer(number) == SenderStyle.AUTOMATED,
                        number.startsWith("mms:") ? groupMembers(number) : GatewayClient.members(prefs, number));
                    entries.add(row);
                    remembered.add(new InboxSnapshot.Entry(row, number, message.optString("protocol"), displayedTimestamp(message),
                        Disappearing.expiry(prefs, message, message.optString("address"))));
                }
                if (!Thread.currentThread().isInterrupted()) runOnUiThread(() -> {
                    if (inboxScreen == target && version == inboxVersion && !isDestroyed()) {
                        filledInbox = target;
                        target.setError(null);
                        target.setThreads(entries);
                    }
                });
                // The next time the inbox opens, this shows at once while the full list loads.
                if (!Thread.currentThread().isInterrupted()) {
                    InboxSnapshot.remember(getApplicationContext(), InboxSnapshot.key(prefs, selectedFolder, typed), remembered);
                }
            } catch (Exception error) {
                if (!Thread.currentThread().isInterrupted()) runOnUiThread(() -> {
                    if (inboxScreen == target && version == inboxVersion && !isDestroyed()) {
                        filledInbox = target;
                        target.setThreads(List.of());
                        target.setError("Unable to load the Android SMS inbox");
                    }
                });
            }
    }

    private static String threadAddress(String key) { return key.split("\u001f", 2)[0]; }

    private String rowAddress(JSONObject row) {
        return GatewayClient.identity(prefs, row.optString("address"), contacts);
    }

    private void invalidateRows() {
        synchronized (rowsLock) {
            rowsCache = null;
            rowsGeneration++;
        }
    }

    /** Every text and protocol message, newest first. Shared read-only between the inbox and conversations. */
    private List<JSONObject> messageRows() throws Exception {
        int generation, revision = ProtocolStore.revision.get();
        synchronized (rowsLock) {
            if (rowsCache != null && rowsRevision == revision && android.os.SystemClock.elapsedRealtime() - rowsAt < 30_000) {
                return rowsCache;
            }
            generation = rowsGeneration;
        }
        var store = ProtocolStore.get(this);
        var rows = new ArrayList<>(store.read());
        if (hasRole()) {
            var texts = SmsStore.read(this, 0);
            for (int i = 0; i < texts.length(); i++) rows.add(texts.getJSONObject(i));
        }
        if (Privacy.decoyMode(prefs)) {
            // Decoy mode: only the chats you allowed, plus your decoy chats. Everything else stays out of sight.
            var allowed = Privacy.decoyAllowed(prefs);
            rows.removeIf(row -> allowed.stream().noneMatch(address -> sameAddress(address, row.optString("address"))));
            rows.addAll(Privacy.decoys(prefs));
        } else if (Privacy.vaultOpen()) rows.addAll(store.readHidden());
        else if (Privacy.vaultExists(prefs)) rows.removeIf(row -> HiddenChats.rowHidden(prefs, row));
        // Encrypted texts are stored scrambled; show what they say, and leave out anything whose time is up.
        long now = System.currentTimeMillis();
        long[] due = {0};
        rows.removeIf(row -> {
            SmsCrypto.reveal(prefs, row);
            long at = Disappearing.expiry(prefs, row, row.optString("address"));
            if (at > now) due[0] = Disappearing.earliest(due[0], at);
            return at > 0 && at <= now;
        });
        // The next one to go leaves the screen at its time, not at the next refresh.
        expireAt(due[0]);
        rows.sort(Comparator.comparingLong((JSONObject row) -> row.optLong("date")).reversed());
        var result = java.util.Collections.unmodifiableList(rows);
        synchronized (rowsLock) {
            if (generation == rowsGeneration) {
                rowsCache = result;
                rowsRevision = revision;
                rowsAt = android.os.SystemClock.elapsedRealtime();
            }
        }
        return result;
    }

    static boolean sameAddress(String one, String two) {
        return one.equals(two) || SmsStore.validRecipient(one) && SmsStore.validRecipient(two) && PhoneNumberUtils.compare(one, two);
    }

    private String threadTitle(String number) {
        if (number.startsWith(Privacy.DECOY)) return number.substring(Privacy.DECOY.length());
        if (number.startsWith("mms:")) {
            var members = groupMembers(number);
            return members.isEmpty() ? "MMS group" : members.stream().map(GroupMember::getName)
                .collect(java.util.stream.Collectors.joining(", "));
        }
        String name = GatewayClient.title(prefs, number);
        if (!name.isEmpty()) return name;
        var contact = SmsStore.validRecipient(number) ? contacts.find(number) : null;
        return contact == null ? number : contact.name();
    }

    private List<GroupMember> sampleGroupMembers(String name) {
        var members = SAMPLE_GROUPS.get(name);
        return members == null ? List.of() : members.stream().map(member ->
            new GroupMember(member, "", null, () -> contactDetails("demo:" + member, member,
                () -> contactDetails("demo:" + name, name, () -> openSampleThread(name))), samplePhoto(member))).toList();
    }

    private List<String> groupNumbers(String key) {
        var draft = unsentGroups.get(key);
        if (draft != null) return draft;
        return key.startsWith("mms:") ? List.of(key.substring(4).split(",")) : List.of();
    }

    private List<GroupMember> groupMembers(String key) {
        var channel = GatewayClient.channel(prefs, key);
        if (channel != null) {
            // IRC channels list nicknames from the live member list on the companion.
            var nicks = channel.optJSONArray("members");
            var result = new ArrayList<GroupMember>();
            for (int i = 0; nicks != null && i < nicks.length() && i < 500; i++) result.add(new GroupMember(nicks.optString(i)));
            return result;
        }
        var users = groupNumbers(key);
        if (users.isEmpty()) return GatewayClient.members(prefs, key);
        return users.stream().map(number -> {
            var match = contacts.find(number);
            return new GroupMember(match == null ? number : match.name(), number, match == null ? null : match.photo(),
                () -> contactDetails(number, number, () -> contactDetails(key, key, () -> chat(key, MessageProtocol.MMS))));
        }).toList();
    }

    private List<MessageProtocol> formats(String key) {
        var routes = new ArrayList<>(GatewayClient.formats(prefs, key));
        if (hasRole()) {
            if (key.startsWith("mms:")) routes.add(MessageProtocol.MMS);
            // One text route per person: SMS upgrades itself to MMS for photos and files.
            else if (SmsStore.validRecipient(key)) routes.add(MessageProtocol.SMS);
        }
        routes.sort(Comparator.naturalOrder());
        return routes.stream().distinct().toList();
    }

    private void openThread(String key) {
        var parts = key.split("\u001f", 2);
        var routes = formats(parts[0]);
        if (routes.isEmpty() && parts.length > 1) routes = List.of(MessageProtocol.parse(parts[1]));
        if (routes.isEmpty()) { toast("This conversation has no message route"); return; }
        var requested = parts.length > 1 && (prefs.getBoolean("combineProtocols", false)
                ? prefs.getString("protocolRoute." + parts[0], "").isEmpty() : true)
            ? MessageProtocol.concrete(routes, MessageProtocol.parse(parts[1])) : MessageProtocol.preferred(prefs, parts[0], routes);
        chat(parts[0], requested);
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
        if (!MessageProtocol.anyEnabled(prefs)) { readOnlyNotice(); return; }
        openRecipientPicker(body, recipients -> composeRecipients(recipients, body));
    }

    private void openRecipientPicker(String body, java.util.function.Consumer<List<String>> proceed) {
        render("recipients");
        heading(body.isEmpty() ? "New message" : "Forward message", "Cancel", this::inbox);
        recipientPicker = new RecipientPicker(root, prefs,
            () -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, PICKER_CONTACT_PERMISSION),
            contacts::key, recipients -> {
                getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(root.getWindowToken(), 0);
                proceed.accept(recipients);
            }, reachableProtocols(true), reachableProtocols(false), () -> accounts(() -> openRecipientPicker(body, proceed)));
        loadRecipients();
    }

    /**
     * Internet protocols that can start a new chat (an account on the companion or this phone), or, with
     * ready=false, ones that are turned on but still need an account. Turned-off protocols are never offered.
     */
    private Set<MessageProtocol> reachableProtocols(boolean ready) {
        var result = new LinkedHashSet<MessageProtocol>();
        for (var format : List.of(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC)) {
            if (!format.enabled(prefs)) continue;
            boolean has = DirectProtocols.on(prefs, format) || GatewayClient.hasAccount(prefs, format);
            if (has == ready) result.add(format);
        }
        return result;
    }

    /** Opens (or creates) the conversation for a typed internet address, then hands it a draft. */
    private void openAddress(String encoded, String draft) {
        int split = encoded.indexOf(':');
        var format = MessageProtocol.parse(encoded.substring(0, split));
        String target = encoded.substring(split + 1);
        var existing = GatewayClient.routeForTarget(prefs, format, target);
        if (existing != null) {
            chat(existing, format);
            conversationScreen.setDraft(draft);
            return;
        }
        String address = "chat-" + Integer.toHexString((format.name() + target.toLowerCase(Locale.ROOT)).hashCode());
        new Thread(() -> {
            try {
                if (DirectProtocols.on(prefs, format)) DirectProtocols.link(this, address, format, target, target, false);
                else {
                    GatewayClient.link(prefs, address, format, target, target, false);
                    GatewayClient.refresh(this, prefs);
                }
                runOnUiThread(() -> {
                    chat(address, format);
                    conversationScreen.setDraft(draft);
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast(error.getMessage()));
            }
        }, "fluid-new-address").start();
    }

    private void loadRecipients() {
        var picker = recipientPicker;
        if (picker == null) return;
        picker.setContactsAllowed(contacts.allowed());
        new Thread(() -> {
            var rows = contacts.recipients();
            if (Privacy.decoyMode(prefs)) {
                // Decoy mode shows only the people you chose to keep visible.
                var allowed = Privacy.decoyAllowed(prefs);
                rows = rows.stream().filter(row -> allowed.stream().anyMatch(address -> sameAddress(address, row.getAddress()))).toList();
            }
            var shown = rows;
            runOnUiThread(() -> {
                if (recipientPicker == picker && !isDestroyed()) {
                    picker.setRecipients(shown);
                    picker.setLoading(false);
                }
            });
        }, "fluid-recipients").start();
    }

    private void openRecipients(String addresses, String body) {
        try { composeRecipients(SmsStore.recipients(addresses), body); }
        catch (IllegalArgumentException error) { inbox(); toast(error.getMessage()); }
    }

    private void composeRecipients(List<String> recipients, String draft) {
        var internet = recipients.stream().filter(recipient -> recipient.matches("(matrix|xmpp|email|irc):.+")).toList();
        if (!internet.isEmpty()) {
            // Groups live on one protocol; internet groups are linked from a room or channel instead.
            if (recipients.size() > 1) { toast("Start internet chats one at a time. To message a group, link its room or channel."); return; }
            openAddress(internet.get(0), draft);
            return;
        }
        if (recipients.size() == 1) {
            chat(recipients.get(0));
            conversationScreen.setDraft(draft);
            return;
        }
        var choice = GroupRouting.pick(prefs, recipients, this::formats);
        if (choice.getProtocol() == MessageProtocol.MMS) {
            String group = MmsTransport.thread(this, recipients);
            unsentGroups.put(group, recipients);
            chat(group, MessageProtocol.MMS);
            conversationScreen.setDraft(draft);
            return;
        }
        // Without a shared group protocol, phone numbers still get the same text one by one.
        if (!MessageProtocol.SMS.enabled(prefs) || !recipients.stream().allMatch(SmsStore::validRecipient)) {
            toast(choice.getReason());
            return;
        }
        toast(choice.getReason());
        render("groupSms");
        heading("Multiple recipients", "Inbox", this::inbox);
        var labels = recipients.stream().map(recipient -> {
            var contact = contacts.find(recipient);
            return contact == null ? recipient : contact.name();
        }).toList();
        GroupSmsComposer.mount(root, prefs, labels, draft, () -> simLabel(), this::chooseSim, (body, result) -> {
            int sim = prefs.getInt("sim", -1);
            new Thread(() -> {
                int submitted = 0;
                for (String recipient : recipients) {
                    try {
                        MessageProtocol.SMS.transport().send(this, new OutgoingMessage(recipient, List.of(recipient), body, null, sim));
                        submitted++;
                    } catch (Exception ignored) {}
                }
                int count = submitted;
                runOnUiThread(() -> {
                    result.accept(count);
                    if (count > 0) sync(false);
                });
            }, "fluid-sms-recipients").start();
        });
    }
    private void chat(String number) {
        var routes = formats(number);
        chat(number, routes.isEmpty() ? MessageProtocol.SMS : MessageProtocol.preferred(prefs, number, routes));
    }

    private void chat(String number, MessageProtocol format) {
        var scopes = Privacy.lockedScopes(prefs, number, format);
        if (!scopes.isEmpty()) {
            askCode(scopes.get(0), () -> chat(number, format));
            return;
        }
        address = number;
        activeProtocol = format;
        render("chat");
        ChatProfile profile = ChatProfile.read(prefs, number);
        boolean phone = format == MessageProtocol.SMS || format == MessageProtocol.MMS;
        var match = SmsStore.validRecipient(number) ? contacts.find(number) : null;
        String name = threadTitle(number);
        var routes = new ArrayList<>(formats(number));
        if (!routes.contains(format)) routes.add(format);
        var routing = new ChatRouting(format, routes, prefs.getBoolean("combineProtocols", false) && routes.size() > 1,
            next -> {
                String draft = conversationScreen.getDraft();
                chat(number, next);
                conversationScreen.setDraft(draft);
            }, next -> {
                prefs.edit().putString("protocolRoute." + number, next.name()).apply();
                chat(number, next.family() == format.family() ? format : next);
            }, format.transport().unavailable(this, prefs, number), format.transport().getCapabilities().getAttachments()
                || (format == MessageProtocol.SMS && MessageProtocol.MMS.enabled(prefs)), savedRoute(number),
            protection(number, format));
        conversationScreen = new ConversationScreen(root, prefs, profile, name, initials(name), format.label, false,
            phone && !number.startsWith("mms:") ? SenderStyle.infer(number) : SenderStyle.SMS,
            phone ? simLabel() : null, this::inbox, () -> contactDetails(number, number, () -> chat(number, format)),
            () -> conversationMenu(number), phone ? this::chooseSim : null, this::forwardLive,
            line -> confirmDelete(() -> {
                try {
                    deleteLine(line.getId(), line.getFormat(), number);
                    invalidateRows();
                    fillChat();
                } catch (Exception error) { toast("Could not delete the message"); }
            }), null, List.of(), (text, author, relation, photo, via) -> sendRouted(via, new OutgoingMessage(number,
                number.startsWith("mms:") ? groupNumbers(number) : List.of(number), text, photo, prefs.getInt("sim", -1), relation)),
            0, match == null ? null : match.photo(), routing, groupMembers(number), this::downloadAttachment, line -> {
                try {
                    deleteLine(line.getId(), line.getFormat(), number);
                    invalidateRows();
                    fillChat();
                } catch (Exception ignored) {}
            });
        conversationScreen.setOpenGifSettings(this::gifSettings);
        fillChat();
        animateIn(root.getChildAt(0), 10);
    }

    private void deleteLine(String id, MessageProtocol format, String number) throws Exception {
        if (id.startsWith("hsms-")) ProtocolStore.get(this).removeHidden(id);
        else if (id.startsWith("decoy-")) {
            var rows = Privacy.decoys(prefs);
            rows.removeIf(row -> row.optString("id").equals(id));
            Privacy.saveDecoys(prefs, rows);
        }
        else if (format == MessageProtocol.SMS) SmsStore.delete(this, Long.parseLong(id), number);
        else ProtocolStore.get(this).remove(id);
        // A deleted message is never shown again, not even as the remembered preview while the inbox reloads.
        InboxSnapshot.clear(this);
    }

    /** Asks for a chat's or protocol's code, then continues. A wrong code keeps the dialog open. */
    private void askCode(String scope, Runnable opened) {
        String title = scope.startsWith("protocol:") ? "Enter the " + MessageProtocol.parse(scope.substring(9)).label + " code"
            : "Enter this chat's passcode";
        SecurityDialogs.askCode(this, prefs, title, code -> !distress(code) && Privacy.checkCode(prefs, scope, code),
            () -> { invalidateRows(); opened.run(); });
    }

    /** Every live send goes through the protocol's shared transport, including replies and reactions. */
    private boolean sendRouted(MessageProtocol format, OutgoingMessage outgoing) {
        if (outgoing.getConversation().startsWith(Privacy.DECOY)) {
            // Made-up conversations never send; the reply is only added on this phone.
            try {
                Privacy.addDecoy(prefs, outgoing.getConversation().substring(Privacy.DECOY.length()), outgoing.getText(), true,
                    System.currentTimeMillis());
            } catch (Exception error) { toast("Could not add the message"); return false; }
            invalidateRows();
            sent();
            return true;
        }
        var message = outgoing.forTransport(format);
        var transport = format.transport();
        String reason = transport.unavailable(this, prefs, message.getConversation());
        if (!reason.isEmpty()) { toast(reason); return false; }
        var current = conversationScreen;
        new Thread(() -> {
            try {
                transport.send(this, message);
                runOnUiThread(() -> { if (conversationScreen == current) sent(); });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (conversationScreen == current) {
                        if (message.getRelation() == null || message.getRelation().getKind() == RelationKind.REPLY) {
                            current.setDraft(message.getText());
                            current.setDraftAttachment(message.getAttachment());
                        }
                        current.setError(error.getMessage() == null ? "Could not send " + format.label : error.getMessage());
                    }
                });
            }
        }, "fluid-send-" + format.name()).start();
        return true;
    }

    /** Fetches a received attachment the person tapped, then shows it in place. */
    private void downloadAttachment(ChatLine line) {
        new Thread(() -> {
            try {
                var message = ProtocolStore.get(this).find(line.getId());
                if (message == null) return;
                GatewayClient.download(this, message);
                runOnUiThread(this::fillChat);
            } catch (Exception error) {
                runOnUiThread(() -> toast(error.getMessage() == null ? "Could not download the attachment" : error.getMessage()));
            }
        }, "fluid-download").start();
    }

    private void sent() {
        feedback(root);
        animateNextMessage = true;
        fillChat();
        sync(false);
    }

    private void forwardLive(String body, Attachment attachment) {
        if (attachment == null) { compose(body); return; }
        openRecipientPicker(body, recipients -> {
            if (recipients.size() == 1) chat(recipients.get(0));
            else {
                String group = MmsTransport.thread(this, recipients);
                unsentGroups.put(group, recipients);
                chat(group, MessageProtocol.MMS);
            }
            conversationScreen.setDraft(body);
            conversationScreen.setDraftAttachment(attachment);
        });
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
        showDialog(dialogBuilder().setTitle("Send with").setItems(labels.toArray(new String[0]), (dialog, index) -> {
            prefs.edit().putInt("sim", ids.get(index)).apply();
            if (conversationScreen != null) conversationScreen.setSim(simLabel());
        }));
    }
    private boolean animateNextMessage;
    private void fillChat() {
        var screen = conversationScreen;
        if (screen == null) return;
        // Reading and formatting runs off the main thread; only the newest request updates the screen.
        if (chatLoad != null) chatLoad.cancel(true);
        int version = ++chatVersion;
        String chatAddress = address;
        var chatProtocol = activeProtocol;
        boolean combined = prefs.getBoolean("combineProtocols", false);
        boolean looking = resumed;
        chatLoad = chatWorker.submit(() -> {
            try {
                var all = messageRows();
                DateFormat day = DateFormat.getDateInstance(DateFormat.MEDIUM);
                DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
                var store = ProtocolStore.get(this);
                List<ChatLine> entries = new ArrayList<>();
                List<JSONObject> unread = new ArrayList<>();
                for (int i = all.size() - 1; i >= 0; i--) {
                    if (Thread.currentThread().isInterrupted()) return;
                    JSONObject entry = all.get(i);
                    if (!chatAddress.equals(rowAddress(entry))) continue;
                    var format = MessageProtocol.parse(entry.optString("protocol"));
                    if (!combined && chatProtocol.family() != format.family()) continue;
                    int kind = entry.getInt("type");
                    if (kind == Telephony.Sms.MESSAGE_TYPE_INBOX && entry.optInt("read", 1) == 0) unread.add(entry);
                    long date = displayedTimestamp(entry);
                    String status = entry.optString("status", switch (kind) {
                        case Telephony.Sms.MESSAGE_TYPE_FAILED -> "Couldn’t send";
                        case Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Sending…";
                        case Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Queued · sends when you have signal";
                        default -> "";
                    });
                    String author = entry.optString("author");
                    if (SmsStore.validRecipient(author)) {
                        var match = contacts.find(author);
                        if (match != null) author = match.name();
                    }
                    entries.add(new ChatLine(entry.getString("id"), entry.getString("body"),
                        messageDay(day, date), kind != Telephony.Sms.MESSAGE_TYPE_INBOX, status, time.format(new Date(date)),
                        author.isBlank() ? null : author, entry.optString("reaction_target").isEmpty() ? null
                            : entry.optString("reaction_target"), store.photo(entry), format,
                        Attachment.fromJson(entry.optJSONObject("attachment"), store.file(entry)),
                        entry.optBoolean("event"), entry.optBoolean("encrypted_text")));
                }
                // Seeing the conversation reads it: clear the unread dot here, in Android's inbox, and on the companion.
                if (looking && !unread.isEmpty()) markRead(chatAddress, unread);
                if (!Thread.currentThread().isInterrupted()) runOnUiThread(() -> {
                    if (conversationScreen != screen || version != chatVersion || isDestroyed()) return;
                    if (animateNextMessage && !entries.isEmpty()) screen.setAnimatedId(entries.get(entries.size() - 1).getId());
                    animateNextMessage = false;
                    screen.setError(null);
                    screen.setLines(entries);
                });
            } catch (Exception error) {
                if (!Thread.currentThread().isInterrupted()) runOnUiThread(() -> {
                    if (conversationScreen == screen && version == chatVersion && !isDestroyed()) {
                        screen.setError("Unable to open conversation");
                    }
                });
            }
        });
    }

    /** Off the main thread. Marks received texts and protocol messages read and clears their notification. */
    private void markRead(String chatAddress, List<JSONObject> rows) {
        var texts = new ArrayList<String>();
        var companion = new ArrayList<String>();
        var store = ProtocolStore.get(this);
        var notifications = getSystemService(android.app.NotificationManager.class);
        notifications.cancel(chatAddress.hashCode());
        for (JSONObject row : rows) {
            notifications.cancel(row.optString("address").hashCode());
            if (row.optBoolean("hidden")) {
                try { store.putHidden(new JSONObject(row.toString()).put("read", 1)); } catch (Exception ignored) {}
                continue;
            }
            if (row.has("protocol")) {
                try {
                    var stored = store.find(row.getString("id"));
                    if (stored == null) continue;
                    store.put(stored.put("read", 1));
                    if (!stored.optBoolean("direct") && !stored.getString("id").startsWith("mms-")) companion.add(stored.getString("id"));
                } catch (Exception ignored) {}
            } else {
                texts.add(String.valueOf(row.optLong("id")));
            }
        }
        if (!texts.isEmpty() && hasRole()) {
            var values = new android.content.ContentValues();
            values.put("read", 1);
            values.put("seen", 1);
            try {
                getContentResolver().update(Telephony.Sms.CONTENT_URI, values,
                    "_id IN (" + String.join(",", java.util.Collections.nCopies(texts.size(), "?")) + ")", texts.toArray(new String[0]));
            } catch (RuntimeException ignored) {}
        }
        invalidateRows();
        if (!companion.isEmpty()) GatewayClient.markRead(prefs, companion);
        runOnUiThread(() -> { if (page.equals("inbox")) fillInbox(); });
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

    private void linkAddress(String key, String name, Runnable done) {
        var formats = List.of(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC);
        String[] labels = formats.stream().map(format -> format.label).toArray(String[]::new);
        showDialog(dialogBuilder().setTitle("Link an address").setItems(labels, (dialog, which) -> {
            var format = formats.get(which);
            String hint = switch (format) {
                case MATRIX -> "@maya:example.org";
                case XMPP -> "maya@example.org or room@conference.example.org";
                case EMAIL -> "maya@example.org";
                default -> "nickname or #channel";
            };
            List<String[]> form = new ArrayList<>();
            form.add(new String[]{format.label + " address", "", hint, "email"});
            if (format == MessageProtocol.XMPP) form.add(new String[]{"This is a group chat", "false", "", "check"});
            SecurityDialogs.form(this, prefs, format.label + " for " + name, "", form, "Link", null, values -> {
                String target = values[0].trim();
                boolean group = values.length > 1 && values[1].equals("true");
                if (target.isEmpty()) return "Enter an address.";
                new Thread(() -> {
                    try {
                        if (DirectProtocols.on(prefs, format)) DirectProtocols.link(this, key, format, target, name, group);
                        else {
                            GatewayClient.link(prefs, key, format, target, name, group);
                            GatewayClient.refresh(this, prefs);
                        }
                        runOnUiThread(() -> { toast(format.label + " linked"); done.run(); });
                    } catch (Exception error) {
                        runOnUiThread(() -> toast(error.getMessage()));
                    }
                }, "fluid-link").start();
                return null;
            }, null);
        }).setNegativeButton("Cancel", null));
    }

    private void confirmUnlink(String key, MessageProtocol format, Runnable done) {
        showDialog(dialogBuilder().setTitle("Unlink " + format.label + "?")
            .setMessage("New " + format.label + " messages stop appearing here. Earlier messages stay.")
            .setNegativeButton("Cancel", null).setPositiveButton("Unlink", (dialog, which) -> new Thread(() -> {
                try {
                    if (DirectProtocols.route(prefs, key, format) != null) DirectProtocols.unlink(this, key, format);
                    else {
                        GatewayClient.unlink(prefs, key, format);
                        GatewayClient.refresh(this, prefs);
                    }
                    runOnUiThread(done);
                } catch (Exception error) { runOnUiThread(() -> toast(error.getMessage())); }
            }, "fluid-unlink").start()));
    }

    private void showContact(String key, String original, Runnable back) {
        boolean sample = key.startsWith("demo:");
        boolean phone = !sample && SmsStore.validRecipient(key);
        var contact = phone ? contacts.find(key) : null;
        String name = sample ? original : threadTitle(key);
        var members = sample ? sampleGroupMembers(original) : groupMembers(key);
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
        if (!sample) {
            if (!contacts.allowed()) actions.add(action("Allow contacts", "Show saved names and photos",
                () -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, PICKER_CONTACT_PERMISSION)));
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
        // Every chat's Security section says how it's protected, with the same colored badge as its header.
        List<SettingsAction> security = new ArrayList<>();
        var protection = protection(key, activeProtocol);
        security.add(new SettingsAction(SecurityUiKt.getShortLabel(protection), activeProtocol.label + " · " + switch (protection) {
            case FLUIDMESSAGES -> "Only people with your shared encryption key can read these messages.";
            case END_TO_END -> "Only you and the people in this chat can read it.";
            case COMPANION_E2EE -> "End-to-end encrypted. Your FluidMessages server holds the keys to show it in the web app.";
            case IN_TRANSIT -> "Protected on the way to the server, but the server can read it.";
            case PREVIEW -> "RCS isn't available in FluidMessages yet, so nothing is sent this way.";
            default -> "Your carrier and anyone along the way can read these messages.";
        }, () -> {}, true, protection));
        if (!sample && (CloudBackup.paired(this) || DirectProtocols.anyOn(prefs))) {
            // Linked addresses let one contact be reached on several protocols, in one chat when combined.
            for (var link : GatewayClient.links(prefs, key)) {
                var format = MessageProtocol.parse(link.optString("protocol"));
                actions.add(action(format.label + " · " + link.optString("target"), "Linked · tap to unlink",
                    () -> confirmUnlink(key, format, returnToDetails)));
            }
            actions.add(action("Link an address", "Reach " + name + " on Matrix, XMPP, Email, or IRC",
                () -> linkAddress(key, name, returnToDetails)));
        }
        if (!sample && key.startsWith(Privacy.DECOY)) {
            actions.add(action("Edit this decoy chat", "Only on this phone · never sent",
                () -> editDecoy(name, returnToDetails)));
        } else if (!sample) {
            groupActions(key, members, actions, returnToDetails);
            securityActions(key, name, security, returnToDetails);
        }
        refreshContact = returnToDetails;
        var channel = GatewayClient.channel(prefs, key);
        boolean irc = channel != null || sample && original.startsWith("#");
        String topic = channel == null ? (irc ? "Antenna tuning tonight at seven" : "") : channel.optString("topic");
        String subtitle = irc ? (topic.isEmpty() ? "IRC channel" : "Topic: " + topic) + " · " + (members.size() + (channel == null ? 1 : 0)) + " people"
            : members.isEmpty() ? (sample ? "Sample contact" : phone ? key : "Conversation")
            : (sample ? "Sample group · " : "") + (members.size() + 1) + " members including you";
        List<SettingsAction> info = new ArrayList<>();
        if (phone && contact != null) {
            for (var field : ContactFields.forNumber(this, key)) info.add(action(field.getLabel(), field.getValue(), () -> useContactField(field, name)));
        }
        ConversationDetails.mount(root, prefs, name, initials(name), subtitle, sample ? samplePhoto(name) : 0,
            contact == null ? null : contact.photo(), key, reachOptions(key, original, name, sample),
            phone ? () -> editContact(key) : null, actions, members, security, info);
    }

    /** What tapping a saved contact detail does: message or call a number, write an email, open a site or map. */
    private void useContactField(ContactField field, String name) {
        String value = field.getValue();
        switch (field.getKind()) {
            case PHONE -> {
                List<String[]> items = new ArrayList<>();
                List<Runnable> runs = new ArrayList<>();
                if (MessageProtocol.SMS.enabled(prefs) && hasRole()) { items.add(new String[]{"Message", "Text " + value}); runs.add(() -> chat(value, MessageProtocol.SMS)); }
                if (Calling.allowed(prefs, Calling.Method.PHONE) || Calling.sipReady(prefs)) { items.add(new String[]{"Call", value}); runs.add(() -> placeCall(value)); }
                items.add(new String[]{"Copy", "Copy the number"});
                runs.add(() -> copyToClipboard(field.getLabel(), value));
                SecurityDialogs.chooseOne(this, prefs, name + " · " + field.getLabel(), "", items, index -> runs.get(index).run());
            }
            case EMAIL -> openExternal(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(value))));
            case SIP -> startCall("sip:" + value.replaceFirst("^sip:", ""), name, false, "SIP");
            case WEBSITE -> openExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(value.matches("(?i)^https?://.*") ? value : "https://" + value)));
            case ADDRESS -> openExternal(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(value))));
            default -> copyToClipboard(field.getLabel(), value);
        }
    }

    private void openExternal(Intent intent) {
        try { startActivity(intent); } catch (android.content.ActivityNotFoundException error) { toast("No app on this phone can open that"); }
    }

    private void copyToClipboard(String label, String value) {
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText(label, value));
        toast("Copied");
    }

    private Runnable contactBack;
    private Runnable refreshContact;

    /** Every way to message, call, or video call this chat, for the buttons under its name. */
    private List<ReachOption> reachOptions(String key, String original, String name, boolean sample) {
        List<ReachOption> options = new ArrayList<>();
        if (sample) {
            for (String[] contact : SAMPLES) {
                if (!contact[0].equals(original)) continue;
                for (var format : sampleFormats(contact)) options.add(new ReachOption(ReachOption.MESSAGE, format.label, name,
                    () -> sampleChat(contact, format)));
                // Pretend calls open the real call screen, with this chat's wallpaper; nothing is dialed or sent.
                if (!SAMPLE_GROUPS.containsKey(original) && !original.equals("Trail Alerts")) {
                    options.add(new ReachOption(ReachOption.CALL, "Phone", samplePhone(original),
                        () -> startActivity(CallHub.demo(this, key, name, false, "Phone", Encryption.NONE))));
                }
                if (contact[5].equals("Matrix") || original.equals("Maya Chen")) {
                    options.add(new ReachOption(ReachOption.CALL, "Matrix", sampleMatrix(original),
                        () -> startActivity(CallHub.demo(this, key, name, false, "Matrix", Encryption.END_TO_END))));
                    options.add(new ReachOption(ReachOption.VIDEO, "Matrix", sampleMatrix(original),
                        () -> startActivity(CallHub.demo(this, key, name, true, "Matrix", Encryption.END_TO_END))));
                }
                if (original.equals("Maya Chen") || contact[5].equals("XMPP")) {
                    options.add(new ReachOption(ReachOption.CALL, "XMPP", "maya@example.org",
                        () -> startActivity(CallHub.demo(this, key, name, false, "XMPP", Encryption.END_TO_END))));
                    options.add(new ReachOption(ReachOption.VIDEO, "XMPP", "maya@example.org",
                        () -> startActivity(CallHub.demo(this, key, name, true, "XMPP", Encryption.END_TO_END))));
                }
                if (!SAMPLE_GROUPS.containsKey(original) && !original.equals("Trail Alerts")) {
                    options.add(new ReachOption(ReachOption.CALL, "SIP", samplePhone(original),
                        () -> startActivity(CallHub.demo(this, key, name, false, "SIP", Encryption.IN_TRANSIT))));
                }
                if (meetingLinks()) {
                    options.add(new ReachOption(ReachOption.CALL, "Meeting link", meetingServer(),
                        () -> startActivity(CallHub.demo(this, key, name, false, "Meeting link", Encryption.IN_TRANSIT))));
                    options.add(new ReachOption(ReachOption.VIDEO, "Meeting link", meetingServer(),
                        () -> startActivity(CallHub.demo(this, key, name, true, "Meeting link", Encryption.IN_TRANSIT))));
                }
            }
            return options;
        }
        if (key.startsWith(Privacy.DECOY)) {
            options.add(new ReachOption(ReachOption.MESSAGE, "SMS", name, () -> chat(key, MessageProtocol.SMS)));
            return options;
        }
        for (var format : formats(key)) {
            String target = key;
            for (var link : GatewayClient.links(prefs, key)) {
                if (MessageProtocol.parse(link.optString("protocol")) == format) target = link.optString("target", key);
            }
            if (key.startsWith("mms:")) target = name;
            options.add(new ReachOption(ReachOption.MESSAGE, format.label, target, () -> chat(key, format)));
        }
        if (SmsStore.validRecipient(key) && Calling.allowed(prefs, Calling.Method.PHONE)) {
            options.add(new ReachOption(ReachOption.CALL, "Phone", key, () -> placeCall(key)));
        }
        if (SmsStore.validRecipient(key) && Calling.sipReady(prefs)) {
            options.add(new ReachOption(ReachOption.CALL, "SIP", key, () -> startCall(key, name, false, "SIP")));
        }
        if (CallHub.xmppCallable(prefs, key)) {
            String target = CallHub.xmppTarget(prefs, key);
            options.add(new ReachOption(ReachOption.CALL, "XMPP", target, () -> startCall(key, name, false, "XMPP")));
            options.add(new ReachOption(ReachOption.VIDEO, "XMPP", target, () -> startCall(key, name, true, "XMPP")));
        }
        if (meetingLinks() && !formats(key).isEmpty()) {
            // A meeting link works with anyone on any service: they tap it to join in their browser or the Jitsi app.
            String server = meetingServer();
            options.add(new ReachOption(ReachOption.CALL, "Meeting link", server, () -> startMeeting(key, false)));
            options.add(new ReachOption(ReachOption.VIDEO, "Meeting link", server, () -> startMeeting(key, true)));
        }
        if (CallHub.callable(prefs, key)) {
            String target = GatewayClient.links(prefs, key).stream()
                .filter(link -> MessageProtocol.parse(link.optString("protocol")) == MessageProtocol.MATRIX)
                .map(link -> link.optString("target")).findFirst().orElse(name);
            options.add(new ReachOption(ReachOption.CALL, "Matrix", target, () -> { if (!callBlocked(Calling.Method.MATRIX)) startActivity(CallHub.screen(this, key, name, false, null)); }));
            options.add(new ReachOption(ReachOption.VIDEO, "Matrix", target, () -> { if (!callBlocked(Calling.Method.MATRIX)) startActivity(CallHub.screen(this, key, name, true, null)); }));
        }
        return options;
    }

    /** Fictional numbers in the reserved 555-01xx range, and Matrix IDs on example.org, for the sample inbox. */
    static String samplePhone(String name) {
        // Real-looking US numbers in the 555-0100 to 555-0199 range, which is reserved for fiction and never rings anyone.
        String[] areas = {"415", "212", "312", "206", "617", "503", "720", "404", "512", "305"};
        int hash = Math.abs(name.hashCode());
        return "(" + areas[hash % areas.length] + ") 555-01" + String.format(java.util.Locale.ROOT, "%02d", (hash / 7) % 100);
    }
    static String sampleMatrix(String name) { return "@" + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "") + ":example.org"; }

    /** A linked group's route: its protocol, target, and whether the phone or the companion connects it. */
    private record GroupRoute(MessageProtocol format, String target, boolean direct) {}

    private GroupRoute groupRoute(String key) {
        if (key.startsWith("mms:")) return new GroupRoute(MessageProtocol.MMS, key, true);
        for (var format : List.of(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.IRC)) {
            var direct = DirectProtocols.route(prefs, key, format);
            for (var route : direct == null ? GatewayClient.links(prefs, key) : List.of(direct)) {
                if (MessageProtocol.parse(route.optString("protocol")) != format) continue;
                String target = route.optString("target");
                boolean group = route.optBoolean("group") || format == MessageProtocol.IRC && target.startsWith("#")
                    || format == MessageProtocol.MATRIX && (target.startsWith("!") || target.startsWith("#"));
                if (group) return new GroupRoute(format, target, direct != null);
            }
        }
        return null;
    }

    private void groupActions(String key, List<GroupMember> members, List<SettingsAction> actions, Runnable refresh) {
        var group = groupRoute(key);
        if (group == null) return;
        String hint = switch (group.format()) {
            case MMS -> "Phone number";
            case MATRIX -> "@maya:example.org";
            case XMPP -> "maya@example.org";
            default -> "Nickname";
        };
        actions.add(action("Add people", group.format() == MessageProtocol.MMS ? "Starts a new group text with everyone"
            : "Invite someone to this " + group.format().label + " group", () -> {
            SecurityDialogs.form(this, prefs, "Add someone", "", List.<String[]>of(new String[]{"Who to add", "", hint,
                group.format() == MessageProtocol.MMS ? "phone" : "email"}), "Add", null, values -> {
                if (values[0].trim().isEmpty()) return "Enter who to add.";
                changeMember(key, group, true, values[0].trim(), refresh);
                return null;
            }, null);
        }));
        if (members.isEmpty() && group.format() != MessageProtocol.XMPP && group.format() != MessageProtocol.IRC) return;
        actions.add(action("Remove someone", switch (group.format()) {
            case MMS -> "Starts a new group text without them";
            case MATRIX -> "Needs permission to remove people in this room";
            default -> "Needs moderator or operator rights";
        }, () -> {
            if (members.isEmpty()) {
                SecurityDialogs.form(this, prefs, "Remove someone", "", List.<String[]>of(new String[]{
                    group.format() == MessageProtocol.XMPP ? "Their nickname in the room" : "Nickname", "", "", "text"}), "Remove", null, values -> {
                    if (values[0].trim().isEmpty()) return "Enter their nickname.";
                    changeMember(key, group, false, values[0].trim(), refresh);
                    return null;
                }, null);
                return;
            }
            String[] names = members.stream().map(GroupMember::getName).toArray(String[]::new);
            showDialog(dialogBuilder().setTitle("Remove someone").setItems(names, (dialog, which) -> {
                var member = members.get(which);
                String id = member.getAddress().isEmpty() ? member.getName() : member.getAddress();
                showDialog(dialogBuilder().setTitle("Remove " + member.getName() + "?").setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (inner, button) -> changeMember(key, group, false, id, refresh)));
            }));
        }));
    }

    private void changeMember(String key, GroupRoute group, boolean add, String member, Runnable refresh) {
        if (member.isEmpty()) return;
        if (group.format() == MessageProtocol.MMS) {
            // Carriers treat every set of people as its own group text, so a change opens a new conversation.
            var people = new ArrayList<>(groupNumbers(key));
            if (add) {
                if (!SmsStore.validRecipient(member)) { toast("Enter a phone number"); return; }
                people.add(member);
            } else people.removeIf(number -> number.equals(member) || PhoneNumberUtils.compare(number, member));
            if (people.size() < 2) { toast("A group text needs at least two other people"); return; }
            String next = MmsTransport.thread(this, people);
            unsentGroups.put(next, people);
            toast("Group texts can't change members, so this is a new group");
            chat(next, MessageProtocol.MMS);
            return;
        }
        new Thread(() -> {
            try {
                if (group.direct()) DirectProtocols.changeMember(this, key, group.format(), add, member);
                else GatewayClient.changeMember(prefs, key, group.format(), add, member);
                runOnUiThread(() -> { toast(add ? "Invited " + member : "Removed " + member); refresh.run(); });
            } catch (Exception error) {
                runOnUiThread(() -> toast(error.getMessage() == null ? "Couldn't change the group" : error.getMessage()));
            }
        }, "fluid-members").start();
    }

    /** How protected this chat is: FluidMessages encryption, an encrypted room on the companion, or the protocol's own. */
    private Encryption protection(String key, MessageProtocol format) {
        if (format != MessageProtocol.RCS && SmsCrypto.active(prefs, key, format)) return Encryption.FLUIDMESSAGES;
        if (GatewayClient.encrypted(prefs, key, format)) return Encryption.COMPANION_E2EE;
        return format.transport().getCapabilities().getEncryption();
    }

    /** The Security section of a conversation's details: encryption, disappearing messages, hiding, and its code. */
    private void securityActions(String key, String name, List<SettingsAction> security, Runnable refresh) {
        var format = activeProtocol;
        String choice = SmsCrypto.chatChoice(prefs, key);
        boolean on = SmsCrypto.wanted(prefs, key, format);
        String state = on ? (SmsCrypto.active(prefs, key, format) ? "On" : "On · needs a key") : "Off";
        String source = choice.isEmpty() ? " · " + format.label + " default" : " · set for this chat";
        security.add(action("FluidMessages encryption", state + source, () -> SecurityDialogs.chatEncryption(this, prefs, name,
            format.label, choice, SmsCrypto.globalOn(prefs, format), SmsCrypto.chatKey(prefs, key), !SmsCrypto.defaultKey(prefs).isEmpty(),
            value -> { SmsCrypto.setChatChoice(prefs, key, value); invalidateRows(); refresh.run(); },
            value -> { try { SmsCrypto.setChatKey(prefs, key, value); } catch (Exception error) { toast("Could not save the key"); } refresh.run(); },
            () -> { try { SmsCrypto.setChatKey(prefs, key, ""); } catch (Exception ignored) {} refresh.run(); })));
        boolean ownTimer = prefs.contains("disappear." + key);
        security.add(action("Disappearing messages", Disappearing.label(Disappearing.seconds(prefs, key)) + (ownTimer ? "" : " · default"),
            () -> SecurityDialogs.disappearing(this, prefs, "Disappearing messages", ownTimer ? Disappearing.seconds(prefs, key) : -1,
                Disappearing.label(Disappearing.globalSeconds(prefs)), seconds -> {
                    Disappearing.set(prefs, key, seconds);
                    SyncJob.reschedule(this);
                    invalidateRows();
                    refresh.run();
                })));
        boolean hidden = HiddenChats.addressHidden(prefs, key);
        if (!Privacy.vaultExists(prefs)) {
            security.add(action("Hide this conversation", "Set up the private folder first", this::privacySettings));
        } else if (hidden) {
            security.add(action("Show in the inbox again", "Chats in the private folder only appear after you enter the passcode", () ->
                new Thread(() -> {
                    try {
                        HiddenChats.restore(this, key);
                        Privacy.setHidden(prefs, "hidden.addresses", key, false);
                        invalidateRows();
                        runOnUiThread(() -> { toast(name + " is back in the inbox"); refresh.run(); });
                    } catch (Exception error) { runOnUiThread(() -> toast(error.getMessage())); }
                }, "fluid-unhide").start()));
        } else {
            security.add(action("Hide this conversation", "Only shows after you type the private folder passcode in search", () ->
                showDialog(dialogBuilder().setTitle("Hide " + name + "?")
                    .setMessage("Its texts move out of Android's shared message store into FluidMessages' encrypted vault, so "
                        + "other apps, Android search, and FluidMessages Web won't show them. New texts go straight to the "
                        + "vault, with an alert that names no one.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Hide", (dialog, which) -> new Thread(() -> {
                        Privacy.setHidden(prefs, "hidden.addresses", key, true);
                        HiddenChats.sweep(this);
                        invalidateRows();
                        runOnUiThread(() -> { toast(name + " is hidden"); refresh.run(); });
                    }, "fluid-hide").start()))));
        }
        String scope = Privacy.chatScope(key);
        boolean coded = Privacy.hasCode(prefs, scope);
        security.add(action(coded ? "Chat lock" : "Turn on chat lock", coded ? "On · change or remove it"
            : "Hide previews and ask for a passcode to open", () -> chooseCode(scope, name, refresh)));
    }

    /** Sets, changes, or removes a chat or protocol code. Changing one first asks for the current code. */
    private void chooseCode(String scope, String name, Runnable done) {
        boolean has = Privacy.hasCode(prefs, scope);
        Runnable edit = () -> SecurityDialogs.code(this, prefs, (has ? "New passcode for " : "Passcode for ") + name,
            has ? "Leave it empty to turn off the lock." : "You'll be asked for it each time you open the app.", true, 4, has,
            value -> new Thread(() -> {
                try {
                    Privacy.setCode(prefs, scope, value);
                    invalidateRows();
                    runOnUiThread(() -> { toast(value.isEmpty() ? "Lock turned off" : "Passcode saved"); done.run(); });
                } catch (Exception error) { runOnUiThread(() -> toast("Could not save the passcode")); }
            }, "fluid-set-code").start());
        if (has) askCode(scope, edit); else edit.run();
    }

    /** Opens the made-up conversation editor, for a new chat (name null) or an existing one. */
    private void editDecoy(String name, Runnable back) {
        render("decoy");
        contactBack = back;
        heading(name == null ? "New decoy chat" : name, "Back", back);
        root.setPadding(dp(18), root.getPaddingTop(), dp(18), root.getPaddingBottom());
        DecoyEditor.mount(root, prefs, name, () -> { invalidateRows(); back.run(); });
    }

    /** Privacy and security, grouped into a few clear pages instead of one long list. */
    private void privacySettings() {
        render("privacy");
        heading("Privacy and security", "Back", this::settings);
        boolean locked = prefs.getBoolean("lock", false);
        boolean decoy = Privacy.decoyMode(prefs);
        List<SettingsSection> sections = new ArrayList<>();
        sections.add(new SettingsSection("Protect the app", List.of(
            toggleRow("App lock", "Ask for your fingerprint, face, or screen lock to open FluidMessages", locked, () -> toggleLock(locked)),
            action("Chat locks", lockSummary(), this::locksSettings),
            action("Emergency erase passcode", Privacy.hasCode(prefs, DISTRESS) ? "On · change or turn off"
                : "A second passcode that erases everything if you're ever forced to unlock", this::chooseDistress))));
        List<SettingsAction> tools = new ArrayList<>();
        // In decoy mode these stay out of sight, so someone looking can't tell anything is hidden.
        if (!decoy) {
            tools.add(action("Private folder", Privacy.vaultExists(prefs) ? "Set up · open from the inbox filter menu or search"
                : "Keep chats out of your inbox, behind your fingerprint or a passcode", this::hiddenChatsSettings));
            tools.add(action("Decoy mode", "If someone else is using your phone, show only pretend chats and the ones you choose",
                this::decoySettings));
        }
        if (!tools.isEmpty()) sections.add(new SettingsSection("Keep things private", tools));
        sections.add(new SettingsSection("Messages", List.of(
            action("Encryption", "Scramble messages so only you and the other person can read them", this::encryptionSettings),
            action("Disappearing messages", "Default: " + Disappearing.label(Disappearing.globalSeconds(prefs)),
                () -> SecurityDialogs.disappearing(this, prefs, "Default timer", Disappearing.globalSeconds(prefs), null, seconds -> {
                    Disappearing.setGlobal(prefs, seconds);
                    SyncJob.reschedule(this);
                    invalidateRows();
                    privacySettings();
                })))));
        SettingsPages.mount(root, prefs, sections);
    }

    private void chooseDistress() {
        boolean on = Privacy.hasCode(prefs, DISTRESS);
        Runnable edit = () -> SecurityDialogs.code(this, prefs, on ? "Change emergency erase passcode" : "Emergency erase passcode",
            "If you're ever forced to open your private folder or a locked chat, type this passcode instead of your real one. "
                + "FluidMessages immediately erases every account, message, key, and setting, as if it were just installed, "
                + "and closes. It can't be undone. Use at least 6 characters, different from your other passcodes."
                + (on ? " Leave it empty to turn it off." : ""), true, 6, on,
            value -> new Thread(() -> {
                try {
                    boolean wasOpen = Privacy.vaultOpen();
                    if (!value.isEmpty() && Privacy.vaultExists(prefs) && Privacy.openVault(prefs, value)) {
                        if (!wasOpen) Privacy.closeVault();
                        runOnUiThread(() -> toast("Use a passcode that's different from your private folder passcode"));
                        return;
                    }
                    Privacy.setCode(prefs, DISTRESS, value);
                    runOnUiThread(() -> { toast(value.isEmpty() ? "Emergency erase turned off" : "Emergency erase passcode saved"); privacySettings(); });
                } catch (Exception error) { runOnUiThread(() -> toast("Could not save the passcode")); }
            }, "fluid-distress").start());
        if (on) deviceAuth("Change emergency erase", edit, null); else edit.run();
    }

    private String lockSummary() {
        long services = SmsCrypto.PROTOCOLS.stream().filter(format -> format != MessageProtocol.MMS
            && Privacy.hasCode(prefs, Privacy.protocolScope(format))).count();
        return services == 0 ? "Lock a chat from its details, or every chat of one kind here"
            : services + (services == 1 ? " kind of chat is locked" : " kinds of chats are locked");
    }

    private void subpage(String page, String title, List<SettingsSection> sections) {
        render(page);
        heading(title, "Back", this::privacySettings);
        SettingsPages.mount(root, prefs, sections);
    }

    private void locksSettings() {
        List<SettingsAction> codes = new ArrayList<>();
        for (var format : List.of(MessageProtocol.SMS, MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL,
                MessageProtocol.IRC)) {
            String scope = Privacy.protocolScope(format);
            String label = format == MessageProtocol.SMS ? "All texts" : "All " + format.label + " chats";
            codes.add(action(label, Privacy.hasCode(prefs, scope) ? "Locked · change or remove the passcode" : "Not locked",
                () -> chooseCode(scope, label, this::locksSettings)));
        }
        subpage("locks", "Chat locks", List.of(
            new SettingsSection("How it works", List.of(new SettingsAction("Locked chats ask for a passcode",
                "Their previews are hidden in the inbox, and they ask again each time you come back to the app. To lock one chat, "
                    + "open its details and choose Lock this chat.", () -> {}, true, null))),
            new SettingsSection("Lock every chat of one kind", codes)));
    }

    private void hiddenChatsSettings() {
        boolean vault = Privacy.vaultExists(prefs);
        boolean open = Privacy.vaultOpen();
        List<SettingsSection> sections = new ArrayList<>();
        sections.add(new SettingsSection("How it works", List.of(new SettingsAction("Chats in the private folder stay out of your inbox",
            "They're only in the private folder. Open it from the filter button at the top of the inbox with your passcode or "
                + "fingerprint, or type your passcode into search. Closing it asks again, and it always closes when you leave the app.",
            () -> {}, true, null))));
        if (!vault) {
            sections.add(new SettingsSection("Get started", List.of(action("Set up the private folder", "Choose a passcode", this::setUpVault))));
            subpage("hiddenChats", "Private folder", sections);
            return;
        }
        if (!open) {
            sections.add(new SettingsSection("The private folder is closed", List.of(action("Open the private folder", "To change these settings",
                () -> openHiddenChats(this::hiddenChatsSettings)))));
            subpage("hiddenChats", "Private folder", sections);
            return;
        }
        boolean device = Privacy.deviceUnlockOn(prefs);
        sections.add(new SettingsSection("Opening", List.of(
            toggleRow("Open with fingerprint or screen lock",
                "Open the private folder from the inbox filter menu without typing the passcode", device, () -> {
                    if (device) { Privacy.disableDeviceUnlock(prefs); hiddenChatsSettings(); return; }
                    deviceAuth("Turn on quick unlock", () -> {
                        try { Privacy.enableDeviceUnlock(prefs); } catch (Exception error) { toast("Couldn't turn on quick unlock"); }
                        hiddenChatsSettings();
                    }, null);
                }),
            toggleRow("Ask for the passcode to close it", "Leaving the private folder for another folder asks again",
                closeNeedsCode(), () -> { prefs.edit().putBoolean("privateCloseCode", !closeNeedsCode()).apply(); hiddenChatsSettings(); }),
            action("Change passcode", "", this::setUpVault))));
        sections.add(new SettingsSection("What's hidden", List.of(
            action("Hide whole services", hiddenProtocolLabel(), this::chooseHiddenProtocols),
            new SettingsAction("Hide one chat", "Open the chat's ⋯ menu or details and choose Move to private folder", () -> {}, true, null))));
        sections.add(new SettingsSection("Done with the private folder?", List.of(
            action("Close the private folder now", "", () -> { Privacy.closeVault(); invalidateRows(); privacySettings(); }),
            action("Turn off the private folder", "Moves everything back to your inbox and deletes the passcode", this::removeVault))));
        subpage("hiddenChats", "Private folder", sections);
    }

    private void decoySettings() {
        boolean vault = Privacy.vaultExists(prefs);
        List<SettingsSection> sections = new ArrayList<>();
        sections.add(new SettingsSection("How it works", List.of(new SettingsAction("For when someone else is using your phone",
            "Decoy mode instantly swaps your inbox and contacts for pretend chats, plus only the real chats you allow. Nothing is deleted. "
                + "Turn it on from the inbox filter menu, the Quick hide tile in your phone's quick settings, or by long-pressing the "
                + "FluidMessages icon. To end it, long-press the Messages title and enter your private folder passcode, type the "
                + "passcode into search, or tap the Quick hide tile again.", () -> {}, true, null))));
        if (!vault) {
            sections.add(new SettingsSection("Get started", List.of(action("Set up the private folder first",
                "Decoy mode ends with your private folder passcode, so set one up", this::hiddenChatsSettings))));
            subpage("decoyMode", "Decoy mode", sections);
            return;
        }
        sections.add(new SettingsSection("Turn on", List.of(action("Turn on decoy mode now", "Shows the decoy inbox right away",
            this::startDecoyMode), action("Add the Quick hide tile", "One tap from your phone's quick settings, even outside the app",
            () -> DecoyTile.offer(this)))));
        int allowed = Privacy.decoyAllowed(prefs).size();
        List<SettingsAction> decoys = new ArrayList<>();
        decoys.add(action("Real chats that stay visible", allowed == 0 ? "None · only pretend chats show" : allowed + " chosen",
            this::chooseDecoyAllowed));
        decoys.add(action("New pretend chat", "Looks like a normal chat. Nothing is ever sent.", () -> editDecoy(null, this::decoySettings)));
        var names = new LinkedHashSet<String>();
        for (var row : Privacy.decoys(prefs)) names.add(row.optString("address").substring(Privacy.DECOY.length()));
        for (String name : names) decoys.add(action(name, "Pretend chat · edit or delete", () -> editDecoy(name, this::decoySettings)));
        sections.add(new SettingsSection("What decoy mode shows", decoys));
        subpage("decoyMode", "Decoy mode", sections);
    }

    private void startDecoyMode() {
        Privacy.setDecoyMode(prefs, true);
        Privacy.forget();
        invalidateRows();
        folder = "Inbox";
        updateQuickHide();
        DecoyTile.refresh(this);
        inbox();
    }

    /** "Quick hide" on the app icon's long-press menu turns on decoy mode in one tap. */
    private void updateQuickHide() {
        var shortcuts = getSystemService(android.content.pm.ShortcutManager.class);
        if (shortcuts == null) return;
        if (!Privacy.vaultExists(prefs)) { shortcuts.removeAllDynamicShortcuts(); return; }
        var intent = new Intent(this, MainActivity.class).setAction(QUICK_HIDE).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        shortcuts.setDynamicShortcuts(List.of(new android.content.pm.ShortcutInfo.Builder(this, "quick-hide")
            .setShortLabel("Quick hide").setLongLabel("Quick hide")
            .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher)).setIntent(intent).build()));
    }

    static final String QUICK_HIDE = "org.fluidmessages.QUICK_HIDE";

    private void chooseDecoyAllowed() {
        new Thread(() -> {
            try {
                var allowed = Privacy.decoyAllowed(prefs);
                Privacy.setDecoyMode(prefs, false);
                List<String> addresses = new ArrayList<>();
                List<String[]> items = new ArrayList<>();
                List<Integer> selected = new ArrayList<>();
                for (JSONObject row : messageRows()) {
                    String address = row.optString("address");
                    if (address.startsWith(Privacy.DECOY) || addresses.contains(address) || HiddenChats.rowHidden(prefs, row)) continue;
                    if (addresses.size() >= 60) break;
                    if (allowed.stream().anyMatch(item -> sameAddress(item, address))) selected.add(addresses.size());
                    addresses.add(address);
                    items.add(new String[]{threadTitle(address), MessageProtocol.parse(row.optString("protocol")).label});
                }
                runOnUiThread(() -> SecurityDialogs.chooseMany(this, prefs, "Real chats in decoy mode",
                    "These stay visible in decoy mode. Choose chats that would look normal to someone else.", items, selected, "Save",
                    chosen -> {
                        Set<String> keep = new LinkedHashSet<>();
                        for (int index : chosen) keep.add(addresses.get(index));
                        Privacy.setDecoyAllowed(prefs, keep);
                        decoySettings();
                    }));
            } catch (Exception error) { runOnUiThread(() -> toast("Couldn't load your chats")); }
        }, "fluid-decoy-chats").start();
    }

    private void encryptionSettings() {
        var encrypted = SmsCrypto.PROTOCOLS.stream().filter(format -> SmsCrypto.globalOn(prefs, format))
            .map(format -> format == MessageProtocol.SMS ? "Texts" : format == MessageProtocol.MMS ? "Group texts" : format.label)
            .collect(java.util.stream.Collectors.joining(", "));
        subpage("encryption", "Encryption", List.of(
            new SettingsSection("What the lock colors mean", List.of(
                new SettingsAction("Green lock", "End-to-end encrypted, or encrypted with FluidMessages. Only you and the people in the chat can read it.",
                    () -> {}, true, Encryption.END_TO_END),
                new SettingsAction("Yellow lock", "Protected on its way to the server, but the service can read it.", () -> {}, true, Encryption.IN_TRANSIT),
                new SettingsAction("Red open lock", "Not encrypted. Regular texts work this way; your carrier can read them.",
                    () -> {}, true, Encryption.NONE))),
            new SettingsSection("Encrypt with FluidMessages", List.of(
                action("Turn on for", (encrypted.isEmpty() ? "Nothing yet" : encrypted)
                    + (SmsCrypto.defaultKey(prefs).isEmpty() ? " · no default key" : " · default key set"),
                    () -> SecurityDialogs.protocolEncryption(this, prefs, this::encryptionSettings)),
                new SettingsAction("How it works", "You and the other person enter the same encryption key, and messages are scrambled "
                    + "before they leave your phone. Both of you need FluidMessages. A chat's own setting, in its details, always wins.",
                    () -> {}, true, null))),
            new SettingsSection("On this phone", List.of(new SettingsAction("Your stored messages are encrypted",
                "Saved messages, accounts, and passwords are locked with a key kept in your phone's secure hardware.", () -> {}, true, null)))));
    }

    private String hiddenProtocolLabel() {
        var hidden = Privacy.hiddenProtocols(prefs);
        if (hidden.isEmpty()) return "None";
        return hidden.stream().map(name -> name.equals("SMS") ? "Texts" : MessageProtocol.parse(name).label)
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private void chooseHiddenProtocols() {
        var formats = List.of(MessageProtocol.SMS, MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC);
        var hidden = Privacy.hiddenProtocols(prefs);
        List<String[]> items = new ArrayList<>();
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < formats.size(); i++) {
            var format = formats.get(i);
            items.add(format == MessageProtocol.SMS ? new String[]{"Texts", "SMS, picture messages, and group texts"}
                : new String[]{format.label, "Every " + format.label + " chat"});
            if (hidden.contains(format.family().name())) selected.add(i);
        }
        SecurityDialogs.chooseMany(this, prefs, "Hide whole services", "Every chat on the services you check moves to the Private folder "
            + "folder, including new ones.", items, selected, "Save", chosen -> {
                for (int i = 0; i < formats.size(); i++) Privacy.setHidden(prefs, "hidden.protocols", formats.get(i).family().name(), chosen.contains(i));
                invalidateRows();
                hiddenChatsSettings();
            });
    }

    private void removeVault() {
        showDialog(dialogBuilder().setTitle("Remove the private folder?")
            .setMessage("Texts in the private folder go back to Android's message store and the inbox. Hidden services show again.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> new Thread(() -> {
                try {
                    for (String hidden : HiddenChats.addresses(prefs)) HiddenChats.restore(this, hidden);
                    Privacy.removeVault(prefs);
                    Privacy.setDecoyMode(prefs, false);
                    invalidateRows();
                    runOnUiThread(() -> { updateQuickHide(); privacySettings(); });
                } catch (Exception error) { runOnUiThread(() -> toast(error.getMessage())); }
            }, "fluid-vault-remove").start()));
    }

    private void setUpVault() {
        boolean exists = Privacy.vaultExists(prefs);
        SecurityDialogs.code(this, prefs, exists ? "New private folder passcode" : "Private folder",
            "To show the private folder, type this passcode into the inbox search. Pick something you wouldn't search for. "
                + "If you forget it, texts in the private folder can't be recovered.", true, 4, false,
            value -> new Thread(() -> {
                try {
                    Privacy.setVaultCode(prefs, value);
                    invalidateRows();
                    runOnUiThread(this::updateQuickHide);
                    runOnUiThread(() -> { toast("The private folder is ready"); hiddenChatsSettings(); });
                } catch (Exception error) { runOnUiThread(() -> toast(error.getMessage())); }
            }, "fluid-vault").start());
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
        var contact = contacts.find(number);
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

    /** A row with an on/off switch. flip runs when it's tapped, and should save and redraw the page. */
    private SettingsAction toggleRow(String label, String detail, boolean on, Runnable flip) {
        return new SettingsAction(label, detail, flip, false, null, on);
    }

    /** GIFs, stickers, where to search for them, and pictures and previews from links. */
    private void gifSettings() {
        render("gifs");
        heading("Media, GIFs, and fun", "Back", this::settings);
        List<SettingsAction> gifs = new ArrayList<>();
        boolean gifsOn = GifSettings.gifsOn(prefs), stickersOn = GifSettings.stickersOn(prefs);
        gifs.add(toggleRow("GIFs", "Send GIFs from the + menu and your keyboard", gifsOn, () -> {
            prefs.edit().putBoolean("gifs", !gifsOn).apply(); gifSettings(); }));
        gifs.add(toggleRow("Stickers", "Send stickers, and make your own from photos", stickersOn, () -> {
            prefs.edit().putBoolean("stickers", !stickersOn).apply(); gifSettings(); }));
        String source = GifSettings.source(prefs);
        gifs.add(action("Search results in the picker", GifSettings.label(source).equals("Your phone and keyboard") ? "Off · browse sites instead" : GifSettings.label(source), () -> {
            List<String[]> items = List.of(
                new String[]{"Your phone and keyboard", "No searching here. Nothing is sent anywhere until you send a GIF."},
                new String[]{"Tenor", "Search Tenor with your own free API key from Google"},
                new String[]{"GIPHY", "Search GIPHY with your own free API key"});
            String[] values = {GifSettings.NONE, GifSettings.TENOR, GifSettings.GIPHY};
            SecurityDialogs.chooseOne(this, prefs, "Search GIFs and stickers with", "Searches go to the service you pick.", items, index -> {
                prefs.edit().putString("gifSource", values[index]).apply();
                gifSettings();
            });
        }));
        if (!source.equals(GifSettings.NONE)) {
            gifs.add(action("API key", GifSettings.key(prefs).isEmpty() ? "Not set · searching is off until you add one" : "Saved",
                () -> textSetting("API key", "Create a free key at " + (source.equals(GifSettings.TENOR) ? "developers.google.com/tenor" : "developers.giphy.com")
                    + ", then paste it here.", "gifKey", true, this::gifSettings)));
            gifs.add(action("API address", GifSettings.apiBase(prefs).isEmpty() ? "Default · " + GifSettings.label(source) + "'s own"
                : GifSettings.apiBase(prefs), () -> textSetting("API address", "Optional. A compatible server or proxy, starting with https://. "
                    + "Leave blank to use " + GifSettings.label(source) + " directly.", "gifApi", false, this::gifSettings)));
        }
        List<SettingsAction> sites = new ArrayList<>();
        for (var site : GifSites.INSTANCE.getDefaults()) {
            boolean on = GifSites.on(prefs, site);
            sites.add(toggleRow(site.getName(), on ? "Shown in the GIF picker" : "Hidden", on, () -> {
                GifSites.setOn(prefs, site.getId(), !on); gifSettings(); }));
        }
        for (String name : GifSites.names(prefs)) sites.add(action(name, "Added by you · tap to remove", () ->
            showDialog(dialogBuilder().setTitle("Remove " + name + "?").setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> { GifSites.remove(prefs, name); gifSettings(); }))));
        sites.add(action("Add a GIF site", "Any site you like, with {query} where search words go", () -> {
            SecurityDialogs.form(this, prefs, "Add a GIF site", "It opens inside the GIF picker, where you can send the GIFs you find.",
                List.of(new String[]{"Name", "", "My GIF site", "text"}, new String[]{"Search address", "", "https://example.com/search/{query}", "uri"}),
                "Add", null, values -> {
                    if (!GifSites.add(prefs, values[0], values[1])) return "Enter a name and an https:// address.";
                    gifSettings();
                    return null;
                }, null);
        }));
        List<SettingsAction> media = new ArrayList<>();
        String quality = MediaSanitizer.quality(this);
        String[] qualities = {"auto", "saver", "standard", "high"};
        String[][] qualityItems = {{"Automatic", "Smaller photos when your connection is slow, normal size otherwise"},
            {"Data saver", "Always small photos that send fast on weak signal"},
            {"Standard", "Sharp photos at a sensible size"},
            {"High quality", "Up to 4096 pixels, with the most detail each service allows"}};
        int chosen = Math.max(0, Arrays.asList(qualities).indexOf(quality));
        media.add(action("Photo quality", qualityItems[chosen][0], () -> SecurityDialogs.chooseOne(this, prefs, "Photo quality",
            "Text messages (MMS) still shrink photos to fit your carrier's limit. Messages that can't send right away wait and send when you're back online.",
            Arrays.asList(qualityItems), index -> { prefs.edit().putString("mediaQuality", qualities[index]).apply(); gifSettings(); })));
        List<SettingsAction> links = new ArrayList<>();
        boolean pictures = GifSettings.autoPictures(prefs), previews = GifSettings.linkPreviews(prefs);
        links.add(toggleRow("Show pictures and GIFs from links", "Loads them from the site that hosts them", pictures, () -> {
            prefs.edit().putBoolean("autoPictures", !pictures).apply(); gifSettings(); }));
        links.add(toggleRow("Link previews", "A card with the title and picture of shared links, like videos and songs", previews, () -> {
            prefs.edit().putBoolean("linkPreviews", !previews).apply(); gifSettings(); }));
        SettingsPages.mount(root, prefs, List.of(new SettingsSection("GIFs and stickers", gifs), new SettingsSection("GIF sites to browse", sites),
            new SettingsSection("Photos and videos", media),
            new SettingsSection("Links", links), new SettingsSection("Fun and effects", funRows())));
    }

    /** Switches for what plays and shows in chats: effects, reaction animations, games, and dice. */
    private List<SettingsAction> funRows() {
        List<SettingsAction> rows = new ArrayList<>();
        boolean effects = FunSettings.effects(prefs), reactions = FunSettings.reactions(prefs),
            games = FunSettings.games(prefs), random = FunSettings.random(prefs);
        rows.add(toggleRow("Message effects", "Confetti, Slam, and the others play, and + offers Send with effect", effects, () -> {
            FunSettings.set(prefs, "effects", !effects); gifSettings(); }));
        rows.add(toggleRow("Reaction animations", "Each built-in reaction plays its own short animation; other emoji pop", reactions, () -> {
            FunSettings.set(prefs, "reactions", !reactions); gifSettings(); }));
        rows.add(toggleRow("Games", "Game boards in chats, and Games in the + menu. Off shows game messages as text", games, () -> {
            FunSettings.set(prefs, "games", !games); gifSettings(); }));
        rows.add(toggleRow("Dice and random", "Dice, coin flips, and random picks as cards", random, () -> {
            FunSettings.set(prefs, "random", !random); gifSettings(); }));
        return rows;
    }

    private void textSetting(String title, String message, String key, boolean secret, Runnable done) {
        SecurityDialogs.form(this, prefs, title, message, List.<String[]>of(new String[]{title, secret ? "" : prefs.getString(key, ""),
            "", secret ? "password" : key.equals("gifApi") ? "uri" : "text"}), "Save", null, values -> {
            String value = values[0].trim();
            if (key.equals("gifApi") && !value.isEmpty() && !value.startsWith("https://")) return "The address needs to start with https://.";
            if (secret && value.isEmpty()) return "Enter a value, or press Back to leave it as it is.";
            prefs.edit().putString(key, value).apply();
            done.run();
            return null;
        }, null);
    }

    /** The way to pay that money requests start with, and payment methods you added yourself. */
    private void paymentSettings() {
        render("payments");
        heading("Payments", "Back", this::settings);
        List<SettingsAction> main = new ArrayList<>();
        main.add(action("Default way to pay", MoneyKt.defaultMoney(prefs).getName(), () -> {
            var all = MoneyKt.moneyAssets(prefs);
            List<String[]> items = new ArrayList<>();
            for (var asset : all) items.add(new String[]{asset.getName().replaceFirst("^via ", ""),
                asset.getCustom() ? "Added by you" : asset.getFiat() ? "Payment app or bank" : "Crypto"});
            SecurityDialogs.chooseOne(this, prefs, "Default way to pay", "Money requests start with this. You can change it each time.", items, index -> {
                prefs.edit().putString("money.default", all.get(index).getName()).apply();
                paymentSettings();
            });
        }));
        List<SettingsAction> custom = new ArrayList<>();
        for (String name : CustomMoney.names(prefs)) custom.add(action(name, "Tap to remove", () ->
            showDialog(dialogBuilder().setTitle("Remove " + name + "?").setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> { CustomMoney.remove(prefs, name); paymentSettings(); }))));
        custom.add(action("Add a way to pay", "Any coin or service with a payment link", () -> {
            SecurityDialogs.form(this, prefs, "Add a way to pay", "Put {address}, {amount}, and {note} where they go in the link. "
                + "FluidMessages can't check custom methods, so their cards show a warning.",
                List.of(new String[]{"Name", "", "DOGE on Dogecoin", "text"}, new String[]{"Payment link", "", "dogecoin:{address}?amount={amount}", "uri"}),
                "Add", null, values -> {
                    String label = values[0].trim(), template = values[1].trim();
                    if (label.isEmpty() || !CustomMoney.validTemplate(template)) return "Enter a name and a link with {address} in it.";
                    CustomMoney.add(prefs, label, template);
                    paymentSettings();
                    return null;
                }, null);
        }));
        List<SettingsAction> about = List.of(new SettingsAction("How paying works",
            "FluidMessages never holds money, keys, or card numbers. A request is a standard payment link (Monero, Bitcoin, Lightning, "
                + "Solana Pay, Ethereum, PayPal.me, Venmo, Cash App, Revolut, UPI, or a payto: bank transfer) that opens the payer's own app. "
                + "Google Pay and Apple Pay don't offer payment links other apps can use, so they aren't listed.", () -> {}, true, null));
        SettingsPages.mount(root, prefs, List.of(new SettingsSection("Requests", main), new SettingsSection("Added by you", custom),
            new SettingsSection("About", about)));
    }

    private SettingsAction action(String label, String detail, Runnable run) {
        return new SettingsAction(label, detail, run);
    }

    private void settings() {
        render("settings");
        heading("Settings", "Inbox", this::inbox);
        boolean locked = prefs.getBoolean("lock", false);
        SettingsPages.mount(root, prefs, List.of(
            new SettingsSection("Look and feel", List.of(
                action("Themes and appearance", "Colors, wallpapers, bubbles, and motion", this::theme),
                action("Messaging", "Timestamps, replies, reactions, sounds", this::messagingSettings))),
            new SettingsSection("Accounts and calls", List.of(
                action("Accounts", "Sign in to Matrix, XMPP, Email, or IRC to chat there too", this::accounts),
                action("Ways to message", MessageProtocol.anyEnabled(prefs) ? "Turn services on or off, and combine one person's chats"
                    : "All off · read-only, nothing is sent", this::protocolSettings),
                action("Calls", Calling.anyAllowed(prefs) ? "Recent calls and meeting links" : "All off · you can't place calls",
                    this::callsSettings))),
            new SettingsSection("In chats", List.of(
                action("Media, GIFs, and fun", "GIFs, stickers, photo quality, links, effects, and games", this::gifSettings),
                action("Payments", "Default: " + MoneyKt.defaultMoney(prefs).getName(), this::paymentSettings))),
            new SettingsSection("Privacy", List.of(
                action("Privacy and security", (locked ? "App lock on" : "App lock off") + " · encryption, the private folder, locks",
                    this::privacySettings))),
            new SettingsSection("Storage and devices", List.of(
                action("Backups", "Save and restore your messages and settings", this::backups),
                action("FluidMessages Web", CloudBackup.paired(this) ? "On · your messages in a browser" : "Off · your messages in a browser",
                    this::companion),
                action("Battery and sync", SyncPolicy.backgroundLabel(SyncPolicy.backgroundMinutes(prefs)) + " in the background",
                    this::batterySettings))),
            new SettingsSection("Help", List.of(
                action("Sample inbox", "Practice with made-up chats and calls. Nothing is sent.", this::sampleInbox),
                action("Setup guide", "A quick tour of what FluidMessages can do", () -> onboarding(0)),
                action("About and updates", "Version and updates", this::about),
                action("Reset or erase", "Start over with default settings, or remove everything", this::resetPage)))));
    }

    private void resetPage() {
        render("reset");
        heading("Reset or erase", "Back", this::settings);
        SettingsPages.mount(root, prefs, List.of(
            new SettingsSection("Settings", List.of(action("Reset settings to defaults",
                "Keeps your messages, accounts, FluidMessages Web, the private folder, and keys", this::confirmResetSettings))),
            new SettingsSection("Everything", List.of(action("Erase everything",
                "Removes all accounts, messages, keys, and settings from this app", this::confirmErase)))));
    }

    private void confirmResetSettings() {
        showDialog(dialogBuilder().setTitle("Reset settings to defaults?")
            .setMessage("Themes, wallpapers, sounds, notifications, messaging options, encryption and disappearing defaults, "
                + "and per-chat themes go back to how they start. Your messages, accounts, FluidMessages Web pairing, the private folder, "
                + "passcodes, and keys stay.")
            .setNegativeButton("Cancel", null).setPositiveButton("Reset", (dialog, which) -> {
                Resets.resetSettings(this, prefs);
                invalidateRows();
                toast("Settings are back to their defaults");
                settings();
            }));
    }

    private void confirmErase() {
        showDialog(dialogBuilder().setTitle("Erase everything?")
            .setMessage("This deletes every account, internet and picture message, hidden chat, decoy, key, passcode, and setting from "
                + "FluidMessages on this phone, and closes the app. It can't be undone. Texts in Android's message store stay, and "
                + "FluidMessages Web keeps its own copy until you reset it there. Make a backup first if you might want anything back.")
            .setNegativeButton("Cancel", null).setPositiveButton("Erase", (dialog, which) ->
                showDialog(dialogBuilder().setTitle("Erase FluidMessages now?").setMessage("Last chance: this can't be undone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Erase everything", (inner, button) -> Resets.eraseEverything(this)))));
    }

    private void about() {
        render("about");
        heading("About and updates", "Back", this::settings);
        try {
            var installed = ReleaseUpdates.installed(this, 0);
            long lastChecked = prefs.getLong("lastUpdateCheck", 0);
            String status = updateStatus.isEmpty() && lastChecked > 0
                ? "Last checked " + DateFormat.getDateTimeInstance().format(new Date(lastChecked)) : updateStatus;
            SettingsPages.mountAbout(root, prefs, installed.versionName, installed.getLongVersionCode(), status,
                ReleaseUpdates.managedByStore(this), checkingUpdates || downloadingUpdate, () -> checkUpdates(true),
                () -> openWebsite(ReleaseUpdates.REPOSITORY + "/releases"),
                () -> openWebsite(HOMEPAGE), ReleaseUpdates.REPOSITORY + "/releases", HOMEPAGE);
        } catch (PackageManager.NameNotFoundException error) { toast("Could not read app version"); }
    }

    private static final String HOMEPAGE = ReleaseUpdates.REPOSITORY;

    private void openWebsite(String url) {
        // Hand the link to the user's default browser in its own task, the same as tapping a link elsewhere.
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT));
        }
        catch (ActivityNotFoundException error) { toast("No browser is available"); }
    }

    private void protocolSettings() {
        render("protocols");
        heading("Ways to message", "Back", this::settings);
        ProtocolSettingsScreen.mount(root, prefs);
    }

    private void accounts() { accounts(this::settings); }

    private void accounts(Runnable back) {
        render("accounts");
        heading("Accounts", "Back", back);
        AccountsScreen.mount(root, prefs, this::companion);
    }

    private void batterySettings() {
        render("battery");
        heading("Battery and sync", "Back", this::settings);
        ProtocolSettingsScreen.mountBattery(root, prefs);
    }

    private boolean canPromptUpdate() {
        return resumed && !isFinishing() && !isDestroyed() && !page.equals("setup")
            && (!prefs.getBoolean("lock", false) || unlocked);
    }

    private void checkUpdates(boolean manual) {
        if (checkingUpdates || downloadingUpdate || !canPromptUpdate() ||
                !prefs.getBoolean("onboarded", false) || ReleaseUpdates.managedByStore(this)) return;
        long now = System.currentTimeMillis();
        long previous = prefs.getLong("lastUpdateAttempt", 0);
        if (!manual && (!prefs.getBoolean("automaticUpdateChecks", true) ||
                (previous <= now && now - previous < ReleaseUpdates.CHECK_INTERVAL))) return;
        checkingUpdates = true;
        int version = ++updateCheckVersion;
        prefs.edit().putLong("lastUpdateAttempt", now).apply();
        if (manual || page.equals("about")) updateStatus("Checking GitHub for updates…");
        handler.postDelayed(() -> {
            if (!checkingUpdates || version != updateCheckVersion || isDestroyed()) return;
            checkingUpdates = false;
            updateCheckVersion++;
            if (manual || page.equals("about")) updateStatus("Could not check for updates. Try again when you’re online.");
        }, 25000);
        new Thread(() -> {
            ReleaseUpdates.Result response;
            try { response = ReleaseUpdates.check(ReleaseUpdates.installed(this, 0).versionName); }
            catch (Exception error) { response = null; }
            var result = response;
            runOnUiThread(() -> {
                if (version != updateCheckVersion) return;
                checkingUpdates = false;
                if (isFinishing() || isDestroyed()) return;
                if (result == null) {
                    if (manual || page.equals("about")) updateStatus("Could not check for updates. Try again when you’re online.");
                    return;
                }
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
                if (manual || page.equals("about")) updateStatus(result.status());
                var release = result.update();
                if (release != null && (manual || (prefs.getBoolean("automaticUpdateChecks", true)
                        && !release.version().equals(prefs.getString("lastUpdatePrompt", ""))))) {
                    pendingRelease = release;
                    promptUpdate();
                }
            });
        }, "fluid-update-check").start();
    }

    private void updateStatus(String status) {
        updateStatus = status;
        if (page.equals("about") && canPromptUpdate()) about();
        else if (!isDestroyed() && !isFinishing() && resumed) toast(status);
    }

    private void promptUpdate() {
        if (pendingRelease == null || !canPromptUpdate()) return;
        var release = pendingRelease;
        pendingRelease = null;
        prefs.edit().putString("lastUpdatePrompt", release.version()).apply();
        showDialog(dialogBuilder().setTitle("Update available")
            .setMessage("FluidMessages " + release.version() + " is available on GitHub. Android will ask you to confirm installation.")
            .setNegativeButton("Later", null).setPositiveButton("Download update", (dialog, which) -> downloadUpdate(release)));
    }

    private void downloadUpdate(ReleaseUpdates.Release release) {
        if (downloadingUpdate) return;
        downloadingUpdate = true;
        updateStatus("Downloading version " + release.version() + "…");
        new Thread(() -> {
            try {
                var file = ReleaseUpdates.download(this, release);
                runOnUiThread(() -> {
                    downloadingUpdate = false;
                    if (isFinishing() || isDestroyed()) { file.delete(); return; }
                    pendingInstaller = file;
                    updateStatus("Update verified and ready to install.");
                    if (canPromptUpdate()) installUpdate();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    downloadingUpdate = false;
                    if (!isDestroyed() && !isFinishing()) updateStatus(error instanceof java.io.IOException
                        ? error.getMessage() : "Could not verify the update.");
                });
            }
        }, "fluid-update-download").start();
    }

    private void installUpdate() {
        if (pendingInstaller == null) return;
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), INSTALL_SOURCE);
                return;
            }
            Uri apk = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".backup-files", pendingInstaller);
            pendingInstaller = null;
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException error) {
            pendingInstaller = null;
            updateStatus("Could not open the Android package installer.");
        }
    }

    private void toggleLock(boolean locked) {
        if (!locked && !getSystemService(KeyguardManager.class).isDeviceSecure()) {
            toast("Set a device screen lock before enabling app lock");
            return;
        }
        prefs.edit().putBoolean("lock", !locked).apply();
        if (locked) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            privacySettings();
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

    private Runnable backupsBack = this::settings;

    /** Backups opened from the setup guide go back there. */
    private void backupsFrom(Runnable back) {
        backupsBack = back;
        backups();
        backupsBack = this::settings;
    }

    private void backups() {
        render("backups");
        Runnable back = backupsBack;
        heading("Backups", back == null ? "Settings" : "Back", back);
        List<SettingsAction> local = new ArrayList<>();
        local.add(action("Export a file", lastRun(prefs.getLong("lastBackup", 0), "Save through Android’s file picker"),
            () -> backupDialog(true)));
        local.add(action("Share to a storage app", "Send a backup to a compatible app on your phone", this::shareBackup));
        local.add(action("Restore from a file", "Choose an archive and review its contents", () -> backupDialog(false)));
        String fixedName = BackupNames.fixed(prefs);
        local.add(action("Backup file name", fixedName.isEmpty() ? "New dated file each time" : fixedName, this::backupFileName));
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
            String detail = phone ? "Phone" : "FluidMessages Web";
            if (phone && hasPhoneSchedule) detail += " · " + intervalLabel(phoneFolder.minutes(prefs));
            else if (!phone && minutes > 0) detail += " · " + intervalLabel(minutes);
            cloud.add(action(target.label, detail, () -> cloudDestination(target)));
            if (minutes > 0 && CloudBackup.paired(this)) scheduledCloud.add(() -> runCloudNow(target));
            if (hasPhoneSchedule) scheduledCloud.add(() -> runPhoneBackup(phoneFolder));
        }
        if (scheduledCloud.size() > 1) {
            cloud.add(action("Back up all cloud schedules", "Run each configured phone and FluidMessages Web schedule", () -> {
                toast("Starting " + scheduledCloud.size() + " cloud backups");
                scheduledCloud.forEach(Runnable::run);
            }));
        }
        List<SettingsAction> servers = new ArrayList<>();
        for (var server : FileServers.list(prefs)) {
            String detail = FileServers.label(server.optString("kind")) + " · " + server.optString("host");
            if (server.optInt("minutes") > 0) detail += " · " + intervalLabel(server.optInt("minutes"));
            String id = server.optString("id");
            servers.add(action(server.optString("name"), detail, () -> fileServer(id)));
        }
        servers.add(action("Add a file server", servers.isEmpty() ? "SFTP, WebDAV (Nextcloud and most NAS boxes), FTPS, or FTP, on any port" : "",
            () -> editFileServer(null)));
        SettingsPages.mount(root, prefs, List.of(
            new SettingsSection("On this phone", local),
            new SettingsSection("Your own server", servers),
            new SettingsSection("Cloud services", cloud)));
    }

    private Runnable fileServerBack = this::backups;

    /** One file server: back up now, schedule, restore, edit, remove. */
    private void fileServer(String id) {
        var server = FileServers.find(prefs, id);
        if (server == null) { backups(); return; }
        render("fileServer");
        heading(server.optString("name"), "Backups", this::backups);
        int minutes = server.optInt("minutes");
        List<SettingsAction> entries = new ArrayList<>();
        entries.add(action("Back up now", lastRun(server.optLong("last"), "Choose what to upload"), () -> fileServerBackupNow(id)));
        entries.add(action("Backup schedule", minutes > 0 ? intervalLabel(minutes) : "Off · back up automatically", () -> fileServerSchedule(id)));
        if (minutes > 0) entries.add(action("Turn off the schedule", "", () -> {
            try { FileServers.unschedule(this, server); } catch (Exception ignored) {}
            fileServer(id);
        }));
        entries.add(action("Restore newest backup", "Download it and review before restoring", () -> fileServerRestore(id)));
        List<SettingsAction> setup = new ArrayList<>();
        String where = FileServers.label(server.optString("kind")) + " · " + server.optString("host") + ":" + server.optInt("port")
            + (FileServers.folder(server).isEmpty() ? "" : " · /" + FileServers.folder(server));
        setup.add(action("Server details", where, () -> editFileServer(server)));
        setup.add(action("Test connection", "Check the address, sign-in, and folder", () -> testFileServer(server, () -> {})));
        setup.add(action("Remove this server", "Backups already on the server stay there", () -> {
            FileServers.remove(this, id);
            backups();
            toast("Removed " + server.optString("name"));
        }));
        SettingsPages.mount(root, prefs, List.of(new SettingsSection("Backups", entries), new SettingsSection("Server", setup)));
    }

    private void editFileServer(JSONObject existing) {
        render("fileServerEdit");
        Runnable back = existing == null ? this::backups : () -> fileServer(existing.optString("id"));
        fileServerBack = back;
        heading(existing == null ? "Add a file server" : "Server details", "Back", back);
        FileServerForm.mount(root, prefs, existing, (server, testOnly) -> {
            if (existing != null && server.optString("password").isEmpty()) {
                try { server.put("password", existing.optString("password")).put("hostKey", existing.optString("hostKey")); }
                catch (Exception ignored) {}
            }
            testFileServer(server, () -> {
                if (testOnly) return;
                try {
                    FileServers.save(prefs, server);
                    fileServer(server.optString("id"));
                    toast("Saved " + server.optString("name"));
                } catch (Exception error) { toast("Couldn't save the server"); }
            });
        });
    }

    private void testFileServer(JSONObject server, Runnable passed) {
        View currentPage = root;
        toast("Connecting to " + server.optString("host") + "…");
        new Thread(() -> {
            try {
                FileServers.test(this, server);
                runOnUiThread(() -> {
                    if (root != currentPage || isFinishing()) return;
                    toast("Connected · the folder is ready");
                    passed.run();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                runOnUiThread(() -> showDialog(dialogBuilder().setTitle("Couldn't connect")
                    .setMessage(message + "\n\nCheck the address, port, username, and password.").setPositiveButton("OK", null)));
            }
        }, "fluid-test-server").start();
    }

    private void fileServerBackupNow(String id) {
        var server = FileServers.find(prefs, id);
        if (server == null) return;
        openBackupForm("Back up to " + server.optString("name"), () -> fileServer(id), BackupFormMode.CLOUD_NOW,
            fileServerDefaults(server, false), selection -> runFileServer(id, "Backup saved to " + server.optString("name"),
                () -> FileServers.run(this, FileServers.find(prefs, id), backupOptions(selection), selection.getPassword())));
    }

    private void fileServerSchedule(String id) {
        var server = FileServers.find(prefs, id);
        if (server == null) return;
        openBackupForm("Schedule " + server.optString("name"), () -> fileServer(id), BackupFormMode.CLOUD,
            fileServerDefaults(server, true), selection -> {
                try {
                    var current = FileServers.find(prefs, id);
                    String password = selection.getKeepPassword() ? Privacy.open(prefs, current.optString("secret")) : selection.getPassword();
                    FileServers.schedule(this, current, backupOptions(selection), password, selection.getIntervalMinutes(), selection.getWifi());
                    fileServer(id);
                    toast("Backups scheduled to " + server.optString("name"));
                } catch (Exception error) { toast(error.getMessage()); }
            });
    }

    private void fileServerRestore(String id) {
        var server = FileServers.find(prefs, id);
        if (server == null) return;
        openBackupForm("Restore from " + server.optString("name"), () -> fileServer(id), BackupFormMode.CLOUD_RESTORE,
            fileServerDefaults(server, false), selection -> {
                Backup.Options options = backupOptions(selection);
                View currentPage = root;
                toast("Downloading the newest backup");
                new Thread(() -> {
                    java.io.File file = null;
                    try {
                        file = java.io.File.createTempFile("fluid-restore-", ".fmb", getCacheDir());
                        FileServers.newest(this, server, file);
                        restorePreview(Backup.read(this, Uri.fromFile(file), selection.getPassword()), options, currentPage,
                            () -> fileServer(id));
                    } catch (Exception error) {
                        String message = error.getMessage();
                        runOnUiThread(() -> toast(message == null ? "Couldn't download or unlock the newest backup" : message));
                    } finally { if (file != null) file.delete(); }
                }, "fluid-server-restore").start();
            });
    }

    private BackupFormDefaults fileServerDefaults(JSONObject server, boolean schedule) {
        var base = backupDefaults();
        String where = FileServers.label(server.optString("kind")) + " · " + server.optString("host");
        if (!schedule) return new BackupFormDefaults(base.getCategories(), base.getIntervalMinutes(), base.getWifi(), false,
            "Straight from this phone to " + where + ".");
        int minutes = server.optInt("minutes");
        return new BackupFormDefaults(minutes > 0 ? server.optInt("categories") : base.getCategories(),
            minutes > 0 ? minutes : base.getIntervalMinutes(), server.optBoolean("wifi"), minutes > 0 && server.has("secret"),
            "Back up to " + where + " automatically. Android runs it when the phone is online, about this often.");
    }

    private void runFileServer(String id, String success, CloudTransfer transfer) {
        View currentPage = root;
        toast("Backing up…");
        new Thread(() -> {
            try {
                transfer.run();
                runOnUiThread(() -> {
                    toast(success);
                    if (root == currentPage && !isFinishing()) fileServer(id);
                });
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> toast(message == null ? "The backup didn't finish" : message));
            }
        }, "fluid-server-backup").start();
    }

    private void backupFileName() {
        SecurityDialogs.form(this, prefs, "Backup file name", "A fixed name replaces that file in scheduled folders and on FluidMessages Web. "
            + "File pickers and sharing apps manage their own copies.", List.<String[]>of(new String[]{"File name", BackupNames.fixed(prefs),
            "Leave blank to use the date", "text"}), "Save", null, values -> {
            try {
                prefs.edit().putString(BackupNames.KEY, BackupNames.normalize(values[0])).apply();
                backups();
                return null;
            } catch (IllegalArgumentException error) { return error.getMessage(); }
        }, null);
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
                description += " A FluidMessages Web schedule is also active.";
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
            sections = List.of(new SettingsSection("FluidMessages Web backup", companionBackupActions(target)));
        } else {
            description = "FluidMessages Web · Not set up";
            sections = List.of(new SettingsSection("FluidMessages Web backup", List.of(
                action("Set up FluidMessages Web", "", () -> companion(() -> cloudDestination(target), true)))));
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
                    else toast("Configure " + target.label + " on your FluidMessages Web server first");
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast("Could not check FluidMessages Web’s backup destinations"));
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
                if (page.equals("privacy")) privacySettings();
                root.setVisibility(View.VISIBLE);
                checkUpdates(false);
                promptUpdate();
                if (pendingInstaller != null && canPromptUpdate()) installUpdate();
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
        var recolorHeading = heading("Appearance", "Done", this::settings);
        ThemeEditor.mount(root, prefs, background -> {
            recolorWindow(background);
            recolorHeading.accept(background);
        });
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
                if (export) picker.putExtra(Intent.EXTRA_TITLE, BackupNames.suggest(prefs));
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
                        file, BackupNames.suggest(prefs));
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
        var palette = uiColors();
        CheckBox view = new CheckBox(this);
        view.setText(label);
        view.setChecked(selected);
        view.setTextColor(palette.ink());
        view.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[0]},
            new int[]{palette.action(), palette.outline()}));
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
        if (request == RECORDING_FOLDER) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                // Keep access to the folder after restarts, so recordings can be saved there later.
                getContentResolver().takePersistableUriPermission(data.getData(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs.edit().putString(pickingVideoFolder ? "recordings.video" : "recordings.audio",
                    CallRecordings.TREE + data.getData()).apply();
            }
            recordingSettings();
            return;
        }
        if (request == INSTALL_SOURCE) {
            if (pendingInstaller != null && getPackageManager().canRequestPackageInstalls()) installUpdate();
            else {
                if (pendingInstaller != null) pendingInstaller.delete();
                pendingInstaller = null;
                updateStatus("Installation permission was not granted.");
            }
        } else if (request == CONTACT_EDITOR) {
            contacts.invalidate();
            if (page.equals("contact") && refreshContact != null) refreshContact.run();
        } else if (request == ROLE) {
            inbox();
            if (!hasRole()) smsRoleBlocked();
            if (hasRole()) {
                // Your own number lets group MMS replies join the group you're in instead of a new one.
                var missing = new ArrayList<String>();
                for (String permission : new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_NUMBERS}) {
                    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
                }
                if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 14);
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
        if (request == CALL_PERMISSION && pendingCall != null) {
            String number = pendingCall;
            pendingCall = null;
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) placeCall(number);
            else dialContact(number);
            return;
        }
        if (request == CALL_LOG_PERMISSION) { if (page.equals("phone")) phone(); return; }
        if (request == PICKER_CONTACT_PERMISSION) {
            contacts.invalidate();
            watchContacts();
            if (page.equals("recipients")) loadRecipients();
            else if (page.equals("contact") && refreshContact != null) refreshContact.run();
        }
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
        heading("FluidMessages Web", "Done", back);
        boolean connected = CloudBackup.paired(this);
        if (!connected && !setup) {
            SettingsPages.mount(root, prefs, List.of(new SettingsSection("Not set up", List.of(
                action("Set up FluidMessages Web", "", () -> companion(back, true))))));
            return;
        }
        item(root, text("In FluidMessages Web, open Settings → Your phone → Make a pairing token, then enter the web app's HTTPS address and that token here. "
            + "Pairing turns on cloud backups. Browser messaging also syncs the newest 2,000 texts and sends texts you write on the web.", 16, uiColors().muted()));
        EditText url = field("https://messages.example.net");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("server", ""));
        item(root, url);
        EditText token = field("Pairing token");
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(Privacy.token(prefs));
        item(root, token);
        CheckBox syncMessages = option(root, "Enable browser messaging",
            prefs.getBoolean("syncSms", CloudBackup.paired(this)));
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
                    .putString("token", Privacy.seal(prefs, pairingToken))
                    .putBoolean("syncSms", syncMessages.isChecked()).apply();
                SyncJob.reschedule(this);
                if (syncMessages.isChecked()) sync(true);
                back.run();
                toast("FluidMessages Web connection saved");
            } catch (Exception error) { toast("Enter the web app's https:// address and the pairing token from its Settings"); }
        }));
        if (connected) item(root, button("Disconnect phone", false, () -> {
            prefs.edit().remove("server").remove("token").apply();
            // Keeps the background job only if disappearing timers or phone-direct accounts still need it.
            SyncJob.reschedule(this);
            back.run();
        }));
    }
    /** Reloads everything from the start: texts, contacts, internet accounts, and FluidMessages Web. */
    private void resync() {
        toast("Resyncing…");
        invalidateRows();
        InboxSnapshot.clear(this);
        if (contacts.allowed()) contacts.invalidate();
        new Thread(() -> {
            try { DirectProtocols.restart(getApplicationContext()); } catch (Exception ignored) {}
            try { DirectProtocols.poll(getApplicationContext()); } catch (Exception ignored) {}
            runOnUiThread(() -> {
                invalidateRows();
                if (CloudBackup.paired(this)) sync(true);
                if (page.equals("inbox")) fillInbox();
            });
        }, "fluid-resync").start();
    }

    private void sync(boolean report) {
        boolean carrierSync = prefs.getBoolean("syncSms", true) && hasRole();
        if (syncing || !CloudBackup.paired(this) || (!carrierSync && !GatewayClient.wanted(prefs))) return;
        syncing = true;
        new Thread(() -> {
            try {
                boolean before = GatewayClient.hasRoutes(prefs);
                boolean updated = GatewayClient.refresh(this, prefs);
                if (updated) invalidateRows();
                if (updated) runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (page.equals("inbox") && !before && GatewayClient.hasRoutes(prefs)) inbox();
                    else if (page.equals("inbox")) fillInbox();
                    else if (page.equals("chat")) fillChat();
                });
                SyncJob.sync(getApplicationContext());
                if (report) runOnUiThread(() -> toast("FluidMessages Web synced"));
            } catch (Exception error) {
                if (report) runOnUiThread(() -> toast("Could not sync. Check the HTTPS certificate, address, and token."));
            } finally {
                runOnUiThread(() -> syncing = false);
            }
        }, "fluid-sync-now").start();
    }
    private void toast(String message) { Toast.makeText(this, message == null ? "Operation failed" : message, Toast.LENGTH_LONG).show(); }
}
