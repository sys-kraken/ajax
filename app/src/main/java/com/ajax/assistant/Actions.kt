package com.ajax.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.ContactsContract

object Actions {

    fun openSettings(ctx: Context, action: String, label: String): Result = try {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Result(true, if (label == "main") "Opening Settings" else "Opening $label settings")
    } catch (e: ActivityNotFoundException) {
        Result(false, "This phone has no $label settings page.")
    }

    fun openApp(ctx: Context, name: String): Result {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { Triple(it.loadLabel(pm).toString(), key(it.loadLabel(pm).toString()), it.activityInfo.packageName) }
            .filter { it.third != ctx.packageName }
        val q = key(name)
        if (q.isEmpty()) return Result(false, "Which app?")
        val best = apps.filter { it.second == q }.minByOrNull { it.first.length }
            ?: apps.filter { it.second.startsWith(q) }.minByOrNull { it.first.length }
            ?: apps.filter { it.second.contains(q) }.minByOrNull { it.first.length }
            ?: return Result(false, "I couldn't find an app called $name.")
        val intent = pm.getLaunchIntentForPackage(best.third)
            ?: return Result(false, "${best.first} can't be opened.")
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result(true, "Opening ${best.first}")
        } catch (e: ActivityNotFoundException) {
            Result(false, "${best.first} can't be opened.")
        }
    }

    private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun torch(ctx: Context, on: Boolean): Result {
        val cm = ctx.getSystemService(CameraManager::class.java)
        return try {
            val id = cm.cameraIdList.firstOrNull {
                val c = cm.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return Result(false, "This phone reports no flashlight.")
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

    fun call(ctx: Context, name: String): Result {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (!has(Manifest.permission.READ_CONTACTS) || !has(Manifest.permission.CALL_PHONE)) {
            return Result(false, "Calling needs Contacts and Phone permission. Open Ajax and allow them.")
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
        if (found.isEmpty()) return Result(false, "I found no contact called $name.")
        val exact = found.keys.filter { it.equals(name, ignoreCase = true) }
        val pick = when {
            exact.size == 1 -> exact.first()
            found.size == 1 -> found.keys.first()
            else -> return Result(false, "I found several: ${found.keys.take(3).joinToString(", ")}. Say the full name.")
        }
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_CALL, Uri.fromParts("tel", found.getValue(pick), null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Result(true, "Calling $pick")
        } catch (e: SecurityException) {
            Result(false, "Android blocked the call.")
        } catch (e: ActivityNotFoundException) {
            Result(false, "This phone can't place calls.")
        }
    }
}
