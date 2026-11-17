package com.splendor.blastbuster.ram

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.CompanionModule
import com.splendor.blastbuster.companion.EventLog

/**
 * RAM watch scanner (DESIGN.md §5.1). Every 60 s (and on demand from the
 * RAM screen or `ram.snapshot`): reads `ActivityManager.getMemoryInfo()`
 * (total/avail/threshold/low-memory), `getRunningAppProcesses()` +
 * `getProcessMemoryInfo(pids)` for PSS per process, and — if Usage Access
 * is granted — `UsageStatsManager` for foreground time / last-used.
 *
 * The numbers here ARE the equivalent of `adb shell dumpsys meminfo`
 * for the questions a user asks ("how much RAM is free, who is eating
 * it"): `getProcessMemoryInfo` returns the same PSS the meminfo command
 * prints, per process. The system-wide meminfo roll-up and cpuinfo stay
 * ADB-from-a-computer diagnostics; the RAM screen says so.
 *
 * `DUMP` is an experimental tier only (§3.4): this scanner is built
 * entirely on public APIs that need no grant, and no DUMP path is
 * claimed here.
 *
 * Logging discipline: a scan summary is logged at most once an hour, and
 * a ROGUE verdict is logged when a package first becomes ROGUE. Logging
 * every 60 s scan would flood the 500-event ring in hours.
 */
class RamWatch : CompanionModule {

