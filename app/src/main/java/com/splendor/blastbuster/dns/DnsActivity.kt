package com.splendor.blastbuster.dns

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
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

/**
 * DNS screen (DESIGN.md N23, §5.4, §6.3).
 *
 * Plain android widgets, no AndroidX, built programmatically like
 * MainActivity in v2. Toggle, upstream selector, blocked-today counter,
 * top-5 blocked domains, custom entries editor, Private-DNS warning,
 * VPN-slot conflict card.
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
                "The Muse bridge still works with the pipe off."
        private const val STATS_NOTE =
            "Counts and top blocked domains only — the pipe never keeps a full " +
                "query log."
        private const val PRIVATE_DNS_WARN =
            "Private DNS is ON — the system bypasses this pipe until Private " +
                "DNS is turned off. Tap below to open the setting."
        private const val CONFLICT_WARN =
            "DNS pipe paused — another VPN is active. Stop the other VPN, " +
                "then turn the pipe back on."
    }

    private val module = DnsModule()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var toggleButton: Button
    private lateinit var statusView: TextView
    private lateinit var upstreamGroup: RadioGroup
    private lateinit var statsView: TextView
    private lateinit var topBlockedView: TextView
    private lateinit var warningView: TextView
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
        warningView.text = when {
            DnsVpnService.conflictWithVpn -> CONFLICT_WARN
            DnsVpnService.privateDnsOn -> PRIVATE_DNS_WARN
            else -> ""
        }
        refreshStats()
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
        // Keep the toggle/status fresh too (conflict can appear anytime).
        toggleButton.text = if (DnsVpnService.isRunning) "Stop DNS filter" else "Start DNS filter"
        if (DnsVpnService.conflictWithVpn) warningView.text = CONFLICT_WARN
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
