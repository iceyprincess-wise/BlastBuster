package com.splendor.blastbuster

/**
 * Pure SIM-removal decision logic. No Android imports, so it is verifiable
 * on the JVM (see the sandbox test).
 *
 * The watch only fires when the phone HAD a SIM when the guard started:
 * arming a phone that never had a SIM must not blast by itself.
 */
object SimPolicy {
    enum class Action { NONE, FIRE_BLAST, ESCALATE }

    /**
     * hadSim: a SIM was present when the guard started.
     * simPresentNow: at least one SIM slot reports READY right now.
     * armed: the app is armed. blasting: a blast is already active.
     */
    fun decide(
        hadSim: Boolean,
        simPresentNow: Boolean,
        armed: Boolean,
        blasting: Boolean
    ): Action {
        if (!armed || !hadSim || simPresentNow) return Action.NONE
        return if (blasting) Action.ESCALATE else Action.FIRE_BLAST
    }
}
