import com.splendor.blastbuster.net.SignalGrade
import com.splendor.blastbuster.net.SignalGrader
import com.splendor.blastbuster.net.Transport

// JVM unit tests for SignalGrade (DESIGN.md §7.1: 10 tests aimed).
// Standalone main() runner — no JUnit dependency (see run-tests.sh).
// Run: java -cp <classes>:<stdlib> SignalGradeTestKt

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
        println("  PASS: $name")
    } else {
        failed++
        println("  FAIL: $name")
    }
}

fun main() {
    println("SignalGradeTest")

    // --- gradeFromDbm: LTE boundaries (RSRP bands) ---
    check("LTE -79 -> EXCELLENT", SignalGrader.gradeFromDbm(-79, Transport.LTE) == SignalGrade.EXCELLENT)
    check("LTE -80 -> EXCELLENT (boundary)", SignalGrader.gradeFromDbm(-80, Transport.LTE) == SignalGrade.EXCELLENT)
    check("LTE -81 -> GOOD", SignalGrader.gradeFromDbm(-81, Transport.LTE) == SignalGrade.GOOD)
    check("LTE -90 -> GOOD (boundary)", SignalGrader.gradeFromDbm(-90, Transport.LTE) == SignalGrade.GOOD)
    check("LTE -91 -> FAIR", SignalGrader.gradeFromDbm(-91, Transport.LTE) == SignalGrade.FAIR)
    check("LTE -100 -> FAIR (boundary)", SignalGrader.gradeFromDbm(-100, Transport.LTE) == SignalGrade.FAIR)
    check("LTE -101 -> POOR", SignalGrader.gradeFromDbm(-101, Transport.LTE) == SignalGrade.POOR)
    check("LTE -110 -> POOR (boundary)", SignalGrader.gradeFromDbm(-110, Transport.LTE) == SignalGrade.POOR)
    check("LTE -111 -> NONE", SignalGrader.gradeFromDbm(-111, Transport.LTE) == SignalGrade.NONE)

    // --- gradeFromDbm: NR uses the same RSRP bands ---
    check("NR -85 -> GOOD", SignalGrader.gradeFromDbm(-85, Transport.NR) == SignalGrade.GOOD)
    check("NR -120 -> NONE", SignalGrader.gradeFromDbm(-120, Transport.NR) == SignalGrade.NONE)

    // --- gradeFromDbm: GSM bands differ from LTE ---
    check("GSM -70 -> EXCELLENT", SignalGrader.gradeFromDbm(-70, Transport.GSM) == SignalGrade.EXCELLENT)
    check("GSM -85 -> GOOD (boundary)", SignalGrader.gradeFromDbm(-85, Transport.GSM) == SignalGrade.GOOD)
    check("GSM -95 -> FAIR (boundary)", SignalGrader.gradeFromDbm(-95, Transport.GSM) == SignalGrade.FAIR)
    check("GSM -105 -> POOR (boundary)", SignalGrader.gradeFromDbm(-105, Transport.GSM) == SignalGrade.POOR)
    check("GSM -106 -> NONE", SignalGrader.gradeFromDbm(-106, Transport.GSM) == SignalGrade.NONE)

    // --- gradeFromDbm: unknown readings grade NONE, never a guess ---
    check("dbm MAX_VALUE -> NONE", SignalGrader.gradeFromDbm(Int.MAX_VALUE, Transport.LTE) == SignalGrade.NONE)
    check("dbm 0 -> NONE", SignalGrader.gradeFromDbm(0, Transport.LTE) == SignalGrade.NONE)
    check("dbm -200 -> NONE", SignalGrader.gradeFromDbm(-200, Transport.LTE) == SignalGrade.NONE)

    // --- gradeFromLevel: 0-4 mapping + out of range ---
    check("level 0 -> NONE", SignalGrader.gradeFromLevel(0) == SignalGrade.NONE)
    check("level 1 -> POOR", SignalGrader.gradeFromLevel(1) == SignalGrade.POOR)
    check("level 2 -> FAIR", SignalGrader.gradeFromLevel(2) == SignalGrade.FAIR)
    check("level 3 -> GOOD", SignalGrader.gradeFromLevel(3) == SignalGrade.GOOD)
    check("level 4 -> EXCELLENT", SignalGrader.gradeFromLevel(4) == SignalGrade.EXCELLENT)
    check("level 5 -> NONE", SignalGrader.gradeFromLevel(5) == SignalGrade.NONE)
    check("level -1 -> NONE", SignalGrader.gradeFromLevel(-1) == SignalGrade.NONE)

    // --- smoothing: last-3 median ---
    check("smooth empty -> NONE", SignalGrader.smoothGrades(emptyList()) == SignalGrade.NONE)
    check("smooth single GOOD -> GOOD",
        SignalGrader.smoothGrades(listOf(SignalGrade.GOOD)) == SignalGrade.GOOD)
    check("smooth two (GOOD, NONE) -> NONE (conservative)",
        SignalGrader.smoothGrades(listOf(SignalGrade.GOOD, SignalGrade.NONE)) == SignalGrade.NONE)
    check("smooth one bad sample ignored (GOOD, GOOD, NONE) -> GOOD",
        SignalGrader.smoothGrades(listOf(SignalGrade.GOOD, SignalGrade.GOOD, SignalGrade.NONE)) == SignalGrade.GOOD)
    check("smooth one good sample ignored (POOR, POOR, EXCELLENT) -> POOR",
        SignalGrader.smoothGrades(listOf(SignalGrade.POOR, SignalGrade.POOR, SignalGrade.EXCELLENT)) == SignalGrade.POOR)
    check("smooth three bad samples grade down (GOOD, FAIR, POOR) -> FAIR",
        SignalGrader.smoothGrades(listOf(SignalGrade.GOOD, SignalGrade.FAIR, SignalGrade.POOR)) == SignalGrade.FAIR)
    check("smooth only last 3 count (NONE, GOOD, GOOD, GOOD) -> GOOD",
        SignalGrader.smoothGrades(
            listOf(SignalGrade.NONE, SignalGrade.GOOD, SignalGrade.GOOD, SignalGrade.GOOD)
        ) == SignalGrade.GOOD)

    // --- drop detection: two-grade fall only ---
    check("drop GOOD -> POOR", SignalGrader.isDrop(SignalGrade.GOOD, SignalGrade.POOR))
    check("drop EXCELLENT -> FAIR", SignalGrader.isDrop(SignalGrade.EXCELLENT, SignalGrade.FAIR))
    check("drop EXCELLENT -> NONE", SignalGrader.isDrop(SignalGrade.EXCELLENT, SignalGrade.NONE))
    check("no drop GOOD -> FAIR (one level)", !SignalGrader.isDrop(SignalGrade.GOOD, SignalGrade.FAIR))
    check("no drop on improvement", !SignalGrader.isDrop(SignalGrade.POOR, SignalGrade.GOOD))
    check("no drop same grade", !SignalGrader.isDrop(SignalGrade.FAIR, SignalGrade.FAIR))

    // --- usability ---
    check("FAIR is usable", SignalGrader.isUsable(SignalGrade.FAIR))
    check("GOOD is usable", SignalGrader.isUsable(SignalGrade.GOOD))
    check("POOR is not usable", !SignalGrader.isUsable(SignalGrade.POOR))
    check("NONE is not usable", !SignalGrader.isUsable(SignalGrade.NONE))

    println("RESULT SignalGradeTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
