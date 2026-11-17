package com.splendor.blastbuster.net

/**
 * Minimal event sink for the network/DNS modules (DESIGN.md §2.6).
 *
 * This interface is deliberately Android-free so the pure modules
 * (DnsPipe, DnsParser, Blocklist) can use it and stay JVM-testable.
 * It carries only module + type + a human sentence — never phone
 * numbers, never GPS, never full DNS query logs (DESIGN.md §2.6
 * privacy rule: the DNS stats show counts + top blocked domains only).
 *
 * MERGED (v1.2 merge): the seam now points at the shared EventLog
 * ring — Android callers wire [eventLogSink] (see NetWatch.kt) at
 * start time; the interface itself stays, because the pure modules
 * (DnsPipe, DnsParser, Blocklist) take an EventSink and stay
 * JVM-testable through it. The blast-gating of log writes (no writes
 * during a blast, DESIGN.md §1.3) is applied by the callers that own
 * blast state (DnsVpnService gates its sink; NetWatch polling is
 * read-only).
 */
fun interface EventSink {
    fun log(module: String, type: String, summary: String)
}

object EventSinks {
    /** Drops every event. Used by the pure pipe when no sink is wired. */
    val NOOP: EventSink = EventSink { _, _, _ -> }
}
