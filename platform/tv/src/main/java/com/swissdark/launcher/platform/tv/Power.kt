package com.swissdark.launcher.platform.tv

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Power off (standby) from the D-pad. A normal app cannot press the power key, so setup.sh
 * registers this app as a device admin ("force lock" only) and [lockNow] sleeps the screen
 * exactly like the remote's power key — SCREEN_OFF, so Continue watching clears as usual.
 */
class PowerAdmin(context: Context) {
    private val admin = ComponentName(context, "com.swissdark.launcher.PowerAdmin")
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)

    /** True once setup.sh has activated the device admin. */
    fun canLock(): Boolean = try { dpm.isAdminActive(admin) } catch (_: Exception) { false }

    /** Standby now. False when the admin is not active yet. */
    fun lockNow(): Boolean {
        if (!canLock()) return false
        return try { dpm.lockNow(); true } catch (_: Exception) { false }
    }
}
