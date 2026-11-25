package org.fluidmessages

import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.Consumer
import java.util.function.IntConsumer
import kotlinx.coroutines.delay

/** Checks and saves the address and token (MainActivity.saveCompanion). Returns what to fix, or null once saved. */
fun interface CompanionSaver { fun save(address: String, token: String, messaging: Boolean): String? }

/** Tries the saved connection (MainActivity.testCompanion) and reports null when it works, or what went wrong. */
fun interface CompanionTester { fun test(done: Consumer<String?>) }

/**
 * Connecting this phone to FluidMessages Web, step by step, drawn like the setup guide (OnboardingScreen): Back and
 * Close on top, progress bars, a big title, a picture, tips that slide in, and one wide button. One full page per
 * step; MainActivity keeps the step so Android's Back gesture goes one step back.
 */
object SyncGuideScreen {
    private val steps = listOf(
        Triple("what", "Your texts on a computer", "Read and send your texts from a browser, with FluidMessages Web. Optional."),
        Triple("start", "Start FluidMessages Web", "On your computer, start it one of these ways."),
        Triple("token", "Copy the link code", "In FluidMessages Web, open Settings → Phone and other devices."),
        Triple("enter", "Enter it here", "The address you open FluidMessages Web at, and the link code."),
        Triple("test", "Test the connection", "Checking that this phone can reach FluidMessages Web."),
        Triple("done", "You’re connected", "Your texts show up in FluidMessages Web within a few minutes."),
    )

    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, step: Int, next: Runnable, back: Runnable, close: Runnable,
              recolor: IntConsumer, learnWeb: Runnable, hasRole: Boolean, saver: CompanionSaver, tester: CompanionTester,
              review: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            SideEffect { recolor.accept(appearance.canvas) }
            AppTheme(appearance) {
                val scheme = MaterialTheme.colorScheme
                val (key, title, subtitle) = steps[step]
                var address by remember { mutableStateOf(prefs.getString("server", "").orEmpty()) }
                var token by remember { mutableStateOf(Privacy.token(prefs)) }
                var messaging by remember { mutableStateOf(prefs.getBoolean("syncSms", hasRole) && hasRole) }
                var problem by remember { mutableStateOf<String?>(null) }
                // The connection test: null while it runs, then "" when it worked or the problem.
                var result by remember { mutableStateOf<String?>(null) }
                var attempt by remember { mutableIntStateOf(0) }
                if (key == "test") LaunchedEffect(attempt) {
                    result = null
                    tester.test { result = it ?: "" }
                }
                // Each page eases in, like a step changing (still when motion is off).
                val enter = remember { Animatable(if (appearance.speed > 0) 0f else 1f) }
                LaunchedEffect(Unit) { if (appearance.speed > 0) enter.animateTo(1f, tween(appearance.speed * 2, easing = FastOutSlowInEasing)) }

                Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = back::run) { Text("Back") }
                        if (step < steps.lastIndex) TextButton(onClick = close::run) { Text("Close") }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .semantics { contentDescription = "Step ${step + 1} of ${steps.size}" },
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (index in steps.indices) {
                            Box(Modifier.weight(1f).height(4.dp)
                                .background(if (index <= step) scheme.primary else scheme.surfaceVariant, RoundedCornerShape(4.dp)))
                        }
                    }
                    LazyColumn(Modifier.weight(1f).graphicsLayer { alpha = enter.value; translationX = (1f - enter.value) * 36.dp.toPx() },
                        contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(title, fontSize = 32.sp, lineHeight = 36.sp, color = scheme.onSurface, fontWeight = FontWeight.Bold)
                                Text(subtitle, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        when (key) {
                            "what" -> {
                                item { ComputerIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.CHAT, "Read and reply on a computer", "Your texts sync to FluidMessages Web"),
                                        Tip(TipIcon.PHONE, "This phone still sends them", "On your phone plan, same as always"),
                                        Tip(TipIcon.FOLDER, "Cloud backups too", "OneDrive, Google Drive, or S3, through FluidMessages Web"),
                                        Tip(TipIcon.OPTIONAL, "Not needed", "Your phone works on its own")), appearance.speed)
                                }
                            }
                            "start" -> {
                                item { LinkIllustration(appearance.speed, null) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.LAPTOP, "The desktop app", "Open FluidMessages on your computer"),
                                        Tip(TipIcon.FOLDER, "Docker", "docker compose up -d, on a server or NAS"),
                                        Tip(TipIcon.TAG, "From the source", "Run scripts/web-app"),
                                        Tip(TipIcon.LOCK, "Your phone needs HTTPS", "An https:// address, for example through Caddy"),
                                        Tip(TipIcon.PRACTICE, "How it works", "The setup guide, in your browser") { learnWeb.run() }), appearance.speed)
                                }
                            }
                            "token" -> {
                                item { TokenIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.PHONE, "Connect your phone", "Under Your phone. It asks for a password first if there isn’t one."),
                                        Tip(TipIcon.LOCK, "Copy the link code", "Shown only once. Keep it private."),
                                        Tip(TipIcon.LAPTOP, "Note the address", "The guide there shows it too, with a Copy button")), appearance.speed)
                                }
                            }
                            "enter" -> {
                                item {
                                    OutlinedTextField(address, { address = it.trim(); problem = null }, Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text("Address") }, placeholder = { Text("https://messages.example.net") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                                }
                                item {
                                    OutlinedTextField(token, { token = it.trim(); problem = null }, Modifier.fillMaxWidth(), singleLine = true,
                                        label = { Text("Link code") }, visualTransformation = PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                                }
                                item {
                                    PreferencesCard("Messages") {
                                        SettingsSwitch("Enable browser messaging", if (hasRole) "Syncs the newest 2,000 texts and sends texts you write on the web"
                                            else "Needs FluidMessages as your texting app. Off still links for cloud backups.", messaging) {
                                            messaging = it; problem = null
                                        }
                                    }
                                }
                                problem?.let { item { Text(it, color = scheme.error) } }
                            }
                            "test" -> {
                                item { LinkIllustration(appearance.speed, result?.isEmpty()) }
                                item {
                                    Text(when {
                                        result == null -> "Testing…"
                                        result!!.isEmpty() -> "Connected. Your first sync is on its way."
                                        else -> result!!
                                    }, color = if (result.isNullOrEmpty()) scheme.onSurface else scheme.error,
                                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                                }
                                if (!result.isNullOrEmpty()) item {
                                    Tips(listOf(
                                        Tip(TipIcon.LAPTOP, "Is FluidMessages Web running?", "And can this phone reach its address?"),
                                        Tip(TipIcon.LOCK, "Does the address start with https://?", "Your phone only connects to secure addresses"),
                                        Tip(TipIcon.FOLDER, "Change the address or link code", "Go back a step") { back.run() }), appearance.speed)
                                }
                            }
                            "done" -> {
                                item { ReadyIllustration(appearance.speed) }
                                item {
                                    Tips(listOf(
                                        Tip(TipIcon.CHAT, "Texts on the web", if (messaging) "They show up within a few minutes" else "Turn on browser messaging to see them there"),
                                        Tip(TipIcon.FOLDER, "Cloud backups", "Settings → Backups"),
                                        *(if (SyncPending.count(prefs) > 0) arrayOf(Tip(TipIcon.PRACTICE, "Choose what to keep",
                                            "Some settings differ from FluidMessages Web") { review.run() }) else emptyArray()),
                                        Tip(TipIcon.OPTIONAL, "Change it later", "Settings → FluidMessages Web")), appearance.speed)
                                }
                            }
                        }
                    }
                    when (key) {
                        "enter" -> AppButton(onClick = {
                            val missing = saver.save(address, token, messaging)
                            if (missing == null) next.run() else problem = missing
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text("Save and continue") }
                        // A first pairing with differences goes straight to choosing what to keep (SyncConflictScreen).
                        "test" -> AppButton(onClick = {
                            if (result != "") attempt++ else if (SyncPending.count(prefs) > 0) review.run() else next.run()
                        }, enabled = result != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) {
                            Text(when (result) { null -> "Testing…"; "" -> "Continue"; else -> "Try again" })
                        }
                        "done" -> AppButton(onClick = close::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text("Done") }
                        else -> AppButton(onClick = next::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text("Continue") }
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

/**
 * A phone and a computer with dots travelling between them. [connected]: null while waiting, true shows a check,
 * false shows the dots stopped.
 */
@Composable
private fun LinkIllustration(speed: Int, connected: Boolean?) {
    val scheme = MaterialTheme.colorScheme
    val phase = if (speed == 0 || connected != null) 1f else {
        val transition = rememberInfiniteTransition(label = "Link")
        val value by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(speed * 6, easing = LinearEasing)), label = "Link dots")
        value
    }
    val check = remember(connected) { Animatable(if (connected == true && speed > 0) 0f else 1f) }
    LaunchedEffect(connected) { if (connected == true && speed > 0) check.animateTo(1f, spring(dampingRatio = .5f)) }
    Box(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp, 96.dp)) {
            val stroke = 3.dp.toPx()
            val ink = scheme.onSurfaceVariant
            val h = size.height
            // Phone
            drawRoundRect(ink, Offset(h * .1f, h * .05f), Size(h * .48f, h * .9f), CornerRadius(9.dp.toPx()), style = Stroke(stroke))
            drawRoundRect(scheme.primary, Offset(h * .22f, h * .5f), Size(h * .26f, h * .12f), CornerRadius(h * .06f))
            // Computer
            val left = size.width - h * 1.25f
            drawRoundRect(ink, Offset(left + h * .1f, h * .1f), Size(h * 1.0f, h * .62f), CornerRadius(7.dp.toPx()), style = Stroke(stroke))
            drawLine(ink, Offset(left - h * .05f, h * .86f), Offset(size.width, h * .86f), stroke * 1.4f, StrokeCap.Round)
            drawRoundRect(scheme.primary, Offset(left + h * .48f, h * .42f), Size(h * .44f, h * .14f), CornerRadius(h * .07f))
            // Between them: travelling dots, or a check once it worked
            val middle = (h * .6f + left) / 2
            if (connected == true) {
                val r = h * .2f * check.value
                drawCircle(scheme.primary, r, Offset(middle, h * .5f))
                if (check.value > .3f) {
                    drawLine(scheme.onPrimary, Offset(middle - r * .45f, h * .5f), Offset(middle - r * .1f, h * .5f + r * .35f), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(scheme.onPrimary, Offset(middle - r * .1f, h * .5f + r * .35f), Offset(middle + r * .5f, h * .5f - r * .35f), 3.dp.toPx(), StrokeCap.Round)
                }
            } else for (dot in 0 until 3) {
                val lit = connected == null && speed > 0 && phase.toInt() == dot
                drawCircle(scheme.primary.copy(alpha = if (connected == false) .25f else if (lit || speed == 0) 1f else .3f), h * .05f,
                    Offset(middle + (dot - 1) * h * .18f, h * .5f))
            }
        }
    }
}

