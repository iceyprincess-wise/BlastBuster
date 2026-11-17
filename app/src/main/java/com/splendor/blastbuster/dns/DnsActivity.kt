package com.splendor.blastbuster.dns

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.EventLog

/**
 * DNS screen (DESIGN.md N23, §5.4, §6.3).
 *
 * Plain android widgets, no AndroidX, built programmatically like
 * MainActivity in v2. Toggle, upstream selector, blocked-today counter,
 * top-5 blocked domains, custom entries editor, Private-DNS warning,
 * VPN-slot conflict card.
 *
 * v1.3 additions (item 6):
 * - Private DNS is read LIVE from Settings.Global on every refresh
 *   (v1.2 only knew the mode at tunnel start), with plain-words
 *   guidance and the steps to turn it off.
 * - The VPN-slot conflict is detected live (active network with a VPN
 *   transport) and the screen lets the user CHOOSE pipe vs the other
 *   VPN — starting the pipe pauses the other VPN (and the Muse bridge
 *   with it), keeping it leaves the pipe off. The existing
 *   DNS_YIELDED_TO_VPN path (DnsVpnService.onRevoke) is verified wired
 *   to this card: onRevoke sets conflictWithVpn, this screen reads it.
 * - Private DNS 1.1.1.1 fallback: one-tap write of
 *   PRIVATE_DNS_MODE=hostname + PRIVATE_DNS_SPECIFIER=one.one.one.one
 *   when the WRITE_SECURE_SETTINGS grant is present; guided manual
 *   steps when it is not. The fallback encrypts DNS to Cloudflare but
 *   bypasses the pipe's blocklist — the screen says so. It is refused
 *   while another VPN is active (Tailscale's DNS does not speak DoT).
 * - DoH/DoT upstream in the pipe itself is NOT in this round
 *   (see DnsState / the v1.3 report): plain UDP forwarding stays,
 *   and the UI still says queries are unencrypted to the upstream.
 *
 * Honest copy (§5.4, shown verbatim under the title):
 * "The pipe can be faster than your carrier's DNS, and it blocks junk
 * domains before they load. It does not make the connection faster, and
 * the first query for a domain is slower. Queries are sent unencrypted
 * (plain DNS) to the upstream you choose."
 * Plus the VPN-slot conflict (§5.4 / D-08) and the no-push… no — the
 * bridge conflict note: "If you use Tailscale on this phone, only one
 * VPN can hold the slot at a time — the DNS pipe and Tailscale cannot
 * run together. The Muse bridge still works with the pipe off."
 *
 * While a blast is active this screen finishes immediately — companion
 * screens are unreachable during a blast (DESIGN.md §1.3 rule 4).
 *
 * NOTE (Agent C): the string literals below are `private const val`s at
 * the top of this file on purpose so they can be moved into
 * res/values/strings.xml in one pass. This agent must not edit
 * strings.xml (§4.7 ownership).
 */
class DnsActivity : Activity() {

    companion object {
        private const val VPN_REQUEST = 1001

        private const val TITLE = "DNS filter"
        private const val HONEST_LIMIT =
            "The pipe can be faster than your carrier's DNS, and it blocks " +
                "junk domains before they load. It does not make the connection " +
                "faster, and the first query for a domain is slower. Queries are " +
                "sent unencrypted (plain DNS) to the upstream you choose."
        private const val VPN_SLOT_NOTE =
            "If you use Tailscale on this phone, only one VPN can hold the slot " +
                "at a time — the DNS pipe and Tailscale cannot run together. " +
                "Starting the pipe pauses the other VPN (and the Muse bridge " +
                "with it) until it is started again. " +
                "The Muse bridge still works with the pipe off."
        private const val STATS_NOTE =
            "Counts and top blocked domains only — the pipe never keeps a full " +
                "query log."
        private const val FALLBACK_NOTE =
            "No pipe? Use Private DNS instead: setting it to 1.1.1.1 sends your " +
                "DNS encrypted to Cloudflare — but the blocklist above does NOT " +
                "apply then, because Private DNS bypasses the pipe. " +
                "One tap needs the ADB grant (WRITE_SECURE_SETTINGS); without it, " +
                "follow the manual steps."
        private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    }

