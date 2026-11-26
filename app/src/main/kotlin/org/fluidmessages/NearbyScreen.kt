package org.fluidmessages

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.LinearLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Plain words for Nearby, shared by Chat services and the Nearby page. */
internal object NearbyText {
    const val EXPLAINER = "Nearby lets you message FluidMessages friends close by with no internet or cell signal, over Bluetooth " +
        "or the same Wi-Fi. See who’s around under People nearby and tap someone to message them. Messages are locked so only " +
        "you two can read them."
    const val FIRST_HINT = "Ask the people with you to turn on Nearby too. They show up here; tap someone to message them."
    const val SET_UP = "Pick the name people close by see. You can change it later."
    const val BLACKOUT = "In a blackout: Nearby needs no internet and no cell signal. It works between FluidMessages phones with " +
        "Nearby on that are within Bluetooth range (about one room to a few houses) or on the same Wi-Fi (a router on battery, or a " +
        "phone’s hotspot, works with no internet). Phones carry messages for each other as people move around, so a message can " +
        "reach someone who isn’t near you, a little later. Nothing reaches phones without FluidMessages, and nothing goes beyond " +
        "the people around."
    const val OPEN_NOTICE = "Anyone nearby can read this channel. Messages are signed, so no one can change them or write as you."
    const val ROOM_NOTICE = "Locked with a passphrase: only people who know it can read this room."
    const val HELP = "Your phone keeps messages for other people nearby and hands them on, so news gets around with no internet. " +
        "It can’t read one-to-one messages it carries. It keeps at most 8 MB, for up to 7 days, and passes each message on at most 8 times."
    const val COMMUNITY = "While you’re in a channel or helping pass messages along, other FluidMessages phones nearby can see that " +
        "yours is here, by the name you chose. Who can see me nearby decides who lists you and who can send you a request."
    const val BLUETOOTH_DENIED = "Bluetooth is off for Nearby, so only phones on the same Wi-Fi are found. Allow Nearby devices to use Bluetooth."

    /** A channel message's status: how many phones it reached right away. It keeps going with the next phones met. */
    fun shared(phones: Int) = when (phones) {
        0 -> "Kept here · shares with the next phone nearby"
        1 -> "Shared with 1 phone nearby"
        else -> "Shared with $phones phones nearby"
    }

    /** The status on a waiting Nearby message: for a person, or for a channel while Nearby isn't running. */
    @JvmStatic fun waitingStatus(prefs: SharedPreferences, message: org.json.JSONObject): String {
        val route = DirectProtocols.route(prefs, message.optString("address"), MessageProtocol.NEARBY)
        return if (route?.optBoolean("group") == true) "Waiting to share · turn on Nearby" else NearbyWire.waiting(message.optString("title"))
    }
    const val DISCOVERABLE = "Keeps Nearby running when FluidMessages is closed, with an ongoing notification. This uses more battery."
    const val NEARBY_ONLY = "FluidMessages makes no internet connections at all: no chat services, no FluidMessages Web, no " +
        "backups to the web, no link previews, pictures or GIFs from the web, and no update checks. Only Nearby works."
    const val TEXTS = "Texts use the cell network, not the internet. Turn this off to stop sending texts too. Texts that arrive still show up."

    fun title(step: String) = when (step) {
        "code" -> "Your Nearby code"
        "scan" -> "Scan their code"
        "type" -> "Type their code"
        "pair" -> "Add nearby person"
        "join" -> "Join a channel"
        "new" -> "People nearby"
        "guide", "guide:settings" -> OfflineGuide.TITLE
        else -> if (step.startsWith("verify:")) "Verify" else "Nearby"
    }
}

object NearbyScreen {
    @JvmStatic
    fun title(step: String) = NearbyText.title(step)

