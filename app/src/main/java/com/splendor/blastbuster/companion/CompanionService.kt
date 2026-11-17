package com.splendor.blastbuster.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import com.splendor.blastbuster.MainActivity
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.bridge.BridgeAuth
import com.splendor.blastbuster.bridge.BridgeCommands
import com.splendor.blastbuster.bridge.BridgeModule
import com.splendor.blastbuster.bridge.BridgeServer
import com.splendor.blastbuster.dnd.DndCulprit
import com.splendor.blastbuster.dnd.DndGuard
import com.splendor.blastbuster.dnd.DndListener
import com.splendor.blastbuster.dns.DnsModule
import com.splendor.blastbuster.net.NetModeValues
import com.splendor.blastbuster.net.NetModule
import com.splendor.blastbuster.net.NetTools
import com.splendor.blastbuster.ram.RamKiller
import com.splendor.blastbuster.ram.RamWatch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One module behind the shared interface (DESIGN.md §4.8).
 * CompanionService holds one of each; the bridge serves each module's
 * [statusJson] fragment.
 */
interface CompanionModule {
    /** Idempotent; no-op when the module is disabled in Prefs. */
    fun start(ctx: Context)

    /** Idempotent; releases timers / listeners. */
    fun stop()

    fun isRunning(): Boolean

    /** MiniJson fragment for GET /v1/status. */
    fun statusJson(): String
}

/**
 * Foreground host for the companion modules (DESIGN.md §1.1, §4.1).
 * Separate from BlastService on purpose: if this service is killed,
 * paused or crashes, the blast guard is unaffected, and vice versa.
 *
 * MERGED (v1.2 merge): all five modules are real — RAM + DND (Agent A),
 * Net + DNS (Agent B), Bridge (Agent C). The Agent A stubs are gone.
 * The bridge providers are wired here: the EventLog adapter, the
 * merged status provider, and the command executor that delegates
 * ram.kill → RamKiller, dnd.apply → DndGuard, net.flick/net.mode →
 * NetTools, dns.set → DnsModule.
 *
 * Blast priority (§1.3): when BLAST_STARTED arrives, the suspendable
 * timers (RAM scan, DND re-check, NetWatch poll) stop within one tick
 * and do not restart until BLAST_STOPPED. The DNS tunnel stays UP
 * (dropping it mid-blast could stall the GPS reply SMS path) and the
 * bridge server keeps serving READS (Muse must see the blast); bridge
 * writes are refused by BridgeCommands validation either way. This
 * service never touches audio, camera, vibration or the lock screen.
 * It is never started during a blast; if a blast is already active at
 * start time, the modules stay suspended until it ends.
 *
 * Memory budget (§4.1): this service + all its timers must stay under
 * ~60 MB PSS when idle (measured on-device, §7 D-05).
 */
class CompanionService : Service() {

