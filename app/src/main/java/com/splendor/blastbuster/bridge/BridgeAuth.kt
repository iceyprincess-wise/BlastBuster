package com.splendor.blastbuster.bridge

import android.content.Context
import com.splendor.blastbuster.Prefs

/**
 * Bridge token storage + verification (DESIGN.md §2.3).
 *
 * Token = 32 random bytes, Base64-URL, generated once at first enable,
 * stored in a private SharedPreferences file, shown in the Bridge
 * screen behind a "show" tap, with Regenerate and Copy buttons.
 * No token in logs, no token in EventLog, no token in screenshots
 * (the Bridge screen shows it only after a tap, and never logs it).
 *
 * MERGED (v1.2 merge): the enabled flag and token now live in
 * [Prefs] ("blast_buster" file, keys `bridge_enabled` /
 * `bridge_token`) — one settings home for the whole app, so
 * CompanionService's `anyCompanionEnabled` sees the bridge toggle
 * and BootReceiver can restart the companion for bridge-only users.
 * Only `last_poll_at_ms` stays in this file's private prefs
 * ("blast_buster_bridge"), because Prefs has no key for it. The token
 * format and verification logic live in [BridgeAuthCore] and do not
 * change either way.
 */
object BridgeAuth {

    private const val PREFS_NAME = "blast_buster_bridge"
    private const val KEY_LAST_POLL_AT = "last_poll_at_ms"

    private val rateLimiter = BridgeAuthCore.RateLimiter()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the owner has switched the bridge on in the app. */
    fun isEnabled(ctx: Context): Boolean = Prefs.bridgeEnabled(ctx)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        Prefs.setBridgeEnabled(ctx, enabled)
    }

    /**
     * The stored token, generating and storing one on first use.
     * A stored value with the wrong shape (e.g. from a damaged prefs
     * file) is discarded and replaced — a bad token must never be
     * served as if it were valid.
     */
    fun getOrCreateToken(ctx: Context): String {
        val stored = Prefs.bridgeToken(ctx).ifEmpty { null }
        if (BridgeAuthCore.isValidTokenFormat(stored)) return stored!!
        val fresh = BridgeAuthCore.generateToken()
        Prefs.setBridgeToken(ctx, fresh)
        return fresh
    }

    /** Replace the token with a fresh random one. The old token stops working. */
    fun regenerateToken(ctx: Context): String {
        val fresh = BridgeAuthCore.generateToken()
        Prefs.setBridgeToken(ctx, fresh)
        return fresh
    }

    /**
     * Verify an `Authorization` header against the stored token.
     * Returns false for a missing/malformed header and for a wrong
     * token. Comparison is constant-time; the token value is never
     * written to any log.
     */
    fun verify(ctx: Context, authorizationHeader: String?): Boolean {
        val presented = BridgeAuthCore.bearerToken(authorizationHeader) ?: return false
        val stored = Prefs.bridgeToken(ctx).ifEmpty { null }
        return BridgeAuthCore.constantTimeEquals(presented, stored)
    }

    /** Rate limit: true while this IP is inside its 5-minute ignore window. */
    fun isIpBlocked(ip: String): Boolean = rateLimiter.isBlocked(ip)

    /** Record one failed auth; returns true if this failure trips the block. */
    fun recordFailedAuth(ip: String): Boolean = rateLimiter.recordFailure(ip)

    fun recordSuccessfulAuth(ip: String) = rateLimiter.recordSuccess(ip)

    /** Epoch ms of the last authenticated poll (0 = never). Shown in-app. */
    fun getLastPollAt(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_POLL_AT, 0L)

    fun setLastPollAt(ctx: Context, atMs: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_POLL_AT, atMs).apply()
    }
}
