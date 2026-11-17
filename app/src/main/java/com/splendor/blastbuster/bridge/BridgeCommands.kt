package com.splendor.blastbuster.bridge

/**
 * Command validation + dispatch contract for `POST /v1/command`
 * (DESIGN.md §2.3, §4.2).
 *
 * Pure Kotlin, zero Android imports, JVM-testable. This file decides
 * whether a command is *allowed*; it never touches the phone. The
 * actual work is done by a [BridgeCommandExecutor] supplied by the
 * host (CompanionService at merge — see BridgeModule / BridgeServer).
 * Until a real executor is wired, [UnavailableExecutor] answers every
 * module command honestly with `module_not_available` instead of
 * pretending the work happened.
 *
 * Deliberate refusals, per the design:
 * - `blast.start` is NOT implemented in v1.2. A remote start is the
 *   most dangerous command (a stolen token = a phone screaming in the
 *   owner's pocket). The owner starts a blast by SMS trigger code.
 * - Writes are blocked while a blast is active, except `blast.stop`
 *   (token + stop code). Reads (`ram.snapshot`, `blast.status`) are
 *   allowed during a blast — Muse must be able to see a blast happening.
 * - `ram.kill` takes one package per call, never kills Blast Buster
 *   itself, the system UI, or the phone app, and never does a bulk kill.
 */
object BridgeCommands {

    /** Commands the bridge accepts in v1.2 (plus the refused `blast.start`). */
    val KNOWN_COMMANDS: Set<String> = setOf(
        "ram.snapshot", "ram.kill",
        "dnd.apply",
        "net.flick", "net.mode",
        "dns.set",
        "blast.status", "blast.stop"
    )

    /** Packages a bridge `ram.kill` must never touch. */
    val KILL_DENYLIST: Set<String> = setOf(
        "com.splendor.blastbuster", // ourselves
        "com.android.systemui", // system UI — killing it bricks the screen
        "com.android.phone", // telephony — killing it drops the radio
        "com.android.dialer", // the phone app (AOSP)
        "com.google.android.dialer", // the phone app (Google)
        "com.samsung.android.dialer", // the phone app (Samsung)
        "android" // the system itself
    )

    val DND_MODES: Set<String> = setOf("on", "off")
    val NET_MODES: Set<String> = setOf("4g", "5g", "auto")

    data class CommandRequest(
        val command: String,
        val args: Map<String, Any?>,
        val code: String?
    )

    /** Outcome of [validate]: allowed, or refused with a stable error code. */
    data class Validation(val ok: Boolean, val error: String? = null)

    /**
     * Pure validation. [isBlasting] is supplied by the caller (it reads
     * `Prefs.isBlasting` — this file must not import Android). The stop
     * *code* itself is checked by the server against `Prefs.stopCode`,
     * not here: this file only requires that a code was supplied.
     */
    fun validate(req: CommandRequest, isBlasting: Boolean): Validation {
        val cmd = req.command.trim()
        if (cmd.isEmpty()) return Validation(false, "missing_command")
        // The one deliberate refusal in the whole bridge.
        if (cmd == "blast.start") return Validation(false, "blast_start_not_supported")
        if (cmd !in KNOWN_COMMANDS) return Validation(false, "unknown_command")

        val isRead = cmd == "ram.snapshot" || cmd == "blast.status"
        if (isBlasting && !isRead && cmd != "blast.stop") {
            return Validation(false, "blocked_blast_active")
        }

        when (cmd) {
            "ram.kill" -> {
                val pkg = (req.args["package"] as? String)?.trim()
                if (pkg.isNullOrEmpty()) return Validation(false, "missing_package")
                if (pkg in KILL_DENYLIST) return Validation(false, "denied_package")
            }
            "dnd.apply" -> {
                val mode = (req.args["mode"] as? String)?.trim()?.lowercase()
                if (mode !in DND_MODES) return Validation(false, "invalid_mode")
            }
            "net.mode" -> {
                val mode = (req.args["mode"] as? String)?.trim()?.lowercase()
                if (mode !in NET_MODES) return Validation(false, "invalid_mode")
            }
            "dns.set" -> {
                if (req.args["enabled"] !is Boolean) {
                    return Validation(false, "invalid_enabled")
                }
            }
            "blast.stop" -> {
                if (req.code.isNullOrBlank()) return Validation(false, "missing_code")
            }
        }
        return Validation(true)
    }

    /** Result of executing an allowed command. */
    data class ExecutorResult(
        val ok: Boolean,
        /** Pre-built JSON fragment for the `result` field, or null. */
        val resultJson: String? = null,
        val error: String? = null
    )

    /**
     * Host-supplied executor. Implementations live with the modules
     * (RAM/DND/Net/DNS, agents A/B); the bridge only calls through
     * this interface so it never depends on a module's concrete class.
     */
    interface BridgeCommandExecutor {
        fun execute(req: CommandRequest): ExecutorResult
    }

    /**
     * Stand-in executor used until the real modules are wired at merge.
     * It refuses honestly instead of faking a result: every module
     * command reports `module_not_available`. `blast.status` /
     * `blast.stop` never reach an executor — the server handles them.
     */
    object UnavailableExecutor : BridgeCommandExecutor {
        override fun execute(req: CommandRequest): ExecutorResult =
            ExecutorResult(ok = false, error = "module_not_available")
    }
}
