package com.splendor.blastbuster.net

/**
 * Signal grading (DESIGN.md §4.2, §5.3). Pure Kotlin — no Android imports —
 * so it is JVM-testable.
 *
 * Honesty rule, enforced here and in the UI: a grade describes the radio
 * conditions the phone can actually measure. It never claims the phone can
 * "boost" a signal that is not there. NONE means the radio reports no
 * usable signal (or reports nothing at all), not "the app failed".
 *
 * Grades are ordered worst-first so ordinal arithmetic works for the drop
 * detector: NONE(0) < POOR(1) < FAIR(2) < GOOD(3) < EXCELLENT(4).
 */
enum class SignalGrade {
    NONE, POOR, FAIR, GOOD, EXCELLENT
}

/** Radio transport, as reported by TelephonyManager. UNKNOWN = could not tell. */
enum class Transport {
    UNKNOWN, GSM, CDMA, UMTS, LTE, NR
}

object SignalGrader {

    /**
     * Grade from a dBm reading for the given transport.
     *
     * Thresholds (RSRP for LTE/NR, RSSI/RSCP for the older transports) follow
     * the usual field-engineering bands; they are starting values for a
     * 4 GB phone, not a law of nature — see DESIGN.md §5.1 for the same
     * caveat applied to RAM thresholds.
     *
     * Unknown/unusable readings ([Int.MAX_VALUE], the value
     * CellSignalStrength reports when it has nothing, or any non-negative
     * dBm) grade as NONE: "no measurable signal" is the honest answer,
     * not a guess.
     */
    fun gradeFromDbm(dbm: Int, transport: Transport): SignalGrade {
        if (dbm >= 0 || dbm < -150) return SignalGrade.NONE
        return when (transport) {
            Transport.LTE, Transport.NR -> when {
                dbm >= -80 -> SignalGrade.EXCELLENT
                dbm >= -90 -> SignalGrade.GOOD
                dbm >= -100 -> SignalGrade.FAIR
                dbm >= -110 -> SignalGrade.POOR
                else -> SignalGrade.NONE
            }
            Transport.GSM, Transport.CDMA, Transport.UMTS -> when {
                dbm >= -70 -> SignalGrade.EXCELLENT
                dbm >= -85 -> SignalGrade.GOOD
                dbm >= -95 -> SignalGrade.FAIR
                dbm >= -105 -> SignalGrade.POOR
                else -> SignalGrade.NONE
            }
            Transport.UNKNOWN -> when {
                dbm >= -70 -> SignalGrade.EXCELLENT
                dbm >= -85 -> SignalGrade.GOOD
                dbm >= -100 -> SignalGrade.FAIR
                dbm >= -110 -> SignalGrade.POOR
                else -> SignalGrade.NONE
            }
        }
    }

    /**
     * Grade from Android's 0–4 signal level
     * ([android.telephony.SignalStrength.getLevel] /
     * [android.telephony.CellSignalStrength.getLevel]).
     * Level 0 is "no service / unusable" and grades as NONE.
     * Out-of-range levels grade as NONE rather than crashing.
     */
    fun gradeFromLevel(level: Int): SignalGrade = when (level) {
        0 -> SignalGrade.NONE
        1 -> SignalGrade.POOR
        2 -> SignalGrade.FAIR
        3 -> SignalGrade.GOOD
        4 -> SignalGrade.EXCELLENT
        else -> SignalGrade.NONE
    }

    /**
     * Smoothing over the last samples (DESIGN.md §4.2: "last-3 median so
     * one bad sample never triggers a flick").
     *
     * Takes the most recent up-to-3 grades, oldest-first or newest-first —
     * order does not matter, only the multiset — and returns their median
     * grade. With 1 sample that sample wins; with 2 samples the *lower*
     * grade wins (conservative: a single good sample must not mask a bad
     * one, and a single bad sample must not drag a good run down — the
     * median of 3 already handles the one-outlier case).
     */
    fun smoothGrades(recent: List<SignalGrade>): SignalGrade {
        if (recent.isEmpty()) return SignalGrade.NONE
        val last3 = recent.takeLast(3).map { it.ordinal }.sorted()
        val medianOrdinal = when (last3.size) {
            1 -> last3[0]
            2 -> last3[0] // conservative: the worse of the two
            else -> last3[1]
        }
        return SignalGrade.entries[medianOrdinal]
    }

    /**
     * A "drop" (DESIGN.md §5.3): the grade fell by two or more levels.
     * One level of wobble is normal radio behaviour and is not a drop.
     */
    fun isDrop(before: SignalGrade, after: SignalGrade): Boolean =
        after.ordinal <= before.ordinal - 2

    /** True when the grade means the phone can currently pass data. */
    fun isUsable(grade: SignalGrade): Boolean = grade >= SignalGrade.FAIR
}
