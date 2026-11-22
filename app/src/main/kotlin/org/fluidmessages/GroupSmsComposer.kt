package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Supplier

object GroupSmsComposer {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, recipients: List<String>, draft: String,
              sim: Supplier<String>, chooseSim: Runnable, send: BiConsumer<String, Consumer<Int>>) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            var body by remember { mutableStateOf(draft) }
            var busy by remember { mutableStateOf(false) }
            var submitted by remember { mutableStateOf<Int?>(null) }
            var simName by remember { mutableStateOf(sim.get()) }
            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "sim") simName = sim.get()
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            AppTheme(Appearance.read(prefs)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Each recipient gets their own SMS. Replies appear in individual chats.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PreferencesCard("Recipients") { PreferenceText(recipients.joinToString(" · "), "") }
                    TextButton(onClick = chooseSim::run, enabled = !busy && submitted == null) { Text(simName) }
                    OutlinedTextField(body, { if (it.length <= 1600) body = it }, Modifier.fillMaxWidth(),
                        enabled = !busy && submitted == null, label = { Text("Message") }, minLines = 3)
                    AppButton(onClick = {
                        busy = true
                        send.accept(body) { count -> submitted = count; busy = false }
                    }, enabled = body.isNotBlank() && body.length <= 1600 && !busy && submitted == null,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Submitting…" else "Send separate SMS")
                    }
                    submitted?.let { count ->
                        val failed = recipients.size - count
                        Text("$count submitted to Android" + if (failed > 0) " · $failed could not be submitted" else "",
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
