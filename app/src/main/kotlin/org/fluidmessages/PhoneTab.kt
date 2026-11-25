package org.fluidmessages

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils
import android.widget.LinearLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** iOS Phone colors for the call button and missed calls; everything else follows the app's theme. */
private val CallGreen = Color(0xFF34C759)
private val MissedRed = Color(0xFFFF3B30)

/**
 * The two main tabs: Phone on the left, Messages on the right. counts adds missed calls and unread chats as
 * badges, unless "Show counts on tabs" is off in Settings → Sounds and notifications → App badges.
 */
@Composable
internal fun MainTabBar(palette: Palette, phoneSelected: Boolean, phone: Runnable, messages: Runnable, counts: TabCounts? = null) {
    val surface = if (palette.appearance.toolbarFinish == "plain") palette.bg else palette.toolbar
    Column(Modifier.fillMaxWidth().background(surface)) {
        HorizontalDivider(color = palette.edge, thickness = .5.dp)
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            for (index in 0..1) {
                val isPhone = index == 0
                val selected = isPhone == phoneSelected
                val tint = if (selected) palette.inboxAction else palette.muted
                val count = if (counts == null || !counts.shown) 0 else if (isPhone) counts.phone else counts.messages
                Column(Modifier.weight(1f).fillMaxHeight()
                    .clickable(role = Role.Tab) { if (selected) return@clickable; if (isPhone) phone.run() else messages.run() }
                    .semantics { contentDescription = TabBadges.description(isPhone, count) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    // The badge overlaps the icon's upper corner without moving the icon or label.
                    Box(Modifier.size(23.dp)) {
                        if (isPhone) HandsetGlyph(tint, Modifier.size(23.dp), filled = selected) else ChatGlyph(tint, selected)
                        TabBadge(count, palette, surface.compositeOver(palette.bg), Modifier.align(Alignment.TopStart)
                            .offset(x = 13.dp, y = (-7).dp).wrapContentSize(Alignment.TopStart, unbounded = true))
                    }
                    Text(if (isPhone) "Phone" else "Messages", color = tint, fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

/** Badge red, shaded so its white count reads clearly in light and dark themes. */
private val BadgeFill = accessibleBubbleFill(MissedRed)

/** A count on a tab: fades and scales in, out, and between numbers, and simply changes when motion is off. */
@Composable
private fun TabBadge(count: Int, palette: Palette, ring: Color, modifier: Modifier) {
    val context = LocalContext.current
    val speed = palette.appearance.speed
    val motion = remember(speed) {
        speed > 0 && android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val duration = speed.coerceIn(120, 360)
    AnimatedContent(TabBadges.label(count), modifier, transitionSpec = {
        if (!motion) EnterTransition.None togetherWith ExitTransition.None
        else (fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = .6f)) togetherWith
            (fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = .6f)) using SizeTransform(clip = false)
    }, contentAlignment = Alignment.CenterStart, label = "Tab badge") { label ->
        if (label.isNotEmpty()) {
            // The tab itself says the count to TalkBack, so the number here stays silent.
            Box(Modifier.clearAndSetSemantics {}.heightIn(min = 18.dp).widthIn(min = 18.dp).border(1.5.dp, ring, CircleShape)
                .padding(1.5.dp).background(BadgeFill, CircleShape).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(label, color = bubbleTextColor(BadgeFill), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun ChatGlyph(color: Color, filled: Boolean) {
    Canvas(Modifier.size(23.dp)) {
        val bubble = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(size.width * .1f, size.height * .14f, size.width * .9f, size.height * .74f,
                androidx.compose.ui.geometry.CornerRadius(size.width * .3f)))
            moveTo(size.width * .26f, size.height * .68f)
            lineTo(size.width * .18f, size.height * .9f)
            lineTo(size.width * .44f, size.height * .73f)
        }
        if (filled) drawPath(bubble, color) else drawPath(bubble, color, style = Stroke(1.7.dp.toPx()))
    }
}

/** A phone handset, drawn so it follows the theme's colors. */
@Composable
internal fun HandsetGlyph(color: Color, modifier: Modifier, filled: Boolean = true) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * .2f, h * .1f)
            cubicTo(w * .28f, h * .06f, w * .36f, h * .1f, w * .38f, h * .18f)
            lineTo(w * .42f, h * .32f)
            cubicTo(w * .43f, h * .38f, w * .4f, h * .42f, w * .35f, h * .45f)
            cubicTo(w * .42f, h * .6f, w * .5f, h * .66f, w * .56f, h * .66f)
            cubicTo(w * .6f, h * .6f, w * .64f, h * .58f, w * .7f, h * .6f)
            lineTo(w * .84f, h * .64f)
            cubicTo(w * .92f, h * .67f, w * .94f, h * .74f, w * .9f, h * .82f)
            lineTo(w * .84f, h * .88f)
            cubicTo(w * .6f, h * 1.02f, w * .0f, h * .5f, w * .12f, h * .18f)
            close()
        }
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(1.6.dp.toPx()))
    }
}

@Composable
private fun VideoGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        drawRoundRect(color, Offset(size.width * .06f, size.height * .26f), androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .48f),
            androidx.compose.ui.geometry.CornerRadius(size.width * .1f))
        drawPath(Path().apply {
            moveTo(size.width * .7f, size.height * .44f); lineTo(size.width * .96f, size.height * .28f)
            lineTo(size.width * .96f, size.height * .72f); lineTo(size.width * .7f, size.height * .56f); close()
        }, color)
    }
}

@Composable
private fun MessageGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        drawPath(Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(size.width * .08f, size.height * .14f, size.width * .92f, size.height * .74f,
                androidx.compose.ui.geometry.CornerRadius(size.width * .32f)))
            moveTo(size.width * .24f, size.height * .66f); lineTo(size.width * .16f, size.height * .92f); lineTo(size.width * .44f, size.height * .73f)
        }, color)
    }
}

@Composable
private fun StarGlyph(color: Color, filled: Boolean, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) size.minDimension * .48f else size.minDimension * .2f
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = center.x + radius * Math.cos(angle).toFloat()
            val y = center.y + radius * Math.sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(1.5.dp.toPx()))
    }
}

