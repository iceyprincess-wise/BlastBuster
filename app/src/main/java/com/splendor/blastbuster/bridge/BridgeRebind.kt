package com.splendor.blastbuster.bridge

/**
 * Pure re-bind decision for the bridge server (v1.3 auto re-bind).
 *
 * Live failure in v1.2: the server bound once in `BridgeServer.start()`
 * (localhost + the tailnet address if present at that moment). When
 * Tailscale wasn't up at first start it bound localhost-only and never
 * re-bound when Tailscale came up — Muse then got an empty reply on
 * the tailnet address. The fix watches for network changes
 * (`BridgeModule`, `ConnectivityManager.NetworkCallback`) and restarts
 * the server when the tailnet address actually changes. This file
 * holds only the decision — no Android imports, JVM-testable.
 *
 * Rules:
 * - Rebind ONLY when the address actually changed. Same address,
 *   including both sides null (still no tailnet), means no rebind —
 *   a flapping network must not thrash the server.
 * - The bind set is always 127.0.0.1, plus the tailnet address when
 *   one exists. 0.0.0.0 is never bound; the port stays 8741 fixed.
 */
object BridgeRebind {

    const val LOCALHOST = "127.0.0.1"

    /** Tailscale addresses live in 100.64.0.0/10 (CGNAT range). */
    fun isTailnetAddress(address: String?): Boolean {
        if (address == null) return false
        val parts = address.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        if (parts[2].toIntOrNull() == null || parts[3].toIntOrNull() == null) return false
        return first == 100 && second in 64..127
    }

    /**
     * True only when the tailnet address actually changed: appearing
     * (null -> address), disappearing (address -> null), or changing
     * to a different address. Identical addresses — including both
     * null — return false.
     */
    fun shouldRebind(oldAddr: String?, newAddr: String?): Boolean = oldAddr != newAddr

    /** The addresses the server binds: localhost, plus tailnet if present. */
    fun bindAddresses(tailnetAddress: String?): List<String> =
        if (tailnetAddress != null) listOf(LOCALHOST, tailnetAddress) else listOf(LOCALHOST)
}
