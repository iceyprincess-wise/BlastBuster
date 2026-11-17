package com.splendor.blastbuster.bridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.CompanionService
import com.splendor.blastbuster.companion.EventLog

/**
 * Bridge screen (DESIGN.md §2.3, §5.5, §6.3).
 *
 * Enable toggle, tailnet address + port, token show / copy /
 * regenerate, the honest "how Muse reaches this phone" explainer
 * (polling, no push), last-poll time, recent bridge events.
 *
 * The token is shown only after a "Show token" tap, and is never
 * written to any log. Regenerating invalidates the old token at once
 * — Muse must be given the new one before it can poll again.
 *
 * MERGED (v1.2 merge): this screen does not own the server's
 * lifetime — the server lives inside CompanionService (BridgeModule).
 * The toggle only flips the enabled flag and asks CompanionService
 * to apply it; the status line reflects the service's server, and
 * the event tail reads the shared EventLog ring directly. The local
 * `module` below is kept only as a cheap way to scan for the
 * tailnet address when the service is not running; it never binds
 * a port (two servers cannot both hold port 8741).
 */
class BridgeActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var tokenView: TextView
    private lateinit var eventsView: TextView
    private lateinit var exportView: TextView
    private lateinit var toggleButton: Button
    private var tokenVisible = false
    private val module by lazy { BridgeModule(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.isBlasting(this)) {
            // Same rule as MainActivity: no companion screen during a blast.
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            setBackgroundColor(getColor(com.splendor.blastbuster.R.color.bb_bg))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(title("Muse bridge"))
        root.addView(caption(
            "Muse reaches this phone by polling — the phone never pushes. " +
                "Muse asks for status every so often; a blast or a SIM pull is " +
                "seen on the next poll, not the instant it happens. There is no " +
                "live push in v1.2, and no cloud relay: the bridge is a small " +
                "server on this phone, reachable only over your Tailscale " +
                "network, protected by the token below."
        ))

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text))
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(card(statusView))

        toggleButton = Button(this).apply {
            setBackgroundResource(com.splendor.blastbuster.R.drawable.btn_primary)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text))
            setOnClickListener { toggleBridge() }
        }
        root.addView(toggleButton)

        root.addView(sectionTitle("Bridge token"))
        tokenView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text2))
        }
        root.addView(card(tokenView))
        root.addView(Button(this).apply {
            text = "Show token"
            setBackgroundResource(com.splendor.blastbuster.R.drawable.btn_ghost)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_accent2))
            setOnClickListener {
                tokenVisible = !tokenVisible
                text = if (tokenVisible) "Hide token" else "Show token"
                refreshToken()
            }
        })
        root.addView(Button(this).apply {
            text = "Copy token"
            setBackgroundResource(com.splendor.blastbuster.R.drawable.btn_ghost)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_accent2))
            setOnClickListener {
                val token = BridgeAuth.getOrCreateToken(this@BridgeActivity)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Blast Buster bridge token", token))
                Toast.makeText(this@BridgeActivity,
                    "Token copied — paste it to Muse once, and to nobody else.",
                    Toast.LENGTH_LONG).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Regenerate token"
            setBackgroundResource(com.splendor.blastbuster.R.drawable.btn_ghost)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_accent2))
            setOnClickListener {
                BridgeAuth.regenerateToken(this@BridgeActivity)
                tokenVisible = false
                text = "Show token"
                refreshToken()
                Toast.makeText(this@BridgeActivity,
                    "New token created — the old one no longer works. Give the new one to Muse.",
                    Toast.LENGTH_LONG).show()
            }
        })

        root.addView(sectionTitle("Log export"))
        exportView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text2))
        }
        root.addView(card(exportView))
        root.addView(Button(this).apply {
            text = "Allow one log export"
            setBackgroundResource(com.splendor.blastbuster.R.drawable.btn_ghost)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_accent2))
            setOnClickListener {
                val ok = BridgeModule.liveServer()?.grantLogExport() ?: false
                if (ok) {
                    Toast.makeText(this@BridgeActivity,
                        "Log export allowed once, for 5 minutes. Muse can fetch /v1/log once.",
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@BridgeActivity,
                        "Bridge is not running — turn it on first.",
                        Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        })
        root.addView(caption(
            "The event log leaves this phone only after you tap the button " +
                "above — one export, 5 minutes, then it closes again. The " +
                "export never contains the token, codes, numbers or GPS."
        ))

        root.addView(sectionTitle("How Muse reaches this phone"))
        root.addView(TextView(this).apply {
            text = "1. Install Tailscale on this phone and join your tailnet.\n" +
                "2. Tell Muse the token above (once, in chat).\n" +
                "3. Muse polls http://<this phone's tailnet address>:8741/v1/status.\n" +
                "4. Commands Muse can send: RAM snapshot / kill one app, DND on/off, " +
                "airplane flick, network mode, DNS on/off, blast stop (token + stop code).\n" +
                "5. Muse CANNOT start a blast from the bridge — that is deliberate. " +
                "A blast is started by SMS trigger code.\n" +
                "6. If the phone has no data, Tailscale is off, or this bridge is off, " +
                "polling fails. Events are kept on the phone and served when it returns."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text))
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "Install Tailscale (Play Store)"
            setBackgroundResource(com.splendor.blastbuster.R.drawable.btn_ghost)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_accent2))
            setOnClickListener {
                try {
                    val i = Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.tailscale.ipn"))
                    startActivity(i)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn")))
                    } catch (_: Exception) {
                        Toast.makeText(this@BridgeActivity,
                            "Open the Play Store and search for Tailscale.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        })

        root.addView(sectionTitle("Recent bridge events"))
        eventsView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text2))
        }
        root.addView(card(eventsView))

        root.addView(caption(
            "Honest limits: no push, no offline reach, no waking a sleeping " +
                "phone, no blast start, no file transfer or screenshots — JSON " +
                "status, events and commands only. The log leaves the phone " +
                "only after one tap on \"Allow one log export\" per export. " +
                "The bridge never sends your token, codes, trusted numbers " +
                "or GPS coordinates to Muse."
        ))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isBlasting(this)) {
            finish()
            return
        }
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun toggleBridge() {
        val nowOn = !BridgeAuth.isEnabled(this)
        BridgeAuth.setEnabled(this, nowOn)
        // The server lives in CompanionService — apply the toggle there.
        CompanionService.applyBridgeState(this)
        if (nowOn) {
            Toast.makeText(this, "Bridge on — listening on port 8741", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bridge off", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun refresh() {
        val enabled = BridgeAuth.isEnabled(this)
        val running = CompanionService.bridgeRunning()
        val tail = CompanionService.bridgeTailscaleAddress()
            ?: module.server().findTailscaleAddress()
        // Real bind state from the live server (v1.3): the addresses it
        // is actually listening on. Falls back to deriving them from
        // the live tailnet address only when the live server is not
        // reachable from this screen — the bind set is always
        // localhost, plus the tailnet address when one exists.
        val liveBinds = BridgeModule.liveServer()?.listenState() ?: emptyList()
        val bindText = when {
            liveBinds.isNotEmpty() -> liveBinds.joinToString(", ")
            running -> if (tail != null) "127.0.0.1, $tail" else "127.0.0.1"
            else -> "not listening"
        }
        val tailnetText = if (tail != null) {
            "up ($tail)"
        } else {
            "down — open the Tailscale app and join your tailnet"
        }
        val lastPoll = BridgeAuth.getLastPollAt(this)
        val lastPollText = if (lastPoll == 0L) {
            "never polled yet"
        } else {
            val mins = (System.currentTimeMillis() - lastPoll) / 60_000
            if (mins < 1) "polled just now" else "last polled $mins min ago"
        }
        statusView.text = "Bridge: " + if (enabled) "ON" else "OFF" +
            "\nServer: " + if (running) "listening" else "stopped" +
            "\nListening on: $bindText" +
            "\nPort: 8741" +
            "\nTailnet: $tailnetText" +
            "\nLast poll: $lastPollText"
        toggleButton.text = if (enabled) "Turn bridge off" else "Turn bridge on"
        val exportAllowed = BridgeModule.liveServer()?.isLogExportAllowed() ?: false
        exportView.text = if (exportAllowed) {
            "Export allowed: Muse can fetch /v1/log once, for the next 5 minutes. " +
                "After that — or after one export — it closes again."
        } else {
            "Export closed: Muse cannot download the log until you tap " +
                "\"Allow one log export\" below."
        }
        refreshToken()
        val (events, _) = EventLog.readSince(this, 0, 10)
        eventsView.text = if (events.isEmpty()) {
            "No bridge events yet."
        } else {
            events.joinToString("\n") { e ->
                "• [${e.type}] ${e.summary}"
            }
        }
    }

    private fun refreshToken() {
        tokenView.text = if (tokenVisible) {
            BridgeAuth.getOrCreateToken(this)
        } else {
            "••••••••••••••••••••••••••••••••••••••••••• (tap Show token)"
        }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text))
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text))
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(getColor(com.splendor.blastbuster.R.color.bb_text2))
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun card(child: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(com.splendor.blastbuster.R.drawable.card_bg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(child)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
