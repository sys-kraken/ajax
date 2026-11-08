package com.ajax.assistant

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Which app is on screen, from Android's usage history (needs usage access, granted from a PC).
 * Reads only the events since the last call, so asking often stays cheap. Main thread only.
 */
object Foreground {
    private var front: String? = null
    private var since = 0L

    fun app(ctx: Context): String? {
        if (!Access.canSeeForeground(ctx)) return null
        val stats = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        // Overlap the previous read: Android sometimes records an event a moment late.
        val start = if (since == 0L) end - 3_600_000L else since - 10_000L
        val events = stats.queryEvents(start, end)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) front = e.packageName
        }
        since = end
        return front
    }
}
