package com.splendor.blastbuster.bridge

/**
 * Per-export confirm gate for `GET /v1/log` (DESIGN.md §8 D-04, v1.3).
 *
 * The log leaves the phone only behind an explicit per-export confirm
 * in the app. The confirm is this in-memory, time-boxed (5 minutes),
 * single-use flag — it is NEVER persisted (no Prefs access, no disk):
 * a restart clears it, which is the safe default.
 *
 * Rules:
 * - [grant] opens one export window of [validityMs] (default 5 min).
 * - [isAllowed] is true only inside an unexpired, unconsumed window.
 * - [tryConsume] returns true once per grant and consumes the window;
 *   a second call without a fresh grant returns false.
 * - After expiry a grant is dead until the user grants again in-app.
 * - The clock is injectable so tests can drive time.
 *
 * The gate decides allow/deny only. The server logs every
 * grant/deny/export in EventLog; the gate itself never logs (it must
 * stay pure and JVM-testable).
 */
class LogExportGate(
    private val validityMs: Long = DEFAULT_VALIDITY_MS,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        /** One export window lasts 5 minutes from the in-app tap. */
        const val DEFAULT_VALIDITY_MS = 5 * 60_000L
    }

    private var grantedAtMs: Long? = null
    private var consumed = false

    /** Open one export window starting now (replaces any old window). */
    @Synchronized
    fun grant() {
        grantedAtMs = now()
        consumed = false
    }

    /** True only while a grant is live: unexpired and not yet consumed. */
    @Synchronized
    fun isAllowed(): Boolean {
        val grantedAt = grantedAtMs ?: return false
        if (consumed) return false
        return now() - grantedAt < validityMs
    }

    /**
     * Consume the window for one export. Returns true exactly once
     * per grant, only while the grant is live; false otherwise (no
     * grant, expired, or already consumed).
     */
    @Synchronized
    fun tryConsume(): Boolean {
        if (!isAllowed()) return false
        consumed = true
        return true
    }

    /** Close any open window without using it. */
    @Synchronized
    fun revoke() {
        grantedAtMs = null
        consumed = false
    }
}
