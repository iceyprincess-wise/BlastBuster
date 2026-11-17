package com.splendor.blastbuster.bridge

/**
 * JVM tests for BridgeAuthCore (DESIGN.md §7.1 — auth token handling).
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

    // 1. Generated token is 43 chars and valid format.
    val token = BridgeAuthCore.generateToken()
    check(
        "generated token shape",
        token.length == 43 && BridgeAuthCore.isValidTokenFormat(token)
    )
    // 2. Two tokens differ.
    check("tokens differ", BridgeAuthCore.generateToken() != BridgeAuthCore.generateToken())
    // 3. Format validation rejects bad values.
    check(
        "format rejects bad tokens",
        !BridgeAuthCore.isValidTokenFormat(null) &&
            !BridgeAuthCore.isValidTokenFormat("") &&
            !BridgeAuthCore.isValidTokenFormat("short") &&
            !BridgeAuthCore.isValidTokenFormat(token + "x") &&
            !BridgeAuthCore.isValidTokenFormat(token.substring(0, 42) + "+")
    )
    // 4. Bearer header parsing.
    check(
        "bearer parsing",
        BridgeAuthCore.bearerToken("Bearer abc123") == "abc123" &&
            BridgeAuthCore.bearerToken("bearer abc123") == null &&
            BridgeAuthCore.bearerToken("Basic abc") == null &&
            BridgeAuthCore.bearerToken("Bearer ") == null &&
            BridgeAuthCore.bearerToken(null) == null &&
            BridgeAuthCore.bearerToken("  Bearer xyz  ") == "xyz"
    )
    // 5. Constant-time comparison.
    check(
        "constant-time equals",
        BridgeAuthCore.constantTimeEquals("abc", "abc") &&
            !BridgeAuthCore.constantTimeEquals("abc", "abd") &&
            !BridgeAuthCore.constantTimeEquals("abc", "abcd") &&
            !BridgeAuthCore.constantTimeEquals(null, "abc") &&
            !BridgeAuthCore.constantTimeEquals("abc", null)
    )
    // 6. Rate limit: 9 failures no block, 10th trips it.
    var now = 0L
    val limiter = BridgeAuthCore.RateLimiter(now = { now })
    var blockedAt = -1
    for (i in 1..10) {
        if (limiter.recordFailure("1.2.3.4")) blockedAt = i
    }
    check("10th failure trips block", blockedAt == 10 && limiter.isBlocked("1.2.3.4"))
    // 7. Blocked IP stays blocked inside the window.
    now = 60_000L
    check("still blocked in window", limiter.isBlocked("1.2.3.4"))
    // 8. Block expires after 5 minutes.
    now = 10 * 60_000L + 1
    check("block expires", !limiter.isBlocked("1.2.3.4"))
    // 9. A success clears the failure count.
    now = 0L
    val limiter2 = BridgeAuthCore.RateLimiter(now = { now })
    repeat(9) { limiter2.recordFailure("5.6.7.8") }
    limiter2.recordSuccess("5.6.7.8")
    repeat(9) { limiter2.recordFailure("5.6.7.8") }
    check("success resets count", !limiter2.isBlocked("5.6.7.8"))
    // 10. Failures older than the window do not count.
    now = 0L
    val limiter3 = BridgeAuthCore.RateLimiter(now = { now })
    repeat(9) { limiter3.recordFailure("9.9.9.9") }
    now = 61_000L // past the 60 s window
    check("stale failures expire", !limiter3.recordFailure("9.9.9.9") && !limiter3.isBlocked("9.9.9.9"))
    // 11. Different IPs are independent.
    now = 0L
    val limiter4 = BridgeAuthCore.RateLimiter(now = { now })
    repeat(10) { limiter4.recordFailure("10.0.0.1") }
    check(
        "IPs independent",
        limiter4.isBlocked("10.0.0.1") && !limiter4.isBlocked("10.0.0.2")
    )
    // 12. A single failure does not block.
    val limiter5 = BridgeAuthCore.RateLimiter(now = { now })
    check("single failure no block", !limiter5.recordFailure("1.1.1.1"))

    println("BridgeAuthCoreTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
