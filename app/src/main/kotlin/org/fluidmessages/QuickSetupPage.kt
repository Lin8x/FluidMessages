package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Quick setup page (QuickSetup): the choices as cards, each with who it's for, what it changes, and what it
 * doesn't protect. Choosing one opens a short summary under it, in place, with "Use this setup". Below the cards,
 * the three questions show the current answers, for people who want to adjust one.
 */
internal object QuickSetupPage {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, openNearby: Runnable, changed: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(remember { Appearance.read(prefs) }) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item {
                        Text("Pick what fits you. Each choice only changes settings you could change yourself, and you can change any " +
                            "of them later. If you do, this shows Custom.", Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    item { QuickSetupChoices(prefs, { openNearby.run() }, questions = true) { changed.run() } }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** One line at the top of Accounts and Chat services: "Quick setup: Standard · Change". */
    @JvmStatic
    fun mountRow(root: LinearLayout, prefs: SharedPreferences, open: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(remember { Appearance.read(prefs) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer).clickable(role = Role.Button) { open.run() }
                    .padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(QuickSetup.label(prefs), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("Change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, -2))
    }
}

/**
 * The choices, and with [questions] the three questions too. Not lazy, so it fits inside another list, like the
 * setup's services step. [changed] runs after a choice is saved.
 */
@Composable
internal fun QuickSetupChoices(prefs: SharedPreferences, openNearby: (() -> Unit)?, questions: Boolean, changed: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val values = remember(tick) { prefs.all }
    val current = QuickSetup.current(values)
    var ready by remember { mutableStateOf(QuickSetup.Ready()) }
    LaunchedEffect(tick) { ready = withContext(Dispatchers.IO) { runCatching { QuickSetup.ready(context) }.getOrDefault(QuickSetup.Ready()) } }
    // The choice or answer whose summary is open: a choice's id, or "q:" and what the question would set.
    var open by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf("") }
    fun use(plan: QuickSetup.Plan) {
        // A card is remembered as your choice; a question's answer is an adjustment of your own, so it isn't.
        val card = open?.takeUnless { it.startsWith("q:") }
        if (card != null) QuickSetup.apply(context, prefs, plan, card)
        else { prefs.edit().remove(QuickSetup.CHOSEN).apply(); QuickSetup.apply(context, prefs, plan, null) }
        open = null
        done = if (plan.steps.isEmpty()) "Done. Quick setup: " + QuickSetup.title(QuickSetup.current(prefs.all)) + "."
            else "Saved. One thing is left: " + plan.steps.first()
        tick++
        changed()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Now: " + QuickSetup.title(current) + if (current == QuickSetup.CUSTOM) " · " + QuickSetup.CUSTOM_LINE else "",
            Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
        if (done.isNotEmpty()) Text(done, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary)
        for (choice in QuickSetup.CHOICES) {
            ChoiceCard(choice, choice.id == current, open == choice.id, { open = if (open == choice.id) null else choice.id; done = "" }) {
                Summary(QuickSetup.plan(choice, values, ready), openNearby, { open = null }, ::use, card = choice.id != current)
            }
        }
        if (questions) {
            val answers = QuickSetup.answers(values)
            Text("Or answer these", Modifier.padding(start = 12.dp, top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
            Question("What can you send?", QuickSetup.Send.entries.map { it.title }, QuickSetup.Send.entries.indexOf(answers.send), "send", open) { index ->
                val id = "q:send:$index"
                open = if (open == id) null else id; done = ""
            }
            QuestionSummary(open, "q:send:", QuickSetup.Send.entries.size, values, ready, openNearby, { open = null }, ::use) {
                QuickSetup.sendValues(QuickSetup.Send.entries[it])
            }
            Question("Connect through Tor?", listOf("No", "Yes"), if (answers.tor) 1 else 0, "tor", open) { index ->
                val id = "q:tor:$index"
                open = if (open == id) null else id; done = ""
            }
            QuestionSummary(open, "q:tor:", 2, values, ready, openNearby, { open = null }, ::use) { QuickSetup.torValues(it == 1) }
            Question("Fewer leaks?", listOf("No", "Yes"), when (answers.fewerLeaks) { true -> 1; false -> 0; null -> -1 }, "leaks", open) { index ->
                val id = "q:leaks:$index"
                open = if (open == id) null else id; done = ""
            }
            if (answers.fewerLeaks == null) Text("Some of these extras are on and some are off.", Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            QuestionSummary(open, "q:leaks:", 2, values, ready, openNearby, { open = null }, ::use) { QuickSetup.leakValues(it == 1) }
        }
    }
}

@Composable
private fun ChoiceCard(choice: QuickSetup.Choice, inUse: Boolean, expanded: Boolean, toggle: () -> Unit, summary: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainer)
        .then(if (inUse) Modifier.border(BorderStroke(2.dp, scheme.primary), RoundedCornerShape(20.dp)) else Modifier)) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Choose ${choice.title}") { toggle() }.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(choice.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (inUse) Text("In use", color = scheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Text("For: " + choice.forWho, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(choice.does, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text("What this doesn't protect: " + choice.doesnt, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (!expanded) Text(if (inUse) "See what it sets" else "See what changes", color = scheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) {
            HorizontalDivider(Modifier.padding(horizontal = 15.dp), color = scheme.outlineVariant)
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What it sets", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                for (line in QuickSetup.lines(choice)) Text("• $line", style = MaterialTheme.typography.bodySmall)
            }
            summary()
        }
    }
}

/** The short summary before saving, in place: what changes, what's left to do, and "Use this setup". */
@Composable
private fun Summary(plan: QuickSetup.Plan, openNearby: (() -> Unit)?, cancel: () -> Unit, use: (QuickSetup.Plan) -> Unit,
                    /** A card that isn't the current choice: it can be chosen even when no setting changes. */
                    card: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(RoundedCornerShape(14.dp))
        .background(scheme.surfaceContainerHigh).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val changes = QuickSetup.describe(plan.set)
        if (changes.isEmpty() && plan.steps.isEmpty()) Text(if (card) "Your settings already fit. Using it only changes what Quick setup shows." else "You're already using these settings.")
        else {
            Text(if (changes.isEmpty()) "Nothing changes yet." else "This changes:", style = MaterialTheme.typography.titleSmall)
            for (line in changes) Text("• $line", style = MaterialTheme.typography.bodySmall)
        }
        for (step in plan.steps) Text(step, color = scheme.error, style = MaterialTheme.typography.bodySmall)
        for (note in plan.notes) Text(note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel) { Text("Cancel") }
            if (openNearby != null && QuickSetup.NEEDS_NEARBY_NAME in plan.steps) OutlinedButton(onClick = openNearby) { Text("Set up Nearby") }
            Spacer(Modifier.weight(1f))
            if (plan.set.isNotEmpty() || card) AppButton(onClick = { use(plan) }) { Text("Use this setup") }
        }
    }
}

