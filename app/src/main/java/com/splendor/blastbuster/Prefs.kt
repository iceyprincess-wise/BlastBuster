package com.splendor.blastbuster

import android.content.Context
import com.splendor.blastbuster.net.NetMode
import com.splendor.blastbuster.net.NetModeValues
import com.splendor.blastbuster.net.SignalGrade

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

    const val DEFAULT_VOICE_PHRASE = "Blast Buster"

    fun voicePhrase(ctx: Context): String =
        (prefs(ctx).getString("voice_phrase", DEFAULT_VOICE_PHRASE)
            ?: DEFAULT_VOICE_PHRASE).trim().ifEmpty { DEFAULT_VOICE_PHRASE }

    fun setVoicePhrase(ctx: Context, v: String) =
        prefs(ctx).edit().putString("voice_phrase", v.trim()).apply()

    /**
     * Voice trigger is OFF by default: it listens continuously (battery)
     * and matches the phrase's WORDS, not the owner's voice — anyone saying
     * the phrase near the phone can trigger the blast.
     */
    fun voiceTriggerEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("voice_enabled", false)

    fun setVoiceTriggerEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("voice_enabled", v).apply()

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

    // ---------- v1.2 companion settings (additive; no v2 key changed) ----------

    /** Fixed bridge port (DESIGN.md §2.3). A custom port is a v1.3 option. */
    const val BRIDGE_PORT = 8741

    // --- RAM watch (§5.1) ---

    /** RAM watch is OFF by default: it scans every 60 s when enabled. */
    fun ramEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("ram_enabled", false)
    fun setRamEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("ram_enabled", v).apply()

    /** Confirm dialog on first kill per package per day. Default on. */
    fun ramKillConfirm(ctx: Context): Boolean =
        prefs(ctx).getBoolean("ram_kill_confirm", true)

    fun setRamKillConfirm(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("ram_kill_confirm", v).apply()

    /** PSS above which a growing background app is ROGUE. Default 400 MB. */
    fun ramRoguePssMb(ctx: Context): Long = prefs(ctx).getLong("ram_rogue_mb", 400L)
    fun setRamRoguePssMb(ctx: Context, v: Long) =
        prefs(ctx).edit().putLong("ram_rogue_mb", v).apply()

    /** PSS above which a background app is WATCH. Default 250 MB. */
    fun ramWatchPssMb(ctx: Context): Long = prefs(ctx).getLong("ram_watch_mb", 250L)
    fun setRamWatchPssMb(ctx: Context, v: Long) =
        prefs(ctx).edit().putLong("ram_watch_mb", v).apply()

    /** User-protected packages (comma-separated) — never killed. */
    fun ramProtectedRaw(ctx: Context): String =
        prefs(ctx).getString("ram_protected", "") ?: ""

    fun setRamProtectedRaw(ctx: Context, v: String) =
        prefs(ctx).edit().putString("ram_protected", v).apply()

    fun ramProtectedList(ctx: Context): List<String> =
        ramProtectedRaw(ctx).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // Kill history is stored as "pkg=epochMs;pkg=epochMs" under one key.
    private fun ramKillMap(ctx: Context): Map<String, Long> {
        val raw = prefs(ctx).getString("ram_kills", "") ?: ""
        val out = LinkedHashMap<String, Long>()
        for (part in raw.split(";")) {
            val kv = part.split("=")
            if (kv.size == 2) kv[1].toLongOrNull()?.let { out[kv[0]] = it }
        }
        return out
    }

    fun ramLastKillMs(ctx: Context, pkg: String): Long =
        ramKillMap(ctx)[pkg] ?: 0L

    fun ramWasKilledBefore(ctx: Context, pkg: String): Boolean =
        ramKillMap(ctx).containsKey(pkg)

    fun recordRamKill(ctx: Context, pkg: String, nowMs: Long) {
        val map = ramKillMap(ctx).toMutableMap()
        map[pkg] = nowMs
        val raw = map.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs(ctx).edit().putString("ram_kills", raw).apply()
        // A fresh kill has not reappeared yet.
        val reappeared = ramReappearedSet(ctx).toMutableSet()
        if (reappeared.remove(pkg)) {
            prefs(ctx).edit().putString("ram_reappeared", reappeared.joinToString(",")).apply()
        }
    }

    private fun ramReappearedSet(ctx: Context): Set<String> =
        (prefs(ctx).getString("ram_reappeared", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** True when [pkg] was seen running again after a kill. */
    fun ramReappearedAfterKill(ctx: Context, pkg: String): Boolean =
        ramReappearedSet(ctx).contains(pkg)

    fun markRamReappeared(ctx: Context, pkg: String) {
        val set = ramReappearedSet(ctx).toMutableSet()
        if (set.add(pkg)) {
            prefs(ctx).edit().putString("ram_reappeared", set.joinToString(",")).apply()
        }
    }

    // Kill-confirm memory: "pkg=epochDay;..." — one confirm per package per day.
    private fun ramKillConfirmedMap(ctx: Context): Map<String, Long> {
        val raw = prefs(ctx).getString("ram_kill_confirmed", "") ?: ""
        val out = LinkedHashMap<String, Long>()
        for (part in raw.split(";")) {
            val kv = part.split("=")
            if (kv.size == 2) kv[1].toLongOrNull()?.let { out[kv[0]] = it }
        }
        return out
    }

    fun ramKillConfirmedToday(ctx: Context, pkg: String): Boolean {
        val today = System.currentTimeMillis() / 86_400_000L
        return ramKillConfirmedMap(ctx)[pkg] == today
    }

    fun markRamKillConfirmedToday(ctx: Context, pkg: String) {
        val today = System.currentTimeMillis() / 86_400_000L
        val map = ramKillConfirmedMap(ctx).toMutableMap()
        map[pkg] = today
        val raw = map.entries.joinToString(";") { "${it.key}=${it.value}" }
        prefs(ctx).edit().putString("ram_kill_confirmed", raw).apply()
    }

    // --- DND enforcement (§5.2) ---

    /** DND enforcement is OFF by default: it needs a schedule + DND access. */
    fun dndEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("dnd_enabled", false)
    fun setDndEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("dnd_enabled", v).apply()

    /** Schedule JSON (DndSchedule format). Default: no rules. */
    fun dndScheduleJson(ctx: Context): String =
        prefs(ctx).getString("dnd_schedule", "[]") ?: "[]"

    fun setDndScheduleJson(ctx: Context, v: String) =
        prefs(ctx).edit().putString("dnd_schedule", v).apply()

    /** Master switch for re-applying on drift. Default on. */
    fun dndReapplyEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("dnd_reapply", true)

    fun setDndReapplyEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("dnd_reapply", v).apply()

    // --- Network tools (§5.3; module owned by Agent B, keys live here) ---

    /** Network watch is OFF by default. */
    fun netEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("net_enabled", false)
    fun setNetEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("net_enabled", v).apply()

    /** Airplane-flick cooldown in minutes. Default 5. */
    fun netFlickCooldownMin(ctx: Context): Int = prefs(ctx).getInt("net_flick_cooldown_min", 5)
    fun setNetFlickCooldownMin(ctx: Context, v: Int) =
        prefs(ctx).edit().putInt("net_flick_cooldown_min", v).apply()

    /** Preferred network mode per SIM, stored as "subId=mode;...". */
    fun netPreferredModeRaw(ctx: Context): String =
        prefs(ctx).getString("net_pref_mode", "") ?: ""

    fun setNetPreferredModeRaw(ctx: Context, v: String) =
        prefs(ctx).edit().putString("net_pref_mode", v).apply()

    fun netPreferredMode(ctx: Context, subId: Int): String {
        for (part in netPreferredModeRaw(ctx).split(";")) {
            val kv = part.split("=")
            if (kv.size == 2 && kv[0].toIntOrNull() == subId) return kv[1]
        }
        return "auto"
    }

    fun setNetPreferredMode(ctx: Context, subId: Int, mode: String) {
        val map = LinkedHashMap<String, String>()
        for (part in netPreferredModeRaw(ctx).split(";")) {
            val kv = part.split("=")
            if (kv.size == 2) map[kv[0]] = kv[1]
        }
        map[subId.toString()] = mode
        setNetPreferredModeRaw(
            ctx, map.entries.joinToString(";") { "${it.key}=${it.value}" }
        )
    }

    // --- Airplane flick + daily net summary (Agent B keys, folded in at merge) ---
    // Key names and defaults are exactly the NetPrefs shim's contract
    // (net_last_flick_at, net_mode_sim_<subId>, net_daily_*), so no data
    // migration or rename was needed when the shim was deleted.

    /** Cooldown between flicks: 5 minutes, fixed by the design. */
    const val FLICK_COOLDOWN_MS = 5L * 60L * 1000L

    /** How long the radio stays off during a flick: 8 seconds, fixed by the design. */
    const val FLICK_OFFLINE_MS = 8_000L

    fun lastFlickAt(ctx: Context): Long = prefs(ctx).getLong("net_last_flick_at", 0L)
    fun setLastFlickAt(ctx: Context, ts: Long) =
        prefs(ctx).edit().putLong("net_last_flick_at", ts).apply()

    /** Preferred network mode per subscription, stored per-SIM key. */
    fun preferredMode(ctx: Context, subscriptionId: Int): NetMode {
        val raw = prefs(ctx).getString("net_mode_sim_$subscriptionId", null)
        return NetModeValues.parse(raw) ?: NetMode.AUTO
    }

    fun setPreferredMode(ctx: Context, subscriptionId: Int, mode: NetMode) =
        prefs(ctx).edit().putString("net_mode_sim_$subscriptionId", NetModeValues.storeKey(mode)).apply()

    fun dailyDate(ctx: Context): String = prefs(ctx).getString("net_daily_date", "") ?: ""
    fun dailyDrops(ctx: Context): Int = prefs(ctx).getInt("net_daily_drops", 0)
    fun dailyWorstGrade(ctx: Context): String = prefs(ctx).getString("net_daily_worst", "") ?: ""

    /** Record one drop today; resets the counters when the day rolls over. */
    fun recordDrop(ctx: Context, today: String, worstNow: SignalGrade) {
        val p = prefs(ctx)
        val storedDate = p.getString("net_daily_date", "") ?: ""
        val editor = p.edit()
        if (storedDate != today) {
            editor.putString("net_daily_date", today)
            editor.putInt("net_daily_drops", 1)
            editor.putString("net_daily_worst", worstNow.name)
        } else {
            editor.putInt("net_daily_drops", p.getInt("net_daily_drops", 0) + 1)
            val prev = p.getString("net_daily_worst", "") ?: ""
            val prevGrade = runCatching { SignalGrade.valueOf(prev) }.getOrNull()
            if (prevGrade == null || worstNow.ordinal < prevGrade.ordinal) {
                editor.putString("net_daily_worst", worstNow.name)
            }
        }
        editor.apply()
    }

    // --- DNS pipe (§5.4; module owned by Agent B, keys live here) ---

    /** DNS pipe is OFF by default (needs the VPN consent dialog). */
    fun dnsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("dns_enabled", false)
    fun setDnsEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("dns_enabled", v).apply()

    /** Upstream DNS server. Default Cloudflare 1.1.1.1. */
    fun dnsUpstream(ctx: Context): String =
        prefs(ctx).getString("dns_upstream", "1.1.1.1") ?: "1.1.1.1"

    fun setDnsUpstream(ctx: Context, v: String) =
        prefs(ctx).edit().putString("dns_upstream", v.trim()).apply()

    /** User block/allow entries, one per line ("block example.com" / "allow example.com"). */
    fun dnsUserEntriesRaw(ctx: Context): String =
        prefs(ctx).getString("dns_user_entries", "") ?: ""

    fun setDnsUserEntriesRaw(ctx: Context, v: String) =
        prefs(ctx).edit().putString("dns_user_entries", v).apply()

    fun dnsUserEntriesList(ctx: Context): List<String> =
        dnsUserEntriesRaw(ctx).lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** When true, per-domain stats are not kept. Default false (stats on). */
    fun dnsStatsOptOut(ctx: Context): Boolean =
        prefs(ctx).getBoolean("dns_stats_opt_out", false)

    fun setDnsStatsOptOut(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("dns_stats_opt_out", v).apply()

    // --- DNS user entries + stats toggle (Agent B keys, folded in at merge) ---
    // Key names and defaults are exactly the NetPrefs shim's contract
    // (dns_user_block / dns_user_allow / dns_user_lie, dns_stats_enabled).

    /** User block entries: comma- or newline-separated domains. Empty by default. */
    fun dnsUserBlockRaw(ctx: Context): String = prefs(ctx).getString("dns_user_block", "") ?: ""
    fun setDnsUserBlockRaw(ctx: Context, v: String) =
        prefs(ctx).edit().putString("dns_user_block", v).apply()

    /** User allow entries: these beat every block entry. Empty by default. */
    fun dnsUserAllowRaw(ctx: Context): String = prefs(ctx).getString("dns_user_allow", "") ?: ""
    fun setDnsUserAllowRaw(ctx: Context, v: String) =
        prefs(ctx).edit().putString("dns_user_allow", v).apply()

    /** User "lie" entries: blocked domains that answer NXDOMAIN instead of 0.0.0.0. */
    fun dnsUserLieRaw(ctx: Context): String = prefs(ctx).getString("dns_user_lie", "") ?: ""
    fun setDnsUserLieRaw(ctx: Context, v: String) =
        prefs(ctx).edit().putString("dns_user_lie", v).apply()

    /** Split a raw entries field on commas/newlines; blank pieces dropped. */
    fun splitEntries(raw: String): List<String> =
        raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Upstream choices offered in the UI. "system" means: use the DNS
     * server of the currently active link (read at start time), falling
     * back to 1.1.1.1 when none is known.
     */
    val DNS_UPSTREAM_CHOICES = listOf("1.1.1.1", "9.9.9.9", "8.8.8.8", "system")

    fun dnsUpstreamLabel(ipOrSystem: String): String = when (ipOrSystem) {
        "1.1.1.1" -> "Cloudflare (1.1.1.1)"
        "9.9.9.9" -> "Quad9 (9.9.9.9)"
        "8.8.8.8" -> "Google (8.8.8.8)"
        "system" -> "System default"
        else -> ipOrSystem
    }

    /**
     * Stats toggle. Default ON — but the stats are counts + top blocked
     * domains only, never a full query log. Turning this off stops even
     * the counting; filtering still works.
     */
    fun dnsStatsEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("dns_stats_enabled", true)
    fun setDnsStatsEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("dns_stats_enabled", v).apply()

    // --- Muse bridge (§2.3; module owned by Agent C, keys live here) ---

    /** Bridge is OFF by default (needs Tailscale + the token). */
    fun bridgeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean("bridge_enabled", false)

    fun setBridgeEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("bridge_enabled", v).apply()

    /**
     * Bridge token (32 random bytes, Base64-URL). Generated once by
     * BridgeAuth (Agent C) at first enable; empty until then. Never
     * logged, never put in EventLog.
     */
    fun bridgeToken(ctx: Context): String =
        prefs(ctx).getString("bridge_token", "") ?: ""

    fun setBridgeToken(ctx: Context, v: String) =
        prefs(ctx).edit().putString("bridge_token", v).apply()

    /** True when any companion module is enabled (BootReceiver / service start). */
    fun anyCompanionEnabled(ctx: Context): Boolean =
        ramEnabled(ctx) || dndEnabled(ctx) || netEnabled(ctx) ||
            dnsEnabled(ctx) || bridgeEnabled(ctx)
}
