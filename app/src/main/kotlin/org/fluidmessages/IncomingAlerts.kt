package org.fluidmessages

/**
 * Which messages synced from FluidMessages Web get a notification. Pure, so it can be tested; GatewayClient applies
 * it to messages this phone hasn't stored yet.
 */
internal object IncomingAlerts {
    /** Older messages arriving in one sync (the first sync after pairing, or after a long time offline) stay quiet. */
    const val RECENT_MS = 24L * 60 * 60 * 1000

    /** A received (type 1), unread, non-event message from the last day. */
    @JvmStatic
    fun shouldNotify(type: Int, unread: Boolean, event: Boolean, date: Long, now: Long): Boolean =
        type == 1 && unread && !event && date > now - RECENT_MS

    /** One notification per chat: its newest new message, newest chat first. */
    @JvmStatic
    fun <T> newestPerChat(items: List<T>, address: (T) -> String, date: (T) -> Long): List<T> =
        items.groupBy(address).values.map { chat -> chat.maxBy(date) }.sortedByDescending(date)

    @JvmStatic
    fun newestPerChat(items: List<org.json.JSONObject>): List<org.json.JSONObject> =
        newestPerChat(items, { it.optString("address") }, { it.optLong("date") })
}