    companion object {
        const val SCAN_INTERVAL_MS = 60L * 1000L
        const val TOP_APPS = 10
        const val SNAPSHOT_RING = 24
        const val SUMMARY_LOG_INTERVAL_MS = 60L * 60L * 1000L

        // Shared across instances: the service's scanner and the RAM
        // screen's one-shot scans feed the same history, so verdicts
        // (which need 3 snapshots of trend) agree everywhere.
        @Volatile
        private var sharedLatest: RamSnapshot? = null
        private val sharedHistory = ArrayList<RamSnapshot>()
        private val sharedKnownRogues = HashSet<String>()
        private var sharedLastSummaryLogMs = 0L
        private val sharedLock = Any()
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
                scanNow()
            } catch (_: Exception) {
                // A crashing scan must never kill the service (§4.4).
            }
            handler?.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    override fun start(ctx: Context) {
        if (running) return
        if (!Prefs.ramEnabled(ctx)) return
        appCtx = ctx.applicationContext
        thread = HandlerThread("BlastBuster-RamWatch").also { it.start() }
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

    /** Latest snapshot, or null before the first scan. */
    fun latestSnapshot(): RamSnapshot? = sharedLatest

    /**
     * One-off scan without starting the 60 s timer (used by RamActivity).
     * Feeds the same shared history as the service scanner.
     */
    fun oneShotScan(ctx: Context): RamSnapshot {
        val prev = appCtx
        appCtx = ctx.applicationContext
        try {
            return scanNow()
        } finally {
            appCtx = prev
        }
    }

    /** Forces a fresh scan now (RAM screen refresh, `ram.snapshot`). */
    fun snapshotNow(): RamSnapshot? {
        val ctx = appCtx ?: return sharedLatest
        if (!Prefs.ramEnabled(ctx)) return sharedLatest
        return try {
            scanNow()
        } catch (_: Exception) {
            sharedLatest
        }
    }

    override fun statusJson(): String {
        val snap = sharedLatest
        if (snap == null) return "{\"enabled\":true,\"scanned\":false}"
        val top = snap.apps.firstOrNull()
        val topJson = if (top != null) {
            "{\"package\":\"${EventLog.jsonEscape(top.packageName)}\"," +
                "\"pss_mb\":${top.pssKb / 1024},\"verdict\":\"${top.verdict}\"}"
        } else "null"
        return "{\"enabled\":true,\"scanned\":true," +
            "\"total_mb\":${snap.totalKb / 1024}," +
            "\"avail_mb\":${snap.availKb / 1024}," +
            "\"free_percent\":${snap.freePercent()}," +
            "\"low_memory\":${snap.lowMemory}," +
            "\"rogue_count\":${snap.rogues().size}," +
            "\"top\":$topJson}"
    }

    /**
     * One scan. Runs on the scanner thread (or the caller's thread for a
     * forced snapshot). Holds a short partial wake lock only while
     * reading, then releases it (§4.1).
     */
    fun scanNow(): RamSnapshot {
        val ctx = appCtx ?: throw IllegalStateException("RamWatch not started")
        var wl: PowerManager.WakeLock? = null
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlastBuster:ram")
            wl.acquire(10L * 1000L)
            val snap = buildSnapshot(ctx)
            synchronized(sharedLock) {
                sharedHistory.add(snap)
                while (sharedHistory.size > SNAPSHOT_RING) sharedHistory.removeAt(0)
            }
            sharedLatest = snap
            logScan(snap)
            return snap
        } finally {
            try {
                if (wl?.isHeld == true) wl.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildSnapshot(ctx: Context): RamSnapshot {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val usageGranted = hasUsageAccess(ctx)
        val usage = if (usageGranted) readUsageStats(ctx) else emptyMap()
        val running = try {
            am.runningAppProcesses ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val pids = running.map { it.pid }.toIntArray()
        val pssByPid: Map<Int, Long> = try {
            // getProcessMemoryInfo returns one entry per requested pid,
            // in the same order — Debug.MemoryInfo carries no pid field.
            val infos = am.getProcessMemoryInfo(pids) ?: emptyArray()
            val map = HashMap<Int, Long>()
            for (i in infos.indices) {
                if (i < pids.size) map[pids[i]] = infos[i].totalPss.toLong()
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
        val roguePss = Prefs.ramRoguePssMb(ctx) * 1024L
        val watchPss = Prefs.ramWatchPssMb(ctx) * 1024L
        val entries = ArrayList<RamAppEntry>()
        for (proc in running) {
            val pss = pssByPid[proc.pid] ?: continue
            val pkg = proc.processName
            val foreground = proc.importance ==
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            val systemApp = isSystemApp(ctx, pkg)
            val stats = usage[pkg]
            val trend = pssTrendKb(pkg)
            val killedBefore = Prefs.ramWasKilledBefore(ctx, pkg)
            // Seen running after a kill: record the re-appearance so the
            // verdict can say WATCH ("came back after a kill").
            if (killedBefore) Prefs.markRamReappeared(ctx, pkg)
            val reappeared = killedBefore && Prefs.ramReappearedAfterKill(ctx, pkg)
            val verdict = RamVerdict.classify(
                pssKb = pss,
                trendKb = trend,
                foreground = foreground,
                systemApp = systemApp,
                killedBefore = killedBefore,
                reappearedAfterKill = reappeared,
                roguePssKb = roguePss,
                watchPssKb = watchPss
            )
            entries.add(
                RamAppEntry(
                    packageName = pkg,
                    pssKb = pss,
                    foreground = foreground,
                    systemApp = systemApp,
                    lastUsedMs = stats?.first ?: 0L,
                    foregroundTimeMs = stats?.second ?: 0L,
                    verdict = verdict.verdict,
                    verdictReason = verdict.reason
                )
            )
        }
        entries.sortByDescending { it.pssKb }
        return RamSnapshot(
            tsMs = System.currentTimeMillis(),
            totalKb = memInfo.totalMem / 1024L,
            availKb = memInfo.availMem / 1024L,
            thresholdKb = memInfo.threshold / 1024L,
            lowMemory = memInfo.lowMemory,
            apps = entries.take(TOP_APPS),
            usageAccessGranted = usageGranted
        )
    }

    /** PSS change for [pkg] since 3 snapshots ago, null when unknown. */
    private fun pssTrendKb(pkg: String): Long? {
        synchronized(sharedLock) {
            if (sharedHistory.size < 3) return null
            val old = sharedHistory[sharedHistory.size - 3].apps.find { it.packageName == pkg }
                ?: return null
            val now = sharedHistory.lastOrNull()?.apps?.find { it.packageName == pkg }
                ?: return null
            return now.pssKb - old.pssKb
        }
    }

    private fun logScan(snap: RamSnapshot) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        for (rogue in snap.rogues()) {
            if (sharedKnownRogues.add(rogue.packageName)) {
                EventLog.append(
                    ctx, "ram", "RAM_ROGUE",
                    "Rogue app: ${rogue.packageName} at ${rogue.pssKb / 1024} MB — " +
                        rogue.verdictReason,
                    mapOf("package" to rogue.packageName)
                )
            }
        }
        sharedKnownRogues.retainAll(snap.apps.map { it.packageName }.toSet())
        if (now - sharedLastSummaryLogMs >= SUMMARY_LOG_INTERVAL_MS) {
            sharedLastSummaryLogMs = now
            val top = snap.apps.firstOrNull()
            EventLog.append(
                ctx, "ram", "RAM_SCAN",
                "RAM ${snap.availKb / 1024} MB free of ${snap.totalKb / 1024} MB " +
                    "(${snap.freePercent()}%), top: " +
                    (top?.let { "${it.packageName} ${it.pssKb / 1024} MB" } ?: "none") +
                    ", rogues: ${snap.rogues().size}"
            )
        }
    }

    /** Usage Access check via AppOps (PACKAGE_USAGE_STATS has no runtime prompt). */
    @Suppress("DEPRECATION")
    private fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /** package -> (lastTimeUsed, totalTimeInForeground) over the last 24 h. */
    private fun readUsageStats(ctx: Context): Map<String, Pair<Long, Long>> {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, now - 24L * 60L * 60L * 1000L, now
            ) ?: return emptyMap()
            val out = HashMap<String, Pair<Long, Long>>()
            for (s in stats) {
                out[s.packageName] = Pair(s.lastTimeUsed, s.totalTimeInForeground)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun isSystemApp(ctx: Context, pkg: String): Boolean {
        return try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) {
            false
        }
    }
}