    /**
     * The Nearby pages: [step] is "home", "code", "scan", "type", "pair", "guide", or "intro" ("guide:settings" and
     * "intro:settings" from Settings → Help). [go] opens another step; [open] opens a chat; [back] leaves, as the
     * heading's Back does (Not now on the first-time What is Nearby? page).
     */
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, step: String, go: Consumer<String>, open: Consumer<String>, back: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(Appearance.read(prefs)) {
                val context = LocalContext.current
                val me = remember { mutableStateOf(DirectNearby.identity(context)) }
                val missing = remember { NoiseCrypto.selfCheck() }
                // The first time Nearby is turned on, What is Nearby? comes before the name step.
                var introDue by remember { mutableStateOf(NearbyIntro.due(prefs, me.value != null)) }
                when {
                    // The page heading already says the title, so the guide starts with its subtitle.
                    step == "guide" -> OfflineGuidePage(prefs, null, showTitle = false)
                    step == "guide:settings" -> OfflineGuidePage(prefs, { go.accept("home") }, showTitle = false)
                    step == "intro" || step == "intro:settings" -> {
                        val on = me.value != null && MessageProtocol.NEARBY.enabled(prefs)
                        NearbyIntroPage(prefs, on, start = {
                            NearbyIntro.markSeen(prefs)
                            // Set up but switched off: Turn on Nearby turns it back on.
                            if (me.value != null && !on) {
                                prefs.edit().putBoolean(MessageProtocol.NEARBY.key(), true).apply()
                                restartQuietly(context)
                            }
                            go.accept("home")
                        }, later = { back.run() })
                    }
                    // A phone without X25519 or ChaCha20-Poly1305: Nearby says so and stays off, rather than failing later.
                    missing.isNotEmpty() -> Page { item { Note(missing, MaterialTheme.colorScheme.error) } }
                    me.value == null && introDue -> NearbyIntroPage(prefs, false, start = {
                        NearbyIntro.markSeen(prefs)
                        introDue = false
                    }, later = { back.run() })
                    // Turning on asks only for a name, then goes straight to People nearby.
                    me.value == null -> SetUp(prefs, null) {
                        me.value = DirectNearby.identity(context)
                        if (me.value != null) { prefs.edit().putBoolean(NearbyPeople.FIRST_HINT, true).apply(); go.accept("new") }
                    }
                    step == "new" -> NewChatPage(prefs, me.value!!, go, open)
                    step.startsWith("verify:") -> VerifyPage(step.removePrefix("verify:"), open)
                    step == "join" -> JoinPage(open)
                    step == "code" -> CodePage(me.value!!)
                    step == "scan" -> ScanPage(go, open)
                    step == "type" -> TypePage(go, open)
                    step == "pair" -> PairPage(prefs, open)
                    else -> HomePage(prefs, me.value!!, go, open) { me.value = DirectNearby.identity(context) }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

private fun restartQuietly(context: Context) = Thread({ DirectProtocols.restart(context) }, "fluid-nearby-restart").start()

@Composable
private fun Page(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp), content = content)
}

@Composable
private fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, Modifier.padding(horizontal = 12.dp), color = color, style = MaterialTheme.typography.bodyMedium)

/** First visit: pick the name people see when they add you. Saving makes your keys and turns Nearby on. */
@Composable
private fun SetUp(prefs: SharedPreferences, current: String?, done: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(current.orEmpty()) }
    // The phone owner's first name, if the app can read it; otherwise blank.
    if (current == null) LaunchedEffect(Unit) {
        val owner = withContext(Dispatchers.IO) { DirectNearby.ownerName(context) }
        if (name.isEmpty()) name = owner
    }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Bluetooth is asked for only now, as Nearby turns on. Saying no still turns Nearby on, for Wi-Fi only.
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        DirectNearby.current?.bluetoothChanged()
        done()
    }
    Page {
        if (current == null) item { Note(NearbyText.SET_UP, MaterialTheme.colorScheme.onSurface) }
        item {
            PreferencesCard("Your name for Nearby") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it; error = "" }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Name") }, supportingText = { Text("People close by see this and a short tag. Never your number.") })
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    AppButton(enabled = !busy && name.isNotBlank(), onClick = {
                        busy = true
                        Thread({
                            val failure = try {
                                DirectNearby.setUp(context, name, DirectProtocols.stayConnected(prefs, MessageProtocol.NEARBY))
                                if (current == null) prefs.edit().putBoolean(MessageProtocol.NEARBY.key(), true).apply()
                                DirectProtocols.restart(context)
                                ""
                            } catch (bad: IllegalArgumentException) { bad.message.orEmpty() }
                            catch (missing: IllegalStateException) { missing.message ?: "Couldn’t save. Try again." }
                            catch (other: Exception) { "Couldn’t save. Try again." }
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                busy = false
                                when {
                                    failure.isNotEmpty() -> error = failure
                                    current == null && !DirectNearby.bluetoothAllowed(context) -> bluetooth.launch(DirectNearby.BLUETOOTH_PERMISSIONS)
                                    else -> done()
                                }
                            }
                        }, "fluid-nearby-setup").start()
                    }) { Text(if (current == null) "Turn on Nearby" else "Save") }
                }
            }
        }
    }
}

