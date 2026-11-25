package org.fluidmessages

import android.content.SharedPreferences

/**
 * Call and meeting-link buttons in IRC chats: hidden unless Settings → Accounts → IRC turns them on. Many IRC channels
 * ban call and meeting links, and moderators may kick or ban you for posting them. Other protocols keep their buttons.
 * FluidMessages Web has the same switch (prefs.ircCalls, companion.js ircCallsHidden).
 */
internal object IrcCalls {
    const val KEY = "ircCalls"
    const val TITLE = "Show call and meeting link buttons in IRC chats"
    const val WARNING = "Not recommended. Many IRC channels ban call and meeting links, and moderators may kick or ban you for posting them."

    @JvmStatic
    fun allowed(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, false)

    /** Whether a chat that goes only through IRC ([formats]) hides its Call and Video buttons. */
    @JvmStatic
    fun hides(formats: Collection<MessageProtocol>, allowed: Boolean): Boolean =
        !allowed && formats.isNotEmpty() && formats.all { it == MessageProtocol.IRC }

    /** The ways a meeting link may be sent: IRC is left out while the switch is off. */
    @JvmStatic
    fun meetingRoute(format: MessageProtocol, allowed: Boolean): Boolean = allowed || format != MessageProtocol.IRC

    /** [options] without Call and Video when the chat is IRC only and the switch is off. Message stays. */
    @JvmStatic
    fun filter(options: List<ReachOption>, formats: Collection<MessageProtocol>, allowed: Boolean): List<ReachOption> =
        if (hides(formats, allowed)) options.filter { it.kind == ReachOption.MESSAGE } else options
}