    companion object {
        const val ACTION_START = "com.splendor.blastbuster.START_COMPANION"
        const val ACTION_STOP = "com.splendor.blastbuster.STOP_COMPANION"
        const val CHANNEL_ID = "blast_buster_companion"
        const val NOTIF_ID = 2

        /** The live service instance, if any (for BridgeActivity display/state). */
        @Volatile
        private var instance: CompanionService? = null

        /** Starts the service when any companion module is enabled. */
        fun startIfNeeded(ctx: Context) {
            if (!Prefs.anyCompanionEnabled(ctx)) return
            val i = Intent(ctx, CompanionService::class.java).setAction(ACTION_START)
            try {
                ctx.startForegroundService(i)
            } catch (_: Exception) {
            }
        }

        fun stopService(ctx: Context) {
            val i = Intent(ctx, CompanionService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(i)
            } catch (_: Exception) {
            }
        }

        /** True when the bridge server is currently listening. */
        fun bridgeRunning(): Boolean =
            instance?.runCatching { bridgeModule.isRunning() }?.getOrNull() ?: false

        /** The tailnet address the bridge is bound to, or null. */
        fun bridgeTailscaleAddress(): String? =
            instance?.runCatching { bridgeModule.server().tailscaleAddress() }?.getOrNull()

        /**
         * Applies a bridge toggle from BridgeActivity: starts the
         * service when the bridge was just turned on, or stops just
         * the bridge module when it was turned off (other modules keep
         * running). No-op when the service is not running and the
         * bridge is off.
         */
        fun applyBridgeState(ctx: Context) {
            val svc = instance
            if (svc != null) {
                svc.refreshBridgeState()
            } else if (BridgeAuth.isEnabled(ctx)) {
                startIfNeeded(ctx)
            }
        }

        /**
         * Applies a RAM / DND / Net hub toggle (v1.2 gap fix): starts
         * the service when a module was just turned on, starts or
         * stops just that module when the service is already running,
         * or stops the service when every module is now off — the
         * service does not self-stop on its own, so the last toggle
         * off is what stops it. No-op when the service is not running
         * and nothing is enabled.
         */
        fun applyModuleStates(ctx: Context) {
            val svc = instance
            if (svc != null) {
                if (!Prefs.anyCompanionEnabled(ctx)) {
                    stopService(ctx)
                } else {
                    svc.refreshModuleStates()
                }
            } else if (Prefs.anyCompanionEnabled(ctx)) {
                startIfNeeded(ctx)
            }
        }
    }

    private val ramWatch = RamWatch()
    private val dndGuard = DndGuard()
    private val netModule = NetModule()
    private val dnsModule = DnsModule()
    private lateinit var bridgeModule: BridgeModule

    private val modules: List<CompanionModule>
        get() = listOf(ramWatch, dndGuard, netModule, dnsModule, bridgeModule)

    @Volatile
    private var suspendedForBlast = false