@Composable
private fun HomePage(prefs: SharedPreferences, me: DirectNearby.Identity, go: Consumer<String>, open: Consumer<String>, changed: () -> Unit) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(MessageProtocol.NEARBY.enabled(prefs)) }
    var discoverable by remember { mutableStateOf(DirectProtocols.stayConnected(prefs, MessageProtocol.NEARBY)) }
    var nearbyOnly by remember { mutableStateOf(NetworkGate.nearbyOnly(prefs)) }
    var texts by remember { mutableStateOf(prefs.getBoolean(NetworkGate.TEXTS, true)) }
    var people by remember { mutableStateOf(DirectNearby.contacts(context)) }
    var channels by remember { mutableStateOf(DirectNearby.channels(context)) }
    var helping by remember { mutableStateOf(DirectNearby.helping(prefs)) }
    var bluetoothOn by remember { mutableStateOf(DirectNearby.bluetoothOn(prefs)) }
    var bluetoothAllowed by remember { mutableStateOf(DirectNearby.bluetoothAllowed(context)) }
    var leaving by remember { mutableStateOf("") }
    var carried by remember { mutableLongStateOf(0L) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        bluetoothAllowed = DirectNearby.bluetoothAllowed(context)
        DirectNearby.current?.bluetoothChanged()
    }
    val visible = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    LaunchedEffect(Unit) { while (true) { carried = withContext(Dispatchers.IO) { DirectNearby.carriedBytes(context) }; delay(10_000) } }
    // Older identities get the key that signs channel messages.
    LaunchedEffect(Unit) { if (me.sign == null) withContext(Dispatchers.IO) { runCatching { DirectNearby.ensureSigningKey(context) } } }
    var tick by remember { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    if (renaming) { SetUp(prefs, me.name) { renaming = false; changed() }; return }
    val node = remember(tick) { DirectNearby.current }
    Page {
        if (on) item { PeopleNearbyCard(prefs, tick, open) }
        item {
            PreferencesCard("Nearby") {
                SettingsSwitch("Nearby", "Message FluidMessages friends close by, over Bluetooth or the same Wi-Fi, with no internet.", on) {
                    on = it
                    prefs.edit().putBoolean(MessageProtocol.NEARBY.key(), it).apply()
                    restartQuietly(context)
                }
                val line = when {
                    !on -> "Off. Your people and messages stay."
                    node?.problem?.isNotEmpty() == true -> node.problem
                    node?.searching() == true -> node.connected().let { (all, known) ->
                        when {
                            all == 0 -> "Looking for phones nearby" + if (node.usingBluetooth()) ", over Wi-Fi and Bluetooth" else " on this Wi-Fi"
                            known == all -> "Connected to $all of your people"
                            else -> "Connected to $all phones nearby, $known of them your people"
                        }
                    }
                    else -> "Starting…"
                }
                PreferenceText("You are “${me.name}”", line)
                TextButton(onClick = { renaming = true }, Modifier.padding(start = 6.dp)) { Text("Change your name") }
            }
        }
        item { AddInPerson(go) }
        item {
            PreferencesCard("People") {
                if (people.isEmpty()) PreferenceText("No one yet", "Tap someone under People nearby, or add someone in person.")
                for (person in people) {
                    val key = person.optString("target")
                    val address = person.optString("address")
                    val name = person.optString("title")
                    val seen = DirectNearby.lastSeen(context, key)
                    val state = when {
                        node?.nearbyNow(key) == true -> "Nearby now"
                        seen > 0 -> "Last nearby " + android.text.format.DateUtils.getRelativeTimeSpanString(seen)
                        else -> "Not seen yet. They need to add you too."
                    }
                    val trust = DirectNearby.person(context, person)
                    val mark = when {
                        trust.changedKey.isNotEmpty() -> " · key changed"
                        NearbyPeople.verified(trust) -> " · Verified"
                        else -> " · Not verified yet"
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp)) {
                        PreferenceText(name, state + mark, Modifier)
                        if (removing == address) {
                            Text("Remove $name? Your earlier messages stay. To message again, you’d add each other again.",
                                Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { removing = "" }) { Text("Cancel") }
                                AppButton(onClick = {
                                    DirectNearby.remove(context, address)
                                    removing = ""
                                    people = DirectNearby.contacts(context)
                                }) { Text("Remove") }
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { open.accept(address) }) { Text("Message") }
                            TextButton(onClick = { removing = address }) { Text("Remove") }
                        }
                    }
                }
            }
        }
        item {
            PreferencesCard("Channels") {
                if (channels.isEmpty()) PreferenceText("No channels yet", "Channels are rooms for everyone nearby, like “Neighborhood”.")
                for (channel in channels) {
                    val address = channel.optString("address")
                    val title = channel.optString("title")
                    Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp)) {
                        PreferenceText(title, if (channel.optBoolean("room")) NearbyText.ROOM_NOTICE else "Open · anyone nearby can read it", Modifier)
                        if (leaving == address) {
                            Text("Leave $title? Its messages stay in your chats.", Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { leaving = "" }) { Text("Cancel") }
                                AppButton(onClick = {
                                    DirectNearby.remove(context, address)
                                    leaving = ""
                                    channels = DirectNearby.channels(context)
                                }) { Text("Leave") }
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { open.accept(address) }) { Text("Open") }
                            TextButton(onClick = { leaving = address }) { Text("Leave") }
                        }
                    }
                }
                TextButton(onClick = { go.accept("join") }, Modifier.padding(start = 6.dp)) { Text("Join or start a channel") }
            }
        }
        item { VisibilityCard(prefs, helping) }
        item {
            PreferencesCard("Passing messages along") {
                SettingsSwitch("Help pass messages along", NearbyText.HELP, helping) {
                    helping = it
                    prefs.edit().putBoolean(DirectNearby.HELP, it).apply()
                    DirectNearby.current?.communityChanged()
                }
                Text("Holding ${android.text.format.Formatter.formatShortFileSize(context, carried)} of messages now. " + NearbyText.COMMUNITY,
                    Modifier.padding(horizontal = 15.dp).padding(bottom = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        item {
            PreferencesCard("Bluetooth") {
                SettingsSwitch("Use Bluetooth too", "Finds phones in Bluetooth range, with no Wi-Fi. Uses a little more battery.", bluetoothOn) {
                    bluetoothOn = it
                    prefs.edit().putBoolean(DirectNearby.BLUETOOTH, it).apply()
                    if (it && !DirectNearby.bluetoothAllowed(context)) askBluetooth.launch(DirectNearby.BLUETOOTH_PERMISSIONS)
                    else DirectNearby.current?.bluetoothChanged()
                }
                if (bluetoothOn && !bluetoothAllowed) {
                    Text(NearbyText.BLUETOOTH_DENIED, Modifier.padding(horizontal = 15.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    TextButton(onClick = { askBluetooth.launch(DirectNearby.BLUETOOTH_PERMISSIONS) }, Modifier.padding(start = 6.dp)) {
                        Text("Allow Bluetooth")
                    }
                }
                if (bluetoothOn && bluetoothAllowed) {
                    Text("Phones you’ve met over Bluetooth find each other on their own. To be found by a phone for the first time, " +
                        "let it see yours for 5 minutes. Android asks first.", Modifier.padding(horizontal = 15.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    TextButton(onClick = {
                        runCatching {
                            visible.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
                        }
                    }, Modifier.padding(start = 6.dp)) { Text("Let phones find mine over Bluetooth") }
                }
            }
        }
        item {
            PreferencesCard("Battery and internet") {
                SettingsSwitch("Stay discoverable", NearbyText.DISCOVERABLE, discoverable) {
                    discoverable = it
                    Thread({ DirectNearby.setDiscoverable(context, it) }, "fluid-nearby-discoverable").start()
                }
                if (discoverable) Text("Nearby keeps looking for your people in the background, which drains the battery faster.",
                    Modifier.padding(horizontal = 15.dp).padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                SettingsSwitch("Nearby only (no internet)", NearbyText.NEARBY_ONLY, nearbyOnly) {
                    nearbyOnly = it
                    prefs.edit().putBoolean(NetworkGate.NEARBY_ONLY, it).apply()
                    restartQuietly(context)
                }
                if (nearbyOnly) SettingsSwitch("Allow texts over the cell network", NearbyText.TEXTS, texts) {
                    texts = it
                    prefs.edit().putBoolean(NetworkGate.TEXTS, it).apply()
                }
            }
        }
        item { ConnectByAddress(node) }
        item { GuideLink(go) }
        item { Note(NearbyText.BLACKOUT) }
    }
}

/**
 * People nearby: FluidMessages phones close by that chose to be seen, with their name, a short tag from their key,
 * and how they're close by. Tap one to message them. Look for people on this Wi-Fi finds phones afresh, and on
 * networks that block finding phones, tries this phone's own Wi-Fi network for a moment (DirectNearby.lookOnWifi),
 * only while this page is open.
 */
@Composable
private fun PeopleNearbyCard(prefs: SharedPreferences, tick: Int, open: Consumer<String>) {
    val context = LocalContext.current
    var people by remember { mutableStateOf(emptyList<DirectNearby.Near>()) }
    var look by remember { mutableStateOf<DirectNearby.Look?>(null) }
    var lookLine by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf(prefs.getBoolean(NearbyPeople.FIRST_HINT, false)) }
    LaunchedEffect(tick) { people = DirectNearby.current?.peopleNearby().orEmpty() }
    LaunchedEffect(look) {
        val running = look ?: return@LaunchedEffect
        while (true) {
            lookLine = running.problem.ifEmpty { NearbyPeople.lookingLine(running.done, running.found) }
            people = DirectNearby.current?.peopleNearby().orEmpty()
            if (running.done || running.cancelled) break
            delay(400)
        }
    }
    DisposableEffect(Unit) { onDispose { look?.cancel() } }
    PreferencesCard("People nearby") {
        if (hint) Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(NearbyText.FIRST_HINT, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            TextButton(onClick = { hint = false; prefs.edit().remove(NearbyPeople.FIRST_HINT).apply() }) { Text("OK") }
        }
        if (people.isEmpty()) PreferenceText("No one yet",
            "FluidMessages phones with Nearby on show here when they’re on your Wi-Fi or within Bluetooth range.")
        for (person in people) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "Message ${person.name}") {
                try {
                    val route = person.address.ifEmpty { DirectNearby.current?.startChat(person.key)?.optString("address").orEmpty() }
                    if (route.isNotEmpty()) { prefs.edit().remove(NearbyPeople.FIRST_HINT).apply(); open.accept(route) }
                } catch (bad: Exception) { error = bad.message ?: "Couldn’t start a chat. Try again." }
            }.padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val detail = person.way + (if (person.added) " · in your people" else "") +
                    (if (person.sameNameAs.isNotEmpty()) " · not the ${person.sameNameAs} you added" else "")
                PreferenceText(person.name + " · " + person.tag, detail, Modifier.weight(1f))
                Text("Message", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
        if (error.isNotEmpty()) Text(error, Modifier.padding(horizontal = 15.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        if (lookLine.isNotEmpty()) Row(Modifier.padding(horizontal = 15.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (look?.done == false) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(lookLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        TextButton(enabled = look?.done != false, onClick = {
            error = ""
            look = DirectNearby.current?.lookOnWifi()
            if (look == null) lookLine = "Nearby is starting. Try again in a moment."
        }, modifier = Modifier.padding(start = 6.dp)) { Text("Look for people on this Wi-Fi") }
        Text("Only people who chose to be seen are listed. A chat with someone you met here says Not verified yet until you " +
            "compare a safety code with them.", Modifier.padding(horizontal = 15.dp).padding(bottom = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

/** The other ways to start a chat: in person, with a code or by comparing a short code. */
@Composable
private fun AddInPerson(go: Consumer<String>) {
    PreferencesCard("Or add someone in person") {
        for ((step, title, detail) in listOf(
            Triple("code", "Show my code", "They scan it, or type it in"),
            Triple("scan", "Scan their code", "With the camera, or from a picture of it"),
            Triple("pair", "Add nearby person", "No camera needed: find each other on this Wi-Fi and compare a short code"),
            Triple("type", "Type their code", "Paste a code they shared as text"))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 55.dp).padding(horizontal = 15.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PreferenceText(title, detail, Modifier.weight(1f))
                TextButton(onClick = { go.accept(step) }) { Text("Open") }
            }
        }
        Text("People added in person are verified right away.", Modifier.padding(horizontal = 15.dp).padding(bottom = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

/** Who can see me nearby: everyone nearby, only people you've added, or no one; and what others see. */
@Composable
private fun VisibilityCard(prefs: SharedPreferences, helping: Boolean) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(DirectNearby.visibility(prefs)) }
    PreferencesCard("Who can see me nearby") {
        for (option in NearbyPeople.Visibility.values()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(choice == option, role = Role.RadioButton) {
                choice = option
                DirectNearby.setVisibility(context, option)
            }.padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(choice == option, onClick = null)
                PreferenceText(option.title, option.line, Modifier.weight(1f).padding(start = 12.dp))
            }
        }
        Text(NearbyPeople.WHAT_THEY_SEE, Modifier.padding(horizontal = 15.dp).padding(bottom = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        if (choice != NearbyPeople.Visibility.EVERYONE && helping) Text(NearbyPeople.HIDDEN_RELAY,
            Modifier.padding(horizontal = 15.dp).padding(bottom = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun GuideLink(go: Consumer<String>) {
    PreferencesCard("Help") {
        for ((step, title, detail) in listOf(
            Triple("intro", NearbyIntro.TITLE, "What it does, who it’s for, and what it can’t do"),
            Triple("guide", OfflineGuide.TITLE, "Texts, Nearby, and other ways to reach people when the internet is down"))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 55.dp).clickable(role = Role.Button) { go.accept(step) }
                .padding(horizontal = 15.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PreferenceText(title, detail, Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary, fontSize = 26.sp)
            }
        }
    }
}

/** New chat on Nearby: People nearby first, then the ways to add someone in person. */
@Composable
private fun NewChatPage(prefs: SharedPreferences, me: DirectNearby.Identity, go: Consumer<String>, open: Consumer<String>) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    Page {
        item { PeopleNearbyCard(prefs, tick, open) }
        item { Note("You are “${me.name}” · ${NearbyPeople.tag(me.keys.public)}") }
        item { AddInPerson(go) }
        item { GuideLink(go) }
    }
}

/**
 * Verify <name>: compare the safety code while their phone is connected, or scan or type their code. When their key
 * changed, check with them first, then use the new key or keep the old one.
 */
@Composable
private fun VerifyPage(address: String, open: Consumer<String>) {
    val context = LocalContext.current
    val prefs = remember { DirectProtocols.prefs(context) }
    var route by remember { mutableStateOf(DirectProtocols.route(prefs, address, MessageProtocol.NEARBY)) }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            code = route?.optString("target")?.let { DirectNearby.current?.safetyCode(it) }.orEmpty()
            delay(1500)
        }
    }
    val current = route ?: run { Page { item { Note("This chat isn’t on this phone anymore.") } }; return }
    val name = current.optString("title").substringBefore(" · ")
    val trust = DirectNearby.person(prefs, current)
    fun refresh() { route = DirectProtocols.route(prefs, address, MessageProtocol.NEARBY) }
    fun checkCard(text: String) {
        val card = NearbyWire.parseCard(text)
        note = when {
            card == null -> "That isn’t a Nearby code. Ask them to open Nearby → Show my code."
            DirectNearby.verifyCard(context, address, card) -> { refresh(); "Verified. It’s the same key." }
            else -> "That code holds a different key. Don’t trust this chat until you’ve checked with $name in person."
        }
    }
    val owner = context as? androidx.lifecycle.LifecycleOwner
    var live by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) live = true else note = "Camera access is off. You can type their code instead, or compare the safety code."
    }
    Page {
        item {
            val tone = if (trust.changedKey.isNotEmpty() || !NearbyPeople.verified(trust)) Encryption.UNVERIFIED else Encryption.END_TO_END
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EncryptionBadge(tone)
                Text(NearbyPeople.lockWords(trust, name), color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (trust.changedKey.isNotEmpty()) item {
            PreferencesCard("$name’s key changed") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("A phone close by says it’s $name (“${current.optString("changed_name").ifEmpty { name }}” · " +
                        NearbyPeople.tag(NoiseCrypto.hex(trust.changedKey)) + ") but has a different key from the one this chat is " +
                        "locked to. That happens when someone gets a new phone or reinstalls FluidMessages, or when someone pretends to be them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Check with $name in person before you use the new key. Until then, nothing is sent in this chat.",
                        color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { DirectNearby.keepOldKey(context, address); refresh() }) { Text("Keep the old key") }
                        AppButton(onClick = { DirectNearby.acceptNewKey(context, address); refresh() }) { Text("Use the new key") }
                    }
                }
            }
        }
        if (trust.changedKey.isEmpty()) item {
            PreferencesCard("Compare the safety code") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (code.isEmpty()) Text("$name’s phone isn’t connected right now. Be close by, on the same Wi-Fi or within Bluetooth range.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text(code, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Text("Ask $name to open this chat’s details → Verify on their phone. If both phones show the same code, " +
                            "it’s really them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!NearbyPeople.verified(trust)) AppButton(onClick = { DirectNearby.markVerified(context, address); refresh(); note = "Verified." }) {
                            Text("It matches")
                        }
                    }
                }
            }
        }
        if (trust.changedKey.isEmpty() && !NearbyPeople.verified(trust)) item {
            PreferencesCard("Or check their code") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (live && owner != null) Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).semantics { contentDescription = "Camera view" }) {
                        CameraScanner(owner) { text -> live = false; checkCard(text) }
                    } else if (owner != null) AppButton(onClick = {
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) live = true
                        else permission.launch(Manifest.permission.CAMERA)
                    }, Modifier.fillMaxWidth()) { Text("Scan their code") }
                    OutlinedTextField(typed, { typed = it; note = "" }, Modifier.fillMaxWidth(), minLines = 2,
                        label = { Text("Or paste their code (fluidnearby:1:…)") })
                    OutlinedButton(enabled = typed.isNotBlank(), onClick = { checkCard(typed) }) { Text("Check") }
                }
            }
        }
        if (note.isNotEmpty()) item { Note(note, MaterialTheme.colorScheme.onSurface) }
        item {
            TextButton(onClick = { open.accept(address) }, Modifier.padding(start = 6.dp)) { Text("Back to the chat") }
        }
    }
}

/** For Wi-Fi that blocks phones from finding each other: connect to the address the other phone shows. */
@Composable
private fun ConnectByAddress(node: DirectNearby?) {
    var address by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    PreferencesCard("Can’t find each other?") {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val mine = node?.addresses().orEmpty()
            Text(if (mine.isEmpty()) "This phone has no Wi-Fi address right now." else "This phone: " + mine.joinToString(", "),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            OutlinedTextField(address, { address = it; note = "" }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Their address, like 192.168.1.20:47610") })
            AppButton(enabled = node != null && address.isNotBlank(), onClick = {
                val host = address.trim().substringBeforeLast(':')
                val port = address.trim().substringAfterLast(':', "").toIntOrNull() ?: DirectNearby.PORT
                if (host.isBlank() || port !in 1..65535) note = "Enter an address like 192.168.1.20:47610"
                else { node?.connectToAddress(host, port); note = "Connecting. If you’ve added each other, they show as Nearby now." }
            }) { Text("Connect") }
            if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CodePage(me: DirectNearby.Identity) {
    val context = LocalContext.current
    val text = remember(me) { NearbyWire.encodeCard(me.card) }
    Page {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QrCode(text, Modifier.size(280.dp).semantics { contentDescription = "Your Nearby code, for ${me.name}" })
                Text(me.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Let them scan this with FluidMessages: Settings → Chat services → Nearby → Scan their code. Then scan theirs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        item {
            PreferencesCard("Your code as text") {
                SelectionContainer { Text(text, Modifier.padding(15.dp), fontSize = 13.sp) }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Share my Nearby code"))
                }, Modifier.padding(start = 6.dp)) { Text("Share my code as text") }
            }
        }
        item { Note("Your code only lets people message you once you add them back. It holds your name and a public key, nothing secret.") }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    // Always black on white, so any camera can read it in any theme.
    Canvas(modifier.background(Color.White)) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
        }
    }
}

/** Reads a QR code from a photo or picture, or null when there isn't one. */
internal fun readQr(context: Context, uri: Uri): String? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    if (bitmap == null) null else {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        try { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
        catch (missed: Exception) { QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints).text }
    }
} catch (error: Exception) { null }

/** Confirms the person in a code, then adds them, all in place. */
@Composable
private fun AddCard(card: NearbyWire.Card, go: Consumer<String>, open: Consumer<String>, again: () -> Unit) {
    val context = LocalContext.current
    var added by remember(card) { mutableStateOf<String?>(null) }
    var error by remember(card) { mutableStateOf("") }
    PreferencesCard(if (added == null) "Add this person?" else "Added") {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleLarge)
            if (added == null) {
                Text("Only add someone who is with you now and showed you this code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = again) { Text("Not them") }
                    AppButton(onClick = {
                        try { added = DirectNearby.add(context, card).optString("address") }
                        catch (bad: Exception) { error = bad.message ?: "Couldn’t add them" }
                    }) { Text("Add ${card.name}") }
                }
            } else {
                Text("Now ${card.name} adds you too: show them your code, or they type it in. Then you can message each other nearby.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { go.accept("code") }) { Text("Show my code") }
                    AppButton(onClick = { open.accept(added!!) }) { Text("Message") }
                }
            }
        }
    }
}

@Composable
private fun ScanPage(go: Consumer<String>, open: Consumer<String>) {
    val context = LocalContext.current
    var card by remember { mutableStateOf<NearbyWire.Card?>(null) }
    var note by remember { mutableStateOf("") }
    val photo = remember { File(File(context.cacheDir, "attachments").apply { mkdirs() }, "nearby-code.jpg") }
    val photoUri = remember { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".backup-files", photo) }
    fun read(uri: Uri?) {
        if (uri == null) return
        Thread({
            val text = readQr(context, uri)
            val parsed = text?.let(NearbyWire::parseCard)
            if (uri == photoUri) photo.delete()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                card = parsed
                note = when {
                    parsed != null -> ""
                    text != null -> "That isn’t a Nearby code. Ask them to open Your Nearby code."
                    else -> "No code found. Hold the phone steady, fill the photo with the code, and try again."
                }
            }
        }, "fluid-nearby-read").start()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) read(photoUri) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { read(it) }
    // The live camera (CameraX, the same scanner as FluidMessages Web pairing), with a photo or a picture as the fallback.
    val owner = context as? androidx.lifecycle.LifecycleOwner
    var live by remember { mutableStateOf(false) }
    var wantsPhoto by remember { mutableStateOf(false) }
    val denied = "Camera access is off for FluidMessages. You can still choose a picture of their code, type their code, or use Add nearby person."
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            !granted -> note = denied
            wantsPhoto -> camera.launch(photoUri)
            else -> live = true
        }
    }
    val cameraAllowed = { context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    Page {
        card?.let { found -> item { AddCard(found, go, open) { card = null } } }
        if (card == null && live && owner != null) item {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer).semantics { contentDescription = "Camera view" }) {
                CameraScanner(owner) { text ->
                    val parsed = NearbyWire.parseCard(text)
                    if (parsed != null) { card = parsed; live = false; note = "" }
                    else note = "That isn’t a Nearby code. Ask them to open Your Nearby code."
                }
            }
        }
        if (card == null) item {
            PreferencesCard("Their code") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ask them to open Settings → Chat services → Nearby → Show my code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!live && owner != null) AppButton(onClick = {
                        wantsPhoto = false
                        if (cameraAllowed()) live = true else permission.launch(Manifest.permission.CAMERA)
                    }, Modifier.fillMaxWidth()) { Text("Scan with the camera") }
                    OutlinedButton(onClick = {
                        wantsPhoto = true
                        live = false
                        if (cameraAllowed()) camera.launch(photoUri) else permission.launch(Manifest.permission.CAMERA)
                    }, Modifier.fillMaxWidth()) { Text("Take a photo of their code") }
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.fillMaxWidth()) { Text("Choose a picture of their code") }
                    if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 6.dp)) {
                TextButton(onClick = { go.accept("type") }) { Text("Type their code") }
                TextButton(onClick = { go.accept("pair") }) { Text("Add nearby person") }
            }
        }
    }
}

