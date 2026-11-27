package org.fluidmessages

import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.IntConsumer

/** Saves a connection the computer approved: its address, the pairing token, and the fingerprint. Null once saved. */
fun interface LanSaver { fun save(origin: String, token: String, fingerprint: String): String? }

/**
 * Find my computer (Settings → FluidMessages Web): computers with FluidMessages Web on this Wi-Fi, found as they
 * appear (LanPairing). This phone asks each one to connect as soon as it's found, so it shows up in the computer's
 * Connect your phone screen too, with the same six digits shown here. Clicking Connect there finishes it here.
 */
object FindComputerScreen {
    private sealed interface Status {
        data object Asking : Status
        data class Waiting(val code: String) : Status
        data class Problem(val line: String) : Status
        data object Saving : Status
    }

    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, recolor: IntConsumer, back: Runnable, scanInstead: Runnable,
              typeInstead: Runnable, saver: LanSaver, paired: Runnable) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            val appearance = remember { Appearance.read(prefs) }
            SideEffect { recolor.accept(appearance.canvas) }
            AppTheme(appearance) { Page(back, scanInstead, typeInstead, saver, paired) }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    @Composable
    private fun Page(back: Runnable, scanInstead: Runnable, typeInstead: Runnable, saver: LanSaver, paired: Runnable) {
        val scheme = MaterialTheme.colorScheme
        val context = LocalContext.current
        var computers by remember { mutableStateOf<List<LanPairing.Computer>>(emptyList()) }
        val statuses = remember { mutableStateMapOf<String, Status>() }
        var slow by remember { mutableStateOf(false) }
        val phone = remember { LanPairing.phoneName(Build.MANUFACTURER, Build.MODEL) }
        val gone = remember { AtomicBoolean(false) }
        val work = remember { Executors.newCachedThreadPool() }
        val main = remember { Handler(Looper.getMainLooper()) }
        val asked = remember { HashSet<String>() }
        var done by remember { mutableStateOf(false) }

        // Asks one computer, then checks every two seconds until it says Connect or Not this one. A request the
        // computer forgot (restarted, or three quiet minutes) is asked again.
        fun ask(computer: LanPairing.Computer) {
            if (!asked.add(computer.key)) return
            statuses[computer.key] = Status.Asking
            work.execute {
                var rounds = 0
                while (!gone.get() && rounds++ < 6) {
                    val secret = LanPairing.secret()
                    val reply = LanPairing.ask(computer, phone, secret)
                    if (reply.id == null || reply.origin == null) {
                        main.post { if (!gone.get()) statuses[computer.key] = Status.Problem(reply.problem.orEmpty()) }
                        break
                    }
                    val code = LanPairing.code(computer.fingerprint, secret)
                    main.post { if (!gone.get()) statuses[computer.key] = Status.Waiting(code) }
                    var misses = 0
                    while (!gone.get()) {
                        try { Thread.sleep(2000) } catch (error: InterruptedException) { return@execute }
                        val answer = LanPairing.check(reply.origin, computer.fingerprint, reply.id, secret)
                        if (answer == null) {
                            if (++misses >= 8) {
                                main.post { if (!gone.get()) statuses[computer.key] = Status.Problem("Lost touch with ${computer.name}. Check that this phone is on the same Wi-Fi.") }
                                return@execute
                            }
                            continue
                        }
                        misses = 0
                        when (answer.state) {
                            "waiting" -> continue
                            "approved" -> {
                                val token = answer.token ?: break
                                main.post {
                                    if (gone.get()) return@post
                                    statuses[computer.key] = Status.Saving
                                    val problem = saver.save(reply.origin, token, computer.fingerprint)
                                    if (problem != null) statuses[computer.key] = Status.Problem(problem)
                                    else { done = true; paired.run() }
                                }
                                return@execute
                            }
                            "declined" -> {
                                main.post { if (!gone.get()) statuses[computer.key] = Status.Problem("${computer.name} said Not this one.") }
                                return@execute
                            }
                            else -> break
                        }
                    }
                }
            }
        }

        DisposableEffect(Unit) {
            val finder = LanPairing.Finder(context) { list ->
                computers = list
                list.forEach { ask(it) }
            }
            finder.start()
            main.postDelayed({ if (!gone.get()) slow = true }, 12_000)
            onDispose {
                gone.set(true)
                finder.stop()
                work.shutdownNow()
            }
        }

        Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
            Row(Modifier.fillMaxWidth()) { TextButton(onClick = back::run) { Text("Back") } }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Find my computer", fontSize = 32.sp, lineHeight = 36.sp, color = scheme.onSurface, fontWeight = FontWeight.Bold)
                        Text("Computers with FluidMessages Web on this Wi-Fi show up here. On the computer, open Settings → Phone and other devices → Connect your phone, then click Connect next to $phone.",
                            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (computers.isEmpty()) item {
                    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(18.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Looking on this Wi-Fi…", color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (computers.isEmpty() && slow) item {
                    Text("Nothing yet? In the FluidMessages Web desktop app, turn on Let my phone connect in Settings → Phone and other devices. " +
                        "A server you run yourself needs FLUID_BIND=0.0.0.0. Some Wi-Fi networks, like at work or school, keep devices apart: scan the QR code instead.",
                        color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(14.dp))
                }
                items(computers, key = { it.key }) { computer ->
                    ComputerCard(computer, statuses[computer.key], done) { asked.remove(computer.key); ask(computer) }
                }
            }
            AppButton(onClick = scanInstead::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text("Scan QR code instead") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = typeInstead::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text("Type them in instead") }
        }
    }

    @Composable
    private fun ComputerCard(computer: LanPairing.Computer, status: Status?, done: Boolean, retry: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(computer.name, color = scheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            when (status) {
                is Status.Waiting -> {
                    Text(if (done) "Connected" else "Click Connect on this computer if it shows:", color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(status.code, fontSize = 34.sp, lineHeight = 38.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        color = scheme.onSurface, modifier = Modifier.semantics { contentDescription = "Code " + status.code.replace(" ", "") })
                    Text("If the numbers are different, don’t connect.", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                is Status.Problem -> {
                    Text(status.line, color = scheme.error, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                }
                Status.Saving -> Text("Connecting…", color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                else -> Text("Asking to connect…", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