/** The pairing token, hidden, with its Copy button breathing. */
@Composable
private fun TokenIllustration(speed: Int) {
    val scheme = MaterialTheme.colorScheme
    val scale = if (speed == 0) 1f else {
        val transition = rememberInfiniteTransition(label = "Copy")
        val value by transition.animateFloat(.94f, 1.04f, infiniteRepeatable(tween(speed * 6, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "Copy scale")
        value
    }
    var shown by remember { mutableIntStateOf(if (speed == 0) 12 else 0) }
    LaunchedEffect(speed) { while (speed > 0 && shown < 12) { delay(60); shown++ } }
    Box(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(22.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.background(scheme.surface, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(22.dp)) {
                val stroke = 1.9.dp.toPx()
                drawCircle(scheme.onSurfaceVariant, size.width * .18f, Offset(size.width * .3f, size.height * .5f), style = Stroke(stroke))
                drawLine(scheme.onSurfaceVariant, Offset(size.width * .48f, size.height * .5f), Offset(size.width * .95f, size.height * .5f), stroke)
                drawLine(scheme.onSurfaceVariant, Offset(size.width * .8f, size.height * .5f), Offset(size.width * .8f, size.height * .66f), stroke)
            }
            Text("•".repeat(shown).padEnd(12, ' '), color = scheme.onSurfaceVariant, fontSize = 16.sp, letterSpacing = 1.sp)
            Text("Copy", Modifier.scale(scale).background(scheme.primary, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                color = scheme.onPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}
