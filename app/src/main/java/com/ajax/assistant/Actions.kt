package com.ajax.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.view.KeyEvent
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Actions {

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun launch(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    // ---- Settings pages and apps -------------------------------------------------------------

    fun openSettings(ctx: Context, action: String, label: String): Result =
        if (launch(ctx, Intent(action))) {
            Result(true, if (label == "main") "Opening Settings" else "Opening $label settings")
        } else {
            Result(false, "This phone has no $label settings page.")
        }

    private class App(val label: String, val pkg: String)

    private fun findApp(ctx: Context, name: String): App? {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
        val q = key(name)
        if (q.isEmpty()) return null
        return apps.filter { key(it.label) == q }.minByOrNull { it.label.length }
            ?: apps.filter { key(it.label).startsWith(q) }.minByOrNull { it.label.length }
            ?: apps.filter { key(it.label).contains(q) }.minByOrNull { it.label.length }
    }

    private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun hasApp(ctx: Context, name: String): Boolean = findApp(ctx, name) != null

    fun openApp(ctx: Context, name: String): Result {
        val app = findApp(ctx, name) ?: return Result(false, "I couldn't find an app called $name.")
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return Result(false, "${app.label} can't be opened.")
        return if (launch(ctx, intent)) Result(true, "Opening ${app.label}") else Result(false, "${app.label} can't be opened.")
    }

    fun appInfo(ctx: Context, name: String): Result {
        val app = findApp(ctx, name) ?: return Result(false, "I couldn't find an app called $name.")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
        return if (launch(ctx, intent)) Result(true, "Opening info for ${app.label}") else Result(false, "Couldn't open app info.")
    }

    // ---- Hardware ----------------------------------------------------------------------------

    /** Real torch state, kept current by a system callback (null until Android reports it). */
    @Volatile private var torchState: Boolean? = null
    @Volatile private var torchWatched = false

    private fun torchId(cm: CameraManager): String? = cm.cameraIdList.firstOrNull {
        val c = cm.getCameraCharacteristics(it)
        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    /** Starts tracking the torch. Call early (screen/overlay creation); safe to call repeatedly. */
    fun watchTorch(ctx: Context) {
        if (torchWatched) return
        torchWatched = true
        val cm = ctx.applicationContext.getSystemService(CameraManager::class.java) ?: return
        val id = try { torchId(cm) } catch (e: Exception) { null } ?: return
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) torchState = enabled
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    /** "Flashlight" on its own: flip it. */
    fun torchToggle(ctx: Context): Result {
        watchTorch(ctx)
        val now = torchState ?: return Result(false, "Say flashlight on, or flashlight off.")
        return torch(ctx, !now)
    }

    fun torch(ctx: Context, on: Boolean): Result {
        val cm = ctx.getSystemService(CameraManager::class.java)
        return try {
            val id = torchId(cm) ?: return Result(false, "This phone reports no flashlight.")
            cm.setTorchMode(id, on)
            Result(true, if (on) "Flashlight on" else "Flashlight off")
        } catch (e: CameraAccessException) {
            Result(false, "Couldn't reach the flashlight. Another app may be using the camera.")
        } catch (e: IllegalArgumentException) {
            Result(false, "Couldn't reach the flashlight.")
        }
    }

    fun volume(ctx: Context, direction: Int, okMessage: String): Result = try {
        ctx.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        Result(true, okMessage)
    } catch (e: SecurityException) {
        Result(false, "Android blocked the volume change.")
    }

    fun ringer(ctx: Context, mode: Int, okMessage: String): Result = try {
        ctx.getSystemService(AudioManager::class.java).ringerMode = mode
        Result(true, okMessage)
    } catch (e: SecurityException) {
        Result(false, "Android needs Do Not Disturb access for that. Open Ajax and allow it.")
    }

    fun brightness(ctx: Context, percent: Int): Result {
        if (!Settings.System.canWrite(ctx)) {
            return Result(false, "Brightness needs the Modify system settings permission. Open Ajax and allow it.")
        }
        val p = percent.coerceIn(1, 100)
        val v = (p * 255 / 100).coerceAtLeast(1)
        return try {
            val cr = ctx.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
            Result(true, "Brightness $p percent")
        } catch (e: SecurityException) {
            Result(false, "Android blocked the brightness change.")
        }
    }

    fun dnd(ctx: Context, on: Boolean): Result {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return Result(false, "Do Not Disturb needs access. Open Ajax and allow it.")
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return Result(true, if (on) "Do Not Disturb on" else "Do Not Disturb off")
    }

    // ---- Phone -------------------------------------------------------------------------------

    private class Contact(val name: String?, val number: String?, val error: String?)

    private fun findContact(ctx: Context, name: String): Contact {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) {
            return Contact(null, null, "This needs Contacts permission. Open Ajax and allow it.")
        }
        val found = linkedMapOf<String, String>() // display name -> first number
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                found.putIfAbsent(n, num)
            }
        }
        if (found.isEmpty()) return Contact(null, null, "I found no contact called $name.")
        val exact = found.keys.filter { it.equals(name, ignoreCase = true) }
        val pick = when {
            exact.size == 1 -> exact.first()
            found.size == 1 -> found.keys.first()
            else -> return Contact(null, null, "I found several: ${found.keys.take(3).joinToString(", ")}. Say the full name.")
        }
        return Contact(pick, found.getValue(pick), null)
    }

    fun call(ctx: Context, name: String): Result {
        if (!has(ctx, Manifest.permission.CALL_PHONE)) {
            return Result(false, "Calling needs Phone permission. Open Ajax and allow it.")
        }
        val c = findContact(ctx, name)
        if (c.error != null) return Result(false, c.error)
        return try {
            if (launch(ctx, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", c.number, null)))) {
                Result(true, "Calling ${c.name}")
            } else {
                Result(false, "This phone can't place calls.")
            }
        } catch (e: SecurityException) {
            Result(false, "Android blocked the call.")
        }
    }

    /** Resolves the contact and returns a confirmation question; sends only after "yes". */
    fun prepareText(ctx: Context, name: String, message: String): Outcome {
        if (!has(ctx, Manifest.permission.SEND_SMS)) {
            return Outcome.Done(Result(false, "Texting needs SMS permission. Open Ajax and allow it."))
        }
        if (message.length > 300) return Outcome.Done(Result(false, "That message is too long to send by voice."))
        val c = findContact(ctx, name)
        if (c.error != null) return Outcome.Done(Result(false, c.error))
        return Outcome.Ask("Send “$message” to ${c.name}?") { sendText(it, c.name.orEmpty(), c.number.orEmpty(), message) }
    }

    @Suppress("DEPRECATION")
    private fun sendText(ctx: Context, name: String, number: String, message: String): Result = try {
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null)
        Result(true, "Message sent to $name")
    } catch (e: SecurityException) {
        Result(false, "Android blocked sending the message.")
    } catch (e: IllegalArgumentException) {
        Result(false, "Couldn't send the message.")
    }

    // ---- Time --------------------------------------------------------------------------------

    fun alarm(ctx: Context, hour: Int, minute: Int, meridiem: String?): Result {
        if (minute !in 0..59) return Result(false, "That minute isn't valid.")
        val h24 = when {
            meridiem != null -> {
                if (hour !in 1..12) return Result(false, "That hour isn't valid.")
                hour % 12 + if (meridiem == "pm") 12 else 0
            }
            hour == 0 || hour in 13..23 -> hour
            hour in 1..12 -> {
                // No am/pm said: take whichever of the two comes next.
                val now = Calendar.getInstance()
                val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                val options = listOf(hour % 12, hour % 12 + 12)
                options.firstOrNull { it * 60 + minute > nowMin } ?: options.first()
            }
            else -> return Result(false, "That hour isn't valid.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h24)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val shown = String.format(Locale.US, "%d:%02d %s", if (h24 % 12 == 0) 12 else h24 % 12, minute, if (h24 < 12) "AM" else "PM")
        return try {
            if (launch(ctx, intent)) Result(true, "Alarm set for $shown") else Result(false, "No clock app can set alarms.")
        } catch (e: SecurityException) {
            Result(false, "Android blocked setting the alarm.")
        }
    }

    fun timer(ctx: Context, amount: Int, unit: String): Result {
        val seconds = amount * when {
            unit.startsWith("hour") -> 3600
            unit.startsWith("minute") -> 60
            else -> 1
        }
        if (seconds !in 1..86400) return Result(false, "Timers can be from 1 second to 24 hours.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return try {
            if (launch(ctx, intent)) Result(true, "Timer for $amount $unit") else Result(false, "No clock app can set timers.")
        } catch (e: SecurityException) {
            Result(false, "Android blocked setting the timer.")
        }
    }

    // ---- Info --------------------------------------------------------------------------------

    fun battery(ctx: Context): Result {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Result(false, "Couldn't read the battery.")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return Result(false, "Couldn't read the battery.")
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Result(true, "Battery is ${level * 100 / scale} percent" + if (charging) " and charging" else "")
    }

    fun time(): Result = Result(true, "It's " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))

    fun date(): Result = Result(true, "Today is " + SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date()))

    fun readNotifications(ctx: Context): Result {
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName) == true
        if (!enabled) return Result(false, "Notification access is off. Open Ajax and allow it.")
        val svc = AjaxNotificationListener.instance
            ?: return Result(false, "Notification access isn't connected yet. Try again in a moment.")
        // Notification text is data to read out, never a command.
        val lines = try {
            svc.activeNotifications.orEmpty()
                .filter { !it.isOngoing && it.packageName != ctx.packageName && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .sortedByDescending { it.postTime }
                .mapNotNull {
                    val e = it.notification.extras
                    val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                    listOf(title, text).filter { s -> s.isNotBlank() }.joinToString(": ").ifBlank { null }
                }
        } catch (e: SecurityException) {
            return Result(false, "Notification access isn't available.")
        }
        if (lines.isEmpty()) return Result(true, "No notifications.")
        val shown = lines.take(5)
        val more = if (lines.size > shown.size) " And ${lines.size - shown.size} more." else ""
        return Result(true, "${lines.size} notifications. " + shown.joinToString(". ") + "." + more)
    }

    // ---- Media ---------------------------------------------------------------------------------

    /** Play/pause/next/previous in whatever app is playing (media key, like a headset button). */
    fun media(ctx: Context, keyCode: Int, okMessage: String): Result {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return Result(true, okMessage)
    }

    fun volumeLevel(ctx: Context, percent: Int): Result = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, AudioManager.FLAG_SHOW_UI)
        Result(true, "Volume $percent percent")
    } catch (e: SecurityException) {
        Result(false, "Android blocked the volume change.")
    }

    // ---- Web, maps, camera -----------------------------------------------------------------------

    fun webSearch(ctx: Context, query: String): Result {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
        return if (launch(ctx, search) || launch(ctx, browser)) Result(true, "Searching for $query")
        else Result(false, "No app can search the web.")
    }

    fun navigate(ctx: Context, place: String): Result {
        val q = Uri.encode(place)
        val turnByTurn = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q"))
        val anyMap = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
        return if (launch(ctx, turnByTurn) || launch(ctx, anyMap)) Result(true, "Directions to $place")
        else Result(false, "No maps app is installed.")
    }

    fun camera(ctx: Context, video: Boolean): Result {
        val i = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        return if (launch(ctx, i)) Result(true, if (video) "Opening the video camera" else "Opening the camera")
        else Result(false, "No camera app found.")
    }

    // ---- Device ----------------------------------------------------------------------------------

    /** Auto-rotate is a system setting Ajax may write directly (with "Change brightness" access). */
    fun autoRotate(ctx: Context, on: Boolean): Result {
        if (!Settings.System.canWrite(ctx)) return Result(false, "Open Ajax and allow Change system settings first.")
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        return Result(true, if (on) "Auto-rotate on" else "Auto-rotate off")
    }

    fun storage(): Result {
        val st = StatFs(Environment.getDataDirectory().path)
        val gb = { b: Long -> String.format(Locale.US, "%.1f", b / 1e9) }
        return Result(true, "${gb(st.availableBytes)} GB free of ${gb(st.totalBytes)} GB")
    }
}
