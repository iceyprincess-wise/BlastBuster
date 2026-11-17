package com.splendor.blastbuster.net

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.BlastGate

/**
 * Network screen (DESIGN.md N18, §5.3, §6.3).
 *
 * Plain android widgets, no AndroidX, built programmatically like
 * MainActivity in v2. Two SIM cards (carrier names from the phone —
 * never hardcoded), a flick button, a per-SIM mode lock, and the last
 * drops as a text list. Grade colours use the design palette (§6.2):
 * green #3FB950 ok, amber #D29922 warn, red #F85149 bad.
 *
 * Honest copy (§5.3, shown verbatim under the title):
 * "No app can create signal that isn't there. A flick or a mode lock
 * can make the phone re-attach to a tower that exists — it cannot
 * invent coverage. If both SIMs show no service, the problem is the
 * towers, not the phone."
 *
 * The flick button label always states which path will run
 * ("Flick (one tap)" vs "Flick (guided — needs ADB grant for one tap)").
 * While a blast is active this screen finishes immediately — companion
 * screens are unreachable during a blast (DESIGN.md §1.3 rule 4).
 *
 * NOTE (Agent C): the string literals below are `private const val`s at
 * the top of this file on purpose so they can be moved into
 * res/values/strings.xml in one pass. This agent must not edit
 * strings.xml (§4.7 ownership).
 */
class NetActivity : Activity() {

    companion object {
        private const val TITLE = "Network tools"
        private const val HONEST_LIMIT =
            "No app can create signal that isn't there. A flick or a mode lock " +
                "can make the phone re-attach to a tower that exists — it cannot " +
                "invent coverage. If both SIMs show no service, the problem is " +
                "the towers, not the phone."
        private const val SOURCE_NOTE =
            "Signal from TelephonyManager, per SIM. Grades are smoothed over " +
                "the last 3 samples; one bad sample never triggers a flick."
        private const val FLICK_ONE_TAP = "Flick (one tap)"
        private const val FLICK_GUIDED = "Flick (guided — needs ADB grant for one tap)"
        private const val ADB_GRANT_LINE =
            "One-tap flick and mode lock need this ADB grant (once, from a computer):\n" +
                "adb shell pm grant com.splendor.blastbuster android.permission.WRITE_SECURE_SETTINGS"
        private const val ADB_GRANTED_LINE = "ADB grant present — one-tap flick and mode lock are available."
        private const val GUIDED_STEPS =
            "Without the grant, both features still work by hand:\n" +
                "• Flick — open Airplane settings, turn Airplane mode ON, wait 8 seconds, " +
                "turn it OFF, then tap “Check signal now”.\n" +
                "• Mode lock — pick a mode and tap Apply; the SIM's mobile-network settings " +
                "open and you set the mode there. Your choice is saved either way."
        private const val NO_SIM_LINE =
            "No SIM information available — grant the Phone permission, or the phone reports no SIMs."
    }

    private val module = NetModule()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var simsContainer: LinearLayout
    private lateinit var flickButton: Button
    private lateinit var flickStatus: TextView
    private lateinit var dropsView: TextView
    private lateinit var grantView: TextView
    private lateinit var guidedView: TextView
    private val modeGroups = mutableMapOf<Int, RadioGroup>()

    private var flickCountdownSeconds = 0
    private val countdownTick = object : Runnable {
        override fun run() {
            if (flickCountdownSeconds > 0) {
                flickStatus.text = "Radio off — back in $flickCountdownSeconds s…"
                flickCountdownSeconds--
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            refreshSnapshot()
            handler.postDelayed(this, 5000)
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
            text = SOURCE_NOTE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#9AA7B8"))
            setPadding(0, 0, 0, dp(12))
        })

        grantView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(grantView)
        guidedView = TextView(this).apply {
            text = GUIDED_STEPS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(guidedView)

        simsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(simsContainer)

        flickButton = Button(this).apply {
            text = FLICK_ONE_TAP
            setOnClickListener { onFlickClicked() }
        }
        root.addView(flickButton)
        flickStatus = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(12))
        }
        root.addView(flickStatus)

