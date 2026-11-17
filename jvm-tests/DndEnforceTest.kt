import android.app.NotificationManager
import com.splendor.blastbuster.dnd.DndAction
import com.splendor.blastbuster.dnd.DndCulprit
import com.splendor.blastbuster.dnd.DndFightTracker
import com.splendor.blastbuster.dnd.DndGuard
import com.splendor.blastbuster.dnd.DndMode
import com.splendor.blastbuster.dnd.DndReapplyLimiter
import com.splendor.blastbuster.dnd.DndRule
import com.splendor.blastbuster.dnd.DndSchedule
import com.splendor.blastbuster.dnd.decideDndAction
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

// DND enforcement wiring (v1.3 item 5): the schedule the UI saves is the
// schedule the guard reads; the fight rule (3 re-applies in an hour,
// then stop, logged once per episode); the culprit tier actually active.
// Follows the same plain-main() harness pattern as DndScheduleTest.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }

    val min = 60_000L
    val allDays = DayOfWeek.values().toSet()
    val night = DndRule(allDays, LocalTime.of(22, 0), LocalTime.of(7, 0), DndMode.ON)

    // --- 1. UI save -> guard read: the exact round-trip both sides use ---
    // DndActivity.saveSchedule stores DndSchedule.encode(listOf(rule)) in
    // Prefs "dnd_schedule"; DndGuard.checkNow reads it back with
    // DndSchedule.decode + modeAt(now).
    val saved = DndSchedule.encode(listOf(night))
    val readBack = DndSchedule.decode(saved)
    check("saved schedule reads back identical", readBack == listOf(night))
    check("guard reads ON at 23:00", DndSchedule.modeAt(readBack, DayOfWeek.TUESDAY, LocalTime.of(23, 0)) == DndMode.ON)
    check("guard reads ON at 06:59 next morning", DndSchedule.modeAt(readBack, DayOfWeek.WEDNESDAY, LocalTime.of(6, 59)) == DndMode.ON)
    check("guard reads nothing at noon", DndSchedule.modeAt(readBack, DayOfWeek.TUESDAY, LocalTime.of(12, 0)) == null)
    check("guard reads ON at 22:00 sharp", DndSchedule.modeAt(readBack, DayOfWeek.TUESDAY, LocalTime.of(22, 0)) == DndMode.ON)
    check("guard reads nothing at 07:00 sharp", DndSchedule.modeAt(readBack, DayOfWeek.WEDNESDAY, LocalTime.of(7, 0)) == null)
    check(
        "next transition 21:00 -> 22:00",
        DndSchedule.nextTransition(readBack, LocalDateTime.of(2026, 11, 17, 21, 0)) ==
            LocalDateTime.of(2026, 11, 17, 22, 0)
    )
    check(
        "next transition 23:00 -> 07:00 next day",
        DndSchedule.nextTransition(readBack, LocalDateTime.of(2026, 11, 17, 23, 0)) ==
            LocalDateTime.of(2026, 11, 18, 7, 0)
    )

    // --- Day-of-week wrap: a Friday-night rule covers Saturday 06:00 ---
    val weekdayNight = DndRule(
        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        LocalTime.of(22, 0), LocalTime.of(7, 0), DndMode.ON
    )
    val weekdayBack = DndSchedule.decode(DndSchedule.encode(listOf(weekdayNight)))
    check(
        "weekday night covers Sat 06:00",
        DndSchedule.modeAt(weekdayBack, DayOfWeek.SATURDAY, LocalTime.of(6, 0)) == DndMode.ON
    )
    check(
        "weekday night skips Sun 06:00",
        DndSchedule.modeAt(weekdayBack, DayOfWeek.SUNDAY, LocalTime.of(6, 0)) == null
    )

    // --- An OFF rule round-trips too, and the guard acts on it ---
    val dayOff = DndRule(allDays, LocalTime.of(7, 0), LocalTime.of(22, 0), DndMode.OFF)
    val offBack = DndSchedule.decode(DndSchedule.encode(listOf(dayOff)))
    check("OFF rule reads OFF at noon", DndSchedule.modeAt(offBack, DayOfWeek.TUESDAY, LocalTime.of(12, 0)) == DndMode.OFF)
    check(
        "OFF rule + DND on -> APPLY_OFF",
        decideDndAction(DndSchedule.modeAt(offBack, DayOfWeek.TUESDAY, LocalTime.of(12, 0)), true) == DndAction.APPLY_OFF
    )
    check(
        "ON rule + DND off -> APPLY_ON",
        decideDndAction(DndSchedule.modeAt(readBack, DayOfWeek.TUESDAY, LocalTime.of(23, 0)), false) == DndAction.APPLY_ON
    )

    // --- Shipped defaults: no schedule, no opinion, no enforcement ---
    check("fresh-install default schedule is empty", DndSchedule.decode("[]") == emptyList<DndRule>())
    check(
        "empty schedule means no action",
        decideDndAction(DndSchedule.modeAt(emptyList(), DayOfWeek.TUESDAY, LocalTime.of(23, 0)), false) == DndAction.NONE
    )
    check(
        "malformed schedule disables enforcement, never throws",
        DndSchedule.decode("not json") == emptyList<DndRule>() &&
            decideDndAction(DndSchedule.modeAt(DndSchedule.decode("not json"), DayOfWeek.TUESDAY, LocalTime.of(23, 0)), false) == DndAction.NONE
    )

    // --- The loop re-applies within 60 s; alarms-only is the filter (D-12) ---
    check("guard tick is 60 s", DndGuard.CHECK_INTERVAL_MS == 60_000L)
    check(
        "ON filter is alarms-only",
        DndGuard.FILTER_ON == NotificationManager.INTERRUPTION_FILTER_ALARMS
    )
    check(
        "OFF filter is all",
        DndGuard.FILTER_OFF == NotificationManager.INTERRUPTION_FILTER_ALL
    )

    // --- 2. Fight detection: 3 re-applies in an hour, then stop ---
    val lim = DndReapplyLimiter()
    check("first re-apply allowed", lim.allow(0L, DndAction.APPLY_ON))
    lim.record(0L, DndAction.APPLY_ON)
    check("same direction inside 5 min blocked", !lim.allow(2 * min, DndAction.APPLY_ON))
    check("opposite direction inside 5 min allowed", lim.allow(2 * min, DndAction.APPLY_OFF))
    lim.record(6 * min, DndAction.APPLY_ON)
    lim.record(12 * min, DndAction.APPLY_OFF)
    check("fight detected at 3 re-applies in an hour", lim.isFight(13 * min))
    check("fourth re-apply blocked in a fight", !lim.allow(13 * min, DndAction.APPLY_ON))
    check("fight clears after the hour passes", !lim.isFight(70 * min))
    check("re-apply allowed again after the hour", lim.allow(70 * min, DndAction.APPLY_ON))

    // --- Fight-episode tracker: log once per episode, flag clears ---
    val tracker = DndFightTracker()
    check("first fight observation logs", tracker.noteFight(true))
    check("second observation of same fight does not log", !tracker.noteFight(true))
    check("fight flag active during episode", tracker.isFightActive())
    check("no fight clears the flag", !tracker.noteFight(false) && !tracker.isFightActive())
    check("a later fight logs again", tracker.noteFight(true))
    check("flag stays active until cleared", tracker.isFightActive())

    // --- 4. Culprit tier actually active, from the two grants ---
    check(
        "READ_LOGS + notification access -> logcat tier",
        DndCulprit.activeTier(true, true) == DndCulprit.Tier.LOGCAT
    )
    check(
        "notification access only -> listener tier",
        DndCulprit.activeTier(false, true) == DndCulprit.Tier.LISTENER
    )
    check(
        "READ_LOGS alone -> polling tier (listener never fires)",
        DndCulprit.activeTier(true, false) == DndCulprit.Tier.POLLING
    )
    check(
        "neither grant -> polling tier",
        DndCulprit.activeTier(false, false) == DndCulprit.Tier.POLLING
    )
    val logcatText = DndCulprit.activeTierDescription(true, true)
    check(
        "logcat tier says system log + Android 16 caveat",
        logcatText.contains("system log") && logcatText.contains("Android 16")
    )
    val listenerText = DndCulprit.activeTierDescription(false, true)
    check(
        "listener tier says inference + names READ_LOGS",
        listenerText.contains("inference") && listenerText.contains("READ_LOGS")
    )
    val pollingText = DndCulprit.activeTierDescription(false, false)
    check(
        "polling tier says 60 s + inference",
        pollingText.contains("60 s") && pollingText.contains("inference")
    )
    check(
        "polling tier with READ_LOGS says it is already granted",
        DndCulprit.activeTierDescription(true, false).contains("already granted")
    )

    // --- Polling-tier resolution: foreground app at detection time ---
    val polling = DndCulprit.resolve(null, "com.front.app", DndCulprit.Tier.POLLING)
    check(
        "polling tier labels the foreground app as inference",
        polling.packageName == "com.front.app" && polling.tier == DndCulprit.Tier.POLLING &&
            polling.label.contains("60 s")
    )
    val logcat = DndCulprit.resolve("com.evil.app", "com.front.app", DndCulprit.Tier.LISTENER)
    check(
        "logcat beats foreground",
        logcat.packageName == "com.evil.app" && logcat.tier == DndCulprit.Tier.LOGCAT &&
            logcat.label.contains("system log")
    )
    val unknown = DndCulprit.resolve(null, null, DndCulprit.Tier.LISTENER)
    check(
        "no evidence is unknown",
        unknown.packageName == null && unknown.tier == DndCulprit.Tier.UNKNOWN &&
            unknown.label.contains("unknown")
    )

    println("DndEnforceTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
