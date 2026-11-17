package com.splendor.blastbuster.dnd

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.CompanionModule
import com.splendor.blastbuster.companion.EventLog
import java.time.LocalDateTime

/** What the guard should do about the current DND state. Pure decision. */
enum class DndAction { NONE, APPLY_ON, APPLY_OFF }

/**
 * Pure drift decision (JVM-tested): given what the schedule wants and
 * whether DND is actually on, what (if anything) should the guard do?
 * A null [expected] means the schedule has no opinion at this time.
 */
fun decideDndAction(expected: DndMode?, actualIsDnd: Boolean): DndAction = when {
    expected == null -> DndAction.NONE
    expected == DndMode.ON && !actualIsDnd -> DndAction.APPLY_ON
    expected == DndMode.OFF && actualIsDnd -> DndAction.APPLY_OFF
    else -> DndAction.NONE
}

/**
 * Re-apply limiter (DESIGN.md §5.2), pure logic with an injectable clock
 * so it is JVM-testable: at most one re-apply per 5 minutes per
 * direction, and after 3 re-applies in an hour the guard stops
 * re-applying (a fighting app must not loop with the guard forever) and
 * the caller logs `DND_FIGHT_DETECTED`.
 */
class DndReapplyLimiter {
    private val applies = ArrayList<Pair<Long, DndAction>>()

    /** True when a re-apply in [direction] is allowed at [nowMs]. */
    fun allow(nowMs: Long, direction: DndAction): Boolean {
        if (direction == DndAction.NONE) return false
        val hourAgo = nowMs - 60L * 60L * 1000L
        applies.removeAll { it.first < hourAgo }
        if (applies.any { it.second == direction && nowMs - it.first < 5L * 60L * 1000L }) return false
        if (applies.size >= 3) return false
        return true
    }

    /** True when the hour window already holds 3 re-applies (a fight). */
    fun isFight(nowMs: Long): Boolean {
        val hourAgo = nowMs - 60L * 60L * 1000L
        applies.removeAll { it.first < hourAgo }
        return applies.size >= 3
    }

    fun record(nowMs: Long, direction: DndAction) {
        if (direction != DndAction.NONE) applies.add(Pair(nowMs, direction))
    }
}

/**
 * DND enforcement loop (DESIGN.md §5.2). Every 60 s, and on every
 * [DndListener] callback, it compares the schedule's expected mode with
 * `NotificationManager.currentInterruptionFilter` and re-applies on
 * drift — unless a blast is active (then it logs
 * `DND_SKIPPED_BLAST_ACTIVE` and does nothing: during a blast v2 forces
 * DND off so the siren cannot be silenced, and this guard must never
 * fight that), unless DND access was never granted (then it is inert),
 * or unless the re-apply limiter says stop.
 *
 * Re-apply uses `INTERRUPTION_FILTER_ALARMS` for ON — the v2 "alarms
 * only" choice, kept consistent — and `INTERRUPTION_FILTER_ALL` for OFF.
 */
class DndGuard : CompanionModule {

    companion object {
        const val CHECK_INTERVAL_MS = 60L * 1000L
        const val FILTER_ON = NotificationManager.INTERRUPTION_FILTER_ALARMS
        const val FILTER_OFF = NotificationManager.INTERRUPTION_FILTER_ALL

        // Shared across instances: the service guard and the DND screen's
        // one-shot checks must share one cooldown / fight state, or the
        // two could re-apply past each other's limits.
        private val sharedLimiter = DndReapplyLimiter()

        @Volatile
        private var sharedLastExpected: DndMode? = null

        @Volatile
        private var sharedLastActualIsDnd: Boolean = false

        @Volatile
        private var sharedFightDetected: Boolean = false
    }

