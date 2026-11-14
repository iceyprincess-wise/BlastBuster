package com.splendor.blastbuster

/**
 * Pure PCM siren synthesis. No Android classes: the tone math is verifiable
 * on the JVM (see the sandbox test that compiles this file standalone).
 *
 * The siren is a smooth FM wail: the instantaneous frequency sweeps
 * 700 Hz -> 1400 Hz -> 700 Hz over one 1-second cycle. Frequency is a
 * raised cosine, so the phase is continuous AND its derivative matches at
 * the loop point — no clicks, unlike an abrupt two-tone switch (whose
 * slope flips at the switch and at the loop).
 *
 * phase(t) = 2π * (fMid*t - fAmp/(2π) * sin(2π*t)), with fMid = 1050 Hz
 * and fAmp = 350 Hz. At t = 0 and t = 1 the phase is a whole number of
 * cycles (1050), so the loop is sample-exact.
 */
object Siren {
    const val SAMPLE_RATE = 22050
    const val LOW_HZ = 700.0
    const val HIGH_HZ = 1400.0
    const val CYCLE_SEC = 1.0

    /** One full wail cycle, 16-bit mono PCM. */
    fun generateCycle(): ShortArray {
        val total = (SAMPLE_RATE * CYCLE_SEC).toInt()
        val out = ShortArray(total)
        val fMid = (LOW_HZ + HIGH_HZ) / 2.0
        val fAmp = (HIGH_HZ - LOW_HZ) / 2.0
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = 2.0 * Math.PI *
                (fMid * t - fAmp / (2.0 * Math.PI) * Math.sin(2.0 * Math.PI * t))
            out[i] = (Math.sin(phase) * 0.9 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Peak absolute sample value, for tests. */
    fun peak(pcm: ShortArray): Int {
        var m = 0
        for (s in pcm) {
            val a = kotlin.math.abs(s.toInt())
            if (a > m) m = a
        }
        return m
    }

    /** Number of zero crossings in [from, to). A tone averaging [freq] Hz
     *  over [seconds] has about 2 * freq * seconds crossings. */
    fun zeroCrossings(pcm: ShortArray, from: Int, to: Int): Int {
        var n = 0
        for (i in from + 1 until to) {
            if ((pcm[i - 1] < 0 && pcm[i] >= 0) || (pcm[i - 1] >= 0 && pcm[i] < 0)) n++
        }
        return n
    }

    /** Largest single-sample jump. A step discontinuity would be close to
     *  twice the peak; a smooth FM tone stays below the max slope of its
     *  highest instantaneous frequency (2π * 1400 * 0.9 / 22050 of full
     *  scale, about 11800). */
    fun maxStep(pcm: ShortArray): Int {
        var m = 0
        for (i in 1 until pcm.size) {
            val d = kotlin.math.abs(pcm[i].toInt() - pcm[i - 1].toInt())
            if (d > m) m = d
        }
        return m
    }
}
