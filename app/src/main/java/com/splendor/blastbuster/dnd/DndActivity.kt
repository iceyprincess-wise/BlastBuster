package com.splendor.blastbuster.dnd

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.EventLog
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * DND screen (DESIGN.md §5.2, §6.3): schedule editor, current-state
 * card, change history with culprit + tier label.
 *
 * The editor manages one rule (the common case: a nightly window). If
 * the stored schedule holds more rules, the editor shows the first and
 * says how many others exist — the full list still enforces via
 * [DndSchedule].
 *
 * Culprit labels are honest: "seen in system log" is the only proof
 * tier; the foreground tiers are labelled as inference, and a row with
 * no evidence says "unknown", never a guess.
 */
class DndActivity : Activity() {

    private lateinit var stateView: TextView
    private lateinit var historyView: LinearLayout
    private lateinit var startPicker: TimePicker
    private lateinit var endPicker: TimePicker
    private val dayButtons = LinkedHashMap<DayOfWeek, Button>()
    private val selectedDays = LinkedHashSet<DayOfWeek>()
    private val handler = Handler(Looper.getMainLooper())
    private val dndGuard = DndGuard()

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
            text = "Do Not Disturb"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Enforces your schedule: when DND should be on and it is off " +
                "(or the other way round), the guard puts it back within " +
                "60 seconds. A culprit is only named when it was observed — " +
                "\"seen in system log\" is proof, \"app in front\" is an " +
                "inference, and no evidence means \"unknown\"."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(12))
        })
        if (Prefs.isBlasting(this)) {
            root.addView(TextView(this).apply {
                text = "DND control paused — blast active."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        stateView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(stateView)

        val grantButton = Button(this).apply {
            text = "Open Do Not Disturb access settings"
            setOnClickListener {
                try {
                    startActivity(
                        android.content.Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    )
                } catch (_: Exception) {
                    Toast.makeText(this@DndActivity, "Could not open settings.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(grantButton)

        root.addView(TextView(this).apply {
            text = "Schedule (one nightly rule)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(6))
        })
        val daysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (day in DayOfWeek.values()) {
            val button = Button(this).apply {
                text = day.name.take(3)
                setOnClickListener {
                    if (selectedDays.contains(day)) selectedDays.remove(day)
                    else selectedDays.add(day)
                    refreshDayButtons()
                }
            }
            dayButtons[day] = button
            daysRow.addView(button)
        }
        root.addView(daysRow)
        root.addView(TextView(this).apply {
            text = "DND on from:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(10), 0, 0)
        })
        startPicker = TimePicker(this).apply { setIs24HourView(true) }
        root.addView(startPicker)
        root.addView(TextView(this).apply {
            text = "DND off at:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        endPicker = TimePicker(this).apply { setIs24HourView(true) }
        root.addView(endPicker)
        val saveButton = Button(this).apply {
            text = "Save schedule"
            setOnClickListener { saveSchedule() }
        }
        root.addView(saveButton)
        val clearButton = Button(this).apply {
            text = "Clear schedule (no enforcement)"
            setOnClickListener {
                Prefs.setDndScheduleJson(this@DndActivity, "[]")
                loadScheduleIntoEditor()
                refreshState()
                Toast.makeText(this@DndActivity, "Schedule cleared.", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clearButton)
        val checkButton = Button(this).apply {
            text = "Check now"
            setOnClickListener {
                Thread({
                    dndGuard.checkOnce(this@DndActivity)
                    handler.post { refreshState() }
                }, "BlastBuster-DndActivity").start()
            }
        }
        root.addView(checkButton)

        root.addView(TextView(this).apply {
            text = "Recent DND changes"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(6))
        })
        historyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyView)

        loadScheduleIntoEditor()
        refreshState()
    }

    private fun refreshDayButtons() {
        for ((day, button) in dayButtons) {
            button.alpha = if (selectedDays.contains(day)) 1.0f else 0.45f
        }
    }

    private fun loadScheduleIntoEditor() {
        val rules = DndSchedule.decode(Prefs.dndScheduleJson(this))
        val first = rules.firstOrNull()
        selectedDays.clear()
        if (first != null) {
            selectedDays.addAll(first.days)
            startPicker.hour = first.start.hour
            startPicker.minute = first.start.minute
            endPicker.hour = first.end.hour
            endPicker.minute = first.end.minute
        } else {
            selectedDays.addAll(DayOfWeek.values().toList())
            startPicker.hour = 22
            startPicker.minute = 0
            endPicker.hour = 7
            endPicker.minute = 0
        }
        refreshDayButtons()
    }

    private fun saveSchedule() {
        if (selectedDays.isEmpty()) {
            Toast.makeText(this, "Pick at least one day.", Toast.LENGTH_SHORT).show()
            return
        }
        val rule = DndRule(
            days = selectedDays.toSet(),
            start = LocalTime.of(startPicker.hour, startPicker.minute),
            end = LocalTime.of(endPicker.hour, endPicker.minute),
            mode = DndMode.ON
        )
        Prefs.setDndScheduleJson(this, DndSchedule.encode(listOf(rule)))
        Toast.makeText(this, "Schedule saved.", Toast.LENGTH_SHORT).show()
        refreshState()
        // Apply right now instead of waiting for the next 60 s tick —
        // the saved schedule is what the guard reads, so a check now
        // enforces it immediately when enforcement is on.
        Thread({
            dndGuard.checkOnce(this@DndActivity)
            handler.post { refreshState() }
        }, "BlastBuster-DndActivity").start()
    }

    private fun refreshState() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val granted = nm.isNotificationPolicyAccessGranted
        val isDnd = nm.currentInterruptionFilter !=
            NotificationManager.INTERRUPTION_FILTER_ALL
        val rules = DndSchedule.decode(Prefs.dndScheduleJson(this))
        val expected = DndSchedule.modeAt(
            rules, java.time.LocalDateTime.now()
        )
        val readLogsGranted = DndCulprit.hasReadLogsGrant(this)
        val tierText = DndCulprit.activeTierDescription(readLogsGranted, granted)
        val fightText = if (dndGuard.isFightDetected()) {
            val suspect = dndGuard.lastCulprit()?.let {
                " Suspected app: ${it.packageName} (${it.label})."
            } ?: " No culprit observed — see history."
            "\nFight detected: the guard stopped re-applying after 3 re-applies in an hour.$suspect " +
                "It tries again after the hour passes — you decide what to do."
        } else ""
        stateView.text = "DND access: ${if (granted) "granted" else "NOT granted — open settings above"}\n" +
            "DND now: ${if (isDnd) "ON" else "OFF"}\n" +
            "Schedule wants: ${expected?.name ?: "nothing right now"}\n" +
            "Rules stored: ${rules.size}" +
            (if (rules.size > 1) " (editor shows the first)" else "") +
            "\nEnforcement: ${if (Prefs.dndEnabled(this)) "on" else "off — turn it on with the switch on the DND tab in the hub"}" +
            "\nREAD_LOGS: ${if (readLogsGranted) "granted" else "not granted"}" +
            "\n$tierText$fightText"
        historyView.removeAllViews()
        val events = try {
            EventLog.readTail(this, 100).filter { it.module == "dnd" }.take(20)
        } catch (_: Exception) {
            emptyList()
        }
        if (events.isEmpty()) {
            historyView.addView(TextView(this).apply { text = "No DND changes logged yet." })
        }
        for (event in events) {
            historyView.addView(TextView(this).apply {
                text = "${java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.US).format(java.util.Date(event.ts))} — ${event.summary}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(4), 0, dp(4))
            })
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
