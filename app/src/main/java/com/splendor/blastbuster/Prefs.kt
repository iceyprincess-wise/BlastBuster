package com.splendor.blastbuster

import android.content.Context

/**
 * Blast Buster settings, kept in one SharedPreferences file.
 *
 * Trigger/stop codes are matched against the SMS body after trimming, case
 * insensitive. A trusted-number list is optional: when it is empty, ANY
 * sender who knows the code can trigger the blast (the code itself is the
 * secret); when it is non-empty, only those numbers may trigger it.
 */
object Prefs {
    private const val NAME = "blast_buster"
    const val DEFAULT_TRIGGER = "BLAST 1234"
    const val DEFAULT_STOP = "STOP 1234"

    const val KEY_BLASTING = "blasting"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isArmed(ctx: Context): Boolean = prefs(ctx).getBoolean("armed", true)
    fun setArmed(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("armed", v).apply()

    fun triggerCode(ctx: Context): String =
        (prefs(ctx).getString("trigger", DEFAULT_TRIGGER) ?: DEFAULT_TRIGGER).trim()
            .ifEmpty { DEFAULT_TRIGGER }

    fun stopCode(ctx: Context): String =
        (prefs(ctx).getString("stop", DEFAULT_STOP) ?: DEFAULT_STOP).trim()
            .ifEmpty { DEFAULT_STOP }

    fun setTriggerCode(ctx: Context, v: String) =
        prefs(ctx).edit().putString("trigger", v.trim()).apply()

    fun setStopCode(ctx: Context, v: String) =
        prefs(ctx).edit().putString("stop", v.trim()).apply()

    fun trustedNumbersRaw(ctx: Context): String =
        prefs(ctx).getString("trusted", "") ?: ""

    fun setTrustedNumbersRaw(ctx: Context, v: String) =
        prefs(ctx).edit().putString("trusted", v).apply()

    fun trustedNumbers(ctx: Context): List<String> =
        trustedNumbersRaw(ctx).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun isBlasting(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BLASTING, false)
    fun setBlasting(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_BLASTING, v).apply()

    /** True when [sender] is allowed to trigger. Empty list = anyone with the code. */
    fun isTrusted(ctx: Context, sender: String?): Boolean {
        val list = trustedNumbers(ctx)
        if (list.isEmpty()) return true
        val digits = sender?.filter { it.isDigit() } ?: return false
        if (digits.isEmpty()) return false
        return list.any { entry ->
            val entryDigits = entry.filter { it.isDigit() }
            entryDigits.isNotEmpty() &&
                (digits == entryDigits || digits.endsWith(entryDigits) || entryDigits.endsWith(digits))
        }
    }

    /** True when [body] is the code, or starts with the code followed by space/end. */
    fun matchesCode(body: String?, code: String): Boolean {
        val b = body?.trim() ?: return false
        val c = code.trim()
        if (c.isEmpty()) return false
        return b.equals(c, ignoreCase = true) ||
            b.startsWith(c, ignoreCase = true) &&
            b.length > c.length && b[c.length] == ' '
    }
}
