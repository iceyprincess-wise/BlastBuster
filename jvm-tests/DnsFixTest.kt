import com.splendor.blastbuster.dns.DnsState
import com.splendor.blastbuster.dns.DnsState.PrivateDnsMode
import com.splendor.blastbuster.dns.DnsState.VpnSlotState

// JVM unit tests for the v1.3 DNS fixes (item 6): Private DNS mode
// mapping, the VPN-slot conflict state machine, and the 1.1.1.1
// Private DNS fallback detection.
// Standalone main() runner — no JUnit dependency (see run-tests.sh).

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
        println("  PASS: $name")
    } else {
        failed++
        println("  FAIL: $name")
    }
}

fun main() {
    println("DnsFixTest")

    // --- Private DNS mode mapping (Settings.Global PRIVATE_DNS_MODE) ---
    check("null -> OFF", DnsState.parsePrivateDnsMode(null) == PrivateDnsMode.OFF)
    check("empty -> OFF", DnsState.parsePrivateDnsMode("") == PrivateDnsMode.OFF)
    check("off -> OFF", DnsState.parsePrivateDnsMode("off") == PrivateDnsMode.OFF)
    check("OFF uppercase -> OFF", DnsState.parsePrivateDnsMode("OFF") == PrivateDnsMode.OFF)
    check("off with spaces -> OFF", DnsState.parsePrivateDnsMode("  off ") == PrivateDnsMode.OFF)
    check("opportunistic -> OPPORTUNISTIC",
        DnsState.parsePrivateDnsMode("opportunistic") == PrivateDnsMode.OPPORTUNISTIC)
    check("hostname -> HOSTNAME",
        DnsState.parsePrivateDnsMode("hostname") == PrivateDnsMode.HOSTNAME)
    check("bogus value -> UNKNOWN, never silently OFF",
        DnsState.parsePrivateDnsMode("weird-mode") == PrivateDnsMode.UNKNOWN)

    // --- bypass rule: only OFF leaves the pipe effective ---
    check("OFF does not bypass", !DnsState.bypassesPipe(PrivateDnsMode.OFF))
    check("OPPORTUNISTIC bypasses", DnsState.bypassesPipe(PrivateDnsMode.OPPORTUNISTIC))
    check("HOSTNAME bypasses", DnsState.bypassesPipe(PrivateDnsMode.HOSTNAME))
    check("UNKNOWN bypasses (cannot prove it is off)",
        DnsState.bypassesPipe(PrivateDnsMode.UNKNOWN))

    // --- 1.1.1.1 fallback detection ---
    check("fallback hostname active",
        DnsState.isFallbackHostnameActive(PrivateDnsMode.HOSTNAME, "one.one.one.one"))
    check("fallback hostname case-insensitive",
        DnsState.isFallbackHostnameActive(PrivateDnsMode.HOSTNAME, "One.One.One.One"))
    check("other hostname is not the fallback",
        !DnsState.isFallbackHostnameActive(PrivateDnsMode.HOSTNAME, "dns.google"))
    check("fallback needs HOSTNAME mode",
        !DnsState.isFallbackHostnameActive(PrivateDnsMode.OFF, "one.one.one.one"))
    check("fallback needs a specifier",
        !DnsState.isFallbackHostnameActive(PrivateDnsMode.HOSTNAME, null))
    check("fallback hostname literal is one.one.one.one",
        DnsState.PRIVATE_DNS_HOSTNAME_FALLBACK == "one.one.one.one")

    // --- VPN-slot conflict state machine (DESIGN.md §8 D-08) ---
    check("pipe running -> PIPE_RUNNING",
        DnsState.resolveVpnSlotState(pipeRunning = true, otherVpnActive = false, yielded = false) ==
            VpnSlotState.PIPE_RUNNING)
    check("pipe running beats a stale yield flag",
        DnsState.resolveVpnSlotState(pipeRunning = true, otherVpnActive = false, yielded = true) ==
            VpnSlotState.PIPE_RUNNING)
    check("nothing active -> SLOT_FREE",
        DnsState.resolveVpnSlotState(pipeRunning = false, otherVpnActive = false, yielded = false) ==
            VpnSlotState.SLOT_FREE)
    check("other VPN active -> OTHER_VPN_ACTIVE",
        DnsState.resolveVpnSlotState(pipeRunning = false, otherVpnActive = true, yielded = false) ==
            VpnSlotState.OTHER_VPN_ACTIVE)
    check("other VPN active beats a stale yield flag",
        DnsState.resolveVpnSlotState(pipeRunning = false, otherVpnActive = true, yielded = true) ==
            VpnSlotState.OTHER_VPN_ACTIVE)
    check("yielded but no VPN active now -> YIELDED_SLOT_FREE (slot freed up)",
        DnsState.resolveVpnSlotState(pipeRunning = false, otherVpnActive = false, yielded = true) ==
            VpnSlotState.YIELDED_SLOT_FREE)

    // --- slot messages: the choice and its bridge consequence are stated ---
    val conflictMsg = DnsState.slotMessage(VpnSlotState.OTHER_VPN_ACTIVE)
    check("conflict message names the choice (pipe vs other VPN)",
        conflictMsg.contains("Choose one", ignoreCase = true))
    check("conflict message states the bridge consequence",
        conflictMsg.contains("bridge", ignoreCase = true))
    val yieldedMsg = DnsState.slotMessage(VpnSlotState.YIELDED_SLOT_FREE)
    check("yielded-free message invites a restart",
        yieldedMsg.contains("tap Start", ignoreCase = true))
    check("slot message for PIPE_RUNNING does not claim a conflict",
        !DnsState.slotMessage(VpnSlotState.PIPE_RUNNING).contains("cannot run"))

    // --- pipe start allowed in every non-running state ---
    check("start allowed when slot free", DnsState.slotAllowsPipeStart(VpnSlotState.SLOT_FREE))
    check("start allowed when other VPN active (takes the slot)",
        DnsState.slotAllowsPipeStart(VpnSlotState.OTHER_VPN_ACTIVE))
    check("start allowed when yielded and slot free",
        DnsState.slotAllowsPipeStart(VpnSlotState.YIELDED_SLOT_FREE))
    check("start not offered when pipe already running",
        !DnsState.slotAllowsPipeStart(VpnSlotState.PIPE_RUNNING))

    println("RESULT DnsFixTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
