package com.splendor.blastbuster.ram

/**
 * Rogue-app classifier (DESIGN.md §5.1). Pure Kotlin, no Android imports,
 * JVM-tested.
 *
 * Inputs per app: PSS, PSS trend over the last 3 snapshots, whether it is
 * currently foreground, whether it is a system app, and kill history.
 * CPU time is deliberately NOT an input: `/proc/<pid>/stat` is not
 * available to third-party apps, so no CPU number is claimed — the
 * classifier uses UsageStats foreground time + PSS growth instead.
 *
 * Thresholds are constants at the top, starting values for a 4 GB phone,
 * not laws of nature; they are configurable in Prefs and passed in here.
 */
object RamVerdict {

    /** ROGUE needs PSS above this (default 400 MB). */
    const val DEFAULT_ROGUE_PSS_KB = 400L * 1024L

    /** WATCH needs PSS above this while in the background (default 250 MB). */
    const val DEFAULT_WATCH_PSS_KB = 250L * 1024L

    /** PSS growth over the last 3 snapshots that counts as "growing". */
    const val GROWTH_KB = 50L * 1024L

    /** PSS growth over the last 3 snapshots that counts as "fast growth". */
    const val FAST_GROWTH_KB = 100L * 1024L

    data class Result(val verdict: Verdict, val reason: String)

    /**
     * Classifies one app.
     *
     * @param pssKb current PSS in KB.
     * @param trendKb PSS change over the last 3 snapshots in KB, or null
     *   when fewer than 3 snapshots exist (no trend can be judged yet).
     * @param foreground true when the app is in the foreground now.
     * @param systemApp true when ApplicationInfo.FLAG_SYSTEM is set.
     * @param killedBefore true when the user has killed this app before.
     * @param reappearedAfterKill true when the app came back after a kill.
     */
    fun classify(
        pssKb: Long,
        trendKb: Long?,
        foreground: Boolean,
        systemApp: Boolean,
        killedBefore: Boolean,
        reappearedAfterKill: Boolean,
        roguePssKb: Long = DEFAULT_ROGUE_PSS_KB,
        watchPssKb: Long = DEFAULT_WATCH_PSS_KB
    ): Result {
        // System apps are never ROGUE and never WATCH: their PSS includes
        // shared system memory and a verdict on them would mislead.
        if (systemApp) {
            return Result(Verdict.OK, "system app — never judged rogue")
        }
        // The foreground app is never ROGUE: the app the user is using is
        // allowed to hold RAM. Fast growth while in use is still a WATCH.
        val growing = trendKb != null && trendKb > GROWTH_KB
        val fastGrowth = trendKb != null && trendKb > FAST_GROWTH_KB
        if (foreground) {
            if (fastGrowth) {
                return Result(
                    Verdict.WATCH,
                    "foreground app growing fast (+${trendKb!! / 1024} MB over 3 scans)"
                )
            }
            return Result(Verdict.OK, "foreground app — in use")
        }
        // ROGUE: big AND growing AND background AND not a system app.
        // A trend is required: without 3 snapshots of history, a big PSS
        // alone is not enough to call an app rogue.
        if (pssKb > roguePssKb && growing) {
            return Result(
                Verdict.ROGUE,
                "background app over ${roguePssKb / 1024} MB and growing " +
                    "(+${trendKb!! / 1024} MB over 3 scans)"
            )
        }
        if (reappearedAfterKill) {
            return Result(
                Verdict.WATCH,
                "came back after a kill" +
                    if (killedBefore) " (killed before)" else ""
            )
        }
        if (pssKb > watchPssKb) {
            return Result(
                Verdict.WATCH,
                "background app over ${watchPssKb / 1024} MB"
            )
        }
        if (fastGrowth) {
            return Result(
                Verdict.WATCH,
                "background app growing fast (+${trendKb!! / 1024} MB over 3 scans)"
            )
        }
        return Result(Verdict.OK, "within limits")
    }
}
