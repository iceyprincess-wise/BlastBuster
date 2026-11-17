import com.splendor.blastbuster.net.NetOutcomeText

// JVM unit tests for the v1.3 net fixes (item 4, net half): the
// flick / mode-lock outcome mapping — the exact status line, whether
// the UI must show the guided manual steps, and what counts as
// success. NetTools builds its detail strings from this mapping and
// NetActivity decides the guided path from it, so these checks cover
// the mapping the app actually uses.
// Standalone main() runner — no JUnit dependency (see run-tests.sh).

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
    println("NetAdbTest")

    // --- flick messages: every outcome has an honest, distinct line ---
    check("flick OK_RESTORED",
        NetOutcomeText.flickMessage("OK_RESTORED") == "Signal restored after flick.")
    check("flick OK_NO_IMPROVEMENT names the towers, not the phone",
        NetOutcomeText.flickMessage("OK_NO_IMPROVEMENT").contains("towers"))
    check("flick NEEDS_ADB names the grant and the guided steps",
        NetOutcomeText.flickMessage("NEEDS_ADB").contains("WRITE_SECURE_SETTINGS") &&
            NetOutcomeText.flickMessage("NEEDS_ADB").contains("guided"))
    check("flick COOLDOWN carries the remaining seconds",
        NetOutcomeText.flickMessage("COOLDOWN", remainingCooldownMs = 240_000L) ==
            "Flick cooling down — try again in 240s.")
    check("flick COOLDOWN zero remaining",
        NetOutcomeText.flickMessage("COOLDOWN", remainingCooldownMs = 0L) ==
            "Flick cooling down — try again in 0s.")
    check("flick BLOCKED_BLAST refuses, never claims success",
        NetOutcomeText.flickMessage("BLOCKED_BLAST").contains("refused"))
    check("flick FAILED with a cause reports it verbatim",
        NetOutcomeText.flickMessage("FAILED", cause = "System refused the airplane write: denied") ==
            "System refused the airplane write: denied")
    check("flick FAILED without a cause is still honest",
        NetOutcomeText.flickMessage("FAILED") == "Flick failed.")
    check("flick unknown outcome is never a fake success",
        NetOutcomeText.flickMessage("SOMETHING_NEW").contains("unknown") &&
            !NetOutcomeText.flickRan("SOMETHING_NEW"))

    // --- flick guided steps: only the no-grant outcome shows them ---
    check("flick NEEDS_ADB shows guided steps",
        NetOutcomeText.flickShowsGuidedSteps("NEEDS_ADB"))
    check("flick OK_RESTORED does not show guided steps",
        !NetOutcomeText.flickShowsGuidedSteps("OK_RESTORED"))
    check("flick FAILED does not show guided steps",
        !NetOutcomeText.flickShowsGuidedSteps("FAILED"))
    check("flick BLOCKED_BLAST does not show guided steps",
        !NetOutcomeText.flickShowsGuidedSteps("BLOCKED_BLAST"))
    check("flick COOLDOWN does not show guided steps",
        !NetOutcomeText.flickShowsGuidedSteps("COOLDOWN"))

    // --- flick success semantics ---
    check("flickRan: OK_RESTORED ran", NetOutcomeText.flickRan("OK_RESTORED"))
    check("flickRan: OK_NO_IMPROVEMENT ran (radio cycled, no gain)",
        NetOutcomeText.flickRan("OK_NO_IMPROVEMENT"))
    check("flickRan: FAILED did not run", !NetOutcomeText.flickRan("FAILED"))
    check("flickRan: NEEDS_ADB did not run", !NetOutcomeText.flickRan("NEEDS_ADB"))
    check("flickRestored only on OK_RESTORED",
        NetOutcomeText.flickRestored("OK_RESTORED") &&
            !NetOutcomeText.flickRestored("OK_NO_IMPROVEMENT"))

    // --- mode messages ---
    check("mode APPLIED",
        NetOutcomeText.modeMessage("APPLIED", "4G only") == "Mode lock applied: 4G only.")
    check("mode NEEDS_ADB saves the choice and names the grant",
        NetOutcomeText.modeMessage("NEEDS_ADB", "4G only").contains("WRITE_SECURE_SETTINGS") &&
            NetOutcomeText.modeMessage("NEEDS_ADB", "4G only").contains("saved"))
    check("mode WRITE_OK_NOT_APPLIED carries the live type and the HyperOS warning",
        NetOutcomeText.modeMessage("WRITE_OK_NOT_APPLIED", "4G only", "(live type 3)")
            .contains("(live type 3)") &&
            NetOutcomeText.modeMessage("WRITE_OK_NOT_APPLIED", "4G only", "(live type 3)")
                .contains("HyperOS"))
    check("mode BLOCKED_BLAST refuses",
        NetOutcomeText.modeMessage("BLOCKED_BLAST").contains("refused"))
    check("mode FAILED with detail reports it verbatim",
        NetOutcomeText.modeMessage("FAILED", extra = "Mode write did not stick (read back 9, wrote 11).") ==
            "Mode write did not stick (read back 9, wrote 11).")
    check("mode FAILED without detail is still honest",
        NetOutcomeText.modeMessage("FAILED") == "Mode change failed.")
    check("mode unknown outcome is never a fake success",
        NetOutcomeText.modeMessage("SOMETHING_NEW").contains("unknown") &&
            !NetOutcomeText.modeApplied("SOMETHING_NEW"))

    // --- mode guided steps: no grant, or write ignored by the radio ---
    check("mode NEEDS_ADB shows guided steps",
        NetOutcomeText.modeShowsGuidedSteps("NEEDS_ADB"))
    check("mode WRITE_OK_NOT_APPLIED shows guided steps (try the setting by hand)",
        NetOutcomeText.modeShowsGuidedSteps("WRITE_OK_NOT_APPLIED"))
    check("mode APPLIED does not show guided steps",
        !NetOutcomeText.modeShowsGuidedSteps("APPLIED"))
    check("mode BLOCKED_BLAST does not show guided steps",
        !NetOutcomeText.modeShowsGuidedSteps("BLOCKED_BLAST"))
    check("mode FAILED does not show guided steps",
        !NetOutcomeText.modeShowsGuidedSteps("FAILED"))

    // --- mode success semantics: only a verified apply counts ---
    check("modeApplied only on APPLIED",
        NetOutcomeText.modeApplied("APPLIED") &&
            !NetOutcomeText.modeApplied("WRITE_OK_NOT_APPLIED") &&
            !NetOutcomeText.modeApplied("FAILED") &&
            !NetOutcomeText.modeApplied("NEEDS_ADB"))

    println("RESULT NetAdbTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