/** What the Phone tab needs from the rest of the app. */
interface PhoneActions {
    fun messages()
    fun call(number: String)
    /** protocol is "Matrix", "XMPP", or "SIP". For SIP, address is the number or SIP address to call. */
    fun internetCall(address: String, title: String, video: Boolean, protocol: String)
    /** Calls a Matrix ID typed on the keypad, linking a chat with them first if there isn't one. */
    fun callUsername(id: String, video: Boolean)
    /** Sends a meeting link in the chat at this address and joins it. Works on any service. */
    fun meeting(addresses: List<String>, title: String, video: Boolean)
    fun openChat(address: String, protocol: String)
    fun allowCallHistory()
    fun allowContacts()
    /** The newest message in a conversation, for the contact page. Called off the main thread. */
    fun preview(address: String): String
    /** New contact, with anything already known filled in (from the keypad or a contact page). */
    fun newContact(name: String, numbers: List<String>, addresses: List<ChatAddress>)
    /** Edit: "contact:<id>" edits a saved contact; "web:<id>" saves a contact from FluidMessages Web. */
    fun editContact(key: String)
    /** Saves every contact from FluidMessages Web into the phone's contacts, asking first. */
    fun saveWebContacts()
    /** Starts (or opens) a chat with a typed or saved address; service is matrix, xmpp, email, or irc. */
    fun startChat(service: String, target: String)
    /** "Muted until 3:40 PM" and the like when every one of [keys] (a person's addresses) is muted, or "" (Mutes). */
    fun muteLabel(keys: List<String>): String
    /** Opens the mute page for all of a person's chats and numbers; [done] runs after a choice. */
    fun mute(keys: List<String>, name: String, done: Runnable)
    /** Whether every one of [keys] (a person's chats and numbers) is blocked. */
    fun blocked(keys: List<String>): Boolean
    /** Blocks or unblocks all of [keys], in the same Blocked folder as the inbox menu. */
    fun block(keys: List<String>, on: Boolean)
}

/** A call in Recents. Phone calls come from Android's call history; protocol and address say how to call back. */
internal data class CallEntry(val name: String, val number: String, val type: Int, val date: Long, val seconds: Long,
                              val protocol: String = "Phone", val address: String = number, val video: Boolean = false)

/** A linked internet address: its conversation, protocol, and the account or room it reaches. */
internal data class Account(val address: String, val protocol: MessageProtocol, val target: String)

/** Someone you can call or message: an Android contact, a linked internet chat, or both. */
internal data class Person(val key: String, val name: String, val photo: Uri?, val numbers: List<String>, val accounts: List<Account>,
                           val photoRes: Int = 0, val fields: List<ContactField> = emptyList()) {
    val tags: List<String> get() = ((if (numbers.isNotEmpty()) listOf("Phone") else emptyList()) +
        accounts.map { it.protocol.label } + fields.filter { it.kind == ContactField.Kind.CHAT }.map { it.label }).distinct() +
        (if (key.startsWith("web:")) listOf("From web") else emptyList())
    val matrix: Account? get() = accounts.firstOrNull { it.protocol == MessageProtocol.MATRIX }
    fun matches(number: String) = numbers.any { PhoneNumberUtils.compare(it, number) }
}

private fun loadPeople(context: Context, prefs: SharedPreferences): List<Person> {
    data class Draft(val name: String, val photo: Uri?, val numbers: MutableList<String> = mutableListOf(),
                     val accounts: MutableList<Account> = mutableListOf())
    val people = linkedMapOf<String, Draft>()
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        try {
            context.contentResolver.query(phone, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI), null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getString(2) ?: continue
                    val draft = people.getOrPut("contact:" + cursor.getLong(0)) {
                        Draft(cursor.getString(1) ?: number, cursor.getString(3)?.let(Uri::parse))
                    }
                    if (draft.numbers.none { PhoneNumberUtils.compare(it, number) }) draft.numbers += number
                }
            }
        } catch (error: RuntimeException) { /* contacts unavailable */ }
    }
    // Linked internet addresses join the contact with the same phone number, or stand on their own.
    for (route in GatewayClient.allRoutes(prefs)) {
        val address = route.optString("address")
        val account = Account(address, MessageProtocol.parse(route.optString("protocol")), route.optString("target"))
        val owner = if (SmsStore.validRecipient(address)) people.values.firstOrNull { draft -> draft.numbers.any { PhoneNumberUtils.compare(it, address) } } else null
        val draft = owner ?: people.getOrPut("chat:$address") { Draft(route.optString("title", address).ifEmpty { address }, null) }
        if (draft.accounts.none { it.address == address && it.protocol == account.protocol }) draft.accounts += account
    }
    // Every saved number, email, and other detail, read in one pass for all contacts.
    val details = ContactFields.all(context)
    // Contacts with only chat addresses, no number, are people too.
    val chatOnly = details.filter { (id, fields) -> "contact:$id" !in people && fields.any { it.kind == ContactField.Kind.CHAT } }.keys
    for ((id, named) in ContactWriter.names(context, chatOnly)) {
        if (named.first.isNotBlank()) people["contact:$id"] = Draft(named.first, named.second)
    }
    val result = people.map { (key, draft) ->
        Person(key, draft.name, draft.photo, draft.numbers, draft.accounts,
            fields = key.removePrefix("contact:").toLongOrNull()?.let { details[it] }.orEmpty())
    }.toMutableList()
    // Contacts made in FluidMessages Web that aren't in the phone's contacts yet show until they're saved.
    if (!Privacy.decoyMode(prefs)) for (entry in WebContacts.pending(context, prefs)) {
        if (WebContacts.contactFor(context, prefs, entry.id) != null) continue
        val draft = entry.draft
        if (draft.numbers.isNotEmpty() && result.any { person -> person.key.startsWith("contact:") && draft.numbers.any(person::matches) }) continue
        result += Person("web:" + entry.id, draft.name, null, draft.numbers, emptyList(), fields =
            draft.numbers.map { ContactField(ContactField.Kind.PHONE, "Phone", it) } + draft.addresses.map { webField(it) })
    }
    return result.sortedBy { it.name.lowercase() }
}

/** A web contact's chat address as a contact detail. */
private fun webField(address: ChatAddress): ContactField = when (address.service) {
    "email" -> ContactField(ContactField.Kind.EMAIL, "Email", address.address)
    "sip" -> ContactField(ContactField.Kind.SIP, "SIP", address.address)
    else -> ContactField(ContactField.Kind.CHAT, NewAddress.label(address.service), address.address)
}

