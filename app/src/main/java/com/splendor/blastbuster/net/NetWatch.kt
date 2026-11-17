package com.splendor.blastbuster.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.EventLog
import com.splendor.blastbuster.companion.MiniJson
import java.time.LocalDate

/**
 * Signal watch (DESIGN.md §5.3). Polls every 30 s per subscription,
 * grades the signal with [SignalGrader], keeps a 48-sample history ring
 * per SIM (24 minutes), and reports drops (a fall of two grades within
 * ~60 s) to the event sink.
 *
 * Carrier names come from the subscription info — never hardcoded.
 * Another owner's SIMs show their own carriers; this code names none.
 *
 * Honesty: this reports the signal that is there. It cannot create
 * coverage, and a grade of NONE means "no usable signal measured",
 * not "the app is broken". A single bad sample never triggers a drop:
 * grades are smoothed over the last 3 samples (SignalGrader) and a drop
 * needs a two-grade fall against the sample from ~60 s ago.
 *
 * Read-only: polling never changes phone state, so it is safe to run
 * while a blast is active — but the host (CompanionService, Agent A)
 * still suspends the timer during a blast per DESIGN.md §1.3, and this
 * class exposes stop() for exactly that. No blast check lives here
 * because this class never writes anything.
 *
 * No-READ_PHONE_STATE fallback: without the permission the subscription
 * list is unavailable, so the snapshot reports a single entry with
 * carrier "unknown" and grade NONE rather than crashing or inventing
 * a SIM. The UI says so.
 */
class NetWatch(var eventSink: EventSink = LogcatEventSink) {

    /** One SIM's reading at one moment. dbm = Int.MAX_VALUE when unknown. */
    data class SimSignal(
        val subscriptionId: Int,
        val slotIndex: Int,
        val carrierName: String,
        val transport: Transport,
        val dbm: Int,
        val level: Int,
        val grade: SignalGrade,
        val dataConnected: Boolean
    )

    data class NetSnapshot(val timestampMs: Long, val sims: List<SimSignal>)

    fun interface NetWatchListener {
        /** Called on the poll thread after every poll — post to main before touching UI. */
        fun onSnapshot(snapshot: NetSnapshot)
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L
        const val HISTORY_CAP = 48
        const val DROP_LOG_CAP = 20
        private const val TAG = "BlastBuster:net"
    }

    var listener: NetWatchListener? = null

    @Volatile
    var lastSnapshot: NetSnapshot? = null
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val history = mutableMapOf<Int, ArrayDeque<SimSignal>>()
    private val dropLog = ArrayDeque<String>()

    /** Last 10 drop descriptions, newest first, for the Network screen. */
    fun recentDrops(): List<String> = synchronized(dropLog) { dropLog.toList() }

    fun isRunning(): Boolean = thread?.isAlive == true

    /** Idempotent. Starts the 30 s poll loop (first poll immediately). */
    fun start(ctx: Context) {
        if (isRunning()) return
        val appCtx = ctx.applicationContext
        val t = HandlerThread("NetWatch").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val runnable = object : Runnable {
            override fun run() {
                try {
                    poll(appCtx)
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed: ${e.message}")
                }
                handler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        h.post(runnable)
    }

    /** Idempotent. Stops the poll loop; history is kept for the next start. */
    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    /** One synchronous read with no history/event side effects (for "check now" and flick verify). */
    fun pollNow(ctx: Context): NetSnapshot = readSnapshot(ctx.applicationContext)

    /** Timer tick: read, update history, detect drops, notify. */
    private fun poll(ctx: Context) {
        val snapshot = readSnapshot(ctx)
        lastSnapshot = snapshot
        val today = LocalDate.now().toString()
        for (sim in snapshot.sims) {
            val ring = history.getOrPut(sim.subscriptionId) { ArrayDeque() }
            // Drop = two-grade fall vs the sample ~60 s ago (two polls back).
            if (ring.size >= 2) {
                val old = ring[ring.size - 2]
                if (SignalGrader.isDrop(old.grade, sim.grade)) {
                    val text = "${sim.carrierName} (SIM ${sim.slotIndex + 1}): " +
                        "${old.grade} -> ${sim.grade} (${old.dbm} -> ${sim.dbm} dBm)"
                    eventSink.log("net", "NET_DROP", "Signal drop: $text")
                    Prefs.recordDrop(ctx, today, sim.grade)
                    synchronized(dropLog) {
                        dropLog.addFirst(text)
                        while (dropLog.size > DROP_LOG_CAP) dropLog.removeLast()
                    }
                }
            }
            ring.addLast(sim)
            while (ring.size > HISTORY_CAP) ring.removeFirst()
        }
        listener?.onSnapshot(snapshot)
    }

    private fun readSnapshot(ctx: Context): NetSnapshot {
        val subs = readSubscriptions(ctx)
        val sims = if (subs.isEmpty()) {
            listOf(
                SimSignal(
                    subscriptionId = -1, slotIndex = 0,
                    carrierName = "unknown", transport = Transport.UNKNOWN,
                    dbm = Int.MAX_VALUE, level = 0, grade = SignalGrade.NONE,
                    dataConnected = false
                )
            )
        } else {
            subs.map { readSim(ctx, it) }
        }
        return NetSnapshot(System.currentTimeMillis(), sims)
    }

    private fun readSubscriptions(ctx: Context): List<SubscriptionInfo> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            sm.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "subscription list denied: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "subscription list failed: ${e.message}")
            emptyList()
        }
    }

