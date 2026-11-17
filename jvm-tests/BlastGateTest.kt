import com.splendor.blastbuster.companion.BlastGate

// BlastGate — DESIGN.md §7.1 target: 8 tests.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }

    val idle = BlastGate.Gate { false }
    val blasting = BlastGate.Gate { true }

    // 1. Writes allowed when idle.
    check("write allowed when idle", idle.allowsWrite())
    // 2. Writes blocked during blast.
    check("write blocked during blast", !blasting.allowsWrite())
    // 3. Reads allowed during blast.
    check("read allowed during blast", blasting.allowsRead())
    // 4. Reads allowed when idle.
    check("read allowed when idle", idle.allowsRead())
    // 5. blast.stop allowed during blast (token + stop code checked elsewhere).
    check("blast.stop allowed during blast", blasting.allowsBlastStop())
    // 6. checkWrite returns BLOCKED during blast, ALLOWED when idle.
    check(
        "checkWrite decision",
        blasting.checkWrite("ram.kill x") == BlastGate.Decision.BLOCKED_BLAST_ACTIVE &&
            idle.checkWrite("ram.kill x") == BlastGate.Decision.ALLOWED
    )
    // 7. checkWrite with callback: skip reason logged once, returns false.
    var skipped = ""
    val ok = blasting.checkWrite("dnd re-apply") { reason -> skipped = reason }
    check(
        "skip callback carries SKIPPED_BLAST_ACTIVE",
        !ok && skipped.startsWith(BlastGate.SKIP_REASON) && skipped.contains("dnd re-apply")
    )
    // 8. shouldPauseCompanionWork mirrors the blast flag both ways.
    check(
        "shouldPause mirrors flag",
        blasting.shouldPauseCompanionWork() && !idle.shouldPauseCompanionWork()
    )
    // 9. Idle callback path returns true and never calls onSkip.
    var called = false
    check(
        "idle checkWrite never calls onSkip",
        idle.checkWrite("x") { called = true } && !called
    )
    // 10. Action strings are the documented broadcast actions.
    check(
        "broadcast actions",
        BlastGate.ACTION_BLAST_STARTED == "com.splendor.blastbuster.BLAST_STARTED" &&
            BlastGate.ACTION_BLAST_STOPPED == "com.splendor.blastbuster.BLAST_STOPPED"
    )

    println("BlastGateTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