    private val blastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BlastGate.ACTION_BLAST_STARTED -> suspendForBlast()
                BlastGate.ACTION_BLAST_STOPPED -> resumeAfterBlast()
            }
        }
    }

    private val dndChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DndListener.ACTION_DND_CHANGED) return
            val filter = intent.getIntExtra(
                DndListener.EXTRA_FILTER,
                NotificationManager.INTERRUPTION_FILTER_ALL
            )
            val pkg = intent.getStringExtra(DndListener.EXTRA_CULPRIT_PKG)
            val tierRaw = intent.getStringExtra(DndListener.EXTRA_CULPRIT_TIER)
            val label = intent.getStringExtra(DndListener.EXTRA_CULPRIT_LABEL)
            val tier = try {
                if (tierRaw != null) DndCulprit.Tier.valueOf(tierRaw)
                else DndCulprit.Tier.UNKNOWN
            } catch (_: Exception) {
                DndCulprit.Tier.UNKNOWN
            }
            val culprit = if (pkg.isNullOrBlank()) {
                DndCulprit.CulpritResult(null, tier, label ?: "unknown — no culprit observed")
            } else {
                DndCulprit.CulpritResult(pkg, tier, label ?: "")
            }
            try {
                dndGuard.onExternalChange(filter, culprit)
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        bridgeModule = BridgeModule(this)
        bridgeModule.setEventProvider(eventProvider())
        bridgeModule.setStatusProvider(object : BridgeServer.StatusProvider {
            override fun extraStatusJson(): String? =
                runCatching { buildStatusJson() }.getOrNull()
        })
        bridgeModule.setCommandExecutor(BridgeExecutor())
        registerBlastReceiver()
        registerDndReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAllModules()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                if (BlastGate.isBlasting(this)) {
                    // Never start companion work during a blast (§1.3).
                    suspendedForBlast = true
                    EventLog.append(
                        this, "system", "COMPANION_SUSPENDED",
                        "Companion service started during a blast — modules suspended until it ends."
                    )
                } else {
                    startAllModules()
                }
                EventLog.append(
                    this, "system", "COMPANION_STARTED",
                    "Companion service started (RAM=${Prefs.ramEnabled(this)}, " +
                        "DND=${Prefs.dndEnabled(this)}, Net=${Prefs.netEnabled(this)}, " +
                        "DNS=${Prefs.dnsEnabled(this)}, Bridge=${Prefs.bridgeEnabled(this)})."
                )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(blastReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(dndChangedReceiver)
        } catch (_: Exception) {
        }
        stopAllModules()
        if (instance === this) instance = null
        EventLog.append(this, "system", "COMPANION_STOPPED", "Companion service stopped.")
        super.onDestroy()
    }

    /** Suspends the suspendable module timers within one tick (§1.3). */
    private fun suspendForBlast() {
        if (suspendedForBlast) return
        suspendedForBlast = true
        // DNS tunnel stays UP and the bridge keeps serving reads (§1.3
        // rules 1 + 3) — only the polling/enforcement timers suspend.
        stopSuspendableModules()
        EventLog.append(
            this, "system", "COMPANION_SUSPENDED",
            "Blast started — companion timers suspended."
        )
    }

    /** Restarts enabled modules after a blast ends. */
    private fun resumeAfterBlast() {
        if (!suspendedForBlast) return
        suspendedForBlast = false
        startAllModules()
        EventLog.append(
            this, "system", "COMPANION_RESUMED",
            "Blast ended — companion timers resumed."
        )
    }

    /** Starts or stops just the bridge module to match its toggle. */
    private fun refreshBridgeState() {
        if (BridgeAuth.isEnabled(this) && !BlastGate.isBlasting(this)) {
            if (!bridgeModule.isRunning()) bridgeModule.start(this)
        } else {
            if (bridgeModule.isRunning()) bridgeModule.stop()
        }
    }

    /**
     * Starts modules just turned on, stops modules just turned off
     * (v1.2 gap fix — the hub RAM/DND/Net toggles). Each module
     * start() is idempotent and Prefs-gated; each stop() is safe when
     * not running. During a blast suspension no timer is (re)started
     * — resumeAfterBlast() restarts the enabled modules via
     * startAllModules() once the blast ends.
     */
    private fun refreshModuleStates() {
        if (Prefs.ramEnabled(this)) {
            if (!suspendedForBlast) runCatching { ramWatch.start(this) }
        } else runCatching { ramWatch.stop() }
        if (Prefs.dndEnabled(this)) {
            if (!suspendedForBlast) runCatching { dndGuard.start(this) }
        } else runCatching { dndGuard.stop() }
        if (Prefs.netEnabled(this)) {
            if (!suspendedForBlast) runCatching { netModule.start(this) }
        } else runCatching { netModule.stop() }
        refreshBridgeState()
    }

    private fun startAllModules() {
        for (module in modules) {
            try {
                module.start(this)
            } catch (_: Exception) {
                // One failing module must never stop the others (§1.2).
            }
        }
    }

    /** Stops the timers that suspend during a blast (RAM, DND, Net only). */
    private fun stopSuspendableModules() {
        for (module in listOf<CompanionModule>(ramWatch, dndGuard, netModule)) {
            try {
                module.stop()
            } catch (_: Exception) {
            }
        }
    }

    /** Stops every module (service stop / destroy). */
    private fun stopAllModules() {
        for (module in modules) {
            try {
                module.stop()
            } catch (_: Exception) {
            }
        }
    }

    /** Assembled status JSON for the bridge and the hub UI. */
    fun buildStatusJson(): String {
        val sb = StringBuilder("{")
        sb.append("\"blasting\":").append(BlastGate.isBlasting(this)).append(',')
        sb.append("\"companion_suspended\":").append(suspendedForBlast).append(',')
        sb.append("\"ram\":").append(ramWatch.statusJson()).append(',')
        sb.append("\"dnd\":").append(dndGuard.statusJson()).append(',')
        sb.append("\"net\":").append(netModule.statusJson()).append(',')
        sb.append("\"dns\":").append(dnsModule.statusJson()).append(',')
        sb.append("\"bridge\":").append(bridgeModule.statusJson())
        sb.append('}')
        return sb.toString()
    }

    /** Exposed for the RAM screen's on-demand refresh. */
    fun ramWatch(): RamWatch = ramWatch

    /** Exposed for the DND screen's on-demand check. */
    fun dndGuard(): DndGuard = dndGuard

    /** EventLog adapter for the bridge (GET /v1/events + BRIDGE_* writes). */
    private fun eventProvider(): BridgeServer.EventProvider =
        object : BridgeServer.EventProvider {
            override fun eventsSince(since: Long, limit: Int): List<Map<String, Any?>> {
                val (events, _) = EventLog.readSince(applicationContext, since, limit)
                return events.map { e ->
                    mapOf<String, Any?>(
                        "id" to e.id,
                        "ts" to e.ts,
                        "module" to e.module,
                        "type" to e.type,
                        "summary" to e.summary,
                        "data" to e.data
                    )
                }
            }

            override fun nextSince(since: Long, served: List<Map<String, Any?>>): Long {
                var next = since
                for (e in served) {
                    val id = (e["id"] as? Number)?.toLong() ?: continue
                    if (id > next) next = id
                }
                return next
            }

            override fun count(): Long = EventLog.count(applicationContext).toLong()

            override fun append(module: String, type: String, summary: String) {
                try {
                    EventLog.append(applicationContext, module, type, summary)
                } catch (_: Exception) {
                }
            }
        }

    private fun registerBlastReceiver() {
        val filter = IntentFilter().apply {
            addAction(BlastGate.ACTION_BLAST_STARTED)
            addAction(BlastGate.ACTION_BLAST_STOPPED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(blastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(blastReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    private fun registerDndReceiver() {
        val filter = IntentFilter(DndListener.ACTION_DND_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dndChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(dndChangedReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Blast Buster companion",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Blast Buster companion running")
            .setContentText("RAM watch and Do Not Disturb guard.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    // ---------- Bridge command executor (wires §2.3 commands to modules) ----------

    /**
     * Executes validated bridge commands. Validation (unknown command,
     * blast.start refusal, denylist, arg checks, blast write-block)
     * already happened in BridgeCommands.validate inside BridgeServer;
     * this executor re-checks the blast gate for writes (defence in
     * depth — the gate is the one enforcement point) and delegates:
     * ram.kill → RamKiller, dnd.apply → DndGuard, net.flick/net.mode →
     * NetTools, dns.set → DnsModule. Reads (ram.snapshot) run as-is.
     *
     * The async module calls (kill verify 5 s, flick, mode lock) are
     * awaited on a latch capped below the server's 10 s command
     * timeout; on timeout the command reports "timeout" and the work
     * continues in the background — its outcome still lands in
     * EventLog. Nothing here invents a result.
     */
    private inner class BridgeExecutor : BridgeCommands.BridgeCommandExecutor {
        override fun execute(req: BridgeCommands.CommandRequest): BridgeCommands.ExecutorResult {
            val ctx = applicationContext
            val isRead = req.command == "ram.snapshot"
            if (!isRead) {
                val gate = BlastGate.forContext(ctx)
                if (!gate.checkWrite(req.command) { reason ->
                        EventLog.append(ctx, "bridge", "BRIDGE_CMD_SKIPPED", reason)
                    }) {
                    return BridgeCommands.ExecutorResult(
                        ok = false, error = "blocked_blast_active"
                    )
                }
            }
            return try {
                when (req.command) {
                    "ram.snapshot" -> {
                        val snap = ramWatch.snapshotNow()
                        if (snap == null) {
                            BridgeCommands.ExecutorResult(ok = false, error = "no_snapshot")
                        } else {
                            BridgeCommands.ExecutorResult(
                                ok = true, resultJson = ramWatch.statusJson()
                            )
                        }
                    }
                    "ram.kill" -> {
                        val pkg = (req.args["package"] as? String)?.trim()
                            ?: return BridgeCommands.ExecutorResult(
                                ok = false, error = "missing_package"
                            )
                        awaitKill(ctx, pkg)
                    }
                    "dnd.apply" -> {
                        val mode = (req.args["mode"] as? String)?.trim()?.lowercase()
                            ?: return BridgeCommands.ExecutorResult(
                                ok = false, error = "invalid_mode"
                            )
                        val ok = dndGuard.applyNow(ctx, mode == "on")
                        if (ok) {
                            BridgeCommands.ExecutorResult(
                                ok = true,
                                resultJson = MiniJson.obj("mode" to mode)
                            )
                        } else {
                            BridgeCommands.ExecutorResult(
                                ok = false, error = "dnd_apply_failed"
                            )
                        }
                    }
                    "net.flick" -> awaitFlick(ctx)
                    "net.mode" -> awaitMode(ctx, req.args)
                    "dns.set" -> {
                        val enabled = req.args["enabled"] as? Boolean
                            ?: return BridgeCommands.ExecutorResult(
                                ok = false, error = "invalid_enabled"
                            )
                        Prefs.setDnsEnabled(ctx, enabled)
                        if (!enabled) {
                            dnsModule.stopPipe(ctx)
                            BridgeCommands.ExecutorResult(
                                ok = true,
                                resultJson = MiniJson.obj("enabled" to false)
                            )
                        } else {
                            when (dnsModule.startPipe(ctx)) {
                                DnsModule.DnsStartResult.STARTED,
                                DnsModule.DnsStartResult.ALREADY_RUNNING ->
                                    BridgeCommands.ExecutorResult(
                                        ok = true,
                                        resultJson = MiniJson.obj("enabled" to true)
                                    )
                                DnsModule.DnsStartResult.NEEDS_CONSENT ->
                                    BridgeCommands.ExecutorResult(
                                        ok = false, error = "needs_consent"
                                    )
                                DnsModule.DnsStartResult.BLOCKED_BLAST ->
                                    BridgeCommands.ExecutorResult(
                                        ok = false, error = "blocked_blast_active"
                                    )
                                DnsModule.DnsStartResult.DISABLED ->
                                    BridgeCommands.ExecutorResult(
                                        ok = false, error = "disabled"
                                    )
                            }
                        }
                    }
                    else -> BridgeCommands.ExecutorResult(ok = false, error = "unknown_command")
                }
            } catch (_: Exception) {
                BridgeCommands.ExecutorResult(ok = false, error = "command_failed")
            }
        }

        private fun awaitKill(ctx: Context, pkg: String): BridgeCommands.ExecutorResult {
            val latch = CountDownLatch(1)
            var result: RamKiller.KillResult? = null
            RamKiller.kill(ctx, pkg) { r ->
                result = r
                latch.countDown()
            }
            if (!latch.await(9, TimeUnit.SECONDS)) {
                return BridgeCommands.ExecutorResult(ok = false, error = "timeout")
            }
            val r = result ?: return BridgeCommands.ExecutorResult(
                ok = false, error = "command_failed"
            )
            return when (r.outcome) {
                RamKiller.KillOutcome.KILLED -> BridgeCommands.ExecutorResult(
                    ok = true,
                    resultJson = MiniJson.obj(
                        "package" to pkg, "outcome" to "killed", "detail" to r.detail
                    )
                )
                RamKiller.KillOutcome.RESTARTED -> BridgeCommands.ExecutorResult(
                    ok = false, error = "restarted"
                )
                RamKiller.KillOutcome.DENIED -> BridgeCommands.ExecutorResult(
                    ok = false, error = "denied_package"
                )
                RamKiller.KillOutcome.COOLDOWN -> BridgeCommands.ExecutorResult(
                    ok = false, error = "cooldown"
                )
                RamKiller.KillOutcome.BLOCKED_BLAST_ACTIVE -> BridgeCommands.ExecutorResult(
                    ok = false, error = "blocked_blast_active"
                )
                RamKiller.KillOutcome.FAILED -> BridgeCommands.ExecutorResult(
                    ok = false, error = "kill_failed"
                )
            }
        }

        private fun awaitFlick(ctx: Context): BridgeCommands.ExecutorResult {
            val latch = CountDownLatch(1)
            var outcome: NetTools.FlickOutcome? = null
            netModule.flickAirplane(ctx) { o ->
                outcome = o
                latch.countDown()
            }
            if (!latch.await(9, TimeUnit.SECONDS)) {
                return BridgeCommands.ExecutorResult(ok = false, error = "timeout")
            }
            val o = outcome ?: return BridgeCommands.ExecutorResult(
                ok = false, error = "command_failed"
            )
            return when (o.result) {
                NetTools.FlickResult.OK_RESTORED,
                NetTools.FlickResult.OK_NO_IMPROVEMENT -> BridgeCommands.ExecutorResult(
                    ok = true,
                    resultJson = MiniJson.obj(
                        "result" to o.result.name.lowercase(), "detail" to o.detail
                    )
                )
                NetTools.FlickResult.NEEDS_ADB -> BridgeCommands.ExecutorResult(
                    ok = false, error = "needs_adb"
                )
                NetTools.FlickResult.COOLDOWN -> BridgeCommands.ExecutorResult(
                    ok = false, error = "cooldown"
                )
                NetTools.FlickResult.BLOCKED_BLAST -> BridgeCommands.ExecutorResult(
                    ok = false, error = "blocked_blast_active"
                )
                NetTools.FlickResult.FAILED -> BridgeCommands.ExecutorResult(
                    ok = false, error = "flick_failed"
                )
            }
        }

        private fun awaitMode(
            ctx: Context,
            args: Map<String, Any?>
        ): BridgeCommands.ExecutorResult {
            val mode = NetModeValues.parse(args["mode"] as? String)
                ?: return BridgeCommands.ExecutorResult(ok = false, error = "invalid_mode")
            val subId = (args["sub_id"] as? Number)?.toInt()
                ?: (args["subscription_id"] as? Number)?.toInt()
                ?: netModule.lastSnapshot()?.sims?.firstOrNull()?.subscriptionId
                ?: return BridgeCommands.ExecutorResult(ok = false, error = "no_sim")
            val slot = (args["slot"] as? Number)?.toInt()
                ?: netModule.lastSnapshot()?.sims
                    ?.firstOrNull { it.subscriptionId == subId }?.slotIndex
                ?: 0
            val latch = CountDownLatch(1)
            var outcome: NetTools.ModeOutcome? = null
            netModule.setNetworkMode(ctx, subId, slot, mode) { o ->
                outcome = o
                latch.countDown()
            }
            if (!latch.await(9, TimeUnit.SECONDS)) {
                return BridgeCommands.ExecutorResult(ok = false, error = "timeout")
            }
            val o = outcome ?: return BridgeCommands.ExecutorResult(
                ok = false, error = "command_failed"
            )
            return when (o.result) {
                NetTools.ModeResult.APPLIED -> BridgeCommands.ExecutorResult(
                    ok = true,
                    resultJson = MiniJson.obj("mode" to mode.name.lowercase(), "detail" to o.detail)
                )
                NetTools.ModeResult.WRITE_OK_NOT_APPLIED -> BridgeCommands.ExecutorResult(
                    ok = false, error = "write_ok_but_not_applied"
                )
                NetTools.ModeResult.NEEDS_ADB -> BridgeCommands.ExecutorResult(
                    ok = false, error = "needs_adb"
                )
                NetTools.ModeResult.BLOCKED_BLAST -> BridgeCommands.ExecutorResult(
                    ok = false, error = "blocked_blast_active"
                )
                NetTools.ModeResult.FAILED -> BridgeCommands.ExecutorResult(
                    ok = false, error = "mode_failed"
                )
            }
        }
    }
}
