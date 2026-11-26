package org.fluidmessages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Staying in touch without internet": a full page, reachable from the Nearby page and Settings → Help, drawn like
 * the Communities page (a big title, then tips). The same words are in docs/offline-guide.md. Plain and honest: what
 * still works, what to set up before you need it, what Nearby can't do, and other tools for longer range.
 */
internal object OfflineGuide {
    const val TITLE = "Staying in touch without internet"
    const val SUBTITLE = "What still works when the internet or the cell network is down, and what to set up before you need it."

    data class Section(val title: String, val tips: List<Tip>, val note: String = "")

    val sections = listOf(
        Section("If cell towers work but the internet is down", listOf(
            Tip(TipIcon.PHONE, "Texts and calls still work", "They use the cell network, not the internet. FluidMessages sends texts as usual."),
            Tip(TipIcon.CHAT, "Keep messages short", "A busy network passes short texts more easily than long ones or photos."),
            Tip(TipIcon.TIMER, "Texts often get through when calls don’t", "A text waits for a gap and then goes. A call needs a clear line the whole time."))),
        Section("If there’s no service at all", listOf(
            Tip(TipIcon.LAPTOP, "Phones on the same Wi-Fi", "Nearby reaches FluidMessages phones on one Wi-Fi network, even when that Wi-Fi has no internet: a router on battery, or one phone’s hotspot."),
            Tip(TipIcon.PEOPLE, "Bluetooth, about 10–30 m", "Through a wall or two, depending on the walls and the phones."),
            Tip(TipIcon.FOLDER, "Phones carry messages as people move", "With Help pass messages along on, a message goes from phone to phone and arrives a little later."),
            Tip(TipIcon.TAG, "Set up meeting points", "Agree on a place and a time, so phones come within range of each other."))),
        Section("Practical tips", listOf(
            Tip(TipIcon.PEOPLE, "Set up Nearby before an emergency", "Turn it on and add your family in person now, so you know it’s really them."),
            Tip(TipIcon.FOLDER, "Turn on Help pass messages along", "Your phone carries messages for others nearby, sealed so it can’t read one-to-one ones."),
            Tip(TipIcon.PHONE, "One charged phone can relay for a building", "Left on with Nearby, in a hallway or on a middle floor, it passes messages between floors."),
            Tip(TipIcon.TIMER, "Keep a battery pack charged", "A phone that’s off can’t carry anything."),
            Tip(TipIcon.OPTIONAL, "Stay discoverable costs battery", "It keeps Nearby running with FluidMessages closed. Turn it on when it matters."),
            Tip(TipIcon.LOCK, "Turn on Nearby only", "On the Nearby page. FluidMessages stops using the internet, which saves data and battery."),
            Tip(TipIcon.CHAT, "Use community channels", "Open channels like “Neighborhood” are for everyone nearby. Anyone close by can read them."))),
        Section("What it can’t do", listOf(
            Tip(TipIcon.OPTIONAL, "It can’t reach people far away", "Not without phones in between to carry the message."),
            Tip(TipIcon.PHONE, "It can’t call", "Nearby sends words only."),
            Tip(TipIcon.TIMER, "It isn’t instant across a city", "Messages move as fast as the people carrying them."),
            Tip(TipIcon.PEOPLE, "It only reaches FluidMessages phones", "With Nearby turned on."))),
        Section("For longer range, other tools exist", listOf(
            Tip(TipIcon.TAG, "LoRa mesh radios", "Small radios, such as Meshtastic devices, pair with a phone and can reach kilometres between radios."),
            Tip(TipIcon.PEOPLE, "GMRS and FRS walkie-talkies", "Voice over a few kilometres. In the US, GMRS needs a license and FRS doesn’t."),
            Tip(TipIcon.PRACTICE, "Amateur (ham) radio", "Much longer range, with a license and some practice.")),
            note = "FluidMessages doesn’t connect to these yet."),
    )

    /** Every line of the page, in order, for the test that keeps it and docs/offline-guide.md in step. */
    fun text(): List<String> = listOf(TITLE, SUBTITLE) + sections.flatMap { section ->
        listOf(section.title) + section.tips.flatMap { listOf(it.title, it.line) } + listOfNotNull(section.note.ifEmpty { null })
    }
}

/** The guide page. [openNearby] is shown as a tip that opens the Nearby page; null when the guide was opened from it. */
@Composable
internal fun OfflineGuidePage(prefs: android.content.SharedPreferences, openNearby: (() -> Unit)?) {
    val appearance = remember { Appearance.read(prefs) }
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(OfflineGuide.TITLE, Modifier.semantics { heading() }, fontSize = 32.sp, lineHeight = 36.sp, color = scheme.onSurface,
                    fontWeight = FontWeight.Bold)
                Text(OfflineGuide.SUBTITLE, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (openNearby != null) item {
            Tips(listOf(Tip(TipIcon.PEOPLE, "Open Nearby", "Set it up, see who’s close by, and add your family", openNearby)), appearance.speed)
        }
        for (section in OfflineGuide.sections) {
            item {
                Text(section.title, Modifier.padding(top = 6.dp).semantics { heading() }, color = scheme.onSurface,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            item { Tips(section.tips, appearance.speed) }
            if (section.note.isNotEmpty()) item {
                Text(section.note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
