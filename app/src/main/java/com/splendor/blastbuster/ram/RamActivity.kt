package com.splendor.blastbuster.ram

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.EventLog

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
 *
 * RAM-list fix (v1.3): Android only lets this screen see its own process
 * in the running-process list, with or without the ADB DUMP grant — the
 * DUMP grant lifts `getProcessMemoryInfo` to other apps' pids, but there
 * is no public API that yields other apps' pids, so DUMP unlocks no
 * usable list here and no DUMP path is wired. The screen therefore also
 * lists recently-used apps from UsageStatsManager (Usage Access), with
 * last-used time and PSS marked unavailable — never an invented number.
 * Blast Buster itself never shows a Kill button.
 *
 * Both memory figures are shown: "Available" (ActivityManager availMem)
 * and "Strictly free" (MemFree from /proc/meminfo), with the one-line
 * explanation that both are true and measure different things.
 *
 * Low-memory alert: when free RAM drops below the system's own
 * low-memory threshold (MemoryInfo.threshold), a banner shows here and
 * one RAM_LOW event is logged per episode (RamWatch).
 *
 * Elimination mode (v1.3): one "Eliminate now" button kills ONLY the
 * current ROGUE verdicts, one at a time through RamKiller (denylist,
 * cooldown, 5 s verify), after a single batch confirm dialog. It is
 * manual, never automatic, never a background sweep, and it refuses
 * while a blast is active (BlastGate). ELIMINATION_STARTED and
 * ELIMINATION_FINISHED are logged with per-package outcomes.
 */
class RamActivity : Activity() {