private fun readCalls(context: Context, phoneCalls: Boolean): List<CallEntry> {
    // Internet calls come from FluidMessages' own list; phone calls from Android's call history.
    val internet = CallHistory.read(context).map {
        CallEntry(it.optString("title").ifEmpty { it.optString("address") }, "", it.optInt("type"), it.optLong("date"), it.optLong("seconds"),
            it.optString("protocol", "Matrix"), it.optString("address"), it.optBoolean("video"))
    }
    // Android's phone calls can be left out in Settings → Accounts and calls.
    if (!phoneCalls || context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return internet
    val contacts = ContactDirectory(context)
    val rows = internet.toMutableList()
    try {
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
            CallLog.Calls.DURATION), null, null, CallLog.Calls.DATE + " DESC")?.use { cursor ->
            while (cursor.moveToNext() && rows.size < 600) {
                val number = cursor.getString(0) ?: continue
                val contact = if (SmsStore.validRecipient(number)) contacts.find(number) else null
                rows += CallEntry(contact?.name() ?: number, number, cursor.getInt(1), cursor.getLong(2), cursor.getLong(3))
            }
        }
    } catch (error: SecurityException) { /* permission was withdrawn */ }
    return rows.sortedByDescending { it.date }
}

/** Fictional people for the sample inbox's Phone tab, matching its conversations. */
private fun samplePeople(): List<Person> {
    fun account(name: String, protocol: MessageProtocol, target: String) = Account("demo:$name", protocol, target)
    fun email(name: String) = name.lowercase().replace(" ", ".") + "@example.org"
    return (listOf(
        Person("demo:Maya Chen", "Maya Chen", null, listOf(MainActivity.samplePhone("Maya Chen")), listOf(
            account("Maya Chen", MessageProtocol.MATRIX, MainActivity.sampleMatrix("Maya Chen")),
            account("Maya Chen", MessageProtocol.XMPP, "maya@example.org"), account("Maya Chen", MessageProtocol.EMAIL, email("Maya Chen"))),
            R.drawable.sample_maya),
        Person("demo:Jordan Ellis", "Jordan Ellis", null, listOf(MainActivity.samplePhone("Jordan Ellis")), emptyList(), R.drawable.sample_jordan),
        Person("demo:Avery Brooks", "Avery Brooks", null, listOf(MainActivity.samplePhone("Avery Brooks")), emptyList()),
        Person("demo:Sam Rivera", "Sam Rivera", null, listOf(MainActivity.samplePhone("Sam Rivera")), emptyList()),
        Person("demo:Nora Patel", "Nora Patel", null, listOf(MainActivity.samplePhone("Nora Patel")), emptyList()),
        Person("demo:Cameron Reed", "Cameron Reed", null, listOf(MainActivity.samplePhone("Cameron Reed")), emptyList()),
        Person("demo:Leah Morgan", "Leah Morgan", null, emptyList(), listOf(account("Leah Morgan", MessageProtocol.EMAIL, email("Leah Morgan")))),
        Person("demo:Amir Nasser", "Amir Nasser", null, emptyList(), listOf(account("Amir Nasser", MessageProtocol.XMPP, "amir@example.org"))),
        // People who are only in the address book, like anyone's real contact list.
    ) + listOf("Mom", "Elena Vasquez", "Marcus Lee", "Priya Raman", "Tom Becker", "Hannah Kim", "Diego Alvarez", "Grace Okafor",
        "Ben Carter", "Olivia Grant", "Dr. Ellis Dental").map { name ->
        Person("demo:$name", name, null, listOf(MainActivity.samplePhone(name)),
            if (name == "Elena Vasquez") listOf(account(name, MessageProtocol.MATRIX, MainActivity.sampleMatrix(name))) else emptyList())
    }).sortedBy { it.name.lowercase() }
}

private fun sampleCalls(): List<CallEntry> {
    val now = System.currentTimeMillis()
    val minute = 60_000L
    fun phone(name: String) = MainActivity.samplePhone(name)
    return listOf(
        CallEntry("Maya Chen", phone("Maya Chen"), CallLog.Calls.INCOMING_TYPE, now - 25 * minute, 252),
        CallEntry("Jordan Ellis", phone("Jordan Ellis"), CallLog.Calls.MISSED_TYPE, now - 2 * 60 * minute, 0),
        CallEntry("Mom", phone("Mom"), CallLog.Calls.INCOMING_TYPE, now - 3 * 60 * minute, 1310),
        CallEntry("Elena Vasquez", "", CallLog.Calls.INCOMING_TYPE, now - 5 * 60 * minute, 846, "Matrix", "demo:Elena Vasquez", video = true),
        CallEntry("Dr. Ellis Dental", phone("Dr. Ellis Dental"), CallLog.Calls.MISSED_TYPE, now - 22 * 60 * minute, 0),
        CallEntry("Maya Chen", "", CallLog.Calls.OUTGOING_TYPE, now - 26 * 60 * minute, 1118, "Matrix", "demo:Maya Chen", video = true),
        CallEntry("Sam Rivera", phone("Sam Rivera"), CallLog.Calls.OUTGOING_TYPE, now - 30 * 60 * minute, 95),
        CallEntry("Maya Chen", "", CallLog.Calls.MISSED_TYPE, now - 50 * 60 * minute, 0, "Matrix", "demo:Maya Chen"),
        CallEntry("Nora Patel", phone("Nora Patel"), CallLog.Calls.REJECTED_TYPE, now - 3 * 1440 * minute, 0),
        CallEntry("Avery Brooks", phone("Avery Brooks"), CallLog.Calls.INCOMING_TYPE, now - 4 * 1440 * minute, 431),
        CallEntry("Cameron Reed", phone("Cameron Reed"), CallLog.Calls.OUTGOING_TYPE, now - 9 * 1440 * minute, 64),
        CallEntry("Marcus Lee", phone("Marcus Lee"), CallLog.Calls.OUTGOING_TYPE, now - 11 * 1440 * minute, 318),
    )
}

private fun favorites(prefs: SharedPreferences, sample: Boolean): Set<String> = favoriteKeys(prefs, sample)

/**
 * The Phone tab, laid out like the iPhone Phone app: Favorites, Recents, Contacts, and Keypad, with a contact
 * page for each person. Colors and light or dark mode come from the current theme.
 */
object PhoneTab {
    /** A contact page to open when the Phone tab next shows, after saving it in the editor. */
    @JvmField var reopen: String? = null
    /** Whether the Phone tab next opens on Contacts. */
    @JvmField var showContacts: Boolean = false

    /** The sample contact with this number, for the pretend call screen. */
    @JvmStatic
    fun sampleName(number: String): String = samplePeople().firstOrNull { it.matches(number) }?.name ?: number

    /**
     * restrictTo is set in decoy mode: only people and calls with these addresses show, plus pretend contacts for
     * the decoy chats, so the Phone tab tells the same story as the inbox.
     */
    /** The sample Phone tab's missed calls, for its badge before it's opened. */
    @JvmStatic
    fun sampleMissedCalls(): Int = TabBadges.missedCalls(sampleCalls().map { BadgeCall(it.type, it.date, it.address) }, Long.MIN_VALUE)