    private val module = DnsModule()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var toggleButton: Button
    private lateinit var statusView: TextView
    private lateinit var upstreamGroup: RadioGroup
    private lateinit var statsView: TextView
    private lateinit var topBlockedView: TextView
    private lateinit var warningView: TextView
    private lateinit var privateDnsView: TextView
    private lateinit var usePipeButton: Button
    private lateinit var keepVpnButton: Button
    private lateinit var blockEdit: EditText
    private lateinit var allowEdit: EditText
    private lateinit var lieEdit: EditText

    private val refreshTick = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BlastGate.isBlasting(this)) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = TITLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = HONEST_LIMIT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = VPN_SLOT_NOTE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = STATS_NOTE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#9AA7B8"))
            setPadding(0, 0, 0, dp(12))
        })

        warningView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#F85149"))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(warningView)

        // VPN-slot choice (v1.3): only visible while another VPN holds
        // the slot. Starting the pipe revokes the other VPN.
        usePipeButton = Button(this).apply {
            text = "Use the DNS pipe instead (pauses the other VPN)"
            visibility = View.GONE
            setOnClickListener { onUsePipeInsteadClicked() }
        }
        root.addView(usePipeButton)
        keepVpnButton = Button(this).apply {
            text = "Keep the other VPN (open VPN settings)"
            visibility = View.GONE
            setOnClickListener { onKeepOtherVpnClicked() }
        }
        root.addView(keepVpnButton)

        // Live Private DNS state (v1.3): read from Settings.Global on
        // every refresh, not only at tunnel start.
        privateDnsView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(privateDnsView)

        toggleButton = Button(this).apply {
            setOnClickListener { onToggleClicked() }
        }
        root.addView(toggleButton)
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(statusView)

        root.addView(TextView(this).apply {
            text = "Upstream DNS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        upstreamGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val currentUpstream = Prefs.dnsUpstream(this)
        for (choice in Prefs.DNS_UPSTREAM_CHOICES) {
            upstreamGroup.addView(RadioButton(this).apply {
                text = Prefs.dnsUpstreamLabel(choice)
                isChecked = choice == currentUpstream
                tag = choice
            })
        }
        root.addView(upstreamGroup)
        root.addView(Button(this).apply {
            text = "Apply upstream"
            setOnClickListener { onApplyUpstreamClicked() }
        })

        root.addView(TextView(this).apply {
            text = "Today"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(2))
        })
        statsView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        root.addView(statsView)
        topBlockedView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(topBlockedView)

        root.addView(TextView(this).apply {
            text = "Your entries (comma-separated domains)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Block more (exact host, or *.example.com for all subdomains)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        blockEdit = EditText(this).apply { setText(Prefs.dnsUserBlockRaw(this@DnsActivity)) }
        root.addView(blockEdit)
        root.addView(TextView(this).apply {
            text = "Always allow (beats every block entry — your choice wins)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        allowEdit = EditText(this).apply { setText(Prefs.dnsUserAllowRaw(this@DnsActivity)) }
        root.addView(allowEdit)
        root.addView(TextView(this).apply {
            text = "Answer NXDOMAIN instead of 0.0.0.0 (\"lie\" — for domains that must look non-existent)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        lieEdit = EditText(this).apply { setText(Prefs.dnsUserLieRaw(this@DnsActivity)) }
        root.addView(lieEdit)
        root.addView(Button(this).apply {
            text = "Save entries"
            setOnClickListener { onSaveEntriesClicked() }
        })

        root.addView(TextView(this).apply {
            text = "Private DNS instead of the pipe"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = FALLBACK_NOTE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "Set Private DNS to 1.1.1.1"
            setOnClickListener { onPrivateDnsFallbackClicked() }
        })
        root.addView(Button(this).apply {
            text = "Turn Private DNS off"
            setOnClickListener { onPrivateDnsOffClicked() }
        })
        root.addView(Button(this).apply {
            text = "Open Private DNS settings"
            setOnClickListener { openPrivateDnsSettings() }
        })
    }

    override fun onResume() {
        super.onResume()
        if (BlastGate.isBlasting(this)) {
            finish()
            return
        }
        refreshAll()
        handler.postDelayed(refreshTick, 2000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTick)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) {
                Prefs.setDnsEnabled(this, true)
                startService(DnsVpnService.startIntent(this))
                statusView.text = "VPN permission granted — starting the pipe…"
            } else {
                statusView.text = "VPN permission denied — the pipe cannot start without it."
            }
            refreshAll()
        }
    }

    private fun refreshAll() {
        toggleButton.text = if (DnsVpnService.isRunning) "Stop DNS filter" else "Start DNS filter"
        statusView.text = when {
            DnsVpnService.isRunning ->
                "Running — upstream ${DnsVpnService.upstreamInUse}, " +
                    "${DnsVpnService.blocklistBlockedCount} domains blocked."
            DnsVpnService.lastError != null -> "Error: ${DnsVpnService.lastError}"
            else -> "Stopped — normal DNS in use."
        }
        refreshConflictAndPrivateDns()
        refreshStats()
    }

    /**
     * Live slot + Private DNS state (v1.3). Reads the real setting
     * every time — v1.2 only knew the Private DNS mode at tunnel
     * start (DnsVpnService.checkPrivateDns) and only showed the
     * conflict after a failed start or an onRevoke. The other-VPN
     * check is the active network carrying a VPN transport; our own
     * running tunnel is excluded (it is the VPN transport then).
     */
    private fun refreshConflictAndPrivateDns() {
        val mode = DnsState.parsePrivateDnsMode(readPrivateDnsMode())
        val specifier = readPrivateDnsSpecifier()
        val slotState = DnsState.resolveVpnSlotState(
            DnsVpnService.isRunning,
            isOtherVpnActive(),
            DnsVpnService.conflictWithVpn
        )
        warningView.text = when (slotState) {
            DnsState.VpnSlotState.PIPE_RUNNING,
            DnsState.VpnSlotState.SLOT_FREE -> ""
            DnsState.VpnSlotState.OTHER_VPN_ACTIVE,
            DnsState.VpnSlotState.YIELDED_SLOT_FREE -> DnsState.slotMessage(slotState)
        }
        val showChoice = slotState == DnsState.VpnSlotState.OTHER_VPN_ACTIVE
        usePipeButton.visibility = if (showChoice) View.VISIBLE else View.GONE
        keepVpnButton.visibility = if (showChoice) View.VISIBLE else View.GONE

        val bypasses = DnsState.bypassesPipe(mode)
        privateDnsView.setTextColor(
            Color.parseColor(if (bypasses) "#F85149" else "#3FB950")
        )
        privateDnsView.text = when {
            mode == DnsState.PrivateDnsMode.OFF ->
                "Private DNS is off — the pipe can filter DNS."
            DnsState.isFallbackHostnameActive(mode, specifier) ->
                "Private DNS is set to 1.1.1.1 — your DNS goes encrypted to " +
                    "Cloudflare, but the pipe's blocklist does NOT apply while " +
                    "Private DNS is on. Turn Private DNS off to let the pipe filter again."
            mode == DnsState.PrivateDnsMode.HOSTNAME ->
                "Private DNS is ON (provider hostname: ${specifier ?: "?"}) — the " +
                    "system sends DNS around this pipe, so the pipe filters nothing " +
                    "while it stays on. Turn it off below: Settings → Private DNS → Off."
            mode == DnsState.PrivateDnsMode.OPPORTUNISTIC ->
                "Private DNS is ON (Automatic) — the system sends DNS around this " +
                    "pipe, so the pipe filters nothing while it stays on. Turn it off " +
                    "below: Settings → Private DNS → Off."
            else ->
                "Private DNS mode is unknown (${readPrivateDnsMode() ?: "?"}) — the " +
                    "pipe may be bypassed. Check Settings → Private DNS."
        }
    }

    private fun readPrivateDnsMode(): String? = try {
        Settings.Global.getString(contentResolver, "private_dns_mode")
    } catch (_: Exception) {
        null
    }

    private fun readPrivateDnsSpecifier(): String? = try {
        Settings.Global.getString(contentResolver, "private_dns_specifier")
    } catch (_: Exception) {
        null
    }

    /** True when a VPN other than our own pipe holds the slot right now. */
    private fun isOtherVpnActive(): Boolean {
        if (DnsVpnService.isRunning) return false
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasSecureSettingsGrant(): Boolean =
        checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Write a Private DNS mode (+ hostname specifier) and verify the read-back. */
    private fun writePrivateDns(mode: String, specifier: String?): Boolean {
        return try {
            Settings.Global.putString(contentResolver, "private_dns_mode", mode)
            Settings.Global.putString(
                contentResolver, "private_dns_specifier", specifier ?: ""
            )
            Settings.Global.getString(contentResolver, "private_dns_mode") == mode
        } catch (_: Exception) {
            false
        }
    }

    /**
     * VPN-slot choice: start the pipe, knowing it pauses the other VPN.
     * Android gives the single VPN slot to the newest VpnService, so
     * this revokes Tailscale (and the Muse bridge with it) until
     * Tailscale is started again — the status line says so before the
     * start runs. Reuses the normal start path (consent + blast gate).
     */
    private fun onUsePipeInsteadClicked() {
        statusView.text =
            "Starting the pipe will pause the other VPN (and the Muse bridge with it) " +
                "until the other VPN is started again."
        onToggleClicked()
    }

    /** VPN-slot choice: keep the other VPN; the pipe stays off. */
    private fun onKeepOtherVpnClicked() {
        if (DnsVpnService.isRunning) {
            Prefs.setDnsEnabled(this, false)
            module.stopPipe(this)
        }
        val launch = packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
        if (launch != null) {
            try {
                startActivity(launch)
            } catch (_: Exception) {
                openVpnSettings()
            }
        } else {
            openVpnSettings()
        }
        refreshAll()
    }

    private fun openVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, "Open the Tailscale app to manage its VPN.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Private DNS 1.1.1.1 fallback (v1.3): set the system Private DNS
     * hostname to one.one.one.one. One tap needs WRITE_SECURE_SETTINGS;
     * without it the guided manual path opens instead of failing
     * silently. Refused while another VPN is active — Tailscale's
     * resolver does not speak DoT, so Android Private DNS cannot
     * connect while Tailscale's DNS is on.
     */
    private fun onPrivateDnsFallbackClicked() {
        val mode = DnsState.parsePrivateDnsMode(readPrivateDnsMode())
        if (DnsState.isFallbackHostnameActive(mode, readPrivateDnsSpecifier())) {
            Toast.makeText(this, "Private DNS is already set to 1.1.1.1.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isOtherVpnActive()) {
            statusView.text =
                "Another VPN is active — Private DNS cannot connect while its DNS is on. " +
                    "Turn the other VPN off first, or use the DNS pipe instead."
            return
        }
        if (!hasSecureSettingsGrant()) {
            statusView.text =
                "To set it by hand: open Private DNS settings → choose " +
                    "“Private DNS provider hostname” → type one.one.one.one → Save. " +
                    "(One tap needs the ADB grant WRITE_SECURE_SETTINGS.)"
            openPrivateDnsSettings()
            return
        }
        val ok = writePrivateDns("hostname", DnsState.PRIVATE_DNS_HOSTNAME_FALLBACK)
        if (ok) {
            EventLog.append(
                this, "dns", "DNS_PRIVATE_DNS_FALLBACK",
                "Private DNS set to one.one.one.one (1.1.1.1 fallback)."
            )
            Toast.makeText(this, "Private DNS set to 1.1.1.1.", Toast.LENGTH_SHORT).show()
        } else {
            statusView.text = "Could not write the Private DNS setting — the system refused it."
        }
        refreshAll()
    }

    /** Turn Private DNS off (same grant rule as the fallback). */
    private fun onPrivateDnsOffClicked() {
        if (DnsState.parsePrivateDnsMode(readPrivateDnsMode()) == DnsState.PrivateDnsMode.OFF) {
            Toast.makeText(this, "Private DNS is already off.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasSecureSettingsGrant()) {
            statusView.text =
                "To turn it off by hand: open Private DNS settings → tap Off. " +
                    "(One tap needs the ADB grant WRITE_SECURE_SETTINGS.)"
            openPrivateDnsSettings()
            return
        }
        val ok = writePrivateDns("off", null)
        if (ok) {
            EventLog.append(this, "dns", "DNS_PRIVATE_DNS_OFF", "Private DNS turned off.")
            Toast.makeText(this, "Private DNS is off — the pipe can filter DNS.", Toast.LENGTH_SHORT).show()
        } else {
            statusView.text = "Could not write the Private DNS setting — the system refused it."
        }
        refreshAll()
    }

    private fun refreshStats() {
        val stats = DnsVpnService.latestStats
        if (stats == null) {
            statsView.text = if (DnsVpnService.isRunning) {
                "No queries yet."
            } else {
                "Pipe stopped — no stats."
            }
            topBlockedView.text = ""
            // Still refresh the live state: the 2 s tick calls only
            // refreshStats, and the conflict / Private DNS card must
            // stay fresh even before the first query.
            toggleButton.text = if (DnsVpnService.isRunning) "Stop DNS filter" else "Start DNS filter"
            refreshConflictAndPrivateDns()
            return
        }
        statsView.text = "Queries: ${stats.queries}   Blocked: ${stats.blocked}   " +
            "Forwarded: ${stats.forwarded}   Failed: ${stats.forwardFailed}"
        topBlockedView.text = if (stats.topBlocked.isEmpty()) {
            "Nothing blocked yet today."
        } else {
            "Top blocked:\n" + stats.topBlocked.joinToString("\n") { (domain, count) ->
                "$domain — $count"
            }
        }
        // Keep the toggle/status fresh too (conflict / Private DNS can appear anytime).
        toggleButton.text = if (DnsVpnService.isRunning) "Stop DNS filter" else "Start DNS filter"
        refreshConflictAndPrivateDns()
    }

    private fun onToggleClicked() {
        if (DnsVpnService.isRunning) {
            Prefs.setDnsEnabled(this, false)
            module.stopPipe(this)
            refreshAll()
            return
        }
        if (BlastGate.isBlasting(this)) {
            Toast.makeText(this, "Blast active — the DNS pipe cannot start now.", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setDnsEnabled(this, true)
        val consent = VpnService.prepare(this)
        if (consent != null) {
            // First start: the system VPN consent dialog.
            startActivityForResult(consent, VPN_REQUEST)
        } else {
            val result = module.startPipe(this)
            statusView.text = when (result) {
                DnsModule.DnsStartResult.STARTED -> "Starting the pipe…"
                DnsModule.DnsStartResult.ALREADY_RUNNING -> "Pipe already running."
                DnsModule.DnsStartResult.BLOCKED_BLAST -> "Blast active — cannot start now."
                DnsModule.DnsStartResult.DISABLED -> "Pipe is disabled."
                DnsModule.DnsStartResult.NEEDS_CONSENT -> "VPN permission needed."
            }
            refreshAll()
        }
    }

    private fun onApplyUpstreamClicked() {
        val checkedId = upstreamGroup.checkedRadioButtonId
        if (checkedId == -1) {
            Toast.makeText(this, "Pick an upstream first.", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = upstreamGroup.findViewById<RadioButton>(checkedId)
        val choice = selected.tag as String
        Prefs.setDnsUpstream(this, choice)
        if (DnsVpnService.isRunning) {
            // Upstream is baked into the tunnel at build time — restart to apply.
            module.stopPipe(this)
            handler.postDelayed({ module.startPipe(this); refreshAll() }, 500)
            statusView.text = "Upstream set to ${Prefs.dnsUpstreamLabel(choice)} — restarting the pipe…"
        } else {
            statusView.text = "Upstream set to ${Prefs.dnsUpstreamLabel(choice)}."
        }
    }

    private fun onSaveEntriesClicked() {
        Prefs.setDnsUserBlockRaw(this, blockEdit.text.toString())
        Prefs.setDnsUserAllowRaw(this, allowEdit.text.toString())
        Prefs.setDnsUserLieRaw(this, lieEdit.text.toString())
        module.reloadBlocklist(this)
        Toast.makeText(this, "Entries saved.", Toast.LENGTH_SHORT).show()
        refreshAll()
    }

    private fun openPrivateDnsSettings() {
        // No public Settings constant is referenced by name here on
        // purpose: use the documented action string, fall back to the
        // main Settings screen when the device has no such page.
        val intent = Intent("android.settings.PRIVATE_DNS_SETTINGS")
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Settings: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