@Composable
private fun TypePage(go: Consumer<String>, open: Consumer<String>) {
    var text by remember { mutableStateOf("") }
    var card by remember { mutableStateOf<NearbyWire.Card?>(null) }
    var error by remember { mutableStateOf("") }
    Page {
        card?.let { found -> item { AddCard(found, go, open) { card = null; text = "" } } }
        if (card == null) item {
            PreferencesCard("Their code") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(text, { text = it; error = "" }, Modifier.fillMaxWidth(), minLines = 3,
                        label = { Text("Starts with fluidnearby:1:") })
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    AppButton(enabled = text.isNotBlank(), onClick = {
                        card = NearbyWire.parseCard(text)
                        if (card == null) error = "That isn’t a whole Nearby code. Check that nothing is missing."
                    }) { Text("Next") }
                }
            }
        }
        item { Note("Get their code from them in person, or from a message you trust. Anyone with your code can only reach you once you add them back.") }
    }
}

/** Add nearby person: both phones on this page find each other and show one safety code; each person taps It matches. */
@Composable
private fun PairPage(prefs: SharedPreferences, open: Consumer<String>) {
    val context = LocalContext.current
    var candidates by remember { mutableStateOf(emptyList<DirectNearby.Candidate>()) }
    var added by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val on = MessageProtocol.NEARBY.enabled(prefs)
    LaunchedEffect(Unit) {
        while (true) {
            val node = DirectNearby.current
            node?.startPairing()
            candidates = node?.candidates().orEmpty()
            delay(1500)
        }
    }
    DisposableEffect(Unit) { onDispose { DirectNearby.current?.stopPairing() } }
    Page {
        item {
            Note(if (!on) "Turn on Nearby first, on the Nearby page."
            else "Ask the other person to open Add nearby person on their phone too. Stay on the same Wi-Fi. Their name appears below.",
                MaterialTheme.colorScheme.onSurface)
        }
        for ((name, address) in added) item {
            PreferencesCard("Added") {
                PreferenceText(name, "Once they tap It matches on their phone too, you can message each other.")
                TextButton(onClick = { open.accept(address) }, Modifier.padding(start = 6.dp)) { Text("Message $name") }
            }
        }
        if (on && candidates.isEmpty()) item {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Looking for phones on this Wi-Fi…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (candidate in candidates) item(candidate.key) {
            PreferencesCard(candidate.name) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Safety code", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(candidate.code, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Check that ${candidate.name}’s phone shows the same code. If it doesn’t, don’t add them.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppButton(onClick = {
                        try {
                            val route = DirectNearby.add(context, NearbyWire.Card(NoiseCrypto.hex(candidate.key), candidate.name, candidate.id))
                            added = added + (candidate.name to route.optString("address"))
                            candidates = candidates - candidate
                        } catch (error: Exception) {}
                    }) { Text("It matches") }
                }
            }
        }
        item { Note("The code is worked out from the locked connection between your two phones. If someone else were in between, the codes wouldn’t match.") }
    }
}