    /**
     * Missed calls after since, for the Phone tab's badge: the same calls Recents shows (internet calls, and phone
     * calls when they're allowed and shown), read without looking up contacts. Called off the main thread.
     */
    @JvmStatic
    fun missedCallsSince(context: Context, prefs: SharedPreferences, since: Long, restrictTo: Collection<String>?): Int {
        val calls = CallHistory.read(context).map { BadgeCall(it.optInt("type"), it.optLong("date"), it.optString("address")) }.toMutableList()
        if (prefs.getBoolean("showPhoneCalls", true)
            && context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            try {
                context.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                    "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} > ?",
                    arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString()), null)?.use { cursor ->
                    while (cursor.moveToNext()) calls += BadgeCall(CallLog.Calls.MISSED_TYPE, cursor.getLong(1), cursor.getString(0).orEmpty())
                }
            } catch (error: RuntimeException) { /* permission was withdrawn or the call log is unavailable */ }
        }
        // Decoy mode: only calls the decoy Phone tab would show.
        val allowed: ((String) -> Boolean)? = restrictTo?.let { list -> { address: String -> address.isNotEmpty() && list.any { it == address
            || SmsStore.validRecipient(it) && SmsStore.validRecipient(address) && PhoneNumberUtils.compare(it, address) } } }
        return TabBadges.missedCalls(calls, since, allowed)
    }

    @JvmStatic @JvmOverloads
    fun mount(root: LinearLayout, prefs: SharedPreferences, actions: PhoneActions, sample: Boolean = false,
              restrictTo: List<String>? = null, decoyNames: List<String> = emptyList(), counts: TabCounts? = null) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            val palette = remember { Palette(appearance) }
            val context = LocalContext.current
            AppTheme(appearance) {
                // Like iOS, the Phone tab opens on Recents.
                var section by rememberSaveable { mutableIntStateOf(1) }
                var people by remember { mutableStateOf<List<Person>?>(null) }
                var calls by remember { mutableStateOf<List<CallEntry>?>(null) }
                var starred by remember { mutableStateOf(favorites(prefs, sample)) }
                var opened by remember { mutableStateOf<Person?>(null) }
                LaunchedEffect(Unit) {
                    var loaded = if (sample) samplePeople() else withContext(Dispatchers.IO) { loadPeople(context, prefs) }
                    var history = if (sample) sampleCalls() else withContext(Dispatchers.IO) { readCalls(context, prefs.getBoolean("showPhoneCalls", true)) }
                    if (restrictTo != null) {
                        fun allowed(address: String) = address.isNotEmpty() && restrictTo.any { it == address
                            || SmsStore.validRecipient(it) && SmsStore.validRecipient(address) && PhoneNumberUtils.compare(it, address) }
                        loaded = loaded.filter { person -> person.numbers.any(::allowed) || person.accounts.any { allowed(it.address) } } +
                            decoyNames.map { name -> Person(Privacy.DECOY + name, name, null, emptyList(),
                                listOf(Account(Privacy.DECOY + name, MessageProtocol.SMS, "Text messages"))) }
                        history = history.filter { allowed(it.number) || allowed(it.address) }
                    }
                    people = loaded.sortedBy { it.name.lowercase() }
                    calls = history
                    // Back from New contact or Edit: the contact's page, or Contacts.
                    reopen?.let { key -> opened = people?.firstOrNull { it.key == key } ?: people?.firstOrNull { it.name == key } }
                    reopen = null
                }
                LaunchedEffect(Unit) { if (showContacts) { section = 2; showContacts = false } }
                fun toggleFavorite(person: Person) {
                    starred = if (person.key in starred) starred - person.key else starred + person.key
                    prefs.edit().putString(if (sample) "samplePhoneFavorites" else "phoneFavorites", JSONArray(starred.toList()).toString()).apply()
                }
                fun personFor(entry: CallEntry): Person = people.orEmpty().firstOrNull {
                    entry.number.isNotEmpty() && it.matches(entry.number) || it.accounts.any { account -> account.address == entry.address }
                } ?: Person("number:${entry.number}", entry.name, null, listOf(entry.number).filter { it.isNotEmpty() }, emptyList())
                var keypad by rememberSaveable { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().background(palette.bg)) {
                    val person = opened
                    if (person != null) {
                        BackHandler { opened = null }
                        PersonPage(prefs, sample, palette, person, person.key in starred, calls.orEmpty(), actions, { opened = null }, restrictTo == null) { toggleFavorite(person) }
                    } else {
                        Segmented(palette, listOf("Favorites", "Recents", "Contacts"), if (keypad) -1 else section) {
                            section = it
                            keypad = false
                            prefs.edit().putInt("phoneSection", it).apply()
                        }
                        // Read-only when every way to call is off: no keypad, and a note saying why.
                        val canCall = sample || Calling.anyAllowed(prefs)
                        if (!canCall) Text("Calling is off · turn a way to call back on in Settings → Calls", Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            color = palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        if (sample) Text("Sample calls and contacts · nothing here is real", Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            color = palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (keypad) {
                                BackHandler { keypad = false }
                                Keypad(palette, people.orEmpty(), actions) { opened = it }
                            } else {
                                when (section) {
                                    0 -> Favorites(palette, people, starred, actions) { opened = it }
                                    1 -> Recents(palette, calls, actions, sample || !prefs.getBoolean("showPhoneCalls", true), ::personFor) { opened = it }
                                    else -> Contacts(palette, people, actions, sample, restrictTo != null) { opened = it }
                                }
                                // A floating keypad button, as in Google's Phone app, on every list.
                                if (canCall) Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(58.dp).clip(CircleShape)
                                    .background(palette.inboxAction).clickable(role = Role.Button) { keypad = true }
                                    .semantics { contentDescription = "Keypad" }, contentAlignment = Alignment.Center) {
                                    KeypadGlyph(bubbleTextColor(palette.inboxAction))
                                }
                            }
                        }
                    }
                    MainTabBar(palette, true, {}, { actions.messages() }, counts)
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
private fun KeypadGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        for (row in 0..3) for (column in 0..2) {
            if (row == 3 && column != 1) continue
            drawCircle(color, size.minDimension * .075f, Offset(size.width * (.22f + column * .28f), size.height * (.12f + row * .25f)))
        }
    }
}