    private lateinit var summaryView: TextView
    private lateinit var lowMemoryBannerView: TextView
    private lateinit var listView: LinearLayout
    private lateinit var recentHeaderView: TextView
    private lateinit var recentListView: LinearLayout
    private lateinit var usageAccessButton: Button
    private lateinit var eliminateButton: Button
    private lateinit var eliminationResultView: TextView
    private lateinit var statusView: TextView
    private val ramWatch = RamWatch()
    private val handler = Handler(Looper.getMainLooper())
    private var currentSnapshot: RamSnapshot? = null
    private var eliminationRunning = false

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
        lowMemoryBannerView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        root.addView(lowMemoryBannerView)
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
                "meminfo roll-up, run that command from your computer. " +
                "Android only lets this screen see its own process here, " +
                "with or without the ADB DUMP grant — recently-used apps " +
                "are listed below from Usage Access instead, with no PSS."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(12), 0, dp(6))
        })
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listView)
        recentHeaderView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(6))
        }
        root.addView(recentHeaderView)
        recentListView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentListView)
        usageAccessButton = Button(this).apply {
            text = "Grant Usage Access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            visibility = View.GONE
        }
        root.addView(usageAccessButton)
        eliminateButton = Button(this).apply {
            text = "Eliminate now (rogue apps only)"
            setOnClickListener { startElimination() }
        }
        root.addView(eliminateButton)
        eliminationResultView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(eliminationResultView)
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
            lowMemoryBannerView.visibility = View.GONE
            listView.removeAllViews()
            recentListView.removeAllViews()
            recentHeaderView.text = ""
            usageAccessButton.visibility = View.GONE
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
        currentSnapshot = snap
        if (snap.belowLowMemoryThreshold()) {
            lowMemoryBannerView.visibility = View.VISIBLE
            lowMemoryBannerView.text = "LOW MEMORY — only ${snap.availKb / 1024} MB free, " +
                "below the system's ${snap.thresholdKb / 1024} MB threshold. " +
                "Close heavy apps or kill a rogue app below."
        } else {
            lowMemoryBannerView.visibility = View.GONE
        }
        summaryView.text = RamListPresentation.availableLine(snap) + "\n" +
            RamListPresentation.freeLine(snap) + "\n" +
            RamListPresentation.AVAILABLE_VS_FREE_NOTE +
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
            val isSelf = app.packageName == packageName
            val label = TextView(this).apply {
                text = "${app.packageName}\n${RamListPresentation.pssText(app)} — ${app.verdict}" +
                    (if (app.foreground) " (foreground)" else "") +
                    (if (app.systemApp) " (system)" else "") +
                    (if (isSelf) "\n(this app — never killed)" else "") +
                    (if (app.verdictReason.isNotEmpty()) "\n${app.verdictReason}" else "")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            // Blast Buster itself NEVER gets a Kill button. Other
            // non-killable rows keep a disabled button, as before.
            if (!isSelf) {
                val killButton = Button(this).apply {
                    text = "Kill"
                    isEnabled = RamListPresentation.canShowKillButton(app, packageName)
                    setOnClickListener { confirmKill(app.packageName) }
                }
                row.addView(killButton)
            }
            listView.addView(row)
        }
        showRecentApps(snap)
        val rogues = snap.rogues().size
        statusView.text = "Scanned just now. Rogues: $rogues. " +
            "Kills are one package at a time — there is no kill-all button. " +
            "Eliminate now kills rogue apps only, one at a time."
    }

    private fun showRecentApps(snap: RamSnapshot) {
        recentListView.removeAllViews()
        if (!snap.usageAccessGranted) {
            recentHeaderView.text = "Recently used apps need Usage Access — " +
                "grant it to see which apps were used recently (no PSS for these rows)."
            usageAccessButton.visibility = View.VISIBLE
            return
        }
        usageAccessButton.visibility = View.GONE
        recentHeaderView.text = "Recently used apps (Usage Access — last 24 h, no PSS)"
        if (snap.recentApps.isEmpty()) {
            recentListView.addView(TextView(this).apply {
                text = "No recently-used apps in the last 24 h."
            })
            return
        }
        for (app in snap.recentApps) {
            recentListView.addView(TextView(this).apply {
                text = "${app.packageName}\n" +
                    "${RamListPresentation.lastUsedText(app, System.currentTimeMillis())}\n" +
                    RamListPresentation.pssText(app)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(6), 0, dp(6))
            })
        }
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

    // ---------- Elimination mode (manual one-tap only) ----------

    private fun startElimination() {
        if (eliminationRunning) return
        val gate = BlastGate.forContext(this)
        if (!gate.checkWrite("elimination") { reason ->
                EventLog.append(this, "ram", "ELIMINATION_SKIPPED", reason)
                eliminationResultView.text = "Elimination refused — blast active. No apps were killed."
            }) {
            return
        }
        eliminationResultView.text = "Scanning for rogue apps…"
        Thread({
            val snap = try {
                ramWatch.oneShotScan(this)
            } catch (e: Exception) {
                null
            }
            handler.post {
                if (snap == null) {
                    eliminationResultView.text = "Scan failed — try again."
                } else {
                    confirmElimination(snap)
                }
            }
        }, "BlastBuster-Elimination").start()
    }

    private fun confirmElimination(snap: RamSnapshot) {
        showSnapshot(snap)
        val targets = EliminationMode.selectRogues(snap, packageName)
        if (targets.isEmpty()) {
            eliminationResultView.text = "No rogue apps right now — nothing to eliminate."
            return
        }
        val list = targets.joinToString("\n") { "• ${it.packageName} (${it.pssKb / 1024} MB)" }
        AlertDialog.Builder(this)
            .setTitle("Eliminate ${targets.size} rogue app(s)?")
            .setMessage(
                "These apps are over 400 MB, growing, background and non-system:\n$list\n\n" +
                    "Each is killed one at a time and verified after 5 s. A kill may fail " +
                    "or the app may restart — the result will say so. This is a manual " +
                    "one-tap action, never automatic."
            )
            .setPositiveButton("Eliminate now") { _, _ -> executeElimination(targets) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeElimination(targets: List<RamAppEntry>) {
        eliminationRunning = true
        eliminateButton.isEnabled = false
        val pkgs = targets.map { it.packageName }
        EventLog.append(
            this, "ram", "ELIMINATION_STARTED",
            "Elimination started: ${pkgs.size} rogue app(s): ${pkgs.joinToString(", ")}",
            mapOf("count" to "${pkgs.size}", "packages" to pkgs.joinToString(","))
        )
        val outcomes = ArrayList<Pair<String, RamKiller.KillResult>>()
        var index = 0
        fun next() {
            if (index >= pkgs.size) {
                finishElimination(outcomes)
                return
            }
            val pkg = pkgs[index++]
            // A blast may have started mid-elimination: re-check before
            // every kill. RamKiller would block it too; stopping here
            // keeps the summary honest about what was not attempted.
            val gate = BlastGate.forContext(this)
            if (!gate.checkWrite("elimination $pkg") { reason ->
                    EventLog.append(this, "ram", "ELIMINATION_SKIPPED", reason)
                }) {
                outcomes.add(
                    pkg to RamKiller.KillResult(
                        RamKiller.KillOutcome.BLOCKED_BLAST_ACTIVE,
                        "blocked: blast started mid-elimination"
                    )
                )
                finishElimination(outcomes, stoppedForBlast = true)
                return
            }
            RamKiller.kill(this, pkg) { result ->
                outcomes.add(pkg to result)
                next()
            }
        }
        next()
    }

    private fun finishElimination(
        outcomes: List<Pair<String, RamKiller.KillResult>>,
        stoppedForBlast: Boolean = false
    ) {
        eliminationRunning = false
        eliminateButton.isEnabled = true
        val killed = outcomes.count { it.second.outcome == RamKiller.KillOutcome.KILLED }
        val restarted = outcomes.count { it.second.outcome == RamKiller.KillOutcome.RESTARTED }
        val failed = outcomes.count { it.second.outcome == RamKiller.KillOutcome.FAILED }
        val denied = outcomes.count { it.second.outcome == RamKiller.KillOutcome.DENIED }
        val cooldown = outcomes.count { it.second.outcome == RamKiller.KillOutcome.COOLDOWN }
        val blocked = outcomes.count { it.second.outcome == RamKiller.KillOutcome.BLOCKED_BLAST_ACTIVE }
        val perPkg = outcomes.joinToString("; ") { "${it.first}: ${it.second.outcome.name.lowercase()}" }
        val summary = "Elimination finished: $killed killed, $restarted restarted, $failed failed, " +
            "$denied denied, $cooldown on cooldown, $blocked blocked by blast" +
            (if (stoppedForBlast) " — stopped early: blast started" else "") +
            ". $perPkg"
        EventLog.append(this, "ram", "ELIMINATION_FINISHED", summary)
        eliminationResultView.text = summary
        Toast.makeText(this, "Elimination: $killed killed of ${outcomes.size}", Toast.LENGTH_LONG).show()
        handler.postDelayed({ refresh() }, RamKiller.VERIFY_DELAY_MS + 1000L)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
