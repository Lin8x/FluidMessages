package org.fluidmessages

import android.content.SharedPreferences
import android.text.format.DateUtils
import android.widget.LinearLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Consumer
import java.util.function.IntConsumer

/** Sends the choices (SyncMergeRun.resolve, off the main thread) and reports null when saved, or the problem. */
fun interface SyncResolver { fun resolve(id: String, choices: Map<String, String>, done: Consumer<String?>) }

/**
 * Differences between this phone and FluidMessages Web, one card each, drawn like the setup guides: Back on top, a
 * big title, and one wide button. The phone is picked for each by default, like "Use my phone's settings"; "Use
 * phone for all" and "Use web for all" set every card at once.
 */
object SyncConflictScreen {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, recolor: IntConsumer, person: java.util.function.Function<String, String>,
              resolver: SyncResolver, done: Runnable, back: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            SideEffect { recolor.accept(appearance.canvas) }
            AppTheme(appearance) {
                val scheme = MaterialTheme.colorScheme
                val id = remember { SyncPending.id(prefs) }
                val conflicts = remember { SyncPending.conflicts(prefs) }
                val choices = remember { mutableStateMapOf<String, String>().apply { putAll(SyncMerge.all(conflicts, SyncMerge.PHONE)) } }
                var saving by remember { mutableStateOf(false) }
                var problem by remember { mutableStateOf<String?>(null) }
                val enter = remember { Animatable(if (appearance.speed > 0) 0f else 1f) }
                LaunchedEffect(Unit) { if (appearance.speed > 0) enter.animateTo(1f, tween(appearance.speed * 2, easing = FastOutSlowInEasing)) }

                Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = back::run) { Text("Back") }
                        if (conflicts.isNotEmpty()) TextButton(onClick = back::run) { Text("Later") }
                    }
                    LazyColumn(Modifier.weight(1f).graphicsLayer { alpha = enter.value; translationX = (1f - enter.value) * 36.dp.toPx() },
                        contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(if (conflicts.isEmpty()) "Everything matches" else "Choose what to keep", fontSize = 32.sp, lineHeight = 36.sp,
                                    color = scheme.onSurface, fontWeight = FontWeight.Bold)
                                Text(if (conflicts.isEmpty()) "This phone and FluidMessages Web have the same settings."
                                    else "This phone and FluidMessages Web each changed these. Everything else was combined.",
                                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (conflicts.isEmpty()) item { ReadyIllustration(appearance.speed) }
                        else item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val allPhone = conflicts.all { choices[it.key] == SyncMerge.PHONE }
                                val allWeb = conflicts.all { choices[it.key] == SyncMerge.WEB }
                                FilterChip(allPhone, { choices.putAll(SyncMerge.all(conflicts, SyncMerge.PHONE)) }, { Text("Use phone for all") })
                                FilterChip(allWeb, { choices.putAll(SyncMerge.all(conflicts, SyncMerge.WEB)) }, { Text("Use web for all") })
                            }
                        }
                        items(conflicts, key = { it.key }) { conflict ->
                            val (section, title) = SyncMerge.describe(conflict.key) { person.apply(it) }
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainer).padding(15.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(section, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                                    Text(title, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    for ((side, label, value, time) in listOf(
                                        Choice4(SyncMerge.PHONE, "This phone", conflict.phone, conflict.phoneTime),
                                        Choice4(SyncMerge.WEB, "FluidMessages Web", conflict.web, conflict.webTime))) {
                                        SideOption(Modifier.weight(1f), label, SyncMerge.valueLabel(conflict.key, value), time,
                                            choices[conflict.key] == side) { choices[conflict.key] = side }
                                    }
                                }
                            }
                        }
                        problem?.let { item { Text(it, color = scheme.error) } }
                    }
                    if (conflicts.isEmpty()) {
                        AppButton(onClick = done::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text("Done") }
                    } else AppButton(onClick = {
                        saving = true
                        problem = null
                        resolver.resolve(id, choices.toMap()) { error ->
                            saving = false
                            if (error == null) done.run() else problem = error
                        }
                    }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) {
                        Text(if (saving) "Saving…" else "Keep these and sync")
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

private data class Choice4(val side: String, val label: String, val value: String?, val time: Long)

/** One side of a difference: who has it, the value, and when it changed. Picked shows the control tint's outline. */
@Composable
private fun SideOption(modifier: Modifier, label: String, value: String, time: Long, selected: Boolean, pick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = Color(MessageStyle.filledControl(LocalControlTint.current))
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(if (selected) tint.copy(alpha = .14f) else scheme.surface)
        .border(if (selected) 2.dp else 1.dp, if (selected) tint else scheme.outlineVariant, shape)
        .selectable(selected, role = Role.RadioButton, onClick = pick).heightIn(min = 72.dp).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioButton(selected, null, Modifier.size(18.dp), colors = RadioButtonDefaults.colors(selectedColor = tint))
            Text(label, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Text(value, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (time > 0) Text("Changed " + DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}
