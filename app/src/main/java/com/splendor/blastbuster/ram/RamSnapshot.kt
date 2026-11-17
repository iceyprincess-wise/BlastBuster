package com.splendor.blastbuster.ram

/** Rogue verdict for one app (DESIGN.md §5.1). */
enum class Verdict {
    OK,
    WATCH,
    ROGUE
}

/** One app's entry in a RAM snapshot. PSS comes from ActivityManager. */
data class RamAppEntry(
    val packageName: String,
    /** PSS in KB, as reported by ActivityManager.getProcessMemoryInfo. */
    val pssKb: Long,
    /** True when this app is the foreground app right now. */
    val foreground: Boolean,
    /** True when ApplicationInfo.FLAG_SYSTEM is set. */
    val systemApp: Boolean,
    /** Last-used time from UsageStatsManager, epoch ms, 0 when unknown. */
    val lastUsedMs: Long = 0L,
    /** Foreground time in the last 24 h from UsageStatsManager, ms. */
    val foregroundTimeMs: Long = 0L,
    val verdict: Verdict = Verdict.OK,
    val verdictReason: String = "",
    /**
     * False when this row comes from UsageStatsManager only (no running
     * process, no PSS). Android reports PSS only for this app itself, so
     * a UsageStats-only row must never show an invented PSS number.
     */
    val pssKnown: Boolean = true
)

/** One RAM scan: device totals plus the top apps by PSS. */
data class RamSnapshot(
    val tsMs: Long,
    /** Total RAM in KB (ActivityManager.MemoryInfo.totalMem). */
    val totalKb: Long,
    /** Available RAM in KB (ActivityManager.MemoryInfo.availMem). */
    val availKb: Long,
    /** Low-memory threshold in KB (ActivityManager.MemoryInfo.threshold). */
    val thresholdKb: Long,
    /** ActivityManager.MemoryInfo.lowMemory flag. */
    val lowMemory: Boolean,
    /** Apps sorted by PSS descending (top 10 kept by the scanner). */
    val apps: List<RamAppEntry>,
    /** True when Usage Access was granted, so last-used times are real. */
    val usageAccessGranted: Boolean,
    /**
     * Strictly-free RAM in KB (MemFree from /proc/meminfo), 0 when it
     * could not be read. This is memory sitting completely unused — it is
     * smaller than [availKb], which also counts memory Android can
     * reclaim from caches. Both figures are true; they measure different
     * things (see [RamListPresentation.AVAILABLE_VS_FREE_NOTE]).
     */
    val freeKb: Long = 0L,
    /**
     * Recently-used apps from UsageStatsManager (last 24 h) that have no
     * running process in [apps], newest first. PSS is unknown for these
     * rows ([RamAppEntry.pssKnown] is false) — they are listed so the
     * screen shows more than Blast Buster itself, never as PSS rows.
     */
    val recentApps: List<RamAppEntry> = emptyList()
) {
    /** Free RAM as a whole percent of total, 0 when total is unknown. */
    fun freePercent(): Int =
        if (totalKb > 0) ((availKb * 100L) / totalKb).toInt() else 0

    /** Apps judged ROGUE in this snapshot. */
    fun rogues(): List<RamAppEntry> = apps.filter { it.verdict == Verdict.ROGUE }

    /** True when a strictly-free reading was obtained. */
    fun hasStrictlyFree(): Boolean = freeKb > 0L

    /** True when free RAM is below the system's own low-memory threshold. */
    fun belowLowMemoryThreshold(): Boolean = thresholdKb > 0L && availKb < thresholdKb
}

/**
 * Pure presentation rules for the RAM list (JVM-tested). No Android
 * imports: the Activity calls these and renders the result.
 */
object RamListPresentation {

    /**
     * One-line explanation shown next to the two memory figures: both
     * are true and measure different things.
     */
    const val AVAILABLE_VS_FREE_NOTE =
        "Available counts memory Android can reclaim from caches; Free is " +
            "memory sitting completely unused — both are true, they measure different things."

    /**
     * Whether the row may show a Kill button at all. Blast Buster itself
     * is never killable, and neither are foreground apps, system apps, or
     * UsageStats-only rows (no running process, no verdict behind them).
     */
    fun canShowKillButton(entry: RamAppEntry, selfPkg: String): Boolean {
        if (entry.packageName == selfPkg) return false
        if (entry.foreground) return false
        if (entry.systemApp) return false
        if (!entry.pssKnown) return false
        return true
    }

    /** PSS text for a row — never an invented number when PSS is unknown. */
    fun pssText(entry: RamAppEntry): String =
        if (entry.pssKnown) "${entry.pssKb / 1024} MB"
        else "PSS unavailable — Android reports PSS only for this app itself"

    /** "Available" figure line (ActivityManager.MemoryInfo.availMem). */
    fun availableLine(snap: RamSnapshot): String =
        "Available: ${snap.availKb / 1024} MB of ${snap.totalKb / 1024} MB (${snap.freePercent()}%)"

    /** "Strictly free" figure line (MemFree from /proc/meminfo). */
    fun freeLine(snap: RamSnapshot): String =
        if (snap.hasStrictlyFree()) "Strictly free: ${snap.freeKb / 1024} MB"
        else "Strictly free: unavailable on this device"

    /** Relative last-used text for a UsageStats row; "unknown" when unknown. */
    fun lastUsedText(entry: RamAppEntry, nowMs: Long): String {
        if (entry.lastUsedMs <= 0L) return "last used unknown"
        val diffMin = (nowMs - entry.lastUsedMs) / 60_000L
        if (diffMin < 1) return "last used just now"
        if (diffMin < 60) return "last used $diffMin min ago"
        val diffHrs = diffMin / 60
        if (diffHrs < 24) return "last used $diffHrs h ago"
        return "last used ${diffHrs / 24} d ago"
    }
}
