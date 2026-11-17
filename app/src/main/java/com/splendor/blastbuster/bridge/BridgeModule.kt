package com.splendor.blastbuster.bridge

import android.content.Context
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.CompanionModule

/**
 * Wiring adapter between CompanionService and [BridgeServer]
 * (DESIGN.md §4.7 parallel-work rules, §4.8 module contract).
 *
 * Shape: exactly the `CompanionModule` interface from §4.8 —
 * `start(ctx)` / `stop()` / `isRunning()` / `statusJson()` — so
 * CompanionService can hold one of these like any other module.
 *
 * MERGED (v1.2 merge): this class now declares `: CompanionModule`
 * — the method names and signatures already matched §4.8 exactly.
 * The blast read is routed through [BlastGate] (one enforcement
 * point), not read from Prefs directly.
 *
 * Blast rule: the module never starts the server during a blast
 * ([start] is a no-op while blasting — a bridge write must never be
 * the thing that wakes a blasting phone's companion work), and the
 * server itself enforces the read/write rule per request (§1.3).
 * If a blast starts while the server runs, the server keeps serving
 * reads (Muse must see the blast) and refuses writes; CompanionService
 * owns suspending timers, not this module.
 */
class BridgeModule(private val appCtx: Context) : CompanionModule {

    private val server = BridgeServer(appCtx)

    /** The server this module wraps (for tests / BridgeActivity). */
    fun server(): BridgeServer = server

    /**
     * Idempotent start. No-op if the bridge is disabled in the app or
     * if a blast is active. The server binds lazily and fails loudly
     * (logs + returns false from its own start) if port 8741 is taken.
     */
    override fun start(ctx: Context) {
        if (!BridgeAuth.isEnabled(ctx)) return
        if (BlastGate.isBlasting(ctx)) return
        server.start()
    }

    /** Idempotent stop. Releases the sockets and the thread pool. */
    override fun stop() {
        server.stop()
    }

    override fun isRunning(): Boolean = server.isRunning()

    /**
     * MiniJson fragment for `GET /v1/status` (§4.8): bridge fields only.
     * CompanionService merges this into the status object.
     */
    override fun statusJson(): String = server.statusFragmentJson()

    /** Hand the real module providers to the server (called by CompanionService at merge). */
    fun setStatusProvider(provider: BridgeServer.StatusProvider) {
        server.statusProvider = provider
    }

    fun setEventProvider(provider: BridgeServer.EventProvider) {
        server.eventProvider = provider
    }

    fun setCommandExecutor(executor: BridgeCommands.BridgeCommandExecutor) {
        server.commandExecutor = executor
    }
}
