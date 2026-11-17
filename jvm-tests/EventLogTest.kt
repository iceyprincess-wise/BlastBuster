import com.splendor.blastbuster.companion.EventLog

// EventLog ring logic only (DESIGN.md §7.1 target: 8 tests).
// The file-I/O half is on-device; encode / parse / evict are pure.
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { passed++; println("PASS $name") }
        else { failed++; println("FAIL $name") }
    }
    fun ev(id: Long) = EventLog.LoggedEvent(id, 1000L + id, "ram", "RAM_SCAN", "summary $id", mapOf("package" to "com.example.app"))

    // 1. Encode/decode round-trip, all fields.
    val e = EventLog.LoggedEvent(7, 1700000000000L, "dnd", "DND_REAPPLIED", "DND re-applied to ON", mapOf("action" to "APPLY_ON"))
    check("round-trip", EventLog.parseLine(EventLog.encodeEvent(e)) == e)
    // 2. Escaping: quotes, backslash, newline survive the round-trip.
    val tricky = EventLog.LoggedEvent(8, 1L, "ram", "X", "say \"hi\" \\ done\nnext", mapOf("k" to "v\"v"))
    check("escaping round-trip", EventLog.parseLine(EventLog.encodeEvent(tricky)) == tricky)
    // 3. Malformed lines parse to null, never throw.
    check(
        "malformed lines are null",
        EventLog.parseLine("") == null &&
            EventLog.parseLine("not json") == null &&
            EventLog.parseLine("{\"id\": 1}") == null &&
            EventLog.parseLine("{\"id\": \"x\", \"ts\": 1, \"module\": \"m\", \"type\": \"t\", \"summary\": \"s\"}") == null
    )
    // 4. Cap eviction: oldest-first by count.
    val lines = (1L..10L).map { EventLog.encodeEvent(ev(it)) }
    val kept = EventLog.evictLines(lines, 5, 1_000_000)
    check(
        "evict by count keeps newest 5",
        kept.size == 5 && EventLog.parseLine(kept.first())?.id == 6L &&
            EventLog.parseLine(kept.last())?.id == 10L
    )
    // 5. Cap eviction: oldest-first by bytes (one ~261-byte line fits a
    // 300-byte cap, but three do not — the two oldest go).
    val big = EventLog.encodeEvent(EventLog.LoggedEvent(1, 1L, "ram", "T", "x".repeat(200)))
    val keptBytes = EventLog.evictLines(listOf(big, big, big), 500, 300)
    check("evict by bytes keeps newest", keptBytes.size == 1)
    // 6. No eviction when under both caps.
    check("no eviction under caps", EventLog.evictLines(lines, 500, 1_000_000).size == 10)
    // 7. Empty input stays empty.
    check("empty evict", EventLog.evictLines(emptyList(), 500, 256 * 1024).isEmpty())
    // 8. Ids stay monotonic through encode/parse.
    val ids = (1L..50L).map { EventLog.parseLine(EventLog.encodeEvent(ev(it)))!!.id }
    check("ids monotonic", ids == (1L..50L).toList())
    // 9. Data map with multiple entries round-trips.
    val multi = EventLog.LoggedEvent(9, 2L, "bridge", "BRIDGE_CMD", "cmd", mapOf("a" to "1", "b" to "two"))
    check("multi-entry data", EventLog.parseLine(EventLog.encodeEvent(multi)) == multi)
    // 10. Trailing newline / surrounding whitespace tolerated.
    check(
        "whitespace tolerated",
        EventLog.parseLine("  " + EventLog.encodeEvent(e) + "  ") == e
    )

    println("EventLogTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
