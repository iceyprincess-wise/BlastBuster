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
    val verdictReason: String = ""
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
    val usageAccessGranted: Boolean
) {
    /** Free RAM as a whole percent of total, 0 when total is unknown. */
    fun freePercent(): Int =
        if (totalKb > 0) ((availKb * 100L) / totalKb).toInt() else 0

    /** Apps judged ROGUE in this snapshot. */
    fun rogues(): List<RamAppEntry> = apps.filter { it.verdict == Verdict.ROGUE }
}
