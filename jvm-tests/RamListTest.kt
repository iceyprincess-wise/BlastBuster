import com.splendor.blastbuster.ram.LowMemoryEpisode
import com.splendor.blastbuster.ram.LowMemoryTransition
import com.splendor.blastbuster.ram.RamAppEntry
import com.splendor.blastbuster.ram.RamListPresentation
import com.splendor.blastbuster.ram.RamSnapshot
import com.splendor.blastbuster.ram.Verdict

// RAM-list presentation logic + low-memory episode — v1.3 RAM module:
// available-vs-free labelling, self-package never killable, PSS text
// never invents a number, once-per-episode low-memory alert.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }
    val self = "com.splendor.blastbuster"
    val now = 1_700_000_000_000L
    fun entry(
        pkg: String, pssMb: Long = 100, fg: Boolean = false,
        sys: Boolean = false, pssKnown: Boolean = true, lastUsedMs: Long = 0L
    ) = RamAppEntry(
        packageName = pkg, pssKb = pssMb * 1024, foreground = fg,
        systemApp = sys, lastUsedMs = lastUsedMs, foregroundTimeMs = 0L,
        verdict = Verdict.OK, verdictReason = "", pssKnown = pssKnown
    )
    fun snap(
        availMb: Long, thresholdMb: Long, freeMb: Long,
        apps: List<RamAppEntry> = emptyList(),
        recent: List<RamAppEntry> = emptyList()
    ) = RamSnapshot(
        tsMs = now, totalKb = 4096L * 1024, availKb = availMb * 1024,
        thresholdKb = thresholdMb * 1024, lowMemory = false, apps = apps,
        usageAccessGranted = true, freeKb = freeMb * 1024, recentApps = recent
    )

    // Kill button: self is never killable, even as a background
    // non-system app with a known PSS.
    check("self never killable", !RamListPresentation.canShowKillButton(entry(self), self))
    // Foreground / system / PSS-unknown rows are never killable.
    check(
        "foreground not killable",
        !RamListPresentation.canShowKillButton(entry("com.example.fg", fg = true), self)
    )
    check(
        "system not killable",
        !RamListPresentation.canShowKillButton(entry("com.example.sys", sys = true), self)
    )
    check(
        "pss-unknown row not killable",
        !RamListPresentation.canShowKillButton(
            entry("com.example.recent", pssMb = 0, pssKnown = false), self
        )
    )
    // An ordinary background app is killable.
    check(
        "ordinary app killable",
        RamListPresentation.canShowKillButton(entry("com.example.game", pssMb = 300), self)
    )
    // PSS text: known shows the number; unknown says so, no invented number.
    check(
        "pss text known",
        RamListPresentation.pssText(entry("com.example.game", pssMb = 512)) == "512 MB"
    )
    val unknownText = RamListPresentation.pssText(
        entry("com.example.recent", pssMb = 0, pssKnown = false)
    )
    check(
        "pss text unknown",
        unknownText.contains("unavailable") && !unknownText.contains("MB")
    )
    // Available vs free labelling: both figures labelled, note says both
    // are true and measure different things.
    val s = snap(availMb = 1024, thresholdMb = 256, freeMb = 300)
    check(
        "available line labelled",
        RamListPresentation.availableLine(s).startsWith("Available:")
    )
    check(
        "free line labelled strictly free",
        RamListPresentation.freeLine(s) == "Strictly free: 300 MB"
    )
    check(
        "free unavailable says so",
        RamListPresentation.freeLine(snap(1024, 256, 0)).contains("unavailable")
    )
    check(
        "note says both true different things",
        RamListPresentation.AVAILABLE_VS_FREE_NOTE.contains("both are true") &&
            RamListPresentation.AVAILABLE_VS_FREE_NOTE.contains("different things")
    )
    // Threshold comparison behind the banner.
    check("below threshold", snap(200, 256, 100).belowLowMemoryThreshold())
    check("above threshold not below", !snap(300, 256, 100).belowLowMemoryThreshold())
    check("zero threshold never below", !snap(0, 0, 0).belowLowMemoryThreshold())
    // Last-used text.
    check(
        "last used minutes",
        RamListPresentation.lastUsedText(
            entry("com.example.recent", lastUsedMs = now - 5 * 60_000L), now
        ) == "last used 5 min ago"
    )
    check(
        "last used unknown",
        RamListPresentation.lastUsedText(entry("com.example.recent"), now) == "last used unknown"
    )
    // Low-memory episode: fires once per episode, resets on recovery.
    val ep = LowMemoryEpisode()
    check(
        "episode starts once",
        ep.update(200L * 1024, 256L * 1024) == LowMemoryTransition.STARTED
    )
    check(
        "episode does not refire",
        ep.update(150L * 1024, 256L * 1024) == LowMemoryTransition.NONE
    )
    check("episode still active", ep.inEpisode())
    check(
        "episode ends on recovery",
        ep.update(300L * 1024, 256L * 1024) == LowMemoryTransition.ENDED
    )
    check("episode inactive after recovery", !ep.inEpisode())
    check(
        "new episode after recovery",
        ep.update(100L * 1024, 256L * 1024) == LowMemoryTransition.STARTED
    )
    check(
        "zero threshold never low",
        LowMemoryEpisode().update(0L, 0L) == LowMemoryTransition.NONE
    )

    println("RamListTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