    @Volatile
    private var running = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var appCtx: Context? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                checkNow()
            } catch (_: Exception) {
            }
            handler?.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun start(ctx: Context) {
        if (running) return
        if (!Prefs.dndEnabled(ctx)) return
        appCtx = ctx.applicationContext
        thread = HandlerThread("BlastBuster-DndGuard").also { it.start() }
        handler = Handler(thread!!.looper)
        running = true
        handler!!.post(tick)
    }

    override fun stop() {
        running = false
        handler?.removeCallbacks(tick)
        handler = null
        try {
            thread?.quitSafely()
        } catch (_: Exception) {
        }
        thread = null
    }

    override fun isRunning(): Boolean = running

    override fun statusJson(): String {
        val expected = sharedLastExpected?.name?.lowercase() ?: "none"
        return "{\"enabled\":${Prefs.dndEnabled(appCtx ?: return "{\"enabled\":false}")}," +
            "\"expected\":\"$expected\",\"dnd_on\":$sharedLastActualIsDnd," +
            "\"fight_detected\":$sharedFightDetected}"
    }

    /** One enforcement check, on the guard thread (or a caller thread). */
    fun checkNow(culprit: DndCulprit.CulpritResult? = null) {
        val ctx = appCtx ?: return
        if (!Prefs.dndEnabled(ctx) || !Prefs.dndReapplyEnabled(ctx)) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            // Inert without DND access; the UI shows the grant card.
            return
        }
        val expected = DndSchedule.modeAt(
            DndSchedule.decode(Prefs.dndScheduleJson(ctx)), LocalDateTime.now()
        )
        sharedLastExpected = expected
        val actualIsDnd = nm.currentInterruptionFilter != FILTER_OFF
        sharedLastActualIsDnd = actualIsDnd
        val action = decideDndAction(expected, actualIsDnd)
        if (action == DndAction.NONE) return
        val gate = BlastGate.forContext(ctx)
        if (!gate.checkWrite("dnd re-apply $action") { reason ->
                EventLog.append(ctx, "dnd", "DND_SKIPPED_BLAST_ACTIVE", reason)
            }) return
        val now = System.currentTimeMillis()
        if (sharedLimiter.isFight(now)) {
            sharedFightDetected = true
            EventLog.append(
                ctx, "dnd", "DND_FIGHT_DETECTED",
                "DND re-applied 3 times in an hour — guard stopped re-applying; " +
                    "suspected app decides next, see history."
            )
            return
        }
        if (!sharedLimiter.allow(now, action)) return
        // Short wake lock only while applying, then released (§4.1).
        var wl: PowerManager.WakeLock? = null
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlastBuster:dnd")
            wl.acquire(10L * 1000L)
            nm.setInterruptionFilter(if (action == DndAction.APPLY_ON) FILTER_ON else FILTER_OFF)
            sharedLimiter.record(now, action)
            val culpritText = if (culprit != null && culprit.packageName != null) {
                " Culprit: ${culprit.packageName} (${culprit.label})."
            } else ""
            EventLog.append(
                ctx, "dnd", "DND_REAPPLIED",
                "DND re-applied to ${if (action == DndAction.APPLY_ON) "ON (alarms only)" else "OFF"} " +
                    "per schedule.$culpritText",
                mapOf("action" to action.name)
            )
        } catch (e: Exception) {
            EventLog.append(ctx, "dnd", "DND_REAPPLY_FAILED", "DND re-apply failed: ${e.message}")
        } finally {
            try {
                if (wl?.isHeld == true) wl.release()
            } catch (_: Exception) {
            }
        }
    }

    /** One-off check without starting the 60 s timer (DND screen). */
    fun checkOnce(ctx: Context) {
        val prev = appCtx
        appCtx = ctx.applicationContext
        try {
            checkNow()
        } catch (_: Exception) {
        } finally {
            appCtx = prev
        }
    }

    /**
     * Direct apply for the bridge `dnd.apply` command (MERGED): sets DND
     * on (alarms only) or off right now, outside the schedule. Blast-
     * gated via [BlastGate] and inert without DND access, like the
     * schedule path. Returns true when the filter was set.
     */
    fun applyNow(ctx: Context, on: Boolean): Boolean {
        val appCtx = ctx.applicationContext
        val gate = BlastGate.forContext(appCtx)
        if (!gate.checkWrite("dnd.apply ${if (on) "on" else "off"}") { reason ->
                EventLog.append(appCtx, "dnd", "DND_SKIPPED_BLAST_ACTIVE", reason)
            }) return false
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            EventLog.append(
                appCtx, "dnd", "DND_APPLY_NO_ACCESS",
                "dnd.apply refused — DND access not granted."
            )
            return false
        }
        return try {
            nm.setInterruptionFilter(if (on) FILTER_ON else FILTER_OFF)
            sharedLastActualIsDnd = on
            EventLog.append(
                appCtx, "dnd", "DND_APPLIED",
                "DND set to ${if (on) "ON (alarms only)" else "OFF"} by a Muse bridge command."
            )
            true
        } catch (e: Exception) {
            EventLog.append(appCtx, "dnd", "DND_APPLY_FAILED", "dnd.apply failed: ${e.message}")
            false
        }
    }

    /**
     * Called when [DndListener] observes an external DND change: logs the
     * change with its culprit label, then re-evaluates the schedule.
     * The listener's own foreground capture is used as the culprit hint.
     */
    fun onExternalChange(filter: Int, culprit: DndCulprit.CulpritResult?) {
        val ctx = appCtx ?: return
        val state = if (filter == FILTER_OFF) "OFF" else "ON"
        val culpritText = if (culprit != null && culprit.packageName != null) {
            " Culprit: ${culprit.packageName} (${culprit.label})."
        } else if (culprit != null) {
            " Culprit: ${culprit.label}."
        } else ""
        EventLog.append(
            ctx, "dnd", "DND_CHANGED_EXTERNAL",
            "DND changed outside the schedule to $state.$culpritText"
        )
        checkNow(culprit)
    }

    /** Foreground package right now, via UsageStats (null when unavailable). */
    fun foregroundPackage(ctx: Context): String? {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 60L * 1000L, now
            ) ?: return null
            stats.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) {
            null
        }
    }
}
