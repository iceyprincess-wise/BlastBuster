package com.splendor.blastbuster.dnd

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * DND change observation (DESIGN.md §5.2, permission P10). A
 * `NotificationListenerService`: the system calls
 * [onInterruptionFilterChanged] whenever the interruption filter changes,
 * whoever changed it. The listener records the change, captures the
 * foreground package at that moment (listener tier — an inference, not
 * proof), tries the logcat tier on a background thread when the
 * `READ_LOGS` grant is held, and hands the result to [DndGuard] via a
 * package-scoped broadcast that `CompanionService` receives.
 *
 * Without notification access this service is never bound by the system
 * and the guard falls back to its 60 s poll (culprit = polling tier).
 */
class DndListener : NotificationListenerService() {

    companion object {
        /** Broadcast sent to CompanionService on an external DND change. */
        const val ACTION_DND_CHANGED = "com.splendor.blastbuster.DND_CHANGED"
        const val EXTRA_FILTER = "filter"
        const val EXTRA_CULPRIT_PKG = "culprit_pkg"
        const val EXTRA_CULPRIT_TIER = "culprit_tier"
        const val EXTRA_CULPRIT_LABEL = "culprit_label"

        /** Max re-bind attempts after a disconnect, then stop trying. */
        const val MAX_REBIND_ATTEMPTS = 3
        const val REBIND_DELAY_MS = 30L * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rebindAttempts = 0

    override fun onListenerConnected() {
        rebindAttempts = 0
    }

    override fun onListenerDisconnected() {
        // System-managed re-bind with a back-off; after 3 attempts stop
        // trying until the next app launch (DESIGN.md §4.1).
        if (rebindAttempts >= MAX_REBIND_ATTEMPTS) return
        rebindAttempts++
        handler.postDelayed({
            try {
                requestRebind(ComponentName(this, DndListener::class.java))
            } catch (_: Exception) {
            }
        }, REBIND_DELAY_MS)
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        // The logcat attempt runs off the main thread; the broadcast and
        // the guard's re-check must not wait for a subprocess.
        Thread({
            try {
                val foregroundPkg = foregroundPackageNow()
                val logcatPkg = if (DndCulprit.hasReadLogsGrant(this)) {
                    DndCulprit.attemptLogcat()
                } else {
                    null
                }
                val culprit = DndCulprit.resolve(
                    logcatPkg, foregroundPkg, DndCulprit.Tier.LISTENER
                )
                val intent = Intent(ACTION_DND_CHANGED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_FILTER, interruptionFilter)
                    putExtra(EXTRA_CULPRIT_PKG, culprit.packageName)
                    putExtra(EXTRA_CULPRIT_TIER, culprit.tier.name)
                    putExtra(EXTRA_CULPRIT_LABEL, culprit.label)
                }
                sendBroadcast(intent)
            } catch (_: Exception) {
            }
        }, "BlastBuster-DndListener").start()
    }

    private fun foregroundPackageNow(): String? {
        return try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                now - 60L * 1000L, now
            ) ?: return null
            stats.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) {
            null
        }
    }
}
