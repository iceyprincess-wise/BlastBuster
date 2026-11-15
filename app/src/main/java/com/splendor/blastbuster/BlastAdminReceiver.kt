package com.splendor.blastbuster

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device-admin component. It does nothing on its own: it exists so the
 * user can enable Blast Buster as a device administrator, which lets the
 * service call DevicePolicyManager.lockNow() when the siren starts — the
 * phone locks to the system lock screen under the red lockout overlay.
 * Enabling it is a setup step in the app; nothing here assumes it is
 * granted.
 */
class BlastAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("BlastAdminReceiver", "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("BlastAdminReceiver", "device admin disabled")
    }
}
