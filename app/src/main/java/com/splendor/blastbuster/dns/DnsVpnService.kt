package com.splendor.blastbuster.dns

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.R
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.net.EventSink
import com.splendor.blastbuster.net.LogcatEventSink
import com.splendor.blastbuster.net.eventLogSink
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

/**
 * Local DNS pipe service (DESIGN.md §5.4). A VpnService that is NOT a
 * VPN: it builds a DNS-only tunnel (address 10.0.0.2, routes only the
 * chosen upstream IP — no 0.0.0.0/0 route), so only DNS traffic enters
 * it and everything else goes direct, unseen by the app.
 *
 * No external server, no account, no real tunnel. Forwarded queries go
 * to the user's chosen upstream over plain UDP/53 (DoH/DoT is design
 * decision D-09, v1.3 at the earliest — the UI says the queries are
 * unencrypted to the upstream).
 *
 * Blast rule (DESIGN.md §1.3 rule 3): on BLAST_STARTED the tunnel
 * STAYS UP (dropping it mid-blast could stall the GPS reply SMS path)
 * but blocklist reloads and log writes stop until BLAST_STOPPED. The
 * service never calls stopSelf() on blast, and never starts on blast.
 *
 * One VPN slot: if another VPN (including Tailscale on the phone)
 * holds the slot, establish() returns null — the pipe yields, logs
 * DNS_YIELDED_TO_VPN, and shows the conflict card. The bridge still
 * works with the pipe off; the user chooses which holds the slot.
 * (Design decision D-08.)
 *
 * Fail-open: any pipe failure tears the tunnel down and traffic
 * returns to normal. A dead filter must never black-hole the phone.
 *
 * MERGE NOTE (Agent C): the manifest entry for this service must be:
 *   <service android:name=".dns.DnsVpnService" android:exported="false"
 *     android:permission="android.permission.BIND_VPN_SERVICE"
 *     android:foregroundServiceType="specialUse">
 *     <intent-filter>
 *       <action android:name="android.net.VpnService" />
 *     </intent-filter>
 *   </service>
 * plus the uses-permissions ACCESS_NETWORK_STATE, INTERNET (P15/P16).
 * This file does NOT edit the manifest (§4.7 ownership).
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.splendor.blastbuster.dns.START"
        const val ACTION_STOP = "com.splendor.blastbuster.dns.STOP"
        const val ACTION_RELOAD = "com.splendor.blastbuster.dns.RELOAD"

        // Broadcast actions (DESIGN.md §4.8). MERGE NOTE (Agent A):
        // these literals MUST match BlastGate's companion constants
        // exactly; if BlastGate uses different strings, the merge agent
        // unifies them here — do not leave two spellings.
        const val ACTION_BLAST_STARTED = "com.splendor.blastbuster.BLAST_STARTED"
        const val ACTION_BLAST_STOPPED = "com.splendor.blastbuster.BLAST_STOPPED"

        private const val TAG = "BlastBuster:dns"
        private const val NOTIF_ID = 4202
        private const val CHANNEL_ID = "dns_pipe"
        private const val TUN_ADDRESS = "10.0.0.2"
        private const val FALLBACK_UPSTREAM = "1.1.1.1"

        @Volatile
        var isRunning = false
            private set

        /** True when another VPN holds the single VPN slot. */
        @Volatile
        var conflictWithVpn = false
            private set

        /** True when Android Private DNS is on — the pipe is then inert (design §5.4). */
        @Volatile
        var privateDnsOn = false
            private set

        /** Last fatal error, for the DNS screen. Null when healthy. */
        @Volatile
        var lastError: String? = null
            private set

        /** Upstream actually in use right now (after "system" resolution). */
        @Volatile
        var upstreamInUse: String = FALLBACK_UPSTREAM
            private set

        /** Latest stats snapshot, pushed by the pipe. Null before the first query. */
        @Volatile
        var latestStats: DnsPipe.DnsStatsSnapshot? = null
            private set

        /** Latest blocklist sizes, for the DNS screen. */
        @Volatile
        var blocklistBlockedCount: Int = 0
            private set

        fun startIntent(ctx: Context): Intent =
            Intent(ctx, DnsVpnService::class.java).setAction(ACTION_START)

        fun stopIntent(ctx: Context): Intent =
            Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP)

        fun reloadIntent(ctx: Context): Intent =
            Intent(ctx, DnsVpnService::class.java).setAction(ACTION_RELOAD)
    }

    @Volatile
    private var blastActive = false

    private var tunFd: ParcelFileDescriptor? = null
    private var pipe: DnsPipe? = null
    private var pipeThread: Thread? = null

    /** Live sink: the shared EventLog ring (MERGED — was logcat). Set in onCreate. */
    private var logSink: EventSink = LogcatEventSink

    /** Log writes stop during a blast (DESIGN.md §1.3 rule 3). */
    private val gatedSink = EventSink { module, type, summary ->
        if (!blastActive) logSink.log(module, type, summary)
    }

    private val blastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_BLAST_STARTED -> {
                    // Keep the tunnel up; stop reloads + log writes.
                    blastActive = true
                }
                ACTION_BLAST_STOPPED -> {
                    blastActive = false
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        logSink = eventLogSink(this)
        val filter = IntentFilter().apply {
            addAction(ACTION_BLAST_STARTED)
            addAction(ACTION_BLAST_STOPPED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(blastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(blastReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "blast receiver registration failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reloadBlocklist()
                return START_STICKY
            }
            ACTION_START, null -> {
                // Null intent = system restart after a kill: come back
                // only if the user still wants the pipe and no blast runs.
                if (BlastGate.isBlasting(this)) {
                    Log.i(TAG, "DNS start refused: blast active.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (intent == null && !Prefs.dnsEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTunnel()
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    override fun onRevoke() {
        // The system gave the single VPN slot to another app (a real VPN
        // or Tailscale). Yield, say so, and stop — do not fight for it.
        gatedSink.log("dns", "DNS_YIELDED_TO_VPN", "Another VPN took the VPN slot; DNS pipe paused.")
        conflictWithVpn = true
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(blastReceiver)
        } catch (_: Exception) {
        }
        stopTunnel()
        super.onDestroy()
    }

    // ---------- tunnel ----------

    private fun startTunnel() {
        if (isRunning) return
        conflictWithVpn = false
        lastError = null
        checkPrivateDns()
        val upstream = resolveUpstream()
        upstreamInUse = upstream.hostAddress ?: FALLBACK_UPSTREAM
        val builder = Builder()
            .setSession("Blast Buster DNS")
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(upstream)
            .addRoute(upstreamInUse, 32)
        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            lastError = "Could not establish the tunnel: ${e.message}"
            Log.w(TAG, "establish failed: ${e.message}")
            stopSelf()
            return
        }
        if (fd == null) {
            // Another VPN holds the slot.
            conflictWithVpn = true
            gatedSink.log("dns", "DNS_YIELDED_TO_VPN", "Another VPN is active; DNS pipe paused.")
            stopSelf()
            return
        }
        tunFd = fd
        startForegroundWithNotification(upstreamInUse)
        val blocklist = loadBlocklist()
        val newPipe = DnsPipe(
            tunIn = FileInputStream(fd.fileDescriptor),
            tunOut = FileOutputStream(fd.fileDescriptor),
            blocklist = blocklist,
            upstream = upstream,
            statsEnabled = Prefs.dnsStatsEnabled(this),
            eventSink = gatedSink,
            onFailOpen = { reason ->
                // Fail-open: tear the tunnel down on the service thread.
                stopTunnel()
                stopSelf()
                Log.w(TAG, "DNS fail-open: $reason")
            }
        )
        pipe = newPipe
        newPipe.statsListener = { snap -> latestStats = snap }
        val t = Thread({ newPipe.runLoop() }, "DnsPipe").apply { start() }
        pipeThread = t
        isRunning = true
        gatedSink.log(
            "dns", "DNS_STARTED",
            "DNS pipe up (upstream $upstreamInUse, ${blocklist.blockedCount} blocked domains)."
        )
    }

    private fun stopTunnel() {
        if (!isRunning && pipe == null && tunFd == null) return
        try {
            pipe?.stop()
        } catch (_: Exception) {
        }
        pipe = null
        pipeThread = null
        try {
            tunFd?.close()
        } catch (_: Exception) {
        }
        tunFd = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        if (isRunning) {
            gatedSink.log("dns", "DNS_STOPPED", "DNS pipe stopped; normal DNS resumed.")
        }
        isRunning = false
        latestStats = null
    }

    /**
     * Reload the blocklist live. Refused during a blast (DESIGN.md
     * §1.3 rule 3) — silently, because log writes are also stopped
     * during a blast; the next reload after the blast picks it up.
     */
    fun reloadBlocklist() {
        if (blastActive || !isRunning) return
        val list = loadBlocklist()
        pipe?.updateBlocklist(list)
        gatedSink.log("dns", "DNS_RELOAD", "Blocklist reloaded (${list.blockedCount} domains).")
    }

    // ---------- helpers ----------

    private fun checkPrivateDns() {
        val mode = try {
            Settings.Global.getString(contentResolver, "private_dns_mode")
        } catch (_: Exception) {
            null
        }
        // "off" (or unset) = the pipe works. Anything else ("opportunistic",
        // "hostname") means the system bypasses the tunnel's DNS.
        privateDnsOn = mode != null && mode != "off"
        if (privateDnsOn) {
            gatedSink.log(
                "dns", "DNS_PRIVATE_DNS_ON",
                "Private DNS is $mode — the system bypasses the DNS pipe until Private DNS is off."
            )
        }
    }

    /** Resolve the configured upstream ("system" = active link's DNS, else the literal IP). */
    private fun resolveUpstream(): InetAddress {
        val configured = Prefs.dnsUpstream(this)
        if (configured == "system") {
            val systemDns = systemDnsServer()
            if (systemDns != null) return systemDns
        }
        return try {
            InetAddress.getByName(configured)
        } catch (_: Exception) {
            try {
                InetAddress.getByName(FALLBACK_UPSTREAM)
            } catch (e: Exception) {
                throw IllegalStateException("no usable upstream DNS", e)
            }
        }
    }

    private fun systemDnsServer(): InetAddress? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bundled list (res/raw) + user entries in one Blocklist. The
     * bundled lines are read first and the user's block lines appended
     * before parsing, so one bad user line can never break the bundled
     * list — it is just skipped by the parser. A failed bundled load
     * degrades to the user entries only, never a crash.
     */
    private fun loadBlocklist(): Blocklist {
        var bundledLines: List<String> = emptyList()
        try {
            resources.openRawResource(R.raw.blocklist_hosts).bufferedReader().useLines { lines ->
                bundledLines = lines.toList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "bundled blocklist failed to load: ${e.message}")
        }
        val merged = Blocklist.fromBlockLines(
            bundledLines + Prefs.splitEntries(Prefs.dnsUserBlockRaw(this))
        )
            .withAllow(Prefs.splitEntries(Prefs.dnsUserAllowRaw(this)))
            .withLie(Prefs.splitEntries(Prefs.dnsUserLieRaw(this)))
        blocklistBlockedCount = merged.blockedCount
        return merged
    }

    private fun startForegroundWithNotification(upstream: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DNS filter",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, DnsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_siren)
            .setContentTitle("Blast Buster DNS filter running")
            .setContentText("Blocking ads and trackers — upstream $upstream.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
