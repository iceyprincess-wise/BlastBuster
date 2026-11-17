package com.splendor.blastbuster.net

/**
 * Network-mode lock values (DESIGN.md §5.3). Pure Kotlin — no Android
 * imports — so the mapping is JVM-testable.
 *
 * The integer values are the AOSP `RILConstants` preferred-network-mode
 * values written to `Settings.Global.PREFERRED_NETWORK_MODE` (and the
 * per-slot `preferred_network_mode1/2` keys). The LTE values (11, 9, 2)
 * are long-stable; the NR values (21, 23) come from the same table but
 * MUST be verified on the Redmi 15C (design test D-11) — HyperOS may
 * ignore the write entirely, in which case NetTools reports
 * `write_ok_but_not_applied` instead of claiming success. A wrong value
 * here is caught by the read-back + live-network-type verify step, never
 * silently trusted.
 *
 * Nothing here is personal: the modes are generic (Auto / 4G / 5G / 3G),
 * no carrier is named, no SIM is assumed.
 */
enum class NetMode(val displayName: String) {
    /** 5G/4G/3G auto — the normal resting state. */
    AUTO("Auto (5G/4G/3G)"),

    /** LTE only. */
    LTE_ONLY("4G only"),

    /** NR only — only where the radio supports it. */
    NR_ONLY("5G only"),

    /** UMTS only. */
    UMTS_ONLY("3G only")
}

object NetModeValues {

    // AOSP RILConstants preferred-network-mode values.
    // NETWORK_MODE_WCDMA_ONLY = 2, NETWORK_MODE_LTE_GSM_WCDMA = 9,
    // NETWORK_MODE_LTE_ONLY = 11, NETWORK_MODE_NR_ONLY = 21,
    // NETWORK_MODE_NR_LTE_GSM_WCDMA = 23.
    fun settingValue(mode: NetMode): Int = when (mode) {
        NetMode.AUTO -> 23
        NetMode.LTE_ONLY -> 11
        NetMode.NR_ONLY -> 21
        NetMode.UMTS_ONLY -> 2
    }

    /** Reverse map for the read-back verify step. Null = unknown value. */
    fun fromSettingValue(value: Int): NetMode? = when (value) {
        23 -> NetMode.AUTO
        11 -> NetMode.LTE_ONLY
        21 -> NetMode.NR_ONLY
        2 -> NetMode.UMTS_ONLY
        else -> null
    }

    /**
     * Parse a mode name as used by the bridge command `net.mode`
     * (DESIGN.md §2.3: `4g` / `5g` / `auto`) and by stored prefs.
     * Accepts the short forms and the enum names. Null = not a mode.
     */
    fun parse(name: String?): NetMode? = when (name?.trim()?.lowercase()) {
        "auto" -> NetMode.AUTO
        "4g", "lte", "lte_only", "lte-only" -> NetMode.LTE_ONLY
        "5g", "nr", "nr_only", "nr-only" -> NetMode.NR_ONLY
        "3g", "umts", "umts_only", "umts-only" -> NetMode.UMTS_ONLY
        else -> null
    }

    /**
     * The live network type (TelephonyManager NETWORK_TYPE_*) a mode
     * should produce, for the verify-after-write step.
     * Returns null for AUTO (any real type is acceptable).
     * NETWORK_TYPE_UMTS = 3, NETWORK_TYPE_LTE = 13, NETWORK_TYPE_NR = 20.
     */
    fun expectedNetworkType(mode: NetMode): Int? = when (mode) {
        NetMode.AUTO -> null
        NetMode.LTE_ONLY -> 13
        NetMode.NR_ONLY -> 20
        NetMode.UMTS_ONLY -> 3
    }

    /** Short stable key stored in prefs per subscription. */
    fun storeKey(mode: NetMode): String = when (mode) {
        NetMode.AUTO -> "auto"
        NetMode.LTE_ONLY -> "4g"
        NetMode.NR_ONLY -> "5g"
        NetMode.UMTS_ONLY -> "3g"
    }
}
