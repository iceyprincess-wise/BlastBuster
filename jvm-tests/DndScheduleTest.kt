import com.splendor.blastbuster.dnd.DndMode
import com.splendor.blastbuster.dnd.DndRule
import com.splendor.blastbuster.dnd.DndSchedule
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

// DndSchedule — DESIGN.md §7.1 target: 18 tests.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }

    val allDays = DayOfWeek.values().toSet()
    val night = DndRule(allDays, LocalTime.of(22, 0), LocalTime.of(7, 0), DndMode.ON)
    val day = DndRule(allDays, LocalTime.of(7, 0), LocalTime.of(22, 0), DndMode.OFF)
    val nightRules = listOf(night)

    // 1. Overnight: on at 23:00.
    check("overnight on at 23:00", DndSchedule.modeAt(nightRules, DayOfWeek.TUESDAY, LocalTime.of(23, 0)) == DndMode.ON)
    // 2. Overnight: on at 06:59 next morning.
    check("overnight on at 06:59", DndSchedule.modeAt(nightRules, DayOfWeek.WEDNESDAY, LocalTime.of(6, 59)) == DndMode.ON)
    // 3. Overnight: off at 07:00 sharp (end is exclusive).
    check("overnight off at 07:00", DndSchedule.modeAt(nightRules, DayOfWeek.WEDNESDAY, LocalTime.of(7, 0)) == null)
    // 4. Overnight: off at 12:00.
    check("overnight off at noon", DndSchedule.modeAt(nightRules, DayOfWeek.TUESDAY, LocalTime.of(12, 0)) == null)
    // 5. Overnight: on exactly at start 22:00 (start is inclusive).
    check("overnight on at 22:00", DndSchedule.modeAt(nightRules, DayOfWeek.TUESDAY, LocalTime.of(22, 0)) == DndMode.ON)
    // 6. Day rule: off at 12:00.
    check("day rule off at noon", DndSchedule.modeAt(listOf(day), DayOfWeek.TUESDAY, LocalTime.of(12, 0)) == DndMode.OFF)
    // 7. Day rule: no opinion at 23:00.
    check("day rule silent at night", DndSchedule.modeAt(listOf(day), DayOfWeek.TUESDAY, LocalTime.of(23, 0)) == null)
    // 8. Midnight boundary: 00:00 belongs to the previous day's night rule.
    check("midnight belongs to prior night", DndSchedule.modeAt(nightRules, DayOfWeek.WEDNESDAY, LocalTime.of(0, 0)) == DndMode.ON)
    // 9. Weekday-only rule does not fire on Sunday morning…
    val weekdayNight = DndRule(
        setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        LocalTime.of(22, 0), LocalTime.of(7, 0), DndMode.ON
    )
    check(
        "weekday night covers Sat 06:00 (Fri night)",
        DndSchedule.modeAt(listOf(weekdayNight), DayOfWeek.SATURDAY, LocalTime.of(6, 0)) == DndMode.ON
    )
    // 10. …but not Sunday 06:00 (Saturday night is not listed).
    check(
        "weekday night skips Sun 06:00",
        DndSchedule.modeAt(listOf(weekdayNight), DayOfWeek.SUNDAY, LocalTime.of(6, 0)) == null
    )
    // 11. …and not Monday 06:00 (Sunday night is not listed).
    check(
        "weekday night skips Mon 06:00",
        DndSchedule.modeAt(listOf(weekdayNight), DayOfWeek.MONDAY, LocalTime.of(6, 0)) == null
    )
    // 12. Overlapping rules: first match wins.
    val firstWins = listOf(
        DndRule(allDays, LocalTime.of(21, 0), LocalTime.of(23, 0), DndMode.OFF),
        DndRule(allDays, LocalTime.of(22, 0), LocalTime.of(7, 0), DndMode.ON)
    )
    check(
        "first match wins on overlap",
        DndSchedule.modeAt(firstWins, DayOfWeek.TUESDAY, LocalTime.of(22, 30)) == DndMode.OFF
    )
    // 13. Empty schedule: no opinion, no transition.
    check("empty schedule silent", DndSchedule.modeAt(emptyList(), DayOfWeek.TUESDAY, LocalTime.of(22, 30)) == null)
    check(
        "empty schedule no transition",
        DndSchedule.nextTransition(emptyList(), LocalDateTime.of(2026, 11, 17, 21, 0)) == null
    )
    // 14. nextTransition: 21:00 -> 22:00 for the night rule.
    check(
        "next transition 21:00 -> 22:00",
        DndSchedule.nextTransition(nightRules, LocalDateTime.of(2026, 11, 17, 21, 0)) ==
            LocalDateTime.of(2026, 11, 17, 22, 0)
    )
    // 15. nextTransition: 23:00 -> 07:00 next day.
    check(
        "next transition 23:00 -> 07:00",
        DndSchedule.nextTransition(nightRules, LocalDateTime.of(2026, 11, 17, 23, 0)) ==
            LocalDateTime.of(2026, 11, 18, 7, 0)
    )
    // 16. Whole-day rule (start == end) covers the listed day.
    val whole = DndRule(setOf(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalTime.of(9, 0), DndMode.ON)
    check(
        "whole-day rule Monday",
        DndSchedule.modeAt(listOf(whole), DayOfWeek.MONDAY, LocalTime.of(3, 0)) == DndMode.ON &&
            DndSchedule.modeAt(listOf(whole), DayOfWeek.TUESDAY, LocalTime.of(3, 0)) == null
    )
    // 17. Encode/decode round-trip preserves rules.
    val rules = listOf(night, DndRule(setOf(DayOfWeek.MONDAY), LocalTime.of(8, 0), LocalTime.of(9, 0), DndMode.OFF))
    check("encode/decode round-trip", DndSchedule.decode(DndSchedule.encode(rules)) == rules)
    // 18. Malformed JSON decodes to empty, never throws.
    check(
        "malformed decode is empty",
        DndSchedule.decode("not json") == emptyList<DndRule>() &&
            DndSchedule.decode("{\"days\":[9]}") == emptyList<DndRule>() &&
            DndSchedule.decode("") == emptyList<DndRule>()
    )
    // 19. LocalDateTime overload agrees with the day/time overload.
    check(
        "LocalDateTime overload agrees",
        DndSchedule.modeAt(nightRules, LocalDateTime.of(2026, 11, 17, 23, 30)) == DndMode.ON &&
            DndSchedule.modeAt(nightRules, LocalDateTime.of(2026, 11, 17, 12, 0)) == null
    )
    // 20. Default night rule is every day 22:00-07:00 ON.
    check(
        "default night rule",
        DndSchedule.defaultNightRule().size == 1 &&
            DndSchedule.defaultNightRule()[0].days == allDays &&
            DndSchedule.defaultNightRule()[0].mode == DndMode.ON
    )

    println("DndScheduleTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
