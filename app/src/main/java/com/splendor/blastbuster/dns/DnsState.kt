package com.splendor.blastbuster.dns

/**
 * Pure DNS state logic (v1.3 item 6). Android-free, JVM-tested
 * (DnsFixTest): Private DNS mode mapping and the VPN-slot conflict
 * state machine.
 *
 * Facts behind this file:
 * - Android has ONE VPN slot. The DNS pipe (a VpnService) and a real
 *   VPN (including Tailscale on the phone) cannot hold it at the same
 *   time. When the other side takes the slot the system revokes ours
 *   (DnsVpnService.onRevoke) and vice versa — starting the pipe
 *   revokes Tailscale, so choosing is real and the UI must say so.
 * - Private DNS (Settings.Global PRIVATE_DNS_MODE) bypasses the pipe
 *   even while the pipe runs: with mode "opportunistic" or "hostname"
 *   the system sends DNS around the tunnel, so the pipe filters
 *   nothing. Only "off" leaves the pipe effective.
 * - The bridge keeps working either way: it needs Tailscale, not the
 *   pipe — but starting the pipe while Tailscale holds the slot
 *   pauses Tailscale, and with it the bridge, until Tailscale is
 *   back. That consequence is stated in the DNS screen, not hidden.
 */
object DnsState {

    /** Private DNS provider hostname used by the 1.1.1.1 fallback option. */
    const val PRIVATE_DNS_HOSTNAME_FALLBACK = "one.one.one.one"

    /** The values Settings.Global PRIVATE_DNS_MODE can hold. */
    enum class PrivateDnsMode {
        /** Private DNS off — the pipe can filter DNS. */
        OFF,

        /** "Automatic": the system tries DoT when the network offers it. */
        OPPORTUNISTIC,

        /** A named DoT provider hostname is set. */
        HOSTNAME,

        /** Unset, empty, or a value this build does not recognise. */
        UNKNOWN
    }

    /**
     * Map the raw PRIVATE_DNS_MODE string to a mode. Null / empty /
     * "off" all mean OFF (an unset setting behaves as off); anything
     * unrecognised is UNKNOWN, never silently treated as off.
     */
    fun parsePrivateDnsMode(raw: String?): PrivateDnsMode = when (raw?.trim()?.lowercase()) {
        null, "", "off" -> PrivateDnsMode.OFF
        "opportunistic" -> PrivateDnsMode.OPPORTUNISTIC
        "hostname" -> PrivateDnsMode.HOSTNAME
        else -> PrivateDnsMode.UNKNOWN
    }

    /** True when this mode makes the system bypass the DNS pipe. */
    fun bypassesPipe(mode: PrivateDnsMode): Boolean = mode != PrivateDnsMode.OFF

    /** True when the fallback hostname is the active Private DNS provider. */
    fun isFallbackHostnameActive(mode: PrivateDnsMode, specifier: String?): Boolean =
        mode == PrivateDnsMode.HOSTNAME &&
            specifier?.trim()?.lowercase() == PRIVATE_DNS_HOSTNAME_FALLBACK

    // ---------- VPN-slot conflict state machine ----------

    /** Who holds the single VPN slot, from the pipe's point of view. */
    enum class VpnSlotState {
        /** The pipe is up and holds the slot. */
        PIPE_RUNNING,

        /** Nothing holds the slot — the pipe can start. */
        SLOT_FREE,

        /** Another VPN (e.g. Tailscale) holds the slot right now. */
        OTHER_VPN_ACTIVE,

        /**
         * The pipe yielded to another VPN earlier (DNS_YIELDED_TO_VPN),
         * but no VPN is active now — the slot is free again and the
         * pipe can be restarted.
         */
        YIELDED_SLOT_FREE
    }

    /**
     * Resolve the slot state from the three live facts. Priority:
     * a running pipe wins (it holds the slot by definition); a live
     * other VPN beats a stale yield flag; a yield with no VPN active
     * means the slot freed up again.
     */
    fun resolveVpnSlotState(
        pipeRunning: Boolean,
        otherVpnActive: Boolean,
        yielded: Boolean
    ): VpnSlotState = when {
        pipeRunning -> VpnSlotState.PIPE_RUNNING
        otherVpnActive -> VpnSlotState.OTHER_VPN_ACTIVE
        yielded -> VpnSlotState.YIELDED_SLOT_FREE
        else -> VpnSlotState.SLOT_FREE
    }

    /** Plain-words line for the DNS screen for each slot state. */
    fun slotMessage(state: VpnSlotState): String = when (state) {
        VpnSlotState.PIPE_RUNNING ->
            "DNS pipe is running and holds the VPN slot."
        VpnSlotState.SLOT_FREE ->
            "VPN slot is free — the DNS pipe can start."
        VpnSlotState.OTHER_VPN_ACTIVE ->
            "Another VPN is active (for example Tailscale) and holds the " +
                "VPN slot, so the DNS pipe cannot run. Choose one: start " +
                "the pipe (the other VPN pauses), or keep the other VPN " +
                "(the pipe stays off). The Muse bridge needs Tailscale, " +
                "not the pipe — it keeps working with the pipe off, but " +
                "starting the pipe pauses Tailscale and the bridge with it."
        VpnSlotState.YIELDED_SLOT_FREE ->
            "The DNS pipe gave the VPN slot to another VPN earlier, but " +
                "no VPN is active now — tap Start to run the pipe again."
    }

    /** True when tapping Start can (try to) take the slot. */
    fun slotAllowsPipeStart(state: VpnSlotState): Boolean =
        state != VpnSlotState.PIPE_RUNNING
}