/** An iOS-style segmented control. */
@Composable
private fun Segmented(palette: Palette, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp))
        .background(palette.wash).padding(2.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(7.dp))
                .background(if (active) palette.bg else Color.Transparent)
                .clickable(role = Role.Tab) { choose(index) }, contentAlignment = Alignment.Center) {
                Text(label, color = palette.ink, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LargeTitle(palette: Palette, title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = palette.ink, fontSize = 34.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
}

/** Small outlined protocol tags, the same style as the inbox's. */
@Composable
private fun Tags(palette: Palette, tags: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (tag in tags) Text(tag, Modifier.border(1.dp, palette.edge, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            color = palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Permission(palette: Palette, title: String, detail: String, button: String, allow: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, Modifier.padding(vertical = 12.dp), color = palette.muted, textAlign = TextAlign.Center)
        Text(button, Modifier.clip(RoundedCornerShape(20.dp)).background(palette.inboxAction).clickable(role = Role.Button, onClick = allow)
            .padding(horizontal = 18.dp, vertical = 10.dp), color = bubbleTextColor(palette.inboxAction), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PersonRow(palette: Palette, person: Person, detail: String?, open: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open).padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(initialsOf(person.name), person.name, 40, person.photoRes, person.photo)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(person.name, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = palette.muted, fontSize = 13.sp, maxLines = 1)
            if (person.tags.isNotEmpty()) Tags(palette, person.tags)
        }
        action?.invoke()
    }
    HorizontalDivider(Modifier.padding(start = 68.dp), color = palette.edge, thickness = .5.dp)
}

@Composable
private fun Favorites(palette: Palette, people: List<Person>?, starred: Set<String>, actions: PhoneActions, open: (Person) -> Unit) {
    val list = people.orEmpty().filter { it.key in starred }
    LazyColumn(Modifier.fillMaxSize()) {
        item { LargeTitle(palette, "Favorites") }
        if (people != null && list.isEmpty()) item {
            Text("Tap the star on someone's contact page to add them here.", Modifier.fillMaxWidth().padding(32.dp),
                color = palette.muted, textAlign = TextAlign.Center)
        }
        items(list, key = { it.key }) { person ->
            PersonRow(palette, person, null, { open(person) }) {
                QuickCall(palette, person, actions)
            }
        }
    }
}

/** Calls the person's phone number, or their Matrix account when that's all they have. */
@Composable
private fun QuickCall(palette: Palette, person: Person, actions: PhoneActions) {
    val number = person.numbers.firstOrNull()
    val matrix = person.matrix
    if (number == null && matrix == null) return
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button) {
        if (number != null) actions.call(number) else actions.internetCall(matrix!!.address, person.name, false, "Matrix")
    }.semantics { contentDescription = "Call ${person.name}" }, contentAlignment = Alignment.Center) {
        HandsetGlyph(palette.inboxAction, Modifier.size(20.dp))
    }
}

@Composable
private fun Recents(palette: Palette, calls: List<CallEntry>?, actions: PhoneActions, sample: Boolean, personFor: (CallEntry) -> Person,
                    open: (Person) -> Unit) {
    val context = LocalContext.current
    val allowed = sample || context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    var missedOnly by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LargeTitle(palette, "Recents", Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(palette.wash).padding(2.dp)) {
                for ((label, missed) in listOf("All" to false, "Missed" to true)) {
                    Text(label, Modifier.clip(RoundedCornerShape(6.dp)).background(if (missedOnly == missed) palette.bg else Color.Transparent)
                        .clickable(role = Role.Tab) { missedOnly = missed }.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = palette.ink, fontSize = 13.sp, fontWeight = if (missedOnly == missed) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        // Internet calls always show; phone calls need Android's call history.
        if (!allowed) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(palette.wash).clickable(role = Role.Button) { actions.allowCallHistory() }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Allow call history to see your phone calls here too. It stays on this phone.", Modifier.weight(1f),
                color = palette.ink, fontSize = 14.sp)
            Text("Allow", Modifier.padding(start = 10.dp), color = palette.inboxAction, fontWeight = FontWeight.SemiBold)
        }
        val shown = calls.orEmpty().filter { !missedOnly || it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }
        if (calls != null && shown.isEmpty()) {
            Text(if (missedOnly) "No missed calls" else "No recent calls", Modifier.fillMaxWidth().padding(40.dp), color = palette.muted,
                textAlign = TextAlign.Center)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown) { entry ->
                val missed = entry.type == CallLog.Calls.MISSED_TYPE || entry.type == CallLog.Calls.REJECTED_TYPE
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    if (entry.protocol == "Phone") actions.call(entry.number) else actions.internetCall(entry.address, entry.name, entry.video, entry.protocol)
                }
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val person = remember(entry, calls) { personFor(entry) }
                    Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { open(person) }) {
                        Avatar(initialsOf(entry.name), entry.name, 40, person.photoRes, person.photo)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(entry.name, color = if (missed) MissedRed else palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tags(palette, listOf(entry.protocol))
                            Text(callKind(entry), color = palette.muted, fontSize = 13.sp)
                        }
                    }
                    Text(callDate(LocalContext.current, entry.date), color = palette.muted, fontSize = 14.sp)
                    Box(Modifier.padding(start = 4.dp).size(40.dp).clip(CircleShape).clickable(role = Role.Button) { open(person) }
                        .semantics { contentDescription = "About ${entry.name}" }, contentAlignment = Alignment.Center) {
                        InfoMark(palette.inboxAction)
                    }
                }
                HorizontalDivider(Modifier.padding(start = 68.dp), color = palette.edge, thickness = .5.dp)
            }
        }
    }
}

private fun callKind(entry: CallEntry) = when (entry.type) {
    CallLog.Calls.INCOMING_TYPE -> if (entry.video) "Incoming video" else "Incoming"
    CallLog.Calls.OUTGOING_TYPE -> if (entry.video) "Outgoing video" else "Outgoing"
    CallLog.Calls.MISSED_TYPE -> "Missed"
    CallLog.Calls.REJECTED_TYPE -> "Declined"
    else -> "Phone"
} + if (entry.seconds > 0) " · " + DateUtils.formatElapsedTime(entry.seconds) else ""

private fun callDate(context: Context, date: Long): String = when {
    DateUtils.isToday(date) -> DateUtils.formatDateTime(context, date, DateUtils.FORMAT_SHOW_TIME)
    System.currentTimeMillis() - date < 6 * DateUtils.DAY_IN_MILLIS -> DateUtils.formatDateTime(context, date, DateUtils.FORMAT_SHOW_WEEKDAY)
    else -> DateUtils.formatDateTime(context, date, DateUtils.FORMAT_NUMERIC_DATE)
}