        root.addView(TextView(this).apply {
            text = "Recent drops (last 10)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        })
        dropsView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        root.addView(dropsView)

        root.addView(Button(this).apply {
            text = "Check signal now"
            setOnClickListener { refreshSnapshot() }
        })
    }

    override fun onResume() {
        super.onResume()
        if (BlastGate.isBlasting(this)) {
            finish()
            return
        }
        updateGrantRow()
        module.startPolling(this)
        module.netWatch.listener = NetWatch.NetWatchListener { snapshot ->
            handler.post { renderSnapshot(snapshot) }
        }
        refreshSnapshot()
        handler.postDelayed(refreshTick, 5000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTick)
        handler.removeCallbacks(countdownTick)
        module.netWatch.listener = null
        module.stop()
    }

    private fun updateGrantRow() {
        val granted = NetTools.hasSecureSettingsGrant(this)
        grantView.text = if (granted) ADB_GRANTED_LINE else ADB_GRANT_LINE
        guidedView.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        flickButton.text = if (granted) FLICK_ONE_TAP else FLICK_GUIDED
    }

    private fun refreshSnapshot() {
        val snap = module.lastSnapshot() ?: return
        renderSnapshot(snap)
    }

    private fun renderSnapshot(snapshot: NetWatch.NetSnapshot) {
        simsContainer.removeAllViews()
        modeGroups.clear()
        if (snapshot.sims.size == 1 && snapshot.sims[0].subscriptionId == -1) {
            simsContainer.addView(TextView(this).apply {
                text = NO_SIM_LINE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        }
        for (sim in snapshot.sims) {
            simsContainer.addView(buildSimCard(sim))
        }
        val drops = module.netWatch.recentDrops().take(10)
        dropsView.text = if (drops.isEmpty()) {
            "No drops recorded."
        } else {
            drops.joinToString("\n")
        }
    }

    private fun buildSimCard(sim: NetWatch.SimSignal): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val gradeColor = when (sim.grade) {
            SignalGrade.EXCELLENT, SignalGrade.GOOD -> "#3FB950"
            SignalGrade.FAIR -> "#D29922"
            SignalGrade.POOR, SignalGrade.NONE -> "#F85149"
        }
        card.addView(TextView(this).apply {
            text = "${sim.carrierName} — SIM ${sim.slotIndex + 1}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = "Grade: ${sim.grade}   Transport: ${sim.transport}   " +
                "Level: ${sim.level}/4   " +
                "Signal: ${if (sim.dbm == Int.MAX_VALUE) "unknown" else "${sim.dbm} dBm"}   " +
                "Data: ${if (sim.dataConnected) "connected" else "not connected"}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor(gradeColor))
        })
        card.addView(TextView(this).apply {
            text = "Network mode"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(2))
        })
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val current = Prefs.preferredMode(this, sim.subscriptionId)
        for (mode in NetMode.entries) {
            group.addView(RadioButton(this).apply {
                text = mode.displayName
                isChecked = mode == current
                tag = mode.name
            })
        }
        modeGroups[sim.subscriptionId] = group
        card.addView(group)
        card.addView(Button(this).apply {
            text = if (NetTools.hasSecureSettingsGrant(this@NetActivity)) {
                "Apply mode for ${sim.carrierName}"
            } else {
                "Apply mode for ${sim.carrierName} (guided — needs ADB grant)"
            }
            setOnClickListener { onApplyModeClicked(sim) }
        })
        return card
    }

    private fun onApplyModeClicked(sim: NetWatch.SimSignal) {
        val group = modeGroups[sim.subscriptionId] ?: return
        val checkedId = group.checkedRadioButtonId
        if (checkedId == -1) {
            Toast.makeText(this, "Pick a mode first.", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = group.findViewById<RadioButton>(checkedId)
        val mode = NetMode.valueOf(selected.tag as String)
        flickStatus.text = "Applying ${mode.displayName} for ${sim.carrierName}… (this takes ~30 s to verify)"
        module.setNetworkMode(this, sim.subscriptionId, sim.slotIndex, mode) { outcome ->
            flickStatus.text = outcome.detail
            if (NetOutcomeText.modeShowsGuidedSteps(outcome.result.name)) {
                openNetworkSettings()
            }
        }
    }

    private fun onFlickClicked() {
        if (!NetTools.hasSecureSettingsGrant(this)) {
            // Guided path: open the airplane settings with the 3 steps.
            flickStatus.text =
                "Guided flick: 1) turn Airplane mode ON, 2) wait 8 seconds, " +
                    "3) turn it OFF — then tap “Check signal now”."
            startActivitySafely(NetTools.airplaneSettingsIntent())
            return
        }
        flickButton.isEnabled = false
        flickCountdownSeconds = 10
        handler.post(countdownTick)
        module.flickAirplane(this) { outcome ->
            handler.removeCallbacks(countdownTick)
            flickButton.isEnabled = true
            flickStatus.text = outcome.detail
            if (outcome.result == NetTools.FlickResult.COOLDOWN) {
                val secs = outcome.remainingCooldownMs / 1000
                flickStatus.text = "Flick cooling down — try again in ${secs}s."
            }
            refreshSnapshot()
        }
    }

    private fun openNetworkSettings() {
        startActivitySafely(NetTools.networkOperatorSettingsIntent())
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Settings: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
