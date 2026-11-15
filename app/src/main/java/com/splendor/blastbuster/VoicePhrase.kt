package com.splendor.blastbuster

/**
 * Pure voice-phrase matching. No Android imports, so it is verifiable on
 * the JVM (see the sandbox test).
 *
 * This matches WORDS, not the speaker: anyone saying the phrase near the
 * phone triggers it. Reliable speaker verification (proving it is the
 * owner's voice) is not available in a free on-device build.
 */
object VoicePhrase {
    fun normalize(s: String): String =
        s.lowercase().trim()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** True when [heard] contains [phrase] as whole words. */
    fun matches(heard: String?, phrase: String): Boolean {
        val p = normalize(phrase)
        if (p.isEmpty()) return false
        val h = normalize(heard ?: return false)
        if (h.isEmpty()) return false
        return h == p || h.contains(" $p ") || h.startsWith("$p ") || h.endsWith(" $p")
    }
}
