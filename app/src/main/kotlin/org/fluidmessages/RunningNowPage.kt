package org.fluidmessages

import android.app.job.JobScheduler
import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.widget.LinearLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.function.Consumer

/**
 * Settings → Running now: everything FluidMessages keeps going, grouped and in plain words (BackgroundStatus). It
 * reads settings, Android's pending jobs, and the live connections every few seconds while it's open, and changes
 * nothing. Each row opens the setting that controls it.
 */
internal object RunningNowPage {
    private const val REFRESH_MS = 3000L

    /** The Settings row's summary. Doesn't wait on Tor, so it's quick enough for the main thread. */
    @JvmStatic
    fun summary(context: Context): String =
        runCatching { BackgroundStatus.summary(BackgroundStatus.rows(snapshot(context, false), ::clock)) }.getOrDefault("What’s running now")

    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, open: Consumer<BackgroundStatus.Opens>) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { AppTheme(remember { Appearance.read(prefs) }) { Page { open.accept(it) } } }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun clock(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))

    /** Reads what's running now. With [checkTor] it asks Tor's port whether it answers, so call it off the main thread. */
    fun snapshot(context: Context, checkTor: Boolean): BackgroundStatus.Snapshot {
        val app = context.applicationContext
        val prefs = SyncPolicy.prefs(app)
        val now = System.currentTimeMillis()
        val live = DirectProtocols.live()
        val pending = runCatching { app.getSystemService(JobScheduler::class.java).allPendingJobs.map { it.id }.toMutableSet() }
            .getOrDefault(mutableSetOf())
        val known = mutableSetOf(SyncJob.PERIODIC_ID, Outbox.JOB)

        val connections = listOf(MessageProtocol.MATRIX, MessageProtocol.XMPP, MessageProtocol.EMAIL, MessageProtocol.IRC).map { p ->
            val status = DirectProtocols.status(prefs, p)
            val on = DirectProtocols.on(prefs, p) && (p.enabled(prefs) || Calling.keepsConnected(prefs, p))
            val usesWeb = on && p == MessageProtocol.IRC && DirectProtocols.deferredToWeb(prefs, p, DirectProtocols.account(app, p))
            BackgroundStatus.Connection(p.label, on, DirectProtocols.stayConnected(prefs, p), p in live,
                status.optString("state"), status.optBoolean("manual"), DirectProtocols.connectedSince(prefs, p),
                status.optLong("retry_at"), usesWeb,
                polled = p == MessageProtocol.EMAIL || p == MessageProtocol.MATRIX && DirectProtocols.autoReconnect(prefs, p))
        }

        // Backups: the phone's folder, folders and cloud services, and file servers, each with its Android job.
        val backups = mutableListOf<BackgroundStatus.Backup>()
        val local = BackupJob.FolderSchedule.LOCAL
        val localOn = local.minutes(prefs) > 0 && local.tree(prefs).isNotEmpty()
        if (localOn) backups += BackgroundStatus.Backup("local", "Backup to a folder", local.minutes(prefs), local.wifiOnly(prefs),
            local.last(prefs), BackupJob.JOB in pending)
        for (target in CloudBackup.Target.values()) {
            val folder = BackupJob.FolderSchedule(target)
            known += folder.id()
            known += BackupJob.cloudJobId(target)
            if (folder.minutes(prefs) > 0 && folder.tree(prefs).isNotEmpty()) backups += BackgroundStatus.Backup("folder-" + target.id,
                "Backup to ${target.label}", folder.minutes(prefs), folder.wifiOnly(prefs), folder.last(prefs), folder.id() in pending)
            val minutes = BackupJob.interval(prefs, target)
            if (minutes > 0) backups += BackgroundStatus.Backup("cloud-" + target.id, "Backup to ${target.label} through FluidMessages Web",
                minutes, prefs.getBoolean(BackupJob.cloudKey(target, "wifi"), false), prefs.getLong(BackupJob.cloudKey(target, "last"), 0),
                BackupJob.cloudJobId(target) in pending)
        }
        for (server in runCatching { FileServers.list(prefs) }.getOrDefault(emptyList())) {
            val id = FileServers.jobId(server.optString("id"))
            known += id
            val minutes = server.optInt("minutes")
            if (minutes > 0) backups += BackgroundStatus.Backup("server-$id",
                "Backup to " + server.optString("name").ifEmpty { FileServers.label(server.optString("kind")) + " server" },
                minutes, server.optBoolean("wifi"), server.optLong("last"), id in pending)
        }
        val syncSoon = SyncJob.SOON_ID in pending
        known += SyncJob.SOON_ID

        val tor = TorRoute.on(prefs)
        // Built-in Tor or Orbot, and its words ("Starting Tor… 45%", "Connected through Tor"). Checked off the main thread.
        val torNow = if (tor && checkTor) TorRoute.status(app) else null
        val nearby = DirectNearby.current
        val power = app.getSystemService(PowerManager::class.java)
        return BackgroundStatus.Snapshot(
            now = now,
            connections = connections,
            nearbyOnly = NetworkGate.nearbyOnly(prefs),
            backgroundMinutes = SyncPolicy.backgroundMinutes(prefs),
            checkScheduled = SyncJob.PERIODIC_ID in pending,
            lastCheck = prefs.getLong(SyncJob.LAST_CHECK, 0),
            wifiOnly = SyncPolicy.wifiOnly(prefs),
            pauseOnSaver = prefs.getBoolean(SyncPolicy.BATTERY_SAVER, true),
            saverOn = power?.isPowerSaveMode == true,
            foregroundSeconds = SyncPolicy.foregroundSeconds(prefs),
            disappearingTimers = Disappearing.anyTimers(prefs),
            paired = CloudBackup.paired(app),
            syncTexts = prefs.getBoolean("syncSms", true),
            lastWebSync = prefs.getLong(SyncJob.LAST_WEB_SYNC, 0),
            webMatrixCalls = GatewayClient.hasAccount(prefs, MessageProtocol.MATRIX),
            backups = backups,
            nearbyOn = DirectProtocols.on(prefs, MessageProtocol.NEARBY) && MessageProtocol.NEARBY.enabled(prefs),
            discoverable = DirectProtocols.stayConnected(prefs, MessageProtocol.NEARBY),
            nearbyLive = nearby != null && MessageProtocol.NEARBY in live && nearby.searching(),
            helping = DirectNearby.helping(prefs),
            bluetooth = DirectNearby.bluetoothOn(prefs) && DirectNearby.bluetoothAllowed(app),
            torOn = tor,
            torWords = torNow?.words.orEmpty(),
            torEngine = if (torNow != null && !torNow.orbot) "built-in" else "Orbot",
            sipReady = Calling.sipReady(prefs),
            sipMode = SipAgent.mode(prefs),
            sipStatus = SipAgent.status(),
            callsHeldByTor = TorRoute.holdsCalls(prefs, Calling.Method.SIP),
            disappearingAlarm = Disappearing.alarmAt(app),
            outboxWaiting = Outbox.JOB in pending,
            syncSoonWaiting = syncSoon,
            updateChecks = prefs.getBoolean("automaticUpdateChecks", true) && !ReleaseUpdates.managedByStore(app)
                && !ReleaseUpdates.fdroidBuild(),
            otherJobs = pending.filter { it !in known && !(it == BackupJob.JOB && localOn) }.sorted())
    }

    @Composable
    private fun Page(open: (BackgroundStatus.Opens) -> Unit) {
        val context = LocalContext.current
        val scheme = MaterialTheme.colorScheme
        var rows by remember { mutableStateOf<List<BackgroundStatus.Row>?>(null) }
        // Live while the page is open: every few seconds, off the main thread (Tor's check waits on a port).
        LaunchedEffect(Unit) {
            while (true) {
                rows = withContext(Dispatchers.IO) {
                    runCatching { BackgroundStatus.rows(snapshot(context, true), ::clock) }.getOrDefault(rows ?: emptyList())
                }
                delay(REFRESH_MS)
            }
        }
        val list = rows
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (list == null) "Checking…" else BackgroundStatus.summary(list).let {
                        if (it == BackgroundStatus.NOTHING) it else "$it in the background" },
                        color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Text("What FluidMessages keeps going right now. Tap one to change it.", color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (list != null) for (group in BackgroundStatus.Group.values()) {
                val entries = list.filter { it.group == group }
                if (entries.isEmpty()) continue
                item(key = group.name) {
                    Column {
                        Text(group.title, Modifier.padding(start = 12.dp, bottom = 7.dp), color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleSmall)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(scheme.surfaceContainer)) {
                            entries.forEachIndexed { index, row ->
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = scheme.outlineVariant)
                                StatusRow(row, group == BackgroundStatus.Group.OFF) { open(row.opens) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusRow(row: BackgroundStatus.Row, off: Boolean, tap: () -> Unit) {
        val scheme = MaterialTheme.colorScheme
        val tint = if (off) scheme.onSurfaceVariant else Color(LocalControlTint.current)
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = "Open its setting", onClick = tap)
            .padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(36.dp).background(tint.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                Glyph(row.icon, tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.name, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(row.state, color = if (off) scheme.onSurfaceVariant else scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                Text(row.why, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (row.costly) Text(BackgroundStatus.COSTLY, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            Text("›", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }
    }

    @Composable
    private fun Glyph(icon: BackgroundStatus.Icon, color: Color) {
        if (icon == BackgroundStatus.Icon.CALL) { HandsetGlyph(color, Modifier.size(20.dp)); return }
        Canvas(Modifier.size(20.dp)) {
            val stroke = Stroke(1.8.dp.toPx())
            val w = size.width
            val h = size.height
            when (icon) {
                BackgroundStatus.Icon.CONNECTION -> {  // two linked rings
                    drawCircle(color, w * .22f, Offset(w * .34f, h * .5f), style = stroke)
                    drawCircle(color, w * .22f, Offset(w * .66f, h * .5f), style = stroke)
                }
                BackgroundStatus.Icon.SCHEDULE, BackgroundStatus.Icon.TIMER -> {  // a clock
                    drawCircle(color, w * .42f, center, style = stroke)
                    drawLine(color, center, Offset(w * .5f, h * .24f), stroke.width)
                    drawLine(color, center, Offset(w * (if (icon == BackgroundStatus.Icon.TIMER) .3f else .7f), h * .58f), stroke.width)
                }
                BackgroundStatus.Icon.WEB -> {  // a laptop
                    drawRoundRect(color, Offset(w * .16f, h * .2f), Size(w * .68f, h * .46f), CornerRadius(w * .05f), style = stroke)
                    drawLine(color, Offset(w * .04f, h * .78f), Offset(w * .96f, h * .78f), stroke.width)
                }
                BackgroundStatus.Icon.BACKUP -> {  // a box with an arrow up
                    drawRoundRect(color, Offset(w * .12f, h * .5f), Size(w * .76f, h * .4f), CornerRadius(w * .06f), style = stroke)
                    drawLine(color, Offset(w * .5f, h * .66f), Offset(w * .5f, h * .08f), stroke.width)
                    drawLine(color, Offset(w * .5f, h * .08f), Offset(w * .32f, h * .26f), stroke.width)
                    drawLine(color, Offset(w * .5f, h * .08f), Offset(w * .68f, h * .26f), stroke.width)
                }
                BackgroundStatus.Icon.NEARBY -> {  // radio waves around a dot
                    drawCircle(color, w * .08f, center)
                    for (ring in 1..2) {
                        val r = w * (.18f + ring * .14f)
                        drawArc(color, -45f, 90f, false, Offset(w * .5f - r, h * .5f - r), Size(r * 2, r * 2), style = stroke)
                        drawArc(color, 135f, 90f, false, Offset(w * .5f - r, h * .5f - r), Size(r * 2, r * 2), style = stroke)
                    }
                }
                BackgroundStatus.Icon.TOR -> {  // nested rings
                    for (ring in 1..3) drawCircle(color, w * ring * .14f, center, style = stroke)
                }
                BackgroundStatus.Icon.OUTBOX -> {  // a paper plane
                    drawLine(color, Offset(w * .08f, h * .5f), Offset(w * .92f, h * .14f), stroke.width)
                    drawLine(color, Offset(w * .92f, h * .14f), Offset(w * .6f, h * .9f), stroke.width)
                    drawLine(color, Offset(w * .6f, h * .9f), Offset(w * .44f, h * .58f), stroke.width)
                    drawLine(color, Offset(w * .44f, h * .58f), Offset(w * .08f, h * .5f), stroke.width)
                }
                else -> drawCircle(color, w * .4f, center, style = stroke)
            }
        }
    }
}
