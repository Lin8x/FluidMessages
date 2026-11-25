package org.fluidmessages

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.widget.LinearLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.function.Consumer

/** One chat address being edited. chosen is set once you pick its service yourself; until then it follows what's typed. */
class AddressField(service: String, address: String, chosen: Boolean) {
    var service by mutableStateOf(service)
    var address by mutableStateOf(address)
    var chosen by mutableStateOf(chosen)
}

/** What the contact editor holds while it's open, so a photo picked in another screen lands here. */
class ContactEditorState(initial: ContactDraft, photo: Uri?) {
    val webId = initial.webId
    val webUpdated = initial.webUpdated
    var name by mutableStateOf(initial.name)
    val numbers = mutableStateListOf<String>().apply { addAll(initial.numbers.ifEmpty { listOf("") }) }
    val addresses = mutableStateListOf<AddressField>().apply { addAll(initial.addresses.map { AddressField(it.service, it.address, true) }) }
    var photo by mutableStateOf(photo)
    var photoBytes by mutableStateOf<ByteArray?>(null)
    var status by mutableStateOf("")
    var saving by mutableStateOf(false)

    fun draft(): ContactDraft = ContactMapping.clean(name, numbers, addresses.map { ChatAddress(it.service, it.address) }, webId, webUpdated)

    /** What's wrong with the form, or null. The same checks as FluidMessages Web's. */
    fun problem(): String? {
        if (name.isBlank()) return "Enter a name"
        numbers.map { it.trim() }.firstOrNull { it.isNotEmpty() && (!Regex("\\+?[0-9][0-9 ().-]{2,39}").matches(it) || it.count(Char::isDigit) < 3) }
            ?.let { return "“$it” isn't a phone number. Use digits, like +1 202 555 0143." }
        addresses.firstOrNull { field -> field.address.isNotBlank() && field.service != "sip" && field.service != "irc"
            && NewAddress.detect(field.address).none { it.service == field.service } }?.let { field ->
            return when (field.service) {
                "matrix" -> "Matrix addresses look like @maya:example.org"
                "xmpp" -> "XMPP addresses look like maya@example.org"
                else -> "Email addresses look like maya@example.org"
            }
        }
        return null
    }
}

/**
 * New contact and Edit, as a full page: photo, name, phone numbers, and chat addresses on any service. Saving goes
 * into Android's own contacts (ContactWriter); MainActivity asks for permission at that moment.
 */
