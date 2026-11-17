package com.splendor.blastbuster.net

/**
 * Pure outcome mapping for the ADB-gated net features (v1.3 item 4).
 * Android-free, JVM-tested (NetAdbTest).
 *
 * This is the single source of truth for what each
 * [NetTools.FlickResult] / [NetTools.ModeResult] means to the user:
 * the exact status line, whether the UI must show the guided manual
 * steps instead of pretending one-tap worked, and whether the outcome
 * counts as success. NetTools builds its detail strings from here and
 * NetActivity decides the guided path from here, so the mapping the
 * tests cover is the mapping the app actually uses.
 *
 * The outcome names are the enum names as strings: NetTools owns the
 * enums (they live on the Android side with the Settings writes), and
 * this file maps them without importing Android. An unknown name maps
 * to the honest fallback — never to a fake success.
 */
object NetOutcomeText {

    // ---------- airplane flick ----------

    /**
     * Status line for a flick outcome. [remainingCooldownMs] is used
     * only for COOLDOWN; [cause] is the full failure sentence for FAILED.
     */
    fun flickMessage(kindName: String, remainingCooldownMs: Long = 0L, cause: String? = null): String =
        when (kindName) {
            "OK_RESTORED" -> "Signal restored after flick."
            "OK_NO_IMPROVEMENT" ->
                "Flick done, but no signal improvement in 60 s — " +
                    "the problem is likely the towers, not the phone."
            "FAILED" -> if (cause.isNullOrEmpty()) "Flick failed." else cause
            "NEEDS_ADB" ->
                "One-tap flick needs the ADB grant WRITE_SECURE_SETTINGS. " +
                    "Use the guided steps instead (airplane settings: on, wait 8 s, off)."
            "COOLDOWN" -> {
                val secs = remainingCooldownMs / 1000
                "Flick cooling down — try again in ${secs}s."
            }
            "BLOCKED_BLAST" -> "Blast active — flick refused."
            else -> "Flick outcome unknown ($kindName) — nothing was changed."
        }

    /** True when the UI must show the guided manual steps for this flick outcome. */
    fun flickShowsGuidedSteps(kindName: String): Boolean = kindName == "NEEDS_ADB"

    /** True when the flick ran and the radio cycle completed (even with no improvement). */
    fun flickRan(kindName: String): Boolean =
        kindName == "OK_RESTORED" || kindName == "OK_NO_IMPROVEMENT"

    /** True when signal was verified restored after the flick. */
    fun flickRestored(kindName: String): Boolean = kindName == "OK_RESTORED"

    // ---------- network-mode lock ----------

    /**
     * Status line for a mode-lock outcome. [modeDisplay] is the chosen
     * mode's display name; [extra] carries the verify detail — for
     * WRITE_OK_NOT_APPLIED the "(live type …)" fragment, for FAILED
     * the full failure sentence.
     */
    fun modeMessage(kindName: String, modeDisplay: String = "", extra: String = ""): String =
        when (kindName) {
            "APPLIED" -> "Mode lock applied: $modeDisplay."
            "WRITE_OK_NOT_APPLIED" ->
                "The setting was written but the radio is not using it $extra. " +
                    "HyperOS may be ignoring the write — try the guided setting instead."
            "FAILED" -> extra.ifEmpty { "Mode change failed." }
            "NEEDS_ADB" ->
                "One-tap mode lock needs the ADB grant WRITE_SECURE_SETTINGS. " +
                    "Your choice ($modeDisplay) is saved; set it in the SIM's " +
                    "mobile-network settings and the app will check the live type."
            "BLOCKED_BLAST" -> "Blast active — mode change refused."
            else -> "Mode outcome unknown ($kindName) — nothing was changed."
        }

    /**
     * True when the UI must open / show the guided manual path for
     * this mode outcome: no grant, or the write was accepted but the
     * radio ignored it (the guided setting is the next thing to try).
     */
    fun modeShowsGuidedSteps(kindName: String): Boolean =
        kindName == "NEEDS_ADB" || kindName == "WRITE_OK_NOT_APPLIED"

    /** True only when the write stuck AND the live network type matches. */
    fun modeApplied(kindName: String): Boolean = kindName == "APPLIED"
}
