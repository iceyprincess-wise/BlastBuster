package com.splendor.blastbuster.net

import android.content.Context
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.CompanionModule

/**
 * Wiring adapter: the network module as one unit for CompanionService
 * (DESIGN.md §4.7, §4.8).
 *
 * MERGED (v1.2 merge): this class now implements [CompanionModule] —
 * the signatures already matched §4.8, so only the `: CompanionModule`
 * declaration and `override` keywords were added. The NetPrefs shim is
 * gone: all settings live in [Prefs]. The event sink is the shared
 * EventLog ring (wired in [start]/[startPolling]); logcat is only
 * the pre-wire fallback.
 *
 * Blast rule: start() only starts polling (a read). CompanionService
 * suspends the poll during a blast by calling stop(), and resumes with
 * start() on BLAST_STOPPED (DESIGN.md §1.3 rule 2). Flicks and mode
 * locks are writes and are blast-gated inside NetTools, not here.
 */
class NetModule(private val eventSink: EventSink = LogcatEventSink) : CompanionModule {

    val netWatch = NetWatch(eventSink)

    /** Idempotent; no-op if disabled in Prefs or already running. Starts the 30 s poll loop. */
    override fun start(ctx: Context) {
        if (!Prefs.netEnabled(ctx)) return
        wireSink(ctx)
        netWatch.start(ctx.applicationContext)
    }

    /**
     * Screen-lifetime polling for NetActivity: starts the poll loop
     * regardless of the Prefs toggle, so the Network screen shows live
     * signal while it is open. The service path ([start]) stays gated
     * on the toggle.
     */
    fun startPolling(ctx: Context) {
        wireSink(ctx)
        netWatch.start(ctx.applicationContext)
    }

    /** Points the watch at the EventLog ring (idempotent). */
    private fun wireSink(ctx: Context) {
        try {
            netWatch.eventSink = eventLogSink(ctx.applicationContext)
        } catch (_: Exception) {
            // Pre-wire logcat fallback stays; nothing is silently lost.
        }
    }

    /** Idempotent; stops the poll loop. */
    override fun stop() {
        netWatch.stop()
    }

    override fun isRunning(): Boolean = netWatch.isRunning()

    /** JSON fragment for GET /v1/net (bridge). */
    override fun statusJson(): String = netWatch.statusJson()

    /** Last snapshot, if any poll has completed. */
    fun lastSnapshot(): NetWatch.NetSnapshot? = netWatch.lastSnapshot

    /** One-tap flick, gated + verified inside NetTools. */
    fun flickAirplane(ctx: Context, callback: (NetTools.FlickOutcome) -> Unit) {
        val verify: () -> Boolean = {
            val snap = netWatch.pollNow(ctx)
            snap.sims.any { it.grade != SignalGrade.NONE }
        }
        NetTools.flickAirplane(ctx, verify, callback)
    }

    /** Mode lock for one subscription, gated + verified inside NetTools. */
    fun setNetworkMode(
        ctx: Context,
        subscriptionId: Int,
        slotIndex: Int,
        mode: NetMode,
        callback: (NetTools.ModeOutcome) -> Unit
    ) {
        NetTools.setNetworkMode(ctx, subscriptionId, slotIndex, mode, callback)
    }
}
