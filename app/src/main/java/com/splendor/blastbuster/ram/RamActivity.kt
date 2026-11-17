package com.splendor.blastbuster.ram

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.splendor.blastbuster.Prefs

/**
 * RAM watch screen (DESIGN.md §5.1, §6.3): summary cards, top-apps list
 * with verdicts, per-row Kill button, honest-limit footer.
 *
 * Honest limits shown on this screen: this phone has 4 GB of RAM and no
 * app can add more; killing a background app frees RAM now, badly
 * behaved apps will restart and eat it again; the numbers here are PSS
 * from ActivityManager (the same per-app PSS `adb shell dumpsys
 * meminfo` prints) — for the full system meminfo roll-up, run that
 * command from a computer.
 */
class RamActivity : Activity() {

    private lateinit var summaryView: TextView
    private lateinit var listView: LinearLayout
    private lateinit var statusView: TextView
    private val ramWatch = RamWatch()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.isBlasting(this)) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "RAM watch"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "This phone has 4 GB of RAM. No app can add more. Killing a " +
                "background app frees RAM now; badly behaved apps will restart " +
                "and eat it again. If RAM is always full, the fix is fewer " +
                "heavy apps, not a stronger killer."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(12))
        })
        summaryView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(summaryView)
        val refreshButton = Button(this).apply {
            text = "Refresh now"
            setOnClickListener { refresh() }
        }
        root.addView(refreshButton)
        root.addView(TextView(this).apply {
            text = "Top apps by PSS (from ActivityManager — the same per-app " +
                "PSS adb shell dumpsys meminfo prints). For the full system " +
                "meminfo roll-up, run that command from your computer."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(12), 0, dp(6))
        })
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listView)
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(statusView)
        refresh()
    }

    private fun refresh() {
        if (!Prefs.ramEnabled(this)) {
            summaryView.text = "RAM watch is off. Turn it on with the switch on the RAM tab in the hub, then tap Refresh now here."
            listView.removeAllViews()
            statusView.text = ""
            return
        }
        statusView.text = "Scanning…"
        Thread({
            val snap = try {
                ramWatch.oneShotScan(this)
            } catch (e: Exception) {
                null
            }
            handler.post { showSnapshot(snap) }
        }, "BlastBuster-RamActivity").start()
    }

    private fun showSnapshot(snap: RamSnapshot?) {
        if (snap == null) {
            summaryView.text = "Scan failed — try again."
            statusView.text = ""
            return
        }
        summaryView.text = "Free: ${snap.availKb / 1024} MB of ${snap.totalKb / 1024} MB " +
            "(${snap.freePercent()}%)" +
            if (snap.lowMemory) " — LOW MEMORY" else "" +
            if (!snap.usageAccessGranted) "\nUsage Access not granted: no last-used times; " +
                "verdicts use PSS only." else ""
        listView.removeAllViews()
        if (snap.apps.isEmpty()) {
            listView.addView(TextView(this).apply { text = "No apps found." })
        }
        for (app in snap.apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val label = TextView(this).apply {
                text = "${app.packageName}\n${app.pssKb / 1024} MB — ${app.verdict}" +
                    (if (app.foreground) " (foreground)" else "") +
                    (if (app.systemApp) " (system)" else "") +
                    (if (app.verdictReason.isNotEmpty()) "\n${app.verdictReason}" else "")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            val killButton = Button(this).apply {
                text = "Kill"
                isEnabled = !app.foreground && !app.systemApp
                setOnClickListener { confirmKill(app.packageName) }
            }
            row.addView(killButton)
            listView.addView(row)
        }
        val rogues = snap.rogues().size
        statusView.text = "Scanned just now. Rogues: $rogues. " +
            "Kills are one package at a time — there is no kill-all button."
    }

    private fun confirmKill(pkg: String) {
        val doKill = {
            RamKiller.kill(this, pkg) { result ->
                Toast.makeText(this, "$pkg: ${result.outcome} — ${result.detail}", Toast.LENGTH_LONG).show()
                handler.postDelayed({ refresh() }, RamKiller.VERIFY_DELAY_MS + 1000L)
            }
        }
        if (Prefs.ramKillConfirm(this) && !Prefs.ramKillConfirmedToday(this, pkg)) {
            AlertDialog.Builder(this)
                .setTitle("Kill $pkg?")
                .setMessage(
                    "It may restart itself — this frees RAM now, it does not " +
                        "fix the app. Killing is one package at a time."
                )
                .setPositiveButton("Kill") { _, _ ->
                    Prefs.markRamKillConfirmedToday(this, pkg)
                    doKill()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            doKill()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
