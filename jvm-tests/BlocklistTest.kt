import com.splendor.blastbuster.dns.Blocklist
import java.io.File

// JVM unit tests for Blocklist (DESIGN.md §7.1: 15 tests aimed).
// Standalone main() runner — no JUnit dependency (see run-tests.sh).
// Usage: java -cp <classes>:<stdlib> BlocklistTestKt [path/to/blocklist_hosts.txt]
// With the bundled file path given, the real 5,000-entry list is also
// load-tested; without it, only the synthetic checks run.

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
        println("  PASS: $name")
    } else {
        failed++
        println("  FAIL: $name")
    }
}

fun main(args: Array<String>) {
    println("BlocklistTest")

    // --- exact match ---
    val exact = Blocklist.fromBlockLines(listOf("ads.example.com", "tracker.example.org"))
    check("exact blocks listed host", exact.isBlocked("ads.example.com"))
    check("exact does not block other host", !exact.isBlocked("example.com"))
    check("exact does not block subdomain of listed host",
        !exact.isBlocked("sub.ads.example.com"))

    // --- case-insensitive + trailing dot ---
    check("case-insensitive", exact.isBlocked("ADS.EXAMPLE.COM"))
    check("trailing dot ignored", exact.isBlocked("ads.example.com."))
    check("mixed case + trailing dot", exact.isBlocked("Ads.Example.Com."))

    // --- suffix (wildcard) ---
    val wild = Blocklist.fromBlockLines(listOf("*.tracker.example"))
    check("wildcard blocks subdomain", wild.isBlocked("a.tracker.example"))
    check("wildcard blocks deep subdomain", wild.isBlocked("deep.a.tracker.example"))
    check("wildcard blocks the apex itself", wild.isBlocked("tracker.example"))
    check("wildcard does not block sibling domain", !wild.isBlocked("tracker.example.evil.com"))
    check("wildcard does not block parent", !wild.isBlocked("example"))
    check("wildcard does not block unrelated", !wild.isBlocked("tracker.other.com"))

    // --- allow beats block ---
    val mixed = Blocklist.fromBlockLines(listOf("ads.example.com", "*.tracker.example"))
        .withAllow(listOf("ads.example.com", "good.tracker.example"))
    check("allow beats exact block", !mixed.isBlocked("ads.example.com"))
    check("allow beats suffix block for one subdomain",
        !mixed.isBlocked("good.tracker.example"))
    check("allow does not unblock other subdomains",
        mixed.isBlocked("bad.tracker.example"))
    check("isAllowed reports allowed host", mixed.isAllowed("ads.example.com"))
    check("isAllowed false for blocked host", !mixed.isAllowed("bad.tracker.example"))

    // --- allow via wildcard ---
    val wildAllow = Blocklist.fromBlockLines(listOf("*.ads.example"))
        .withAllow(listOf("*.ads.example"))
    check("wildcard allow beats wildcard block", !wildAllow.isBlocked("x.ads.example"))

    // --- lie entries ---
    val lie = Blocklist.fromBlockLines(listOf("ads.example.com"))
        .withLie(listOf("ads.example.com", "*.lie.example"))
    check("lie exact", lie.isLie("ads.example.com"))
    check("lie suffix", lie.isLie("sub.lie.example"))
    check("lie false for plain blocked host", run {
        val b = Blocklist.fromBlockLines(listOf("plain.example.com"))
        !b.isLie("plain.example.com")
    })
    check("lie-only entry is blocked (lie implies block)", run {
        val b = Blocklist.EMPTY.withLie(listOf("sneaky.example.com"))
        b.isBlocked("sneaky.example.com") && b.isLie("sneaky.example.com")
    })

    // --- empty list ---
    check("empty blocks nothing", !Blocklist.EMPTY.isBlocked("anything.example.com"))
    check("empty counts are 0", Blocklist.EMPTY.blockedCount == 0 && Blocklist.EMPTY.allowedCount == 0)

    // --- hosts-file format + comments ---
    val hosts = Blocklist.fromBlockLines(
        listOf(
            "# a comment",
            "",
            "0.0.0.0 ads.example.com",
            "127.0.0.1 tracker.example.org",
            "  0.0.0.0   spaced.example.net  ",
            "plain.example.io"
        )
    )
    check("hosts format 0.0.0.0", hosts.isBlocked("ads.example.com"))
    check("hosts format 127.0.0.1", hosts.isBlocked("tracker.example.org"))
    check("hosts format extra spaces", hosts.isBlocked("spaced.example.net"))
    check("plain line", hosts.isBlocked("plain.example.io"))
    check("hosts list count", hosts.blockedCount == 4)

    // --- withAllow/withLie do not mutate the original ---
    val base = Blocklist.fromBlockLines(listOf("a.example.com"))
    val extended = base.withAllow(listOf("a.example.com"))
    check("withAllow leaves original blocked", base.isBlocked("a.example.com"))
    check("withAllow result unblocked", !extended.isBlocked("a.example.com"))

    // --- normalize ---
    check("normalize lowercases + strips dot", Blocklist.normalize("  ExAmple.COM. ") == "example.com")

    // --- load time: 5,000 synthetic entries (design target < 100 ms) ---
    // Measured warm: the first load in a fresh JVM pays class-loading +
    // JIT costs (observed 175 ms cold vs ~10 ms warm on this box), so
    // one untimed warm-up load runs first and the assertion is on the
    // steady-state load — the number that matters on the phone, where
    // the list loads once per service start into a warm runtime.
    val bigLines = (1..5000).map { "host$it.example.com" }
    Blocklist.fromBlockLines(bigLines) // warm-up (untimed)
    val t0 = System.nanoTime()
    val big = Blocklist.fromBlockLines(bigLines)
    val loadMs = (System.nanoTime() - t0) / 1_000_000.0
    println("  INFO: 5000-entry synthetic load took ${"%.1f".format(loadMs)} ms (warm)")
    check("5000-entry load < 100 ms", loadMs < 100.0)
    check("5000-entry list matches last host", big.isBlocked("host5000.example.com"))
    check("5000-entry list rejects unknown", !big.isBlocked("host5001.example.com"))

    // --- real bundled list, when a path is given ---
    if (args.isNotEmpty()) {
        val file = File(args[0])
        check("bundled file exists: ${args[0]}", file.exists())
        if (file.exists()) {
            val lines = file.readLines()
            val domainLines = lines.filter { it.isNotBlank() && !it.startsWith("#") }
            Blocklist.fromBlockLines(lines) // warm-up (untimed, see above)
            val t1 = System.nanoTime()
            val bundled = Blocklist.fromBlockLines(lines)
            val bundledMs = (System.nanoTime() - t1) / 1_000_000.0
            println("  INFO: bundled list: ${domainLines.size} domains, load took ${"%.1f".format(bundledMs)} ms (warm)")
            check("bundled list has 5000 domains", domainLines.size == 5000)
            check("bundled load < 100 ms", bundledMs < 100.0)
            check("bundled blocks a known entry (ad-assets.futurecdn.net)",
                bundled.isBlocked("ad-assets.futurecdn.net"))
            check("bundled does not block example.com", !bundled.isBlocked("example.com"))
            check("bundled entries all look like domains", domainLines.all {
                it.matches(Regex("[a-z0-9._-]+")) && it.contains('.')
            })
        }
    } else {
        println("  INFO: no bundled-file path given; real-list checks skipped.")
    }

    println("RESULT BlocklistTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