/** Chat services → Nearby: the switch, what it is in plain words, and the way to the Nearby page. */
@Composable
internal fun NearbyServiceCard(prefs: SharedPreferences, openNearby: Runnable) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(MessageProtocol.NEARBY.enabled(prefs)) }
    PreferencesCard("Nearby") {
        SettingsSwitch("Nearby", MessageProtocol.NEARBY.description, on) {
            if (it && DirectNearby.identity(context) == null) { openNearby.run(); return@SettingsSwitch }
            on = it
            prefs.edit().putBoolean(MessageProtocol.NEARBY.key(), it).apply()
            restartQuietly(context)
        }
        Text(NearbyText.EXPLAINER, Modifier.padding(horizontal = 15.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Text(NearbyText.BLACKOUT, Modifier.padding(horizontal = 15.dp).padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        if (NetworkGate.nearbyOnly(prefs)) Text("Nearby only is on: FluidMessages uses no internet.", Modifier.padding(horizontal = 15.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { openNearby.run() }, Modifier.padding(start = 6.dp)) {
            Text(if (DirectNearby.identity(context) == null) "Set up Nearby" else "Open Nearby: your code, people, and settings")
        }
    }
}

/** The inbox banner while Nearby only is on, with Turn off. */
@Composable
internal fun NearbyOnlyBanner(prefs: SharedPreferences, ink: Color, muted: Color, action: Color) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(NetworkGate.nearbyOnly(prefs)) }
    val text = NetworkGate.banner(on)
    if (text.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, color = ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Only Nearby works.", color = muted, fontSize = 13.sp)
        }
        TextButton(onClick = {
            prefs.edit().putBoolean(NetworkGate.NEARBY_ONLY, false).apply()
            on = false
            restartQuietly(context)
        }) { Text("Turn off", color = action) }
    }
}

