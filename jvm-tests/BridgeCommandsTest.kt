package com.splendor.blastbuster.bridge

import com.splendor.blastbuster.bridge.BridgeCommands.CommandRequest

/**
 * JVM tests for BridgeCommands (DESIGN.md §7.1: 12 tests — 16 written).
 * Standalone main() runner — no JUnit dependency in the offline sandbox.
 * Run via jvm-tests/run-tests.sh.
 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) {
            passed++
            println("PASS: $name")
        } else {
            failed++
            println("FAIL: $name")
        }
    }
    fun v(cmd: String, args: Map<String, Any?> = emptyMap(), code: String? = null, blasting: Boolean = false) =
        BridgeCommands.validate(CommandRequest(cmd, args, code), blasting)

    // 1. Unknown command rejected.
    check("unknown command rejected", v("ram.explode").error == "unknown_command")
    // 2. blast.start is deliberately refused.
    check(
        "blast.start refused",
        v("blast.start", code = "BLAST 1234").error == "blast_start_not_supported"
    )
    // 3. Empty command rejected.
    check("empty command rejected", v("").error == "missing_command")
    // 4. ram.kill without a package rejected.
    check("ram.kill needs package", v("ram.kill").error == "missing_package")
    // 5. ram.kill denylist: self, system UI, phone app, system.
    check(
        "kill denylist",
        v("ram.kill", mapOf("package" to "com.splendor.blastbuster")).error == "denied_package" &&
            v("ram.kill", mapOf("package" to "com.android.systemui")).error == "denied_package" &&
            v("ram.kill", mapOf("package" to "com.google.android.dialer")).error == "denied_package" &&
            v("ram.kill", mapOf("package" to "android")).error == "denied_package" &&
            v("ram.kill", mapOf("package" to "com.android.phone")).error == "denied_package"
    )
    // 6. ram.kill allows an ordinary package.
    check(
        "kill allows ordinary package",
        v("ram.kill", mapOf("package" to "com.example.game")).ok
    )
    // 7. dnd.apply mode validation.
    check(
        "dnd.apply mode",
        v("dnd.apply", mapOf("mode" to "on")).ok &&
            v("dnd.apply", mapOf("mode" to "off")).ok &&
            v("dnd.apply", mapOf("mode" to "silent")).error == "invalid_mode" &&
            v("dnd.apply").error == "invalid_mode"
    )
    // 8. net.mode validation.
    check(
        "net.mode validation",
        v("net.mode", mapOf("mode" to "4g")).ok &&
            v("net.mode", mapOf("mode" to "5g")).ok &&
            v("net.mode", mapOf("mode" to "auto")).ok &&
            v("net.mode", mapOf("mode" to "3g")).error == "invalid_mode"
    )
    // 9. dns.set needs a boolean.
    check(
        "dns.set needs boolean",
        v("dns.set", mapOf("enabled" to true)).ok &&
            v("dns.set", mapOf("enabled" to "yes")).error == "invalid_enabled"
    )
    // 10. blast.stop needs a code.
    check(
        "blast.stop needs code",
        v("blast.stop").error == "missing_code" &&
            v("blast.stop", code = "STOP 1234").ok
    )
    // 11. Writes blocked during a blast.
    check(
        "writes blocked during blast",
        v("ram.kill", mapOf("package" to "com.example.game"), blasting = true).error == "blocked_blast_active" &&
            v("dnd.apply", mapOf("mode" to "on"), blasting = true).error == "blocked_blast_active" &&
            v("net.flick", blasting = true).error == "blocked_blast_active" &&
            v("net.mode", mapOf("mode" to "4g"), blasting = true).error == "blocked_blast_active" &&
            v("dns.set", mapOf("enabled" to true), blasting = true).error == "blocked_blast_active"
    )
    // 12. Reads allowed during a blast.
    check(
        "reads allowed during blast",
        v("ram.snapshot", blasting = true).ok && v("blast.status", blasting = true).ok
    )
    // 13. blast.stop is the only write allowed during a blast.
    check(
        "blast.stop allowed during blast",
        v("blast.stop", code = "STOP 1234", blasting = true).ok
    )
    // 14. Every known command passes when not blasting.
    check(
        "all commands pass idle",
        v("ram.snapshot").ok && v("dnd.apply", mapOf("mode" to "off")).ok &&
            v("net.flick").ok && v("dns.set", mapOf("enabled" to false)).ok &&
            v("blast.status").ok
    )
    // 15. Unavailable executor refuses honestly, never fakes a result.
    val r = BridgeCommands.UnavailableExecutor.execute(CommandRequest("ram.snapshot", emptyMap(), null))
    check("unavailable executor honest", !r.ok && r.error == "module_not_available")
    // 16. Command names are trimmed.
    check("command trimmed", v("  ram.snapshot  ").ok)

    println("BridgeCommandsTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
