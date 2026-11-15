package com.splendor.blastbuster

/**
 * Pure PCM siren synthesis. No Android classes: the tone math is verifiable
 * on the JVM (see the sandbox test that compiles this file standalone).
 *
 * v1.1: the siren is still a smooth FM wail (instantaneous frequency sweeps
 * 700 Hz -> 1400 Hz -> 700 Hz over one 1-second cycle), but the waveform
 * is no longer a pure sine. Odd harmonics are added (3rd at 0.35, 5th at
 * 0.18), normalised by the true maximum of the sum (0.9406, measured):
 * a square-ish wave is perceptually louder than a sine at the same peak,
 * because the ear hears the extra harmonics. A 3 ms Hann fade at both ends
 * makes the loop sample-exact (both ends 0 with zero slope) — without it
 * the steep harmonic slope leaves a step at the loop point, a click once
 * a second. Absolute loudness is still capped by the phone's single
 * speaker.
 *
 * phase(t) = 2π * (fMid*t - fAmp/(2π) * sin(2π*t)), with fMid = 1050 Hz
 * and fAmp = 350 Hz.
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
        // Normalisation: the true maximum of
        // sin(p) + 0.35 sin(3p) + 0.18 sin(5p) over p is 0.9406 (measured by
        // numerical scan), not the 1.53 sum of amplitudes — the three terms
        // peak at different phases. Dividing by the true max puts the peak
        // at 0.95 of full scale.
        val norm = 0.9406
        // 3 ms Hann fade at both ends: the loop point is sample-exact
        // (both ends 0 with zero slope), so the AudioTrack loop has no
        // click. The fade is 6 ms per second of output — inaudible as a
        // gap, and far better than the step the raw wave leaves at the loop.
        val fade = (SAMPLE_RATE * 0.003).toInt() // 66 samples
        for (i in out.indices) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = 2.0 * Math.PI *
                (fMid * t - fAmp / (2.0 * Math.PI) * Math.sin(2.0 * Math.PI * t))
            val y = (Math.sin(phase) + 0.35 * Math.sin(3.0 * phase) +
                0.18 * Math.sin(5.0 * phase)) / norm
            val w = when {
                i < fade -> 0.5 * (1.0 - Math.cos(Math.PI * i / fade))
                i >= total - fade ->
                    0.5 * (1.0 - Math.cos(Math.PI * (total - 1 - i) / fade))
                else -> 1.0
            }
            out[i] = (y * w * 0.95 * Short.MAX_VALUE).toInt().toShort()
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