@Composable
private fun Question(title: String, options: List<String>, selected: Int, id: String, open: String?, pick: (Int) -> Unit) {
    PreferencesCard(title) {
        options.forEachIndexed { index, option ->
            Row(Modifier.fillMaxWidth().selectable(index == selected, role = Role.RadioButton) { if (index != selected) pick(index) }
                .padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(index == selected || open == "q:$id:$index", null)
                Spacer(Modifier.width(10.dp))
                Text(option, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun QuestionSummary(open: String?, prefix: String, count: Int, values: Map<String, *>, ready: QuickSetup.Ready,
                            openNearby: (() -> Unit)?, cancel: () -> Unit, use: (QuickSetup.Plan) -> Unit, set: (Int) -> Map<String, Any>) {
    val index = (0 until count).firstOrNull { open == prefix + it } ?: return
    val answer = QuickSetup.Choice("answer", "", "", "", "", set(index))
    Summary(QuickSetup.plan(answer, values, ready), openNearby, cancel, use)
}

/** The setup's services step: "Choose a quick setup", closed until tapped. Optional; Standard is what you start with. */
@Composable
internal fun QuickSetupCard(prefs: SharedPreferences) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    PreferencesCard("Choose a quick setup") {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PreferenceText(remember(tick) { QuickSetup.label(prefs) }, "Optional. Pick a few settings at once, like app lock or " +
                "sending only encrypted messages. You can change it later in Settings → Privacy and security.",
                Modifier.weight(1f).padding(end = 12.dp))
            Text(if (expanded) "Close" else "Choose", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) Box(Modifier.padding(bottom = 12.dp)) { QuickSetupChoices(prefs, null, questions = false) { tick++ } }
    }
}
