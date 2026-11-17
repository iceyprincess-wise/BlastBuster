import com.splendor.blastbuster.ram.EliminationMode
import com.splendor.blastbuster.ram.RamAppEntry
import com.splendor.blastbuster.ram.RamSnapshot
import com.splendor.blastbuster.ram.Verdict

// EliminationMode selection — v1.3 RAM module. Pure decision logic:
// ROGUE-only, system/foreground/self never selected, empty snapshot,
// blast-blocked flag.
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
        pkg: String, pssMb: Long, verdict: Verdict,
        fg: Boolean = false, sys: Boolean = false
    ) = RamAppEntry(
        packageName = pkg, pssKb = pssMb * 1024, foreground = fg,
        systemApp = sys, lastUsedMs = 0L, foregroundTimeMs = 0L,
        verdict = verdict, verdictReason = "test"
    )
    fun snap(apps: List<RamAppEntry>) = RamSnapshot(
        tsMs = now, totalKb = 4096L * 1024, availKb = 1024L * 1024,
        thresholdKb = 256L * 1024, lowMemory = false, apps = apps,
        usageAccessGranted = true
    )

    val rogueA = entry("com.example.hog1", 500, Verdict.ROGUE)
    val rogueB = entry("com.example.hog2", 600, Verdict.ROGUE)
    val watch = entry("com.example.watch", 300, Verdict.WATCH)
    val ok = entry("com.example.ok", 50, Verdict.OK)

    // 1. ROGUE-only: WATCH and OK are never selected.
    check(
        "rogue only",
        EliminationMode.selectRogues(snap(listOf(rogueA, watch, ok)), self) == listOf(rogueA)
    )
    // 2. Multiple rogues are all selected, in snapshot order.
    check(
        "all rogues selected",
        EliminationMode.selectRogues(snap(listOf(rogueB, rogueA)), self) == listOf(rogueB, rogueA)
    )
    // 3. System app labelled ROGUE is never selected.
    check(
        "system rogue never selected",
        EliminationMode.selectRogues(
            snap(listOf(entry("com.example.sys", 900, Verdict.ROGUE, sys = true))), self
        ).isEmpty()
    )
    // 4. Foreground app labelled ROGUE is never selected.
    check(
        "foreground rogue never selected",
        EliminationMode.selectRogues(
            snap(listOf(entry("com.example.fg", 900, Verdict.ROGUE, fg = true))), self
        ).isEmpty()
    )
    // 5. Blast Buster itself is never selected.
    check(
        "self rogue never selected",
        EliminationMode.selectRogues(
            snap(listOf(entry(self, 900, Verdict.ROGUE))), self
        ).isEmpty()
    )
    // 6. Empty snapshot selects nothing.
    check(
        "empty snapshot",
        EliminationMode.selectRogues(snap(emptyList()), self).isEmpty()
    )
    // 7. Snapshot with no rogues selects nothing.
    check(
        "no rogues",
        EliminationMode.selectRogues(snap(listOf(watch, ok)), self).isEmpty()
    )
    // 8. Blast-blocked flag: may not run during a blast.
    check("blocked during blast", !EliminationMode.canRun(true))
    // 9. Allowed when not blasting.
    check("allowed when not blasting", EliminationMode.canRun(false))
    // 10. A big WATCH app is not selected (verdict-gated, not PSS-gated).
    check(
        "big WATCH not selected",
        EliminationMode.selectRogues(
            snap(listOf(entry("com.example.bigwatch", 900, Verdict.WATCH))), self
        ).isEmpty()
    )

    println("EliminationTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
