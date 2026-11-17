package com.splendor.blastbuster.companion

import android.content.Context
import com.splendor.blastbuster.Prefs

/**
 * Blast-priority rule (DESIGN.md §1.3) — the single most important
 * invariant in v1.2: while a blast is active, no companion module may
 * change phone state. Reads are always allowed (Muse must be able to see
 * that a blast is happening); writes are blocked, except a remote
 * `blast.stop`, which is allowed because it is the owner's remote stop
 * (the token + stop-code checks for it live in BridgeCommands, Agent C).
 *
 * The pure core is [Gate], which takes a `() -> Boolean` blasting
 * supplier so it is JVM-testable with no Android classes. The Android
 * wrapper reads the real blast flag from [Prefs].
 */
object BlastGate {

    /** Broadcast when a blast starts; CompanionService/DnsVpnService suspend on it. */
    const val ACTION_BLAST_STARTED = "com.splendor.blastbuster.BLAST_STARTED"

    /** Broadcast when a blast stops; suspended companion work resumes on it. */
    const val ACTION_BLAST_STOPPED = "com.splendor.blastbuster.BLAST_STOPPED"

    /** EventLog reason recorded whenever a companion action is skipped. */
    const val SKIP_REASON = "SKIPPED_BLAST_ACTIVE"

    enum class Decision { ALLOWED, BLOCKED_BLAST_ACTIVE }

    /** Pure blast-priority check. No Android imports; JVM-tested. */
    class Gate(private val isBlasting: () -> Boolean) {

        /** True while a blast is active — companion timers must suspend. */
        fun shouldPauseCompanionWork(): Boolean = isBlasting()

        /** Reads (status, events, RAM snapshot, DND state) are always allowed. */
        fun allowsRead(): Boolean = true

        /** Any write that changes phone state is blocked during a blast. */
        fun allowsWrite(): Boolean = !isBlasting()

        /**
         * The one write allowed during a blast: the owner's remote stop.
         * The bridge token + stop-code checks are the caller's job;
         * this gate only says the blast-priority rule does not forbid it.
         */
        fun allowsBlastStop(): Boolean = true

        /** Pure decision for a named write action, without side effects. */
        fun checkWrite(@Suppress("UNUSED_PARAMETER") action: String): Decision =
            if (isBlasting()) Decision.BLOCKED_BLAST_ACTIVE else Decision.ALLOWED

        /**
         * Returns true when [action] may proceed. When blocked, calls
         * [onSkip] once with the skip reason (the caller logs it to
         * EventLog) and returns false. The blast itself is never touched.
         */
        fun checkWrite(action: String, onSkip: (String) -> Unit): Boolean {
            if (!isBlasting()) return true
            onSkip("$SKIP_REASON: $action blocked while blast active")
            return false
        }
    }

    /** Android wrapper: reads the live blast flag from [Prefs]. */
    fun isBlasting(ctx: Context): Boolean = Prefs.isBlasting(ctx)

    /** Android wrapper: a [Gate] backed by the live blast flag. */
    fun forContext(ctx: Context): Gate = Gate { Prefs.isBlasting(ctx) }
}
