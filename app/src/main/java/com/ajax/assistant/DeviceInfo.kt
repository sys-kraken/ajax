package com.ajax.assistant

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import java.util.Locale

/** What the phone can tell about itself: hardware, software, battery, memory, network. */
object DeviceInfo {
    private fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1e9)

    fun device(ctx: Context): Result {
        val name = Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
        val model = if (name != null && !name.contains(Build.MODEL)) "$name (${Build.MODEL})" else name ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        val patch = if (Build.VERSION.SDK_INT >= 23) ", security patch ${Build.VERSION.SECURITY_PATCH}" else ""
        return Result(true, "$model, Android ${Build.VERSION.RELEASE}$patch.")
    }

    fun memory(ctx: Context): Result {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return Result(false, "Couldn't read the memory.")
        val m = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val low = if (m.lowMemory) " Memory is low, so Android is closing apps." else ""
        return Result(true, "Memory: ${gb(m.availMem)} free of ${gb(m.totalMem)}.$low")
    }

    fun storage(): Result {
        val st = StatFs(Environment.getDataDirectory().path)
        return Result(true, "Storage: ${gb(st.availableBytes)} free of ${gb(st.totalBytes)}.")
    }

    fun uptime(): Result {
        val minutes = SystemClock.elapsedRealtime() / 60_000
        val days = minutes / 1440
        val hours = minutes % 1440 / 60
        val mins = minutes % 60
        val parts = listOfNotNull(
            "$days day${if (days == 1L) "" else "s"}".takeIf { days > 0 },
            "$hours hour${if (hours == 1L) "" else "s"}".takeIf { hours > 0 },
            "$mins minute${if (mins == 1L) "" else "s"}".takeIf { days == 0L },
        )
        return Result(true, "On for ${parts.joinToString(" ")} since the last restart.")
    }

    private fun batteryIntent(ctx: Context) = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    /** Battery level, charging, temperature, health and charge cycles. */
    fun batteryDetails(ctx: Context): String? {
        val i = batteryIntent(ctx) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ", charging"
            BatteryManager.BATTERY_STATUS_FULL -> ", full"
            else -> ""
        }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.let { ", ${it / 10} degrees" }.orEmpty()
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> ", good health"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> ", overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> ", worn out"
            BatteryManager.BATTERY_HEALTH_COLD -> ", too cold"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> ", over voltage"
            else -> ""
        }
        val cycles = if (Build.VERSION.SDK_INT >= 34) {
            i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }?.let { ", $it charge cycles" }.orEmpty()
        } else ""
        return "Battery ${level * 100 / scale} percent$charging$temp$health$cycles."
    }

    fun temperature(ctx: Context): Result {
        val battery = batteryIntent(ctx)?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10 }
        val state = thermal(ctx)
        if (battery == null && state == null) return Result(false, "This phone doesn't report its temperature.")
        val b = battery?.let { "The battery is $it degrees" } ?: "The phone"
        return Result(true, "$b and the phone is ${state ?: "fine"}.")
    }

    private fun thermal(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return when (ctx.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "cool"
            PowerManager.THERMAL_STATUS_LIGHT -> "a little warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "warm, and slowing down a little"
            PowerManager.THERMAL_STATUS_SEVERE -> "hot, and slowing down"
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN ->
                "very hot. Let it cool down"
            else -> null
        }
    }

    fun network(ctx: Context): Result {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Result(false, "Couldn't read the network.")
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return Result(true, "Not connected to the internet.")
        val via = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "a cable"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "a network"
        }
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val vpn = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) ", through a VPN" else ""
        val speed = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { ", about ${it / 1000} megabits down" }.orEmpty()
        return Result(true, if (online) "Online on $via$vpn$speed." else "Connected to $via$vpn, but there's no internet.")
    }

    /** Everything at once. The running apps and microphone lines need the PC-granted permission. */
    fun report(ctx: Context): Result {
        val lines = mutableListOf<String>()
        lines += device(ctx).message
        batteryDetails(ctx)?.let { lines += it }
        thermal(ctx)?.let { lines += "The phone is $it." }
        lines += memory(ctx).message
        lines += storage().message
        lines += network(ctx).message
        lines += uptime().message
        if (SystemDump.allowed(ctx)) {
            lines += Actions.runningApps(ctx).message
            lines += Actions.micUsers(ctx).message
        }
        return Result(true, lines.joinToString("\n"))
    }
}