@Composable
private fun SearchField(palette: Palette, query: String, change: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(palette.wash)
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(query, change, singleLine = true, textStyle = TextStyle(color = palette.ink, fontSize = 16.sp),
            cursorBrush = SolidColor(palette.inboxAction), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search contacts" },
            decorationBox = { field -> if (query.isEmpty()) Text("Search", color = palette.muted, fontSize = 16.sp); field() })
    }
}

@Composable
private fun Contacts(palette: Palette, people: List<Person>?, actions: PhoneActions, sample: Boolean, decoy: Boolean, open: (Person) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val contactsAllowed = sample || context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val words = query.trim().lowercase()
    val shown = people.orEmpty().filter { person ->
        words.isEmpty() || person.name.lowercase().contains(words) || person.numbers.any { it.filter(Char::isDigit).contains(words.filter(Char::isDigit).ifEmpty { "\u0000" }) }
            || person.accounts.any { it.target.lowercase().contains(words) }
    }
    val fromWeb = people.orEmpty().count { it.key.startsWith("web:") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                LargeTitle(palette, "Contacts", Modifier.weight(1f))
                if (!decoy) Text("New contact", Modifier.clip(RoundedCornerShape(18.dp)).background(palette.wash)
                    .clickable(role = Role.Button) { actions.newContact("", emptyList(), emptyList()) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = palette.inboxAction, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item { SearchField(palette, query) { query = it } }
        if (fromWeb > 0 && !sample) item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(palette.wash).clickable(role = Role.Button) { actions.saveWebContacts() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text((if (fromWeb == 1) "1 contact" else "$fromWeb contacts") + " from FluidMessages Web " +
                    (if (fromWeb == 1) "isn't" else "aren't") + " in your phone's contacts yet.", Modifier.weight(1f), color = palette.ink, fontSize = 14.sp)
                Text("Save", Modifier.padding(start = 10.dp), color = palette.inboxAction, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!contactsAllowed) item {
            Permission(palette, "Your contacts", "Show the people saved on this phone, with their numbers and linked accounts.",
                "Allow contacts") { actions.allowContacts() }
        }
        if (people != null && shown.isEmpty() && contactsAllowed) item {
            Text(if (words.isEmpty()) "No contacts yet" else "No matches for “$query”", Modifier.fillMaxWidth().padding(32.dp),
                color = palette.muted, textAlign = TextAlign.Center)
        }
        items(shown, key = { it.key }) { person ->
            PersonRow(palette, person, person.numbers.firstOrNull() ?: person.accounts.firstOrNull()?.target, { open(person) })
        }
    }
}

private val keyLetters = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV",
    "9" to "WXYZ", "0" to "+")

@Composable
private fun Keypad(palette: Palette, people: List<Person>, actions: PhoneActions, open: (Person) -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        // Call a phone number, or an internet account by its username.
        Row(Modifier.padding(horizontal = 60.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.wash).padding(2.dp)) {
            for ((index, label) in listOf("Number", "Username").withIndex()) {
                Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(6.dp)).background(if (mode == index) palette.bg else Color.Transparent)
                    .clickable(role = Role.Tab) { mode = index }, contentAlignment = Alignment.Center) {
                    Text(label, color = palette.ink, fontSize = 13.sp, fontWeight = if (mode == index) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        if (mode == 0) NumberPad(palette, people, actions, open) else UsernameCall(palette, actions)
    }
}

@Composable
private fun UsernameCall(palette: Palette, actions: PhoneActions) {
    var id by rememberSaveable { mutableStateOf("") }
    // Matrix @name:server, SIP sip:name@host, or XMPP name@server.
    val valid = Regex("@[^\\s:]+:[A-Za-z0-9.:-]+|sip:[^\\s@]+@[A-Za-z0-9.:-]+|[^\\s@:]+@[A-Za-z0-9.-]+").matches(id.trim())
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Call someone by their address on Matrix (@name:server.org), XMPP (name@server.org), or SIP (sip:name@provider.com).",
            color = palette.muted, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.wash).padding(horizontal = 14.dp, vertical = 14.dp)) {
            BasicTextField(id, { id = it.trim().take(255) }, singleLine = true, textStyle = TextStyle(color = palette.ink, fontSize = 18.sp),
                cursorBrush = SolidColor(palette.inboxAction), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Matrix username" },
                decorationBox = { field -> if (id.isEmpty()) Text("name@example.org", color = palette.muted, fontSize = 18.sp); field() })
        }
        if (id.isNotEmpty() && !valid) Text("Addresses look like @name:server.org, name@server.org, or sip:name@provider.com",
            color = if (palette.appearance.dark) MissedRed else accessibleBubbleFill(MissedRed), fontSize = 13.sp)
        // Message them, or save them as a contact, on the service the address is on.
        val typed = remember(id) { NewAddress.detect(id).firstOrNull { !it.guess && it.service != "sms" } }
        if (valid && typed != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (typed.service != "sip") Text("Message", Modifier.clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { actions.startChat(typed.service, id.trim()) }.padding(horizontal = 8.dp, vertical = 4.dp),
                color = palette.inboxAction, fontSize = 15.sp)
            Text("Add to contacts", Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                actions.newContact("", emptyList(), listOf(ChatAddress(typed.service, typed.target.removePrefix("sip:"))))
            }.padding(horizontal = 8.dp, vertical = 4.dp), color = palette.inboxAction, fontSize = 15.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((label, video) in listOf("Voice call" to false, "Video call" to true)) {
                // The call green, shaded so its white label stays readable (the bubble contrast rule).
                Row(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(if (valid) accessibleBubbleFill(CallGreen) else palette.wash)
                    .clickable(enabled = valid, role = Role.Button) { actions.callUsername(id.trim(), video) }.padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    val ink = if (valid) Color.White else palette.muted
                    HandsetGlyph(ink, Modifier.size(18.dp))
                    Text(label, Modifier.padding(start = 8.dp), color = ink, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NumberPad(palette: Palette, people: List<Person>, actions: PhoneActions, open: (Person) -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    // Like iOS, a typed number that belongs to a contact shows their name, which opens their page.
    val match = remember(number, people) { if (number.length >= 4) people.firstOrNull { it.matches(number) } else null }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val key = minOf(76.dp, (maxHeight - 200.dp) / 5)
        Column(Modifier.fillMaxSize().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom) {
            Text(number, Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 48.dp), color = palette.ink,
                fontSize = if (number.length > 13) 28.sp else 36.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center, maxLines = 1)
            if (match == null && number.count(Char::isDigit) >= 3 && number.none { it == '*' || it == '#' }) {
                // Someone new: text them, or save them as a contact.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, run) in listOf<Pair<String, () -> Unit>>("Message" to { actions.openChat(number, "sms") },
                            "Add to contacts" to { actions.newContact("", listOf(number), emptyList()) })) {
                        Text(label, Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = run)
                            .padding(horizontal = 8.dp, vertical = 2.dp), color = palette.inboxAction, fontSize = 15.sp)
                    }
                }
            } else Text(match?.name ?: " ", Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = match != null) { match?.let(open) }
                .padding(horizontal = 8.dp, vertical = 2.dp), color = palette.inboxAction, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            for (row in listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))) {
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    for (digit in row) {
                        Column(Modifier.size(key).clip(CircleShape).background(palette.wash)
                            .clickable(role = Role.Button) { if (number.length < 32) number += digit }.semantics { contentDescription = digit },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(digit, color = palette.ink, fontSize = (key.value * .42f).sp, lineHeight = (key.value * .46f).sp)
                            keyLetters[digit]?.let {
                                Text(it, color = palette.ink, fontSize = (key.value * .13f).sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(key))
                Box(Modifier.size(key).clip(CircleShape).background(CallGreen)
                    .clickable(role = Role.Button, enabled = number.isNotEmpty()) { actions.call(number) }
                    .semantics { contentDescription = "Call" }, contentAlignment = Alignment.Center) {
                    HandsetGlyph(Color.White, Modifier.size(key * .42f))
                }
                Box(Modifier.size(key).clip(CircleShape).clickable(role = Role.Button, enabled = number.isNotEmpty()) { number = number.dropLast(1) }
                    .semantics { contentDescription = "Delete" }, contentAlignment = Alignment.Center) {
                    if (number.isNotEmpty()) DeleteGlyph(palette.ink)
                }
            }
        }
    }
}

@Composable
private fun DeleteGlyph(color: Color) {
    Canvas(Modifier.size(30.dp, 22.dp)) {
        val stroke = 1.7.dp.toPx()
        drawPath(Path().apply {
            moveTo(size.width * .3f, size.height * .1f); lineTo(size.width * .95f, size.height * .1f)
            lineTo(size.width * .95f, size.height * .9f); lineTo(size.width * .3f, size.height * .9f)
            lineTo(size.width * .03f, size.height * .5f); close()
        }, color, style = Stroke(stroke))
        drawLine(color, Offset(size.width * .5f, size.height * .33f), Offset(size.width * .75f, size.height * .67f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .75f, size.height * .33f), Offset(size.width * .5f, size.height * .67f), stroke, StrokeCap.Round)
    }
}

/** Everything about one person: how to reach them, their chats, and recent calls. */
@Composable
private fun PersonPage(prefs: SharedPreferences, sample: Boolean, palette: Palette, person: Person, favorite: Boolean, calls: List<CallEntry>, actions: PhoneActions,
                       back: () -> Unit, editable: Boolean, toggleFavorite: () -> Unit) {
    val number = person.numbers.firstOrNull()
    val matrix = person.matrix
    // Every conversation with this person: texts on each number, and each linked internet chat.
    val chats = remember(person) {
        person.numbers.map { Triple(it, "sms", "Text messages · $it") } +
            person.accounts.map { Triple(it.address, it.protocol.name.lowercase(), it.protocol.label + " · " + it.target) }
    }
    var previews by remember(person) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(person) {
        previews = withContext(Dispatchers.IO) { chats.associate { it.first to actions.preview(it.first) } }
    }
    val history = calls.filter { entry -> entry.number.isNotEmpty() && person.matches(entry.number)
        || person.accounts.any { it.address == entry.address } }.take(20)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back", Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = back).padding(8.dp),
                    color = palette.inboxAction, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                // Edit a saved contact; save one from FluidMessages Web; or add anyone else to your contacts.
                if (editable) Text(when {
                    person.key.startsWith("contact:") -> "Edit"
                    person.key.startsWith("web:") -> "Save to contacts"
                    else -> "Add to contacts"
                }, Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                    if (person.key.startsWith("contact:") || person.key.startsWith("web:")) actions.editContact(person.key)
                    else actions.newContact(if (person.name == number) "" else person.name, person.numbers, person.accounts
                        .filter { NewAddress.protocol(it.protocol.name.lowercase()) != null && !it.target.startsWith("!") && !it.target.startsWith("#") }
                        .map { ChatAddress(it.protocol.name.lowercase(), it.target) })
                }.padding(8.dp), color = palette.inboxAction, fontSize = 17.sp)
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = toggleFavorite)
                    .semantics { contentDescription = if (favorite) "Remove from favorites" else "Add to favorites" }, contentAlignment = Alignment.Center) {
                    StarGlyph(palette.inboxAction, favorite)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(initialsOf(person.name), person.name, 96, person.photoRes, person.photo)
                Text(person.name, color = palette.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (person.tags.isNotEmpty()) Tags(palette, person.tags)
            }
        }
        item {
            // The same Message, Call, and Video buttons as a conversation's details.
            val options = remember(person) {
                person.numbers.map { ReachOption(ReachOption.MESSAGE, "SMS", it, Runnable { actions.openChat(it, "sms") }) } +
                    person.accounts.map { account -> ReachOption(ReachOption.MESSAGE, account.protocol.label, account.target,
                        Runnable { actions.openChat(account.address, account.protocol.name.lowercase()) }) } +
                    // Saved chat addresses without a chat yet start one there.
                    person.fields.filter { field -> field.kind == ContactField.Kind.CHAT && person.accounts.none { it.target.equals(field.value, true) } }
                        .map { field -> ReachOption(ReachOption.MESSAGE, field.label, field.value, Runnable { actions.startChat(field.service, field.value) }) } +
                    person.numbers.filter { Calling.allowed(prefs, Calling.Method.PHONE) }.map { ReachOption(ReachOption.CALL, "Phone", it, Runnable { actions.call(it) }) } +
                    (if (Calling.allowed(prefs, Calling.Method.MEETING) && (person.numbers.isNotEmpty() ||
                            person.accounts.any { IrcCalls.meetingRoute(it.protocol, IrcCalls.allowed(prefs)) })) {
                        // Every way to reach them, so the link can go wherever you choose (IRC only when allowed, IrcCalls).
                        val addresses = (person.numbers + person.accounts.filter { IrcCalls.meetingRoute(it.protocol, IrcCalls.allowed(prefs)) }
                            .map { it.address }).distinct()
                        val services = MeetingPages.summary(prefs)
                        listOf(ReachOption(ReachOption.CALL, "Meeting link", services, Runnable { actions.meeting(addresses, person.name, false) }),
                            ReachOption(ReachOption.VIDEO, "Meeting link", services, Runnable { actions.meeting(addresses, person.name, true) }))
                    } else emptyList()) +
                    listOfNotNull(matrix?.takeIf { Calling.allowed(prefs, Calling.Method.MATRIX) }).flatMap { account -> listOf(
                        ReachOption(ReachOption.CALL, "Matrix", account.target, Runnable { actions.internetCall(account.address, person.name, false, "Matrix") }),
                        ReachOption(ReachOption.VIDEO, "Matrix", account.target, Runnable { actions.internetCall(account.address, person.name, true, "Matrix") })) } +
                    person.accounts.filter { it.protocol == MessageProtocol.XMPP && (sample || CallHub.xmppCallable(prefs, it.address)) }.flatMap { account -> listOf(
                        ReachOption(ReachOption.CALL, "XMPP", account.target, Runnable { actions.internetCall(account.address, person.name, false, "XMPP") }),
                        ReachOption(ReachOption.VIDEO, "XMPP", account.target, Runnable { actions.internetCall(account.address, person.name, true, "XMPP") })) } +
                    person.numbers.filter { sample || Calling.sipReady(prefs) }.map {
                        ReachOption(ReachOption.CALL, "SIP", it, Runnable { actions.internetCall(it, person.name, false, "SIP") }) }
            }
            ReachTiles(prefs, person.key, options, palette.inboxAction, palette.wash, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        if (person.numbers.isNotEmpty() || person.accounts.isNotEmpty() || person.fields.isNotEmpty()) item {
            val context = LocalContext.current
            Card(palette, "Contact info") {
                // Saved details carry Android's labels (mobile, work…); numbers without one fall back to "Phone".
                val labelled = person.fields.ifEmpty { person.numbers.map { ContactField(ContactField.Kind.PHONE, "Phone", it) } }
                labelled.forEachIndexed { index, field ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 14.dp), color = palette.edge, thickness = .5.dp)
                    InfoRow(palette, field.label, field.value) {
                        when (field.kind) {
                            ContactField.Kind.PHONE -> actions.call(field.value)
                            ContactField.Kind.SIP -> actions.internetCall("sip:" + field.value.removePrefix("sip:"), person.name, false, "SIP")
                            ContactField.Kind.CHAT -> actions.startChat(field.service, field.value)
                            else -> openField(context, field)
                        }
                    }
                }
                person.accounts.forEachIndexed { index, account ->
                    if (index > 0 || labelled.isNotEmpty()) HorizontalDivider(Modifier.padding(start = 14.dp), color = palette.edge, thickness = .5.dp)
                    InfoRow(palette, account.protocol.label, account.target) { actions.openChat(account.address, account.protocol.name.lowercase()) }
                }
            }
        }
        // Mute all of their chats and numbers at once: 1 hour to forever, or Unmute.
        if (sample || person.numbers.isNotEmpty() || person.accounts.isNotEmpty()) item {
            val keys = remember(person) { if (sample) listOf(person.key) else (person.numbers + person.accounts.map { it.address }).distinct() }
            var muted by remember(person) { mutableStateOf(actions.muteLabel(keys)) }
            Card(palette, "Notifications") {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.mute(keys, person.name) { muted = actions.muteLabel(keys) } }
                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (muted.isNotEmpty()) MutedMark(muted, palette.ink, Modifier.padding(end = 10.dp), 18)
                    Column(Modifier.weight(1f)) {
                        Text(muted.ifEmpty { "Mute ${person.name}" }, color = if (muted.isEmpty()) palette.inboxAction else palette.ink, fontSize = 16.sp)
                        Text(if (muted.isEmpty()) "All of their chats and numbers" else "All of their chats and numbers · tap to change or unmute",
                            color = palette.muted, fontSize = 13.sp)
                    }
                }
            }
        }
        if (chats.isNotEmpty()) item {
            Card(palette, "Chats") {
                chats.forEachIndexed { index, (address, protocol, label) ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 14.dp), color = palette.edge, thickness = .5.dp)
                    InfoRow(palette, label, previews[address]?.ifEmpty { null } ?: "No messages yet") { actions.openChat(address, protocol) }
                }
            }
        }
        if (history.isNotEmpty()) item {
            Card(palette, "Recent calls") {
                history.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 14.dp), color = palette.edge, thickness = .5.dp)
                    val missed = entry.type == CallLog.Calls.MISSED_TYPE || entry.type == CallLog.Calls.REJECTED_TYPE
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(callKind(entry), Modifier.weight(1f), color = if (missed) MissedRed else palette.ink, fontSize = 15.sp)
                        Text(callDate(LocalContext.current, entry.date), color = palette.muted, fontSize = 14.sp)
                    }
                }
            }
        }
        // Block all of their chats and numbers, confirmed right in the card.
        if (sample || person.numbers.isNotEmpty() || person.accounts.isNotEmpty()) item {
            val keys = remember(person) { if (sample) listOf(person.key) else (person.numbers + person.accounts.map { it.address }).distinct() }
            val choice = remember(person) { BlockChoice(person.name, actions.blocked(keys)) { on -> actions.block(keys, on) } }
            Card(palette, "Block") { BlockControl(choice, palette.ink, palette.muted, palette.inboxAction) }
        }
    }
}

