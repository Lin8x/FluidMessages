package org.fluidmessages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * How chat services and battery go together, in plain words, for the setup guide's Chat services step and
 * Settings → Chat services. A service switched on costs nothing by itself; only an account you sign in to connects.
 * FluidMessages Web says the same where services are turned on (server/settings.js SERVICE_BATTERY).
 */
internal object ServiceBattery {
    const val TITLE = "What about battery?"
    const val SWITCH = "Turning a service on uses no battery by itself. Only an account you sign in to connects."
    const val WHY = "To get new messages, a chat service needs a connection that stays open. On your phone, that keeps it awake."

    /** What FluidMessages Web does about it: already doing it ([paired]), or what it could do. */
    fun web(paired: Boolean) = if (paired)
        "FluidMessages Web is set up, so the computer it runs on holds those connections for you. Your phone only checks in now and then."
    else "FluidMessages Web can run on a computer that stays on and hold those connections for you. Then your phone only checks in now and then."

    fun lines(paired: Boolean) = listOf(SWITCH, WHY, web(paired))
}

/** The battery explanation as a card. */
@Composable
internal fun ServiceBatteryCard(paired: Boolean) {
    val scheme = MaterialTheme.colorScheme
    PreferencesCard(ServiceBattery.TITLE) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (line in ServiceBattery.lines(paired)) Text(line, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
