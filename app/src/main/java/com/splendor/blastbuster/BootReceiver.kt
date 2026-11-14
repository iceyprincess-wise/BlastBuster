package com.splendor.blastbuster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the guard foreground service after a reboot, when the app is
 * armed — otherwise the phone would be unprotected until Blast Buster is
 * opened again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isArmed(context)) return
        try {
            BlastService.startGuard(context)
        } catch (e: Exception) {
            // startForegroundService from BOOT_COMPLETED is allowed, but stay
            // silent on failure: the user re-arms by opening the app.
        }
    }
}
