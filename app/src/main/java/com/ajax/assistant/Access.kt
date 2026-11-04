package com.ajax.assistant

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.widget.Toast

/**
 * Runs Ajax's accessibility service only while a command needs it, so it is really off the rest of
 * the time and apps that refuse to run beside one (GCash) open normally. Needs WRITE_SECURE_SETTINGS,
 * granted once from a PC. Without it the service stays under the user's control in Settings.
 * Main thread only.
 */
object Access {
    private val protectedApps = mapOf("com.globe.gcash.android" to "GCash")

    private const val IDLE_MS = 2_500L
    private const val MAX_HOLD_MS = 60_000L
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val POLL_MS = 100L
    private const val SETTLE_MS = 200L

    private val main = Handler(Looper.getMainLooper())
    private val off = Runnable { turnOff() }
    private var appCtx: Context? = null

    /** A command is running: the off timer is only the safety net. */
    private var held = false

    fun onDemand(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun canSeeForeground(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    /** Why a command that uses the service can't run now, or null when it can. */
    fun refusal(ctx: Context): Result? {
        protectedInFront(ctx)?.let { return Result(false, "I can't do that while $it is open.") }
        if (AjaxAccessibilityService.instance == null && !onDemand(ctx)) {
            return Result(false, "This needs Accessibility. Open Ajax and turn it on.")
        }
        return null
    }

    /** Starts the service if needed, runs [work], reports its result, then lets the service stop. */
    fun run(ctx: Context, work: () -> Result, then: () -> Unit = {}) {
        val app = ctx.applicationContext
        appCtx = app
        val managed = onDemand(app)
        if (managed) {
            held = true
            main.removeCallbacks(off)
            main.postDelayed(off, MAX_HOLD_MS)
            write(app, on = true)
        }
        val finish = {
            if (managed) release()
            then()
        }
        val alreadyRunning = AjaxAccessibilityService.instance != null
        whenConnected(0L) { connected ->
            if (!connected) {
                Toast.makeText(app, "Accessibility didn't start. Try again.", Toast.LENGTH_SHORT).show()
                finish()
            } else if (alreadyRunning) {
                AjaxAccessibilityService.announce(work(), finish)
            } else {
                main.postDelayed({ AjaxAccessibilityService.announce(work(), finish) }, SETTLE_MS)
            }
        }
    }

    /** The service is running with no command behind it (restart, update, turned on by hand): stop it soon. */
    fun settle(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (held || !onDemand(app) || !ServiceList.has(enabled(app), SERVICE_PKG, SERVICE_CLASS)) return
        main.removeCallbacks(off)
        main.postDelayed(off, IDLE_MS)
    }

    /** Ajax is about to open [pkg]: if that app refuses accessibility services, stop now instead of soon. */
    fun beforeOpening(ctx: Context, pkg: String) {
        if (pkg in protectedApps && onDemand(ctx)) {
            appCtx = ctx.applicationContext
            main.removeCallbacks(off)
            turnOff()
        }
    }

    private fun release() {
        held = false
        main.removeCallbacks(off)
        main.postDelayed(off, IDLE_MS)
    }

    private fun turnOff() {
        held = false
        appCtx?.let { write(it, on = false) }
    }

    private fun whenConnected(waited: Long, go: (Boolean) -> Unit) {
        when {
            AjaxAccessibilityService.instance != null -> go(true)
            waited >= CONNECT_TIMEOUT_MS -> go(false)
            else -> main.postDelayed({ whenConnected(waited + POLL_MS, go) }, POLL_MS)
        }
    }

    private fun enabled(ctx: Context): String? =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    /** Only Ajax's own entry changes: other accessibility services the user has on stay as they are. */
    private fun write(ctx: Context, on: Boolean) {
        val r = ctx.contentResolver
        val now = enabled(ctx)
        val next = if (on) ServiceList.with(now, SERVICE_PKG, SERVICE_CLASS) else ServiceList.without(now, SERVICE_PKG, SERVICE_CLASS)
        if (next != now.orEmpty()) Settings.Secure.putString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        if (on) Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        else if (next.isEmpty()) Settings.Secure.putInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }

    /** The label of the protected app in front, if Ajax is allowed to know what is in front. */
    private fun protectedInFront(ctx: Context): String? {
        if (protectedApps.isEmpty() || !canSeeForeground(ctx)) return null
        val stats = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val events = stats.queryEvents(end - 3_600_000L, end)
        val event = UsageEvents.Event()
        var front: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) front = event.packageName
        }
        return protectedApps[front]
    }

    private const val SERVICE_PKG = "com.ajax.assistant"
    private const val SERVICE_CLASS = "com.ajax.assistant.AjaxAccessibilityService"
}

/** The system's colon-separated list of enabled services, which may hold short or full component names. */
object ServiceList {
    private fun isOurs(entry: String, pkg: String, cls: String) =
        entry.equals("$pkg/$cls", ignoreCase = true) ||
            entry.equals("$pkg/${cls.removePrefix(pkg)}", ignoreCase = true)

    private fun entries(list: String?) = list.orEmpty().split(':').filter { it.isNotBlank() }

    fun has(list: String?, pkg: String, cls: String) = entries(list).any { isOurs(it, pkg, cls) }

    fun with(list: String?, pkg: String, cls: String): String =
        if (has(list, pkg, cls)) list.orEmpty() else (entries(list) + "$pkg/$cls").joinToString(":")

    fun without(list: String?, pkg: String, cls: String): String =
        entries(list).filterNot { isOurs(it, pkg, cls) }.joinToString(":")
}
