package com.splendor.blastbuster.bridge

/**
 * JVM tests for BridgeRebind (v1.3 auto re-bind decision).
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

    // shouldRebind: ONLY an actual change re-binds.
    check("both null -> no rebind", !BridgeRebind.shouldRebind(null, null))
    check("same address -> no rebind", !BridgeRebind.shouldRebind("100.64.1.2", "100.64.1.2"))
    check("null -> address -> rebind", BridgeRebind.shouldRebind(null, "100.64.1.2"))
    check("address -> null -> rebind", BridgeRebind.shouldRebind("100.64.1.2", null))
    check("address -> different address -> rebind",
        BridgeRebind.shouldRebind("100.64.1.2", "100.64.9.9"))

    // isTailnetAddress: 100.64.0.0/10 only.
    check("100.64.0.1 is tailnet", BridgeRebind.isTailnetAddress("100.64.0.1"))
    check("100.64.0.0 boundary is tailnet", BridgeRebind.isTailnetAddress("100.64.0.0"))
    check("100.127.255.255 boundary is tailnet", BridgeRebind.isTailnetAddress("100.127.255.255"))
    check("100.128.0.1 is not tailnet", !BridgeRebind.isTailnetAddress("100.128.0.1"))
    check("100.63.255.255 is not tailnet", !BridgeRebind.isTailnetAddress("100.63.255.255"))
    check("192.168.1.1 is not tailnet", !BridgeRebind.isTailnetAddress("192.168.1.1"))
    check("127.0.0.1 is not tailnet", !BridgeRebind.isTailnetAddress("127.0.0.1"))
    check("null is not tailnet", !BridgeRebind.isTailnetAddress(null))
    check("garbage is not tailnet", !BridgeRebind.isTailnetAddress("not-an-ip"))
    check("3-part address is not tailnet", !BridgeRebind.isTailnetAddress("100.64.1"))
    check("5-part address is not tailnet", !BridgeRebind.isTailnetAddress("100.64.0.1.5"))

    // bindAddresses: localhost always, tailnet when present, never 0.0.0.0.
    check("no tailnet -> localhost only",
        BridgeRebind.bindAddresses(null) == listOf("127.0.0.1"))
    check("tailnet -> localhost + tailnet",
        BridgeRebind.bindAddresses("100.64.1.2") == listOf("127.0.0.1", "100.64.1.2"))
    check("0.0.0.0 is never bound",
        "0.0.0.0" !in BridgeRebind.bindAddresses("100.64.1.2") &&
            "0.0.0.0" !in BridgeRebind.bindAddresses(null))

    println("BridgeRebindTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
