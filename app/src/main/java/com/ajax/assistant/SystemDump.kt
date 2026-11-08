package com.ajax.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.concurrent.TimeUnit

/**
 * Reads Android's own status reports ("dumpsys"), which name the apps behind each recording and
 * each running process. Needs the DUMP permission, granted once from a PC; ordinary apps never get
 * it. Without it every function here returns null and callers fall back to what Android allows.
 */
object SystemDump {
    fun allowed(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** The report of one system service, or null. Blocks for up to [timeoutMs]: not on the main thread for slow ones. */
    fun read(ctx: Context, service: String, vararg args: String, timeoutMs: Long = 3_000): String? {
        if (!allowed(ctx)) return null
        return try {
            val p = ProcessBuilder(listOf("dumpsys", service) + args).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try { p.inputStream.bufferedReader().use { r -> r.forEachLine { out.appendLine(it) } } } catch (e: Exception) {}
            }.apply { start() }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroy(); return null }
            reader.join(500)
            out.toString().takeUnless { it.contains("Permission Denial") }
        } catch (e: Exception) {
            null
        }
    }

    /** Packages recording from the microphone right now (the audio service names them). */
    fun recordingPackages(report: String): List<String> =
        Regex("""^\s+session:\d+ .*?-- pack:([\w.]+)""", RegexOption.MULTILINE)
            .findAll(report).map { it.groupValues[1] }.distinct().toList()

    /**
     * Apps running in the background, from the process list: those running a service, a foreground
     * service (music, navigation, recording) or a background job. Cached apps are only kept in memory
     * and aren't doing anything, so they are left out.
     */
    fun backgroundPackages(report: String): List<String> {
        val running = setOf("FGS", "BFGS", "IMPB", "IMPF", "SVC", "RCVR", "BKUP", "TRNB", "FGSL")
        return Regex("""^\s+#\d+:\s+\S+\s+(\S+)\s+\S+\s+\d+:([\w.]+)(?::[\w.]+)?/u\d+a\d+""", RegexOption.MULTILINE)
            .findAll(report)
            .filter { it.groupValues[1] in running }
            .map { it.groupValues[2] }
            .distinct()
            .toList()
    }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /** Apps the user can open from the home screen, as opposed to parts of Android itself. */
    fun isUserApp(ctx: Context, pkg: String): Boolean =
        ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0,
        ).isNotEmpty()

    fun names(ctx: Context, pkgs: List<String>): String {
        val labels = pkgs.map { label(ctx, it) }.distinct()
        return when (labels.size) {
            0 -> ""
            1 -> labels[0]
            else -> labels.dropLast(1).joinToString(", ") + " and " + labels.last()
        }
    }
}
