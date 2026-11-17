import com.splendor.blastbuster.ram.RamVerdict
import com.splendor.blastbuster.ram.Verdict

// RamVerdict — DESIGN.md §7.1 target: 15 tests.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }
    val mb = 1024L
    fun c(
        pssMb: Long, trendMb: Long? = null, fg: Boolean = false,
        sys: Boolean = false, killed: Boolean = false, reappeared: Boolean = false
    ) = RamVerdict.classify(pssMb * mb, trendMb?.times(mb), fg, sys, killed, reappeared)

    // 1. ROGUE: big + growing + background + not system.
    check("rogue big growing background", c(500, 60).verdict == Verdict.ROGUE)
    // 2. Big but NOT growing -> not ROGUE (WATCH via the 250 MB bar).
    check("big flat is WATCH not ROGUE", c(500, 0).verdict == Verdict.WATCH)
    // 3. Big + growing but no trend history -> not ROGUE.
    check("big growing no-trend is WATCH", c(500, null).verdict == Verdict.WATCH)
    // 4. ROGUE boundary: exactly 400 MB is not over the bar.
    check("400MB boundary not ROGUE", c(400, 60).verdict == Verdict.WATCH)
    // 5. Just over 400 MB + growing -> ROGUE.
    check("401MB growing is ROGUE", c(401, 60).verdict == Verdict.ROGUE)
    // 6. Shrinking big app is not ROGUE.
    check("big shrinking is WATCH", c(500, -60).verdict == Verdict.WATCH)
    // 7. System app never ROGUE, however big and fast-growing.
    check("system never ROGUE", c(900, 300, sys = true).verdict == Verdict.OK)
    // 8. Foreground app never ROGUE, however big and growing.
    check("foreground never ROGUE", c(900, 300, fg = true).verdict == Verdict.WATCH)
    // 9. Foreground app, calm -> OK.
    check("foreground calm is OK", c(900, 0, fg = true).verdict == Verdict.OK)
    // 10. WATCH: background over 250 MB.
    check("background 300MB is WATCH", c(300, 0).verdict == Verdict.WATCH)
    // 11. WATCH boundary: exactly 250 MB is not over the bar.
    check("250MB boundary is OK", c(250, 0).verdict == Verdict.OK)
    // 12. WATCH: fast growth even under 250 MB.
    check("fast growth is WATCH", c(100, 150).verdict == Verdict.WATCH)
    // 13. WATCH: re-appearance after a kill.
    check("reappeared after kill is WATCH", c(50, 0, killed = true, reappeared = true).verdict == Verdict.WATCH)
    // 14. Kill history alone (killed but not reappeared) is not a WATCH.
    check("killed-but-gone is OK", c(50, 0, killed = true, reappeared = false).verdict == Verdict.OK)
    // 15. Small, calm, background -> OK.
    check("small calm is OK", c(50, 0).verdict == Verdict.OK)
    // 16. ROGUE reason names the cause.
    check(
        "rogue reason text",
        c(500, 60).reason.contains("growing") && c(500, 60).reason.contains("400")
    )
    // 17. Custom thresholds are honoured.
    check(
        "custom thresholds",
        RamVerdict.classify(300 * mb, 60 * mb, false, false, false, false, roguePssKb = 200 * mb).verdict == Verdict.ROGUE
    )

    println("RamVerdictTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