@Composable
private fun ActionTile(palette: Palette, label: String, enabled: Boolean, modifier: Modifier, icon: @Composable (Color) -> Unit, run: () -> Unit) {
    val tint = if (enabled) palette.inboxAction else palette.muted.copy(alpha = .5f)
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(palette.wash).clickable(enabled = enabled, role = Role.Button, onClick = run)
        .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        icon(tint)
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Card(palette: Palette, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, Modifier.padding(start = 4.dp, bottom = 6.dp), color = palette.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.wash), content = content)
    }
}

@Composable
private fun InfoRow(palette: Palette, label: String, value: String, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, color = palette.muted, fontSize = 13.sp)
        Text(value, color = palette.inboxAction, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Emails open the mail app, websites the browser, addresses the map app. */
private fun openField(context: Context, field: ContactField) {
    val intent = when (field.kind) {
        ContactField.Kind.EMAIL -> android.content.Intent(android.content.Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(field.value)))
        ContactField.Kind.WEBSITE -> android.content.Intent(android.content.Intent.ACTION_VIEW,
            Uri.parse(if (field.value.startsWith("http", true)) field.value else "https://" + field.value))
        ContactField.Kind.ADDRESS -> android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(field.value)))
        else -> null
    } ?: return
    runCatching { context.startActivity(intent) }
}