    private fun readSim(ctx: Context, sub: SubscriptionInfo): SimSignal {
        val carrier = (sub.carrierName?.toString()?.trim().takeUnless { it.isNullOrEmpty() })
            ?: (sub.displayName?.toString()?.trim().takeUnless { it.isNullOrEmpty() })
            ?: "SIM ${sub.simSlotIndex + 1}"
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val subTm = tm.createForSubscriptionId(sub.subscriptionId)
        val networkType = try {
            // dataNetworkType needs API 24+; minSdk is 26, so no fallback branch.
            subTm.dataNetworkType
        } catch (_: Exception) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        val transport = networkTypeToTransport(networkType)
        // SignalStrength direct read needs API 28+. Below that we report
        // the transport but no grade — honestly, rather than guessing.
        // (Target device is API 36; minSdk 26 keeps this branch honest.)
        var dbm = Int.MAX_VALUE
        var level = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val strength = subTm.signalStrength
                val cell = strength?.cellSignalStrengths?.firstOrNull()
                if (cell != null) {
                    dbm = cell.dbm
                    level = strength.level
                }
            } catch (e: Exception) {
                Log.w(TAG, "signal read failed: ${e.message}")
            }
        }
        val dataConnected = try {
            subTm.dataState == TelephonyManager.DATA_CONNECTED
        } catch (_: Exception) {
            false
        }
        val grade = if (dbm == Int.MAX_VALUE) {
            SignalGrader.gradeFromLevel(level)
        } else {
            SignalGrader.gradeFromDbm(dbm, transport)
        }
        return SimSignal(
            subscriptionId = sub.subscriptionId,
            slotIndex = sub.simSlotIndex,
            carrierName = carrier,
            transport = transport,
            dbm = dbm,
            level = level,
            grade = grade,
            dataConnected = dataConnected
        )
    }

    /** TelephonyManager NETWORK_TYPE_* int -> Transport. Pure mapping, no radio access. */
    internal fun networkTypeToTransport(type: Int): Transport = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE -> Transport.GSM
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD -> Transport.CDMA
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> Transport.UMTS
        TelephonyManager.NETWORK_TYPE_LTE,
        // 19 = NETWORK_TYPE_LTE_CA (LTE carrier aggregation). It is not
        // a public constant, so the AOSP value is used as a literal.
        19 -> Transport.LTE
        TelephonyManager.NETWORK_TYPE_NR -> Transport.NR
        else -> Transport.UNKNOWN
    }

    /** JSON fragment for GET /v1/net (bridge). Built with MiniJson (§4.2). */
    fun statusJson(): String {
        val snap = lastSnapshot
        val sims = snap?.sims?.map { sim ->
            mapOf<String, Any?>(
                "sub_id" to sim.subscriptionId,
                "slot" to sim.slotIndex,
                "carrier" to sim.carrierName,
                "transport" to sim.transport.name,
                "dbm" to if (sim.dbm == Int.MAX_VALUE) null else sim.dbm,
                "level" to sim.level,
                "grade" to sim.grade.name,
                "data_connected" to sim.dataConnected
            )
        } ?: emptyList<Map<String, Any?>>()
        return MiniJson.obj("poll_interval_s" to 30, "sims" to sims)
    }
}

/**
 * The default sink for Android modules: logcat. Defined here (not in
 * EventSink.kt) so EventSink.kt stays Android-free and JVM-testable.
 * MERGED (v1.2 merge): the live sink is [eventLogSink] — every module
 * that defaults to LogcatEventSink is re-pointed to the EventLog ring
 * at start time (see NetModule); logcat remains only as the pre-wire
 * fallback so nothing is silently lost if wiring fails.
 */
val LogcatEventSink: EventSink = EventSink { module, type, summary ->
    Log.d("BlastBuster:$module", "$type: $summary")
}

/**
 * The live sink: appends to the shared EventLog ring (DESIGN.md §2.6).
 * Never throws — a lost log line is acceptable, a crashed caller is not.
 */
fun eventLogSink(ctx: Context): EventSink = EventSink { module, type, summary ->
    try {
        EventLog.append(ctx.applicationContext, module, type, summary)
    } catch (_: Exception) {
    }
}
