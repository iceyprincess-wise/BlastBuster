package com.splendor.blastbuster.bridge

/**
 * JVM tests for LogExportGate (v1.3 D-04 per-export confirm) + the
 * /v1/log route in BridgeHttp.
 * Standalone main() runner — no JUnit dependency in the offline sandbox.
 * Run via jvm-tests/run-tests-c.sh (integration agent adds this file).
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

    // 1. Deny with no grant at all.
    run {
        val gate = LogExportGate(now = { 0L })
        check("deny without grant", !gate.isAllowed() && !gate.tryConsume())
    }

    // 2. Allow after grant, then single-use: consume once, second use denied.
    run {
        var t = 0L
        val gate = LogExportGate(now = { t })
        gate.grant()
        check("allow after grant", gate.isAllowed())
        check("first consume succeeds", gate.tryConsume())
        check("denied after consume", !gate.isAllowed() && !gate.tryConsume())
    }

    // 3. Re-grant after a consume opens a fresh window.
    run {
        var t = 0L
        val gate = LogExportGate(now = { t })
        gate.grant()
        gate.tryConsume()
        gate.grant()
        check("re-grant after consume works", gate.isAllowed() && gate.tryConsume())
    }

    // 4. Expiry: allowed at 299,999 ms, dead at exactly 300,000 ms.
    run {
        var t = 0L
        val gate = LogExportGate(now = { t })
        gate.grant()
        t = 299_999L
        check("allowed just before expiry", gate.isAllowed())
        t = 300_000L
        check("denied at expiry", !gate.isAllowed() && !gate.tryConsume())
    }

    // 5. Grant after expiry opens a fresh window.
    run {
        var t = 0L
        val gate = LogExportGate(now = { t })
        gate.grant()
        t = 400_000L
        check("expired grant stays denied", !gate.isAllowed())
        gate.grant()
        check("grant after expiry works", gate.isAllowed() && gate.tryConsume())
    }

    // 6. A later grant resets the expiry clock.
    run {
        var t = 0L
        val gate = LogExportGate(now = { t })
        gate.grant()
        t = 200_000L
        gate.grant()
        t = 400_000L
        check("re-grant resets expiry (allowed)", gate.isAllowed())
        t = 500_000L
        check("re-grant resets expiry (denied at new deadline)", !gate.isAllowed())
    }

    // 7. Revoke closes the window without using it.
    run {
        var t = 0L
        val gate = LogExportGate(now = { t })
        gate.grant()
        gate.revoke()
        check("revoke closes the window", !gate.isAllowed() && !gate.tryConsume())
    }

    // 8. Default window is 5 minutes.
    check("default validity is 5 minutes", LogExportGate.DEFAULT_VALIDITY_MS == 5 * 60_000L)

    // 9. /v1/log route (BridgeHttp change): GET routes, POST is rejected.
    check(
        "route /v1/log",
        BridgeHttp.route("GET", "/v1/log") == BridgeHttp.Route.LOG &&
            BridgeHttp.route("POST", "/v1/log") == BridgeHttp.Route.METHOD_NOT_ALLOWED &&
            BridgeHttp.route("GET", "/v1/status") == BridgeHttp.Route.STATUS
    )

    println("BridgeLogExportTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
