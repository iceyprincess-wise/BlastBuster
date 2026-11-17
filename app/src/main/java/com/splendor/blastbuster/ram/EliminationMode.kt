package com.splendor.blastbuster.ram

/**
 * Elimination mode (v1.3): manual one-tap kill of the current ROGUE
 * verdicts only. Pure decision logic, no Android imports, JVM-tested.
 *
 * Hard rules (DESIGN.md §8 D-06 stays NO, ever): this is NOT automatic,
 * NOT a background sweep, NOT scheduled. It runs only from the
 * "Eliminate now" button in RamActivity, kills one package at a time
 * through RamKiller (which enforces its own denylist, 1 h cooldown and
 * 5 s verify), and must refuse while a blast is active ([canRun]).
 * The execution (confirm dialog, sequential kills, ELIMINATION_STARTED /
 * ELIMINATION_FINISHED logging, result summary) lives in RamActivity;
 * this file holds only the selection decision, so it stays JVM-testable.
 */
object EliminationMode {

    /**
     * The packages that qualify for elimination from [snapshot]: current
     * ROGUE verdicts only. A ROGUE verdict already means PSS over 400 MB
     * AND growing AND background AND non-system (RamVerdict), but the
     * foreground / system / self checks are repeated here defensively so
     * a hand-built snapshot can never smuggle one through.
     */
    fun selectRogues(snapshot: RamSnapshot, selfPkg: String): List<RamAppEntry> =
        snapshot.rogues().filter { entry ->
            entry.packageName != selfPkg && !entry.foreground && !entry.systemApp
        }

    /** Elimination may run only when no blast is active. */
    fun canRun(isBlasting: Boolean): Boolean = !isBlasting
}
