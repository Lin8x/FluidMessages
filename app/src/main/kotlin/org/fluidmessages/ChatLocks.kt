package org.fluidmessages

/**
 * When an unlocked chat locks again. A locked chat stays open only while you're in it: the chat itself, its
 * details, its appearance page, and what you open from there (media, the photo picker, or a call, which don't
 * change the page). Going back to the inbox, any other page, or a different chat locks it again.
 */
object ChatLocks {
    /** Pages that belong to the chat you're in. */
    @JvmField
    val CHAT_PAGES: Set<String> = setOf("chat", "contact", "chatAppearance", "decoy", "contactEditor")

    /**
     * Which opened locks stay open when the app moves to [page]. [owner] is the chat you're in (null when none),
     * and [chat] is the chat [page] opens, or null when the page belongs to the chat you're already in.
     */
    @JvmStatic
    fun stillOpen(opened: Set<String>, owner: String?, page: String, chat: String?): Set<String> = when {
        page !in CHAT_PAGES || owner == null -> emptySet()
        chat != null && chat != owner -> emptySet()
        else -> opened
    }

    /**
     * Whether the activity stopping counts as leaving the app, which locks every chat again. The screen turning off
     * always does. Pressing Home or switching apps does, except while a fingerprint, face, or PIN prompt is up
     * (Android may show its PIN screen as its own activity, which stops this one) or when FluidMessages itself
     * opened the next screen (the photo picker, camera, or a call).
     */
    @JvmStatic
    fun leftApp(screenOff: Boolean, userLeaving: Boolean, openedInside: Boolean, prompting: Boolean): Boolean =
        screenOff || userLeaving && !openedInside && !prompting
}
