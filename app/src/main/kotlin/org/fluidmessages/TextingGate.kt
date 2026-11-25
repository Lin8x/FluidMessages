package org.fluidmessages

import android.app.role.RoleManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Texting (SMS, MMS, and the RCS preview) needs FluidMessages to be Android's default messages app: Android lets
 * only one app send and receive texts. "Texting on" is the three text switches in Settings → Chat services
 * (protocolSMS, protocolMMS, protocolRCS); any one of them on counts as on.
 *
 * While texting is on and FluidMessages isn't the default app, the app shows [TextingGateScreen] and nothing else:
 * no inbox and no chats. It goes away once FluidMessages is the default app or texting is turned off here. With
 * texting off, texts are neither shown nor sent, and the other chat services and FluidMessages Web keep working.
 */
internal object TextingGate {
    const val TITLE = "Make FluidMessages your texting app"
    const val WHY = "Android lets only one app send and receive your texts. Until you choose FluidMessages, " +
        "your texts stay in your old app."
    const val ALLOW = "Make FluidMessages my texting app"
    const val TURN_OFF = "Turn off texting in FluidMessages"
    const val OFF_MEANS = "With texting off, FluidMessages shows and sends no texts. Your other chat services and " +
        "FluidMessages Web keep working. Your texts stay safe in your old app."

    private val texts = MessageProtocol.values().filter { it.isText() }

    /** The settings that make up "texting": protocolRCS, protocolSMS, and protocolMMS. */
    @JvmStatic val keys: List<String> = texts.map { it.key() }

    @JvmStatic fun textingOn(prefs: SharedPreferences) = texts.any { it.enabled(prefs) }

    @JvmStatic fun roleHeld(context: Context) =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

    /** The full-page ask shows, and no messages do: texting is on, but another app is the default messages app. */
    @JvmStatic fun mustAsk(textingOn: Boolean, roleHeld: Boolean) = textingOn && !roleHeld

    /** Texts are read from the phone and can be sent only with texting on and the default role held. */
    @JvmStatic fun showsTexts(textingOn: Boolean, roleHeld: Boolean) = textingOn && roleHeld

    @JvmStatic fun showsTexts(context: Context, prefs: SharedPreferences) = showsTexts(textingOn(prefs), roleHeld(context))

    /** A text switch may turn on only while FluidMessages is the default app. Turning one off always works. */
    @JvmStatic fun maySwitch(wanted: Boolean, roleHeld: Boolean) = !wanted || roleHeld

    /** Whether a stored message of this protocol ("sms", "mms", "rcs", "matrix", …; none means a text) stays out of sight. */
    @JvmStatic fun hidesRow(protocol: String?, showsTexts: Boolean) = !showsTexts && MessageProtocol.parse(protocol).isText()

    /** The only page that stays open while the ask is due: the setup guide, which has the same ask as one of its steps. */
    @JvmStatic fun staysOpen(page: String) = page == "setup"

    @JvmStatic fun turnOff(prefs: SharedPreferences) = set(prefs, false)
    @JvmStatic fun turnOn(prefs: SharedPreferences) = set(prefs, true)

    private fun set(prefs: SharedPreferences, on: Boolean) {
        val edit = prefs.edit()
        for (key in keys) edit.putBoolean(key, on)
        edit.apply()
    }
}

/**
 * The full page that asks for the default messages app. As the gate ([gate] true) its second button turns texting
 * off; opened from a switch that tried to turn texting on, the second button goes back and leaves texting off.
 */
internal object TextingGateScreen {
    @JvmStatic
    fun mount(root: LinearLayout, prefs: SharedPreferences, gate: Boolean, allow: Runnable, second: Runnable, sample: Runnable?) {
        val view = ComposeView(root.context)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent {
            AppTheme(Appearance.read(prefs)) {
                val scheme = MaterialTheme.colorScheme
                Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 22.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(TextingGate.TITLE, fontSize = 32.sp, lineHeight = 36.sp, color = scheme.onSurface,
                            fontWeight = FontWeight.Bold)
                        Text(TextingGate.WHY, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                        Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (gate) "Don’t want to switch?" else "Texting is off right now", color = scheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge)
                            Text(TextingGate.OFF_MEANS, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (gate) Text("FluidMessages asks again each time you open it, until you pick one.",
                            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    AppButton(onClick = allow::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) { Text(TextingGate.ALLOW) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = second::run, modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) {
                        Text(if (gate) TextingGate.TURN_OFF else "Keep texting off")
                    }
                    if (sample != null) TextButton(onClick = sample::run, modifier = Modifier.fillMaxWidth()) {
                        Text("Try the sample inbox (pretend chats)")
                    }
                }
            }
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}