/**
 * Join a channel: type a name for an open channel anyone nearby can read, or add a passphrase for a room only people
 * who know it can read. Open channels other phones are carrying show below, to join with one tap.
 */
@Composable
private fun JoinPage(open: Consumer<String>) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(emptyList<Pair<String, Int>>()) }
    LaunchedEffect(Unit) { while (true) { seen = withContext(Dispatchers.IO) { DirectNearby.channelsSeen(context) }; delay(5000) } }
    fun join(channel: String, secret: String) {
        busy = true
        Thread({
            val result = try { Result.success(DirectNearby.join(context, channel, secret).optString("address")) } catch (bad: Exception) { Result.failure(bad) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                busy = false
                result.onSuccess { open.accept(it) }.onFailure { error = (it as? IllegalArgumentException)?.message ?: it.message ?: "Couldn’t join. Try again." }
            }
        }, "fluid-nearby-join").start()
    }
    Page {
        item {
            PreferencesCard("Channel") {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it; error = "" }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Name, like Neighborhood") })
                    OutlinedTextField(passphrase, { passphrase = it; error = "" }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Passphrase (leave empty for an open channel)") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Text(if (passphrase.isBlank()) NearbyText.OPEN_NOTICE
                        else "Only people who type the same name and passphrase can read it. Share the passphrase in person.",
                        color = if (passphrase.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    AppButton(enabled = !busy && name.isNotBlank(), onClick = { join(name, passphrase) }) {
                        Text(if (busy) "Joining…" else "Join")
                    }
                }
            }
        }
        item {
            PreferencesCard("Open channels nearby") {
                if (seen.isEmpty()) PreferenceText("None seen yet", "Channels show here once phones nearby share messages in them.")
                for ((channel, count) in seen) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PreferenceText("#$channel", if (count == 1) "1 message" else "$count messages", Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = { join(channel, "") }) { Text("Join") }
                    }
                }
            }
        }
        item { Note(NearbyText.COMMUNITY) }
    }
}
