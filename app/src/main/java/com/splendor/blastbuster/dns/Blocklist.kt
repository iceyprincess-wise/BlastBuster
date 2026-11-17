package com.splendor.blastbuster.dns

/**
 * Domain blocklist matching (DESIGN.md §4.2, §5.4). Pure Kotlin — no
 * Android imports — so it is JVM-testable.
 *
 * Rules, exactly as designed:
 * - Exact match: an entry `tracker.example` blocks `tracker.example`.
 * - Suffix match: an entry `*.tracker.example` blocks every subdomain of
 *   `tracker.example` — and `tracker.example` itself (blocking the
 *   tracker's own host is the point of blocking its wildcard).
 * - Case-insensitive; a trailing dot is ignored (`example.com.` is the
 *   same name as `example.com`).
 * - The allow-list always beats the block-list: the user's explicit
 *   choice wins over the bundled list.
 * - "Lie" entries are a separate set: a blocked domain that is also a
 *   lie entry gets NXDOMAIN instead of the `0.0.0.0` sinkhole
 *   (DESIGN.md §5.4; default is the sinkhole).
 *
 * Matching is a hash-set lookup plus a walk up the domain's parent
 * suffixes (one set lookup per label) — fast enough for the ~5,000-entry
 * bundled list on a 4 GB phone; see BlocklistTest load-time check.
 */
class Blocklist private constructor(
    private val blockedExact: Set<String>,
    private val blockedSuffix: Set<String>,
    private val allowedExact: Set<String>,
    private val allowedSuffix: Set<String>,
    private val lieExact: Set<String>,
    private val lieSuffix: Set<String>
) {
    val blockedCount: Int get() = blockedExact.size + blockedSuffix.size
    val allowedCount: Int get() = allowedExact.size + allowedSuffix.size
    val lieCount: Int get() = lieExact.size + lieSuffix.size

    /**
     * True when [domain] is blocked and not explicitly allowed.
     * A "lie" entry is a blocked entry with a different answer, so a
     * domain on the lie list is blocked even if it is not on the block
     * list itself — marking a domain "lie" must never leave it
     * forwarded to the upstream.
     */
    fun isBlocked(domain: String): Boolean {
        val name = normalize(domain)
        if (name.isEmpty()) return false
        if (matches(name, allowedExact, allowedSuffix)) return false
        return matches(name, blockedExact, blockedSuffix) ||
            matches(name, lieExact, lieSuffix)
    }

    /** True when [domain] is on the allow-list (exact or suffix). */
    fun isAllowed(domain: String): Boolean {
        val name = normalize(domain)
        if (name.isEmpty()) return false
        return matches(name, allowedExact, allowedSuffix)
    }

    /** True when a blocked [domain] should get NXDOMAIN instead of the sinkhole. */
    fun isLie(domain: String): Boolean {
        val name = normalize(domain)
        if (name.isEmpty()) return false
        return matches(name, lieExact, lieSuffix)
    }

    private fun matches(name: String, exact: Set<String>, suffix: Set<String>): Boolean {
        if (exact.contains(name)) return true
        // Walk up: a.b.tracker.example -> b.tracker.example ->
        // tracker.example -> example. A suffix base matches the name
        // itself and every name below it.
        var rest = name
        while (true) {
            if (suffix.contains(rest)) return true
            val dot = rest.indexOf('.')
            if (dot == -1) return false
            rest = rest.substring(dot + 1)
        }
    }

    /** A new Blocklist with these allow entries added (bundled list untouched). */
    fun withAllow(lines: Iterable<String>): Blocklist {
        val (exact, suffix) = parseEntries(lines)
        return Blocklist(blockedExact, blockedSuffix, allowedExact + exact, allowedSuffix + suffix, lieExact, lieSuffix)
    }

    /** A new Blocklist with these "lie" (NXDOMAIN) entries added. */
    fun withLie(lines: Iterable<String>): Blocklist {
        val (exact, suffix) = parseEntries(lines)
        return Blocklist(blockedExact, blockedSuffix, allowedExact, allowedSuffix, lieExact + exact, lieSuffix + suffix)
    }

    companion object {

        /** The empty list: blocks nothing. A list that fails to load degrades to this, never to a crash. */
        val EMPTY = Blocklist(emptySet(), emptySet(), emptySet(), emptySet(), emptySet(), emptySet())

        /**
         * Build a block-list from lines in either format:
         * - plain domains, one per line (`ads.example.com`),
         * - hosts-file lines (`0.0.0.0 ads.example.com`, `127.0.0.1 ads.example.com`),
         * - wildcard lines (`*.tracker.example`),
         * - `#` comments and blank lines are skipped.
         * The bundled `res/raw/blocklist_hosts.txt` and the user's own
         * entries both come through here.
         */
        fun fromBlockLines(lines: Iterable<String>): Blocklist {
            val (exact, suffix) = parseEntries(lines)
            return Blocklist(exact, suffix, emptySet(), emptySet(), emptySet(), emptySet())
        }

        /** Split parsed entries into exact hosts and `*.` suffix bases. */
        private fun parseEntries(lines: Iterable<String>): Pair<Set<String>, Set<String>> {
            val exact = mutableSetOf<String>()
            val suffix = mutableSetOf<String>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                // Hosts-file line: "0.0.0.0 domain" — the domain is the
                // last whitespace-separated token. Char split (not a
                // per-line Regex) keeps a 5,000-line load under 100 ms.
                val token = line.split(' ', '\t').lastOrNull { it.isNotEmpty() } ?: continue
                if (token.startsWith("*.")) {
                    val base = normalize(token.substring(2))
                    if (base.isNotEmpty()) suffix.add(base)
                } else {
                    val name = normalize(token)
                    if (name.isNotEmpty() && name.contains('.')) exact.add(name)
                }
            }
            return Pair(exact, suffix)
        }

        /** Lowercase, trim, drop a trailing dot. Empty/invalid input stays empty. */
        fun normalize(domain: String): String {
            var name = domain.trim().lowercase()
            while (name.endsWith(".")) name = name.dropLast(1)
            return name
        }
    }
}
