package com.splendor.blastbuster.bridge

import java.security.SecureRandom
import java.util.Base64

/**
 * Pure token + rate-limit logic for the bridge (DESIGN.md §2.3).
 *
 * Pure Kotlin (java.* only, no Android imports), JVM-testable. The
 * Android half — where the token is stored — lives in [BridgeAuth];
 * this file holds only the logic that must never depend on Android:
 * generating a token, comparing one in constant time, checking its
 * shape, and the failed-auth rate limit.
 */
object BridgeAuthCore {

    const val TOKEN_BYTES = 32

    /** 32 random bytes, Base64-URL, no padding. Never logged anywhere. */
    fun generateToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** A stored token must be exactly the shape [generateToken] produces. */
    fun isValidTokenFormat(token: String?): Boolean {
        if (token == null) return false
        if (token.length != 43) return false // 32 bytes -> 43 Base64-URL chars, no padding
        return token.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /**
     * Parse an `Authorization` header. Returns the token for
     * `Bearer <token>`, null for anything else (missing, wrong scheme,
     * empty token). Comparison with the stored token must go through
     * [constantTimeEquals], never `==` on a prefix.
     */
    fun bearerToken(header: String?): String? {
        if (header == null) return null
        val trimmed = header.trim()
        if (!trimmed.startsWith("Bearer ")) return null
        val token = trimmed.substring("Bearer ".length).trim()
        return if (token.isEmpty()) null else token
    }

    /** Constant-time comparison: no early exit on the first mismatch. */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    /**
     * Failed-auth rate limit (DESIGN.md §2.3): max 10 failed auths per
     * minute per IP, then that IP is ignored for 5 minutes. In-memory
     * only — a restart clears it, which is acceptable for a local server.
     * The clock is injectable so tests can drive time.
     */
    class RateLimiter(
        private val maxFailures: Int = 10,
        private val windowMs: Long = 60_000L,
        private val blockMs: Long = 5 * 60_000L,
        private val now: () -> Long = { System.currentTimeMillis() }
    ) {
        private data class Entry(var failures: MutableList<Long>, var blockedUntil: Long)
        private val entries = HashMap<String, Entry>()

        /** True while the IP is inside its 5-minute ignore window. */
        @Synchronized
        fun isBlocked(ip: String): Boolean {
            val entry = entries[ip] ?: return false
            return now() < entry.blockedUntil
        }

        /**
         * Record one failed auth for [ip]. Returns true if this failure
         * trips the block. A success ([recordSuccess]) clears the count.
         */
        @Synchronized
        fun recordFailure(ip: String): Boolean {
            val t = now()
            val entry = entries.getOrPut(ip) { Entry(ArrayList(), 0L) }
            if (t >= entry.blockedUntil) {
                entry.failures.removeAll { it < t - windowMs }
                entry.failures.add(t)
                if (entry.failures.size >= maxFailures) {
                    entry.blockedUntil = t + blockMs
                    entry.failures.clear()
                    return true
                }
            }
            return t < entry.blockedUntil
        }

        @Synchronized
        fun recordSuccess(ip: String) {
            entries.remove(ip)
        }
    }
}
