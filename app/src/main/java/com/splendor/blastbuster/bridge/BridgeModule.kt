package com.splendor.blastbuster.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 *
 * v1.3 AUTO RE-BIND (live v1.2 failure: the server bound once at
 * start — localhost-only when Tailscale wasn't up yet — and never
 * re-bound when Tailscale came up, so Muse got an empty reply on the
 * tailnet address). While the bridge runs, a
 * `ConnectivityManager.NetworkCallback` watches for network changes.
 * On any change it re-reads the tailnet address (debounced ~2 s so a
 * flapping network doesn't thrash the server) and restarts the server
 * ONLY when [BridgeRebind.shouldRebind] says the address actually
 * changed. The restart logs BRIDGE_REBOUND with the new bind state.
 * A re-bind is skipped while a blast is active — restarting the
 * server mid-blast would briefly drop the reads Muse needs to see
 * the blast; the old bind set (localhost still works) stays up, and
 * the next network change re-triggers the check.
 *
 * v1.3 D-04 log export: the per-export confirm flag lives in-memory
 * in the server ([BridgeServer.grantLogExport]); this module is only
 * the pass-through BridgeActivity uses, via the live-server holder
 * below.
 *
 * LIVE-SERVER HOLDER: CompanionService owns the one running module;
 * BridgeActivity creates its own (never-started) module only to scan
 * for the tailnet address. `liveServer` lets the activity reach the
 * real running server — its true bind state and its export gate —
 * without touching CompanionService (owned by the integration agent).
 */
class BridgeModule(private val appCtx: Context) : CompanionModule {

    companion object {
        private const val TAG = "BridgeModule"

        /** Debounce: wait this long after the last network change before re-binding. */
        const val REBIND_DEBOUNCE_MS = 2_000L

        /** The server of the module that actually started the bridge, if any. */
        @Volatile
        private var liveServer: BridgeServer? = null

        /** The running server, or null when the bridge is not running. */
        fun liveServer(): BridgeServer? = liveServer
    }

    private val server = BridgeServer(appCtx)

    /** The server this module wraps (for tests / BridgeActivity). */
    fun server(): BridgeServer = server

    private val handler = Handler(Looper.getMainLooper())

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleRebindCheck()
        override fun onLost(network: Network) = scheduleRebindCheck()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            scheduleRebindCheck()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) =
            scheduleRebindCheck()
    }
    private var callbackRegistered = false

    private val rebindCheck = Runnable { doRebindCheck() }

    /**
     * Idempotent start. No-op if the bridge is disabled in the app or
     * if a blast is active. The server binds lazily and fails loudly
     * (logs + returns false from its own start) if port 8741 is taken.
     * On a successful start the network watcher is registered so the
     * server re-binds when the tailnet address appears or changes.
     */
    override fun start(ctx: Context) {
        if (!BridgeAuth.isEnabled(ctx)) return
        if (BlastGate.isBlasting(ctx)) return
        if (!server.start()) return
        liveServer = server
        registerNetworkCallback()
    }

    /** Idempotent stop. Releases the sockets, the thread pool and the watcher. */
    override fun stop() {
        handler.removeCallbacks(rebindCheck)
        unregisterNetworkCallback()
        if (liveServer === server) liveServer = null
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

    /** v1.3 D-04 pass-through: open one log-export window on the live server. */
    fun grantLogExport(): Boolean = server.grantLogExport()

    /** True while a log-export window is live on this server. */
    fun isLogExportAllowed(): Boolean = server.isLogExportAllowed()

    /** The addresses this server is actually bound to (empty when stopped). */
    fun listenState(): List<String> = server.listenState()

    private fun connectivityManager(): ConnectivityManager? =
        try {
            appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: Exception) {
            null
        }

    private fun registerNetworkCallback() {
        if (callbackRegistered) return
        try {
            // API 24+ (minSdk 26): the modern API, not the deprecated broadcast.
            connectivityManager()?.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        } catch (e: Exception) {
            // No watcher is a degraded bridge, not a dead one: the server
            // keeps its start-time bind set; the UI still shows the state.
            Log.w(TAG, "Could not register network callback — no auto re-bind: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        if (!callbackRegistered) return
        callbackRegistered = false
        try {
            connectivityManager()?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    /** Debounced: only the last change in a burst triggers a check. */
    private fun scheduleRebindCheck() {
        handler.removeCallbacks(rebindCheck)
        handler.postDelayed(rebindCheck, REBIND_DEBOUNCE_MS)
    }

    /**
     * Re-bind only when the tailnet address actually changed.
     * Skipped while blasting (the server keeps serving reads on its
     * old bind set) and while the server is stopped.
     */
    private fun doRebindCheck() {
        if (!server.isRunning()) return
        if (BlastGate.isBlasting(appCtx)) {
            Log.i(TAG, "Re-bind check deferred — blast active")
            return
        }
        val current = server.findTailscaleAddress()
        val old = server.tailscaleAddress()
        if (!BridgeRebind.shouldRebind(old, current)) return
        Log.i(TAG, "Tailnet address changed ($old -> $current) — re-binding bridge")
        server.rebind()
    }
}
