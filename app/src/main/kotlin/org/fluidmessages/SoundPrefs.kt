package org.fluidmessages

import android.media.AudioAttributes

/**
 * Settings → Sounds and notifications: how loud FluidMessages' own send and receive sounds are, and which volume
 * they follow. The notification channel's sound is Android's to play and isn't changed by these.
 *
 * The tone choices themselves keep their older keys ("sendTone", "receiveTone", and the on/off "sound" and
 * "receiveSound" before them), read by [MessageFeedback.selection].
 */
internal object SoundPrefs {
    /** 0–100. Backed up with sounds, haptics and animations. */
    const val VOLUME = "soundVolume"
    /** [MEDIA] or [NOTIFICATION]. */
    const val STREAM = "soundStream"
    const val MEDIA = "media"
    const val NOTIFICATION = "notification"
    const val DEFAULT_VOLUME = 100

    @JvmStatic
    fun volume(raw: Int): Int = raw.coerceIn(0, 100)

    /** The Ringtone gain for a 0–100 volume. */
    @JvmStatic
    fun gain(raw: Int): Float = volume(raw) / 100f

    /**
     * Media volume plays with music and videos (and still plays when notifications are silenced); notification
     * volume follows the ringer and Do Not Disturb, like the sounds of other messaging apps. Anything else is the
     * notification default, which is how in-app sounds played before this setting.
     */
    @JvmStatic
    fun usage(stream: String?): Int =
        if (stream == MEDIA) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_NOTIFICATION_EVENT

    @JvmStatic
    fun streamLabel(stream: String?): String = if (stream == MEDIA) "Media volume" else "Notification volume"
}

/**
 * The status line on Settings → Sounds and notifications. Pure, so the rules can be tested: notifications reach the
 * shade only when the permission is granted, the app's notifications are on, and the message channel isn't off.
 */
internal data class NotificationStatus(val permission: Boolean, val appEnabled: Boolean, val channelEnabled: Boolean) {
    val allowed: Boolean get() = permission && appEnabled && channelEnabled

    /** Asking again shows Android's prompt only while the permission is still missing; otherwise settings are the way. */
    val canAsk: Boolean get() = !permission

    val label: String get() = if (allowed) "Notifications: allowed" else "Notifications: blocked"

    val detail: String get() = when {
        allowed -> "New texts, chat messages and missed calls show in the notification shade."
        !permission -> "Android hasn't given FluidMessages permission to show notifications, so new messages arrive silently."
        !appEnabled -> "Notifications for FluidMessages are turned off in Android's settings."
        else -> "The Messages notification category is turned off in Android's settings."
    }
}
