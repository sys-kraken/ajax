package com.ajax.assistant

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings

/** Real on/off state of a radio, read from the system. Null when Android won't say. */
object SwitchState {
    fun wifi(ctx: Context): Boolean? = safe {
        ctx.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
    }

    fun bluetooth(ctx: Context): Boolean? = safe {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
    }

    fun airplane(ctx: Context): Boolean? = safe {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }

    private inline fun safe(f: () -> Boolean?): Boolean? = try { f() } catch (_: SecurityException) { null }
}
