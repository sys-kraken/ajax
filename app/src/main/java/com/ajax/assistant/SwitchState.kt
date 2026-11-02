package com.ajax.assistant

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
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

    fun location(ctx: Context): Boolean? = safe {
        if (Build.VERSION.SDK_INT >= 28) ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled else null
    }

    fun batterySaver(ctx: Context): Boolean? = safe { ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode }

    fun nfc(ctx: Context): Boolean? = safe { NfcAdapter.getDefaultAdapter(ctx)?.isEnabled }

    private inline fun safe(f: () -> Boolean?): Boolean? = try { f() } catch (_: SecurityException) { null }
}
