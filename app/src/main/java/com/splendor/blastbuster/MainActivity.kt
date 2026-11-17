package com.splendor.blastbuster

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.splendor.blastbuster.bridge.BridgeActivity
import com.splendor.blastbuster.bridge.BridgeAuth
import com.splendor.blastbuster.bridge.BridgeServer
import com.splendor.blastbuster.companion.CompanionService

/**
 * Blast Buster hub (DESIGN.md §6.1). Plain android widgets, no AndroidX —
 * mirrors the proven SplendorCam approach.
 *
 * Six sections, switched by a plain Button row (no TabLayout, no
 * ViewPager — a manual switcher that shows/hides six LinearLayouts in
 * one ScrollView, the same construction technique v2 already uses):
 * Blast (the v2 screen, behaviour identical), RAM, Do Not Disturb,
 * Network, Muse bridge, Setup & grants. Each companion section is a
 * summary card that opens its dedicated screen; the dedicated screens
 * hold the detail, the hub holds the state at a glance.
 *
 * v1.1 rule preserved VERBATIM: while a blast is active this screen
 * redirects straight to the red lockout screen — there is no reachable
 * Stop button in the app while blasting. The owner stops a blast with
 * a fingerprint on the lockout screen, or with the stop SMS.
 *
 * Status (armed / blasting) is read from SharedPreferences and refreshed
 * once a second while this screen is visible.
 *
 * v1.2 gap fix: the RAM, DND and Network hub cards each carry their
 * own enable toggle (like the bridge toggle in BridgeActivity and the
 * DNS toggle in DnsActivity). Before this fix no in-app switch ever
 * set ram_enabled / dnd_enabled / net_enabled, so those modules could
 * never actually turn on. Toggling on writes the Prefs flag and asks
 * CompanionService to apply it; toggling off clears the flag, and if
 * no companion module is left enabled the service stops itself.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var armButton: Button
    private lateinit var triggerEdit: EditText
    private lateinit var stopEdit: EditText
    private lateinit var trustedEdit: EditText
    private lateinit var voiceEdit: EditText
    private lateinit var voiceToggleButton: Button
    private lateinit var ramToggleButton: Button
    private lateinit var dndToggleButton: Button
    private lateinit var netToggleButton: Button
    private lateinit var bridgeSummaryView: TextView
    private lateinit var grantStatusView: TextView

    private val sections = ArrayList<LinearLayout>()
    private val tabButtons = ArrayList<Button>()
    private var currentSection = 0

    private val handler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHub()
            handler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val PERM_REQUEST = 1
        private val PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )

        // Companion screens — opened by class name (kept from the
        // merge; BridgeActivity is opened directly, below).
        private const val RAM_ACTIVITY = "com.splendor.blastbuster.ram.RamActivity"
        private const val DND_ACTIVITY = "com.splendor.blastbuster.dnd.DndActivity"
        private const val NET_ACTIVITY = "com.splendor.blastbuster.net.NetActivity"
        private const val DNS_ACTIVITY = "com.splendor.blastbuster.dns.DnsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No in-app controls while blasting: straight to the lockout.
        if (Prefs.isBlasting(this)) {
            startLockout()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(getColor(R.color.bb_bg))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        // Header: siren mark + name.
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_siren)
            setColorFilter(getColor(R.color.bb_accent))
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(icon)
        root.addView(TextView(this).apply {
            text = "Blast Buster"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.bb_text))
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "Theft alarm — triggered by SMS, works offline (no internet needed)."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.bb_text2))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
        })

        // Hub tab row: six plain buttons, manual switcher.
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val tabLabels = arrayOf("Blast", "RAM", "DND", "Network", "Bridge", "Setup")
        for (i in tabLabels.indices) {
            val btn = Button(this).apply {
                text = tabLabels[i]
                textSize = 12f
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener { showSection(i) }
            }
            tabButtons.add(btn)
            tabRow.addView(btn)
        }
        root.addView(tabRow)

        // Six sections in one ScrollView; only one is visible at a time.
        val blastSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ramSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dndSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val netSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bridgeSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val setupSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sections.addAll(listOf(blastSection, ramSection, dndSection, netSection, bridgeSection, setupSection))
        for (s in sections) root.addView(s)

        buildBlastSection(blastSection)
        buildRamSection(ramSection)
        buildDndSection(dndSection)
        buildNetSection(netSection)
        buildBridgeSection(bridgeSection)
        buildSetupSection(setupSection)

        showSection(0)
        refreshStatus()
        refreshHub()
    }

    // ---- Hub switching (free platform animation only, §6.2) ----

    private fun showSection(index: Int) {
        currentSection = index
        for (i in sections.indices) {
            val visible = i == index
            sections[i].visibility = if (visible) View.VISIBLE else View.GONE
            tabButtons[i].setBackgroundResource(
                if (visible) R.drawable.btn_primary else R.drawable.btn_ghost
            )
            tabButtons[i].setTextColor(getColor(R.color.bb_text))
        }
        // 150 ms fade-in for the section switch; instant on a Helio G81
        // or it is removed, not tuned (§6.2).
        val shown = sections[index]
        shown.alpha = 0f
        shown.animate().alpha(1f).setDuration(150).start()
        refreshHub()
    }

    // ---- Section 1: Blast (v2 content, behaviour identical) ----

    private fun buildBlastSection(root: LinearLayout) {
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(statusView)

        // Codes.
        root.addView(sectionTitle("Trigger code (SMS text that starts the siren)"))
        triggerEdit = editText(Prefs.triggerCode(this))
        root.addView(triggerEdit)
        root.addView(sectionTitle("Stop code (SMS text that stops the siren)"))
        stopEdit = editText(Prefs.stopCode(this))
        root.addView(stopEdit)
        root.addView(sectionTitle("Trusted numbers (comma-separated, optional)"))
        trustedEdit = EditText(this).apply {
            setText(Prefs.trustedNumbersRaw(this@MainActivity))
            hint = "e.g. 08000000000 — empty = any sender with the code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextColor(getColor(R.color.bb_text))
            setHintTextColor(getColor(R.color.bb_text2))
        }
        root.addView(trustedEdit)
        root.addView(TextView(this).apply {
            text = "If trusted numbers are set, only those numbers can trigger or stop " +
                "the siren. If empty, anyone who knows the code can — keep the code secret."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.bb_text2))
            setPadding(0, dp(4), 0, dp(8))
        })

        // Voice trigger (v1.1).
        root.addView(sectionTitle("Voice trigger phrase (optional, OFF by default)"))
        voiceEdit = editText(Prefs.voicePhrase(this))
        root.addView(voiceEdit)
        root.addView(TextView(this).apply {
            text = "Saying this phrase near the phone starts the siren. It matches " +
                "the WORDS, not your voice — anyone saying the phrase can trigger " +
                "it. Listening all the time costs battery."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.bb_text2))
            setPadding(0, dp(4), 0, dp(8))
        })
        voiceToggleButton = Button(this).apply {
            setBackgroundResource(R.drawable.btn_ghost)
            setTextColor(getColor(R.color.bb_accent2))
            setOnClickListener { toggleVoiceTrigger() }
        }
        root.addView(voiceToggleButton)
        refreshVoiceToggle()

        root.addView(Button(this).apply {
            text = "Save settings"
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener { saveSettings() }
        })

        armButton = Button(this).apply {
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener { toggleArm() }
        }
        root.addView(armButton)

        root.addView(Button(this).apply {
            text = "Test siren (plays now)"
            setBackgroundResource(R.drawable.btn_danger)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener {
                BlastService.startBlast(this@MainActivity, null)
                Toast.makeText(this@MainActivity, "Siren on — stop it with your fingerprint", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Stop siren"
            setBackgroundResource(R.drawable.btn_ghost)
            setTextColor(getColor(R.color.bb_accent2))
            setOnClickListener { BlastService.stopBlast(this@MainActivity) }
        })

        // Permissions.
        root.addView(sectionTitle("Permissions"))
        root.addView(Button(this).apply {
            text = "Grant permissions (SMS, location, notifications, camera, microphone, phone state)"
            setBackgroundResource(R.drawable.btn_ghost)
            setTextColor(getColor(R.color.bb_accent2))
            setOnClickListener {
                requestPermissions(PERMISSIONS, PERM_REQUEST)
            }
        })
        root.addView(Button(this).apply {
            text = "Allow Do Not Disturb access (lets the siren force DND off)"
            setBackgroundResource(R.drawable.btn_ghost)
            setTextColor(getColor(R.color.bb_accent2))
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Open Settings > Apps > Special app access > Do Not Disturb access", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Allow display over other apps (blocks the notification shade during a blast)"
            setBackgroundResource(R.drawable.btn_ghost)
            setTextColor(getColor(R.color.bb_accent2))
            setOnClickListener {
                try {
                    val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Open Settings > Apps > Special app access > Display over other apps", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Enable device admin (locks the screen when the siren starts)"
            setBackgroundResource(R.drawable.btn_ghost)
            setTextColor(getColor(R.color.bb_accent2))
            setOnClickListener {
                try {
                    val admin = ComponentName(this@MainActivity, BlastAdminReceiver::class.java)
                    val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    i.putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Blast Buster locks the screen when the theft siren starts, so the red lockout screen appears over the lock screen."
                    )
                    startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Open Settings > Security > Device admin apps", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Battery: ask for No restrictions"
            setBackgroundResource(R.drawable.btn_ghost)
            setTextColor(getColor(R.color.bb_accent2))
            setOnClickListener {
                try {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (e: Exception) {
                    try {
                        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        i.data = Uri.parse("package:$packageName")
                        startActivity(i)
                    } catch (_: Exception) {
                    }
                }
            }
        })
        root.addView(TextView(this).apply {
            text = "Limits, honestly: the power button cannot be blocked by any app " +
                "— pressing it turns the screen off, but the red lockout comes " +
                "straight back when the screen turns on. If the phone is unlocked, " +
                "force-stop in Settings cannot be blocked either — the screen lock " +
                "plus this lockout is what protects that path. The fingerprint " +
                "sensor on this phone is the side power button, not under the " +
                "screen — place your thumb there. If the SIM is pulled while " +
                "armed, the phone now blasts by itself (no SMS needed). The siren " +
                "is as loud as the phone's single speaker allows — v1.1 maximises " +
                "perceived loudness within that cap. The voice trigger matches " +
                "words, not your voice."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.bb_text2))
            setPadding(0, dp(8), 0, 0)
        })
    }

    // ---- Section 2: RAM (Agent A screen; hub card only, §6.1) ----

    private fun buildRamSection(root: LinearLayout) {
        root.addView(sectionTitle("RAM watch"))
        root.addView(cardText(
            "Top apps by memory, with a verdict for each — OK, WATCH or ROGUE — " +
                "and a one-tap kill for a background app that is eating RAM.\n\n" +
                getString(R.string.ram_limit)
        ))
        ramToggleButton = Button(this).apply {
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener { toggleRam() }
        }
        root.addView(ramToggleButton)
        refreshRamToggle()
        root.addView(Button(this).apply {
            text = "Open RAM watch"
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener { openModuleScreen(RAM_ACTIVITY, "RAM watch") }
        })
    }

    // ---- Section 3: DND (Agent A screen; hub card only, §6.1) ----

    private fun buildDndSection(root: LinearLayout) {
        root.addView(sectionTitle("Do Not Disturb"))
        root.addView(cardText(
            "A schedule that keeps DND on when it should be on — and notices " +
                "when another app turns it off, naming the app that was in front " +
                "when it changed. A change the app only inferred is labelled as " +
                "inferred, never as proof."
        ))
        dndToggleButton = Button(this).apply {
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener { toggleDnd() }
        }
        root.addView(dndToggleButton)
        refreshDndToggle()
        root.addView(Button(this).apply {
            text = "Open DND"
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener { openModuleScreen(DND_ACTIVITY, "DND") }
        })
    }

    // ---- Section 4: Network (Agent B screen; hub card only, §6.1) ----

    private fun buildNetSection(root: LinearLayout) {
        root.addView(sectionTitle("Network tools"))
        root.addView(cardText(
            "Signal per SIM, airplane-mode flick, and network-mode lock — plus " +
                "the local DNS pipe, which blocks junk domains before they load.\n\n" +
                getString(R.string.net_limit) + "\n\n" + getString(R.string.dns_limit)
        ))
        netToggleButton = Button(this).apply {
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener { toggleNet() }
        }
        root.addView(netToggleButton)
        refreshNetToggle()
        root.addView(Button(this).apply {
            text = "Open network tools"
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener { openModuleScreen(NET_ACTIVITY, "Network tools") }
        })
        root.addView(Button(this).apply {
            text = "Open DNS pipe"
            setBackgroundResource(R.drawable.btn_ghost)
            setTextColor(getColor(R.color.bb_accent2))
            setOnClickListener { openModuleScreen(DNS_ACTIVITY, "DNS pipe") }
        })
    }

    // ---- Section 5: Muse bridge (Agent C screen, live summary) ----

    private fun buildBridgeSection(root: LinearLayout) {
        root.addView(sectionTitle("Muse bridge"))
        bridgeSummaryView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.bb_text))
        }
        root.addView(card(bridgeSummaryView))
        root.addView(TextView(this).apply {
            text = getString(R.string.bridge_limit)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.bb_text2))
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(Button(this).apply {
            text = "Open bridge"
            setBackgroundResource(R.drawable.btn_primary)
            setTextColor(getColor(R.color.bb_text))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BridgeActivity::class.java))
            }
        })
    }

    // ---- Section 6: Setup & grants (§3.3, §3.5) ----

    private fun buildSetupSection(root: LinearLayout) {
        root.addView(sectionTitle("Setup & grants"))
        root.addView(TextView(this).apply {
            text = getString(R.string.no_adb_needed)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.bb_text))
            setPadding(0, dp(4), 0, dp(8))
        })
        grantStatusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.bb_text))
        }
        root.addView(card(grantStatusView))
        root.addView(sectionTitle("Setup checklist — do all of these"))
        root.addView(TextView(this).apply {
            text = "1. Grant the permissions above (SMS, location, notifications, " +
                "camera, microphone, phone state).\n" +
                "2. Do Not Disturb access: allow Blast Buster (button above, or " +
                "Settings > Apps > Special app access > Do Not Disturb access). " +
                "Fixed in v1.1 — the app now appears in that list.\n" +
                "3. Display over other apps: allow it (button above) — this is " +
                "what blocks the notification shade during a blast.\n" +
                "4. Device admin: enable it (button above) — this is what locks " +
                "the screen when the siren starts.\n" +
                "5. Autostart ON: Security app > Autostart > Blast Buster ON.\n" +
                "6. Battery: Settings > Apps > Blast Buster > Battery saver > " +
                "No restrictions (button above asks the system for this).\n" +
                "7. Lock in recents: open the recents screen, long-press the " +
                "Blast Buster card and lock it, so HyperOS cannot swipe it away.\n" +
                "8. Keep the phone's screen lock ON — a thief who cannot unlock " +
                "the phone cannot force-stop the app.\n" +
                "9. Voice trigger is OFF by default. Turn it on only if you want " +
                "it: it listens all the time (battery) and matches the phrase, " +
                "not your voice.\n" +
                "10. Test it: text the trigger code to this phone from your " +
                "second number, then text the stop code. Test the lockout too: " +
                "start a test blast, and stop it with your fingerprint on the " +
                "sensor (the side power button) — no scanner shows on screen."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.bb_text))
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    // ---- Hub refresh (bridge summary + grant rows) ----

    private fun refreshHub() {
        if (!::bridgeSummaryView.isInitialized) return
        // Module toggles always show the real Prefs state, on every
        // section — they are refreshed once a second by the status
        // tick as well as on open and on section switch.
        refreshModuleToggles()
        // Only refresh the section the user is looking at: the tailscale
        // scan is cheap but there is no reason to run it every second
        // while the Blast tab is showing.
        if (currentSection != 4 && currentSection != 5) return
        val enabled = BridgeAuth.isEnabled(this)
        val tail = try {
            BridgeServer(applicationContext).findTailscaleAddress()
        } catch (_: Exception) {
            null
        }
        val lastPoll = BridgeAuth.getLastPollAt(this)
        val lastPollText = if (lastPoll == 0L) {
            "never polled yet"
        } else {
            val mins = (System.currentTimeMillis() - lastPoll) / 60_000
            if (mins < 1) "polled just now" else "last polled $mins min ago"
        }
        bridgeSummaryView.text = "Bridge: " + if (enabled) "ON" else "OFF" +
            "\nTailnet: " + (tail ?: "not connected") +
            "\nPort: 8741" +
            "\nLast poll: $lastPollText"

        if (::grantStatusView.isInitialized) {
            fun row(label: String, granted: Boolean) =
                (if (granted) "✓ " else "✗ ") + label
            val adbWrite = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
                PackageManager.PERMISSION_GRANTED
            val adbDump = checkSelfPermission("android.permission.DUMP") ==
                PackageManager.PERMISSION_GRANTED
            val adbLogs = checkSelfPermission("android.permission.READ_LOGS") ==
                PackageManager.PERMISSION_GRANTED
            grantStatusView.text =
                row(getString(R.string.grant_write_secure), adbWrite) + "\n" +
                row(getString(R.string.grant_dump), adbDump) + "\n" +
                row(getString(R.string.grant_read_logs), adbLogs) + "\n\n" +
                "One-time ADB setup is optional — see the Bridge screen for " +
                "what works without it. If a grant above shows ✗ after a reboot " +
                "or an update, re-run the ADB setup."
        }
    }

    /** Open a module screen by class name; the classes ship in this build. */
    private fun openModuleScreen(className: String, label: String) {
        try {
            val clazz = Class.forName(className)
            startActivity(Intent(this, clazz))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not open $label.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun card(child: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(child)
    }

    private fun cardText(text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(TextView(this@MainActivity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.bb_text))
        })
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isBlasting(this)) {
            startLockout()
            finish()
            return
        }
        refreshStatus()
        refreshHub()
        handler.post(statusTick)
        // Start the guard whenever the app is opened and armed.
        if (Prefs.isArmed(this) && !Prefs.isBlasting(this)) {
            try {
                BlastService.startGuard(this)
            } catch (_: Exception) {
            }
        }
        // MERGED: pick up any companion module enabled since the
        // service last started (module starts are idempotent, and
        // startIfNeeded is a no-op when nothing is enabled).
        if (!Prefs.isBlasting(this)) {
            CompanionService.startIfNeeded(this)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTick)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            val denied = permissions.filterIndexed { i, _ ->
                i >= grantResults.size || grantResults[i] != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isEmpty()) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Missing: ${denied.joinToString(", ")} — the siren needs SMS most of all.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startLockout() {
        try {
            val i = Intent(this, LockoutActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun refreshStatus() {
        val dnd = try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) "DND: granted" else "DND: NOT granted"
        } catch (_: Exception) {
            "DND: unknown"
        }
        val admin = try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val c = ComponentName(this, BlastAdminReceiver::class.java)
            if (dpm.isAdminActive(c)) "Admin: granted" else "Admin: NOT granted"
        } catch (_: Exception) {
            "Admin: unknown"
        }
        val overlay = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                "Overlay: granted"
            } else {
                "Overlay: NOT granted"
            }
        } catch (_: Exception) {
            "Overlay: unknown"
        }
        val extra = "$dnd · $admin · $overlay"
        if (Prefs.isBlasting(this)) {
            statusView.text = "🔴 BLASTING — siren on\n$extra"
            statusView.setTextColor(getColor(R.color.bb_accent))
            armButton.text = "Disarm"
        } else if (Prefs.isArmed(this)) {
            statusView.text = "Armed — listening for your SMS trigger\n$extra"
            statusView.setTextColor(getColor(R.color.bb_ok))
            armButton.text = "Disarm"
        } else {
            statusView.text = "Disarmed — SMS trigger ignored\n$extra"
            statusView.setTextColor(getColor(R.color.bb_text2))
            armButton.text = "Arm"
        }
    }

    private fun refreshVoiceToggle() {
        voiceToggleButton.text = if (Prefs.voiceTriggerEnabled(this)) {
            "Voice trigger: ON — tap to turn off"
        } else {
            "Voice trigger: OFF — tap to turn on"
        }
    }

    // ---- Hub module toggles (v1.2 gap fix) ----
    // Same component style as the bridge toggle ("Turn bridge on/off")
    // and the same copy voice as the voice trigger toggle. Each toggle
    // reads the real Prefs state on open and on every status tick, so
    // it can never show a stale state.

    private fun refreshModuleToggles() {
        refreshRamToggle()
        refreshDndToggle()
        refreshNetToggle()
    }

    private fun refreshRamToggle() {
        if (!::ramToggleButton.isInitialized) return
        ramToggleButton.text = if (Prefs.ramEnabled(this)) {
            "Turn RAM watch off"
        } else {
            "Turn RAM watch on"
        }
    }

    private fun refreshDndToggle() {
        if (!::dndToggleButton.isInitialized) return
        dndToggleButton.text = if (Prefs.dndEnabled(this)) {
            "Turn DND enforcement off"
        } else {
            "Turn DND enforcement on"
        }
    }

    private fun refreshNetToggle() {
        if (!::netToggleButton.isInitialized) return
        netToggleButton.text = if (Prefs.netEnabled(this)) {
            "Turn network watch off"
        } else {
            "Turn network watch on"
        }
    }

    private fun toggleRam() {
        val nowOn = !Prefs.ramEnabled(this)
        Prefs.setRamEnabled(this, nowOn)
        refreshRamToggle()
        CompanionService.applyModuleStates(this)
        Toast.makeText(
            this,
            if (nowOn) "RAM watch on — scanning every 60 seconds"
            else "RAM watch off",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleDnd() {
        val nowOn = !Prefs.dndEnabled(this)
        Prefs.setDndEnabled(this, nowOn)
        refreshDndToggle()
        CompanionService.applyModuleStates(this)
        Toast.makeText(
            this,
            if (nowOn) "DND enforcement on — the guard puts DND back within 60 seconds when it drifts"
            else "DND enforcement off",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun toggleNet() {
        val nowOn = !Prefs.netEnabled(this)
        Prefs.setNetEnabled(this, nowOn)
        refreshNetToggle()
        CompanionService.applyModuleStates(this)
        Toast.makeText(
            this,
            if (nowOn) "Network watch on — signal checked every 30 seconds"
            else "Network watch off",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleVoiceTrigger() {
        val nowOn = !Prefs.voiceTriggerEnabled(this)
        Prefs.setVoiceTriggerEnabled(this, nowOn)
        refreshVoiceToggle()
        if (nowOn) {
            // Starting the guard (re)starts the voice listener.
            try {
                BlastService.startGuard(this)
            } catch (_: Exception) {
            }
            Toast.makeText(this, "Voice trigger on — saying the phrase starts the siren", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Voice trigger off", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSettings() {
        val trigger = triggerEdit.text.toString().trim()
        val stop = stopEdit.text.toString().trim()
        if (trigger.isEmpty() || stop.isEmpty()) {
            Toast.makeText(this, "Trigger and stop codes cannot be empty.", Toast.LENGTH_SHORT).show()
            return
        }
        if (trigger.equals(stop, ignoreCase = true)) {
            Toast.makeText(this, "Trigger and stop codes must be different.", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setTriggerCode(this, trigger)
        Prefs.setStopCode(this, stop)
        Prefs.setTrustedNumbersRaw(this, trustedEdit.text.toString())
        Prefs.setVoicePhrase(this, voiceEdit.text.toString())
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun toggleArm() {
        val nowArmed = !Prefs.isArmed(this)
        Prefs.setArmed(this, nowArmed)
        if (nowArmed) {
            try {
                BlastService.startGuard(this)
            } catch (_: Exception) {
            }
            Toast.makeText(this, "Armed", Toast.LENGTH_SHORT).show()
        } else {
            BlastService.stopBlast(this)
            try {
                stopService(Intent(this, BlastService::class.java))
            } catch (_: Exception) {
            }
            Toast.makeText(this, "Disarmed — SMS trigger ignored", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.bb_text))
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun editText(value: String): EditText = EditText(this).apply {
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setTextColor(getColor(R.color.bb_text))
        setHintTextColor(getColor(R.color.bb_text2))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
