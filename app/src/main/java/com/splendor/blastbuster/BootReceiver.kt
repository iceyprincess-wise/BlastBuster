package com.splendor.blastbuster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.splendor.blastbuster.companion.CompanionService

/**
 * Restarts the guard foreground service after a reboot, when the app is
 * armed — otherwise the phone would be unprotected until Blast Buster is
 * opened again.
 *
 * v1.2: also restarts the companion service when any companion module
 * (RAM, DND, Net, DNS, Bridge) is enabled. The companion start is
 * independent of the blast-armed flag (DESIGN.md §4.1: BootReceiver
 * restarts the companion "if any module enabled") — a user who runs RAM
 * watch with the blast disarmed still gets it back after a reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.isArmed(context)) {
            try {
                BlastService.startGuard(context)
            } catch (e: Exception) {
                // startForegroundService from BOOT_COMPLETED is allowed, but stay
                // silent on failure: the user re-arms by opening the app.
            }
        }
        try {
            CompanionService.startIfNeeded(context)
        } catch (e: Exception) {
            // Companion start is best-effort on boot; the user re-enables
            // it by opening the app. Never let it break the guard start.
        }
    }
}