object ContactEditor {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, state: ContactEditorState, sample: Boolean, pickPhoto: Runnable,
              save: Consumer<ContactDraft>) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        // Linking an address suggests people from your chats on this phone (fictional ones in the sample inbox).
        val known = if (sample) LinkSuggestions.SAMPLE else LinkSuggestions.known(prefs)
        view.setContent { AppTheme(Appearance.read(prefs)) { EditorForm(state, sample, pickPhoto, save, known) } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

@Composable
private fun EditorForm(state: ContactEditorState, sample: Boolean, pickPhoto: Runnable, save: Consumer<ContactDraft>,
                       known: List<LinkSuggestion>) {
    val scheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val allowed = ContactWriter.allowed(context)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val bytes = state.photoBytes
                val picture = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                if (picture != null) Image(picture, null, Modifier.size(96.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                else Avatar(initialsOf(state.name.ifBlank { "?" }), state.name, 96, photoUri = state.photo)
                TextButton(onClick = pickPhoto::run) { Text(if (picture != null || state.photo != null) "Change photo" else "Add photo") }
            }
        }
        if (state.webId.isNotEmpty()) item {
            Text("From FluidMessages Web. Saving puts it in your phone's contacts; changes made on the web later update it here.",
                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            OutlinedTextField(state.name, { state.name = it.take(100); state.status = "" }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Name") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        }
        item { Text("Phone numbers", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface) }
        items(state.numbers.size) { index ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(state.numbers[index], { value -> state.numbers[index] = value.take(40); state.status = "" }, Modifier.weight(1f),
                    singleLine = true, label = { Text("Phone") }, placeholder = { Text("+1 202 555 0143") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                TextButton(onClick = { state.numbers.removeAt(index) }, Modifier.semantics { contentDescription = "Remove this phone number" }) { Text("Remove") }
            }
        }
        item { TextButton(onClick = { state.numbers.add("") }) { Text("+ Add phone number") } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Chat addresses", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Text("Matrix @name:server, XMPP or email name@server, IRC nickname (riley@irc.libera.chat for a network), or SIP name@provider. " +
                    "The service is picked from what you type; tap another if it's wrong.", color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        items(state.addresses.size) { index -> AddressRow(state, state.addresses[index], known) }
        item { TextButton(onClick = { state.addresses.add(AddressField("matrix", "", false)) }) { Text("+ Add chat address") } }
        item {
            Text(when {
                sample -> "Sample contact · nothing is saved."
                allowed -> "Saves into your phone's contacts, so other apps see it too."
                else -> "Saving adds this to your phone's contacts. Android asks once to let FluidMessages change your contacts; " +
                    "if you'd rather not, your Contacts app opens with everything filled in instead."
            }, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.status.isNotEmpty()) item { Text(state.status, color = scheme.error, style = MaterialTheme.typography.bodyMedium) }
        item {
            AppButton(onClick = {
                val problem = state.problem()
                if (problem != null) state.status = problem else save.accept(state.draft())
            }, Modifier.fillMaxWidth(), enabled = !state.saving) { Text(if (state.saving) "Saving…" else "Save contact", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun AddressRow(state: ContactEditorState, field: AddressField, known: List<LinkSuggestion>) {
    var focused by remember { mutableStateOf(false) }
    // People from your chats on this service (any of them until you pick one), searched on this phone only.
    val suggestions = remember(field.address, field.service, field.chosen, focused) {
        if (!focused) emptyList() else LinkSuggestions.match(known, field.address, field.service.takeIf { field.chosen })
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(field.address, { value ->
                field.address = value.take(320)
                state.status = ""
                if (!field.chosen) NewAddress.detect(value).firstOrNull { !it.guess && it.service in ContactMapping.SERVICES }?.let {
                    field.service = it.service
                }
            }, Modifier.weight(1f).onFocusChanged { focused = it.isFocused }, singleLine = true, label = { Text(NewAddress.label(field.service)) },
                placeholder = { Text("@maya:example.org") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None))
            TextButton(onClick = { state.addresses.remove(field) }, Modifier.semantics { contentDescription = "Remove this chat address" }) { Text("Remove") }
        }
        LinkSuggestionList(suggestions) { picked ->
            field.address = picked.address
            field.service = picked.service
            field.chosen = true
            state.status = ""
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (service in ContactMapping.SERVICES) {
                FilterChip(selected = field.service == service, onClick = { field.service = service; field.chosen = true },
                    label = { Text(NewAddress.label(service)) })
            }
        }
    }
}

/**
 * Saves contacts into Android's contacts (ContactsContract) with ContactMapping's rows. Needs WRITE_CONTACTS; without
 * it, insertIntent and editIntent hand the same details to the Contacts app instead.
 */
object ContactWriter {
    @JvmStatic fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    @JvmStatic fun canRead(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** A contact's name and its numbers and chat addresses, by contact id, for Edit. */
    @JvmStatic fun read(context: Context, contactId: Long): ContactDraft? {
        if (!canRead(context)) return null
        val name = runCatching {
            context.contentResolver.query(uriOf(contactId),
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: return null
        return ContactMapping.draft(name, rows(context, Data.CONTACT_ID, contactId))
    }

    /** The photo shown for a contact, for the editor. */
    @JvmStatic fun photo(context: Context, contactId: Long): Uri? = if (!canRead(context)) null else runCatching {
        context.contentResolver.query(uriOf(contactId),
            arrayOf(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI), null, null, null)?.use { if (it.moveToFirst()) it.getString(0)?.let(Uri::parse) else null }
    }.getOrNull()

    private fun uriOf(contactId: Long): Uri = android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)

    private fun rows(context: Context, column: String, id: Long): List<ContactRow> {
        val result = mutableListOf<ContactRow>()
        val kinds = ContactMapping.MANAGED.toList()
        runCatching {
            context.contentResolver.query(Data.CONTENT_URI, arrayOf(Data._ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA5, Data.DATA6),
                "$column = ? AND ${Data.MIMETYPE} IN (${kinds.joinToString(",") { "?" }})", arrayOf(id.toString()) + kinds, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    result += ContactRow(cursor.getString(1), cursor.getString(2).orEmpty(), cursor.getInt(3),
                        if (cursor.isNull(4)) null else cursor.getInt(4), cursor.getString(5), cursor.getLong(0))
                }
            }
        }
        return result
    }

    /** The first raw contact of a contact, where new details go. */
    @JvmStatic fun rawContact(context: Context, contactId: Long): Long? = runCatching {
        context.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()), RawContacts._ID)?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    /** The contact a raw contact belongs to, or null when it was deleted. */
    @JvmStatic fun contactOf(context: Context, rawId: Long): Long? = runCatching {
        context.contentResolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.CONTACT_ID), "${RawContacts._ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(rawId.toString()), null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }.getOrNull()

    /** A saved contact with one of these numbers, so a web contact for someone you already have joins them. */
    @JvmStatic fun findByNumber(context: Context, numbers: List<String>): Long? {
        if (!canRead(context)) return null
        for (number in numbers) {
            val found = runCatching {
                context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun values(row: ContactRow): ContentValues = ContentValues().apply {
        put(Data.MIMETYPE, row.mime)
        put(Data.DATA1, row.value)
        put(Data.DATA2, row.type)
        row.protocol?.let { put(Data.DATA5, it) }
        row.custom?.let { put(Data.DATA6, it) }
    }

    /**
     * Saves [draft]: into the contact [contactId] when set, otherwise as a new contact on this phone. [removable] says
     * which of the contact's rows the draft may delete (ContactMapping.plan). Returns the raw contact written to.
     */
    @JvmStatic @JvmOverloads
    fun save(context: Context, draft: ContactDraft, photo: ByteArray?, contactId: Long?, removable: (ContactRow) -> Boolean = { true }): Long {
        val resolver = context.contentResolver
        val operations = ArrayList<ContentProviderOperation>()
        val raw = contactId?.let { rawContact(context, it) }
        if (raw == null) {
            operations += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null).build()
            operations += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, draft.name).build()
            for (row in ContactMapping.rows(draft)) {
                operations += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValues(values(row)).build()
            }
            if (photo != null) operations += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, photo).build()
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
            return results[0].uri?.lastPathSegment?.toLongOrNull() ?: 0
        }
        val currentName = read(context, contactId)?.name.orEmpty()
        val plan = ContactMapping.plan(currentName, rows(context, Data.CONTACT_ID, contactId), draft, removable)
        for (id in plan.delete) operations += ContentProviderOperation.newDelete(android.content.ContentUris.withAppendedId(Data.CONTENT_URI, id)).build()
        for (row in plan.insert) {
            operations += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.RAW_CONTACT_ID, raw).withValues(values(row)).build()
        }
        if (plan.rename) {
            // The name row's parts are cleared, so Android splits the new name again.
            operations += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection("${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(raw.toString(), StructuredName.CONTENT_ITEM_TYPE)).build()
            operations += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.RAW_CONTACT_ID, raw)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, draft.name).build()
        }
        if (photo != null) {
            operations += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection("${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf(raw.toString(), Photo.CONTENT_ITEM_TYPE)).build()
            operations += ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.RAW_CONTACT_ID, raw)
                .withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, photo).build()
        }
        if (operations.isNotEmpty()) resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        return raw
    }

    /** The Contacts app's new-contact screen with everything filled in, for when FluidMessages may not write contacts. */
    @JvmStatic fun insertIntent(draft: ContactDraft): Intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
        .putExtra(ContactsContract.Intents.Insert.NAME, draft.name)
        .putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, ArrayList(ContactMapping.rows(draft).map(::values)))
        .putExtra("finishActivityOnSaveCompleted", true)

    /** The Contacts app's editor for a saved contact, with the details the draft adds. */
    @JvmStatic fun editIntent(context: Context, contactId: Long, draft: ContactDraft): Intent {
        val have = rows(context, Data.CONTACT_ID, contactId).map { it.key }.toSet()
        val added = ContactMapping.rows(draft).filter { it.key !in have }
        return Intent(Intent.ACTION_EDIT, android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId))
            .putExtra(ContactsContract.Intents.Insert.NAME, draft.name)
            .putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, ArrayList(added.map(::values)))
            .putExtra("finishActivityOnSaveCompleted", true)
    }

    /** Chat addresses saved on every contact, by contact id, for the Phone tab. */
    @JvmStatic fun chatAddresses(context: Context): Map<Long, List<ChatAddress>> {
        if (!canRead(context)) return emptyMap()
        val result = linkedMapOf<Long, MutableList<ChatAddress>>()
        runCatching {
            context.contentResolver.query(Data.CONTENT_URI, arrayOf(Data.CONTACT_ID, Data.DATA1, Data.DATA5, Data.DATA6, Data.DISPLAY_NAME_PRIMARY),
                "${Data.MIMETYPE} = ?", arrayOf(ContactMapping.IM), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val address = ContactMapping.address(ContactRow(ContactMapping.IM, cursor.getString(1).orEmpty(), 0,
                        if (cursor.isNull(2)) null else cursor.getInt(2), cursor.getString(3))) ?: continue
                    val list = result.getOrPut(cursor.getLong(0)) { mutableListOf() }
                    if (address !in list) list += address
                }
            }
        }
        return result
    }

    /** Names of contacts with chat addresses but no phone number, by contact id, so they're in the Phone tab too. */
    @JvmStatic fun names(context: Context, ids: Collection<Long>): Map<Long, Pair<String, Uri?>> {
        if (!canRead(context) || ids.isEmpty()) return emptyMap()
        val result = linkedMapOf<Long, Pair<String, Uri?>>()
        for (chunk in ids.chunked(200)) runCatching {
            context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
                "${ContactsContract.Contacts._ID} IN (${chunk.joinToString(",") { "?" }})", chunk.map { it.toString() }.toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) result[cursor.getLong(0)] = (cursor.getString(1) ?: "") to cursor.getString(2)?.let(Uri::parse)
            }
        }
        return result
    }
}

/**
 * Contacts made in FluidMessages Web, as this phone last fetched them (GET /api/contacts, its own endpoint: they're
 * never settings-merge items, which older phones would read as deletions). They show in the Phone tab right away;
 * once you allow it, they're saved into Android's contacts, and later edits on the web update the same contact.
 * Deleting one on the web leaves the copy in your phone's contacts. Only names, numbers, and addresses are kept.
 */
object WebContacts {
    private const val LIST = "webContacts"
    private const val SAVED = "webContactsSaved"
    const val AUTO = "webContactsAutoSave"

    data class Entry(val id: String, val updated: Long, val draft: ContactDraft)

    /** Fetches the web's contacts, during a sync. An older server without contacts answers 404 and nothing changes. */
    @JvmStatic fun refresh(context: Context) {
        val prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE)
        if (Privacy.decoyMode(prefs)) return
        val answer = GatewayClient.contacts(prefs) ?: return
        val list = answer.optJSONArray("contacts") ?: return
        val kept = JSONArray()
        for (i in 0 until minOf(list.length(), 2000)) {
            val item = list.optJSONObject(i) ?: continue
            val entry = parse(item) ?: continue
            kept.put(JSONObject().put("id", entry.id).put("updated", entry.updated).put("name", entry.draft.name)
                .put("numbers", JSONArray(entry.draft.numbers))
                .put("addresses", JSONArray(entry.draft.addresses.map { JSONObject().put("service", it.service).put("address", it.address) })))
        }
        prefs.edit().putString(LIST, kept.toString()).apply()
        if (prefs.getBoolean(AUTO, false) && ContactWriter.allowed(context)) saveAll(context)
    }

    private fun parse(item: JSONObject): Entry? {
        val id = item.optString("id")
        if (!Regex("wc-[0-9a-f]{16}").matches(id)) return null
        val numbers = item.optJSONArray("numbers")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        val addresses = item.optJSONArray("addresses")?.let { array -> (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { ChatAddress(it.optString("service"), it.optString("address")) } } }.orEmpty()
        val draft = ContactMapping.clean(item.optString("name"), numbers, addresses, id, item.optLong("updated"))
        return if (draft.name.isEmpty()) null else Entry(id, item.optLong("updated"), draft)
    }

    @JvmStatic fun list(prefs: SharedPreferences): List<Entry> = runCatching {
        val array = JSONArray(prefs.getString(LIST, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parse) }
    }.getOrDefault(emptyList())

    private fun saved(prefs: SharedPreferences): JSONObject = runCatching { JSONObject(prefs.getString(SAVED, "{}")!!) }.getOrDefault(JSONObject())

    /** The phone contact a web contact was saved as, while it still exists. */
    @JvmStatic fun contactFor(context: Context, prefs: SharedPreferences, id: String): Long? {
        val raw = saved(prefs).optJSONObject(id)?.optLong("raw", 0) ?: 0
        return if (raw > 0) ContactWriter.contactOf(context, raw) else null
    }

    /** Web contacts not in the phone's contacts yet, or changed on the web since they were saved. */
    @JvmStatic fun pending(context: Context, prefs: SharedPreferences): List<Entry> {
        val saved = saved(prefs)
        return list(prefs).filter { entry ->
            val record = saved.optJSONObject(entry.id)
            record == null || record.optLong("updated") < entry.updated || contactFor(context, prefs, entry.id) == null
        }
    }

    @JvmStatic fun pendingCount(context: Context): Int = pending(context, context.getSharedPreferences("fluid", Context.MODE_PRIVATE)).size

    /** Saves one web contact into the phone's contacts: an update to the one saved before, someone you already have, or new. */
    @JvmStatic fun save(context: Context, entry: Entry) {
        val prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE)
        val record = saved(prefs).optJSONObject(entry.id)
        val previous = record?.optJSONArray("keys")?.let { array -> (0 until array.length()).map(array::optString).toSet() }.orEmpty()
        val earlier = contactFor(context, prefs, entry.id)
        val raw = if (earlier != null) {
            // Only what the web saved before may go; anything you added on the phone stays.
            ContactWriter.save(context, entry.draft, null, earlier) { it.key in previous }
        } else {
            val existing = ContactWriter.findByNumber(context, entry.draft.numbers)
            // Someone you already have gets the new details added, and keeps their name.
            if (existing != null) ContactWriter.save(context, entry.draft.copy(name = ContactWriter.read(context, existing)?.name ?: entry.draft.name),
                null, existing) { false }
            else ContactWriter.save(context, entry.draft, null, null)
        }
        remember(prefs, entry, raw)
    }

    @JvmStatic fun remember(prefs: SharedPreferences, entry: Entry, raw: Long) {
        val all = saved(prefs)
        all.put(entry.id, JSONObject().put("updated", entry.updated).put("raw", raw).put("keys", JSONArray(ContactMapping.keys(entry.draft).toList())))
        prefs.edit().putString(SAVED, all.toString()).apply()
    }

    /** Saves every pending web contact. Needs WRITE_CONTACTS. Returns how many were saved. */
    @JvmStatic fun saveAll(context: Context): Int {
        val prefs = context.getSharedPreferences("fluid", Context.MODE_PRIVATE)
        var count = 0
        for (entry in pending(context, prefs)) {
            try { save(context, entry); count++ } catch (_: Exception) {}
        }
        if (count > 0) ContactDirectory.shared(context).invalidate()
        return count
    }

    @JvmStatic fun find(prefs: SharedPreferences, id: String): Entry? = list(prefs).firstOrNull { it.id == id }

    /** Forgets everything fetched from the web, when the phone disconnects from it. */
    @JvmStatic fun forget(prefs: SharedPreferences) { prefs.edit().remove(LIST).apply() }
}
