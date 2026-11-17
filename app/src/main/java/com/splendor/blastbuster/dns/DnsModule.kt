package com.splendor.blastbuster.dns

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.CompanionModule
import com.splendor.blastbuster.companion.MiniJson

/**
 * Wiring adapter: the DNS module as one unit for CompanionService /
 * the bridge (DESIGN.md §4.7, §4.8).
 *
 * MERGED (v1.2 merge): this class now implements [CompanionModule].
 * The §4.8 signatures return Unit / Boolean / String, but the DNS
 * pipe also needs to report *why* a start did (or did not) happen
 * (consent, blast, disabled), so the result-carrying work lives in
 * [startPipe]/[stopPipe] and the interface methods delegate to them:
 * [start] stores the context and calls [startPipe] (discarding the
 * result — the service start is best-effort); [stop] stops the pipe
 * with the stored context; [statusJson] reads the stored context.
 *
 * VPN consent: the first start needs the system VPN dialog
 * (VpnService.prepare() returns an intent). A module cannot show a
 * dialog — [consentIntent] returns it and the DNS screen (DnsActivity)
 * starts it; [startPipe] reports NEEDS_CONSENT in that case instead
 * of pretending the pipe is up.
 *
 * Blast rule: startPipe() refuses while a blast is active (DESIGN.md
 * §1.3 rule 3 — the pipe never starts on blast). An already-running
 * pipe is left alone by suspend: CompanionService never calls [stop]
 * on BLAST_STARTED — the service itself keeps the tunnel up and gates
 * reloads/logs. Only an explicit user/bridge off stops the pipe.
 */
class DnsModule : CompanionModule {

    enum class DnsStartResult {
        STARTED, ALREADY_RUNNING, DISABLED, NEEDS_CONSENT, BLOCKED_BLAST
    }

    private var appCtx: Context? = null

    /** CompanionModule start: stores the context, best-effort pipe start. */
    override fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        startPipe(ctx)
    }

    /** CompanionModule stop: stops the pipe with the stored context (no-op if never started). */
    override fun stop() {
        val ctx = appCtx ?: return
        stopPipe(ctx)
    }

    override fun isRunning(): Boolean = DnsVpnService.isRunning

    /** Idempotent start. Returns why it did (or did not) start. */
    fun startPipe(ctx: Context): DnsStartResult {
        appCtx = ctx.applicationContext
        if (BlastGate.isBlasting(ctx)) return DnsStartResult.BLOCKED_BLAST
        if (!Prefs.dnsEnabled(ctx)) return DnsStartResult.DISABLED
        if (DnsVpnService.isRunning) return DnsStartResult.ALREADY_RUNNING
        if (VpnService.prepare(ctx) != null) return DnsStartResult.NEEDS_CONSENT
        ctx.startService(DnsVpnService.startIntent(ctx))
        return DnsStartResult.STARTED
    }

    /** Idempotent stop. Safe to call during a blast (stopping is not starting). */
    fun stopPipe(ctx: Context) {
        ctx.startService(DnsVpnService.stopIntent(ctx))
    }

    /** The VPN consent intent, or null when consent was already given. The UI starts it with startActivityForResult. */
    fun consentIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

    /** Ask a running pipe to reload its blocklist (blast-gated inside the service). */
    fun reloadBlocklist(ctx: Context) {
        if (DnsVpnService.isRunning) {
            ctx.startService(DnsVpnService.reloadIntent(ctx))
        }
    }

    /** Live Private DNS bypass check for the bridge status fragment. */
    internal fun livePrivateDnsOn(ctx: Context): Boolean {
        val mode = try {
            Settings.Global.getString(ctx.contentResolver, "private_dns_mode")
        } catch (_: Exception) {
            null
        }
        return DnsState.bypassesPipe(DnsState.parsePrivateDnsMode(mode))
    }

    /** JSON fragment for the bridge status (built with MiniJson, §4.2). */
    override fun statusJson(): String {
        val ctx = appCtx
        val stats = DnsVpnService.latestStats
        val fields = LinkedHashMap<String, Any?>()
        fields["running"] = DnsVpnService.isRunning
        fields["enabled"] = ctx?.let { Prefs.dnsEnabled(it) } ?: false
        fields["upstream"] = DnsVpnService.upstreamInUse
        fields["blocked_domains"] = DnsVpnService.blocklistBlockedCount
        // Live Private DNS mode (v1.3): the service flag is only set at
        // tunnel start, so read the setting here — the bridge must not
        // report "private_dns_on: false" while the user turned Private
        // DNS on after the pipe started.
        fields["private_dns_on"] = ctx?.let { livePrivateDnsOn(it) }
            ?: DnsVpnService.privateDnsOn
        fields["vpn_conflict"] = DnsVpnService.conflictWithVpn
        if (stats != null) {
            fields["queries_today"] = stats.queries
            fields["blocked_today"] = stats.blocked
            fields["forwarded_today"] = stats.forwarded
            fields["forward_failed_today"] = stats.forwardFailed
            fields["top_blocked"] = stats.topBlocked.map { (domain, count) ->
                mapOf<String, Any?>("domain" to domain, "count" to count)
            }
        }
        return MiniJson.obj(fields)
    }
}
