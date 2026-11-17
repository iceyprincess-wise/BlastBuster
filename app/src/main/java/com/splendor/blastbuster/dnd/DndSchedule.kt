package com.splendor.blastbuster.dnd

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** What the schedule wants DND to be: ON (alarms only) or OFF. */
enum class DndMode { ON, OFF }

/**
 * One schedule rule: on [days], between [start] and [end], DND should be
 * [mode]. When [start] is after [end] the interval wraps overnight
 * (e.g. 22:00–07:00 means 22:00 on a listed day until 07:00 the next
 * morning). When [start] equals [end] the rule covers the whole listed day.
 */
data class DndRule(
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val mode: DndMode
)

/**
 * Schedule model (DESIGN.md §5.2). Pure Kotlin (java.time only), no
 * Android imports, JVM-tested.
 *
 * Rules are tried in list order; the first matching rule wins — that is
 * the documented overlap rule, so users (and the UI) put the most
 * specific rule first.
 */
object DndSchedule {

    /** The mode the schedule wants at [dateTime], or null when no rule matches. */
    fun modeAt(rules: List<DndRule>, dateTime: LocalDateTime): DndMode? {
        for (rule in rules) {
            if (matches(rule, dateTime.dayOfWeek, dateTime.toLocalTime())) return rule.mode
        }
        return null
    }

    /** Day/time form of [modeAt], convenient for tests and the UI. */
    fun modeAt(rules: List<DndRule>, day: DayOfWeek, time: LocalTime): DndMode? {
        for (rule in rules) {
            if (matches(rule, day, time)) return rule.mode
        }
        return null
    }

    private fun matches(rule: DndRule, day: DayOfWeek, time: LocalTime): Boolean {
        if (rule.start == rule.end) {
            // Whole-day rule.
            return day in rule.days
        }
        if (rule.start.isBefore(rule.end)) {
            // Same-day interval, e.g. 07:00–22:00.
            return day in rule.days && !time.isBefore(rule.start) && time.isBefore(rule.end)
        }
        // Overnight wrap, e.g. 22:00–07:00: the evening part belongs to a
        // listed day, the morning part to the day after a listed day.
        if (day in rule.days && !time.isBefore(rule.start)) return true
        val yesterday = day.minus(1)
        if (yesterday in rule.days && time.isBefore(rule.end)) return true
        return false
    }

    /**
     * The next moment after [from] at which the scheduled mode changes,
     * or null when no change happens in the next 8 days (e.g. an empty
     * schedule, or a single whole-week rule). Found by a minute-by-minute
     * scan: schedules are weekly, so 8 days always covers a full cycle
     * plus a margin, and the scan is obviously correct.
     */
    fun nextTransition(rules: List<DndRule>, from: LocalDateTime): LocalDateTime? {
        val current = modeAt(rules, from)
        var t = from.plusMinutes(1).withSecond(0).withNano(0)
        val end = from.plusDays(8)
        while (!t.isAfter(end)) {
            if (modeAt(rules, t) != current) return t
            t = t.plusMinutes(1)
        }
        return null
    }

    /** Example night rule: every day 22:00–07:00, DND on. */
    fun defaultNightRule(): List<DndRule> = listOf(
        DndRule(DayOfWeek.values().toSet(), LocalTime.of(22, 0), LocalTime.of(7, 0), DndMode.ON)
    )

    // ---------- JSON storage (what Prefs keeps) ----------

    /**
     * Encodes rules as `[{"days":[1,2,3,4,5,6,7],"start":"22:00",
     * "end":"07:00","mode":"on"}]` — day numbers are DayOfWeek values
     * (1 = Monday … 7 = Sunday).
     */
    fun encode(rules: List<DndRule>): String {
        val sb = StringBuilder("[")
        for ((i, rule) in rules.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"days\":[")
            sb.append(rule.days.sortedBy { it.value }.joinToString(",") { it.value.toString() })
            sb.append("],\"start\":\"").append(rule.start.toString()).append('"')
            sb.append(",\"end\":\"").append(rule.end.toString()).append('"')
            sb.append(",\"mode\":\"")
                .append(if (rule.mode == DndMode.ON) "on" else "off").append("\"}")
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Decodes [encode]'s format. Malformed input yields an empty list —
     * a corrupt schedule disables enforcement rather than crashing it.
     * Individual malformed rules are skipped, not fatal.
     */
    fun decode(json: String): List<DndRule> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        val out = ArrayList<DndRule>()
        var i = 1
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (c == ']') break
            if (c == ',' || c.isWhitespace()) {
                i++
                continue
            }
            if (c != '{') return emptyList()
            var depth = 0
            val start = i
            while (i < trimmed.length) {
                if (trimmed[i] == '{') depth++
                if (trimmed[i] == '}') {
                    depth--
                    if (depth == 0) {
                        i++
                        break
                    }
                }
                i++
            }
            decodeRule(trimmed.substring(start, i))?.let { out.add(it) }
        }
        return out
    }

    private fun decodeRule(obj: String): DndRule? {
        val days = extractInts(obj, "\"days\"") ?: return null
        val daySet = days.mapNotNull { v ->
            if (v in 1..7) DayOfWeek.of(v) else null
        }.toSet()
        if (daySet.isEmpty()) return null
        val start = extractString(obj, "\"start\"")?.let { parseTime(it) } ?: return null
        val end = extractString(obj, "\"end\"")?.let { parseTime(it) } ?: return null
        val modeRaw = extractString(obj, "\"mode\"") ?: return null
        val mode = when (modeRaw.lowercase()) {
            "on" -> DndMode.ON
            "off" -> DndMode.OFF
            else -> return null
        }
        return DndRule(daySet, start, end, mode)
    }

    private fun parseTime(raw: String): LocalTime? = try {
        LocalTime.parse(raw)
    } catch (_: Exception) {
        null
    }

    private fun extractString(obj: String, key: String): String? {
        val k = obj.indexOf(key)
        if (k < 0) return null
        val colon = obj.indexOf(':', k + key.length)
        if (colon < 0) return null
        val open = obj.indexOf('"', colon)
        if (open < 0) return null
        val close = obj.indexOf('"', open + 1)
        if (close < 0) return null
        return obj.substring(open + 1, close)
    }

    private fun extractInts(obj: String, key: String): List<Int>? {
        val k = obj.indexOf(key)
        if (k < 0) return null
        val open = obj.indexOf('[', k + key.length)
        if (open < 0) return null
        val close = obj.indexOf(']', open)
        if (close < 0) return null
        val inner = obj.substring(open + 1, close).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(",").mapNotNull { it.trim().toIntOrNull() }
    }
}
