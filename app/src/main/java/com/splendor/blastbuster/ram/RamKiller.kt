package com.splendor.blastbuster.ram

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telecom.TelecomManager
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.EventLog

/**
 * Kill path (DESIGN.md §5.1). One package per call; there is deliberately
 * NO "kill all" button — a bulk kill on HyperOS causes a restart storm
 * and can kill the guard's own helpers. There is NO fully-automatic kill
 * in v1.2: every kill is a user tap or an explicit Muse command
 * (`ram.kill`), never a background sweep.
 *
 * `killBackgroundProcesses` only kills *background* processes; a
 * foreground app cannot be killed this way, and badly behaved apps
 * restart themselves — the verify step reports that honestly instead of
 * claiming a kill that did not stick.
 */
object RamKiller {

    const val SYSTEM_UI_PKG = "com.android.systemui"

    /** Same package cannot be killed more than once per hour. */
    const val KILL_COOLDOWN_MS = 60L * 60L * 1000L

    /** Re-scan delay for the kill verify step. */
    const val VERIFY_DELAY_MS = 5000L

    enum class KillOutcome {
        KILLED, RESTARTED, FAILED, DENIED, BLOCKED_BLAST_ACTIVE, COOLDOWN
    }

    data class KillResult(val outcome: KillOutcome, val detail: String)

    /**
     * Pure denylist check. Returns the reason the package must not be
     * killed, or null when killing it is allowed. JVM-testable.
     *
     * @param pkg the package to kill.
     * @param selfPkg this app's own package — never kill ourselves.
     * @param protectedList the user's protected list from Prefs.
     * @param defaultDialer the default dialer package, or null if unknown.
     * @param defaultSms the default SMS package, or null if unknown.
     * @param launcherPkg the current launcher package, or null if unknown.
     */
    fun denialReason(
        pkg: String,
        selfPkg: String,
        protectedList: List<String>,
        defaultDialer: String?,
        defaultSms: String?,
        launcherPkg: String?
    ): String? {
        if (pkg.isBlank()) return "empty package name"
        if (pkg == selfPkg) return "never kill Blast Buster itself"
        if (pkg == SYSTEM_UI_PKG) return "never kill the system UI"
        if (pkg == defaultDialer) return "never kill the default dialer"
        if (pkg == defaultSms) return "never kill the default SMS app"
        if (pkg == launcherPkg) return "never kill the launcher"
        if (pkg in protectedList) return "on the user's protected list"
        return null
    }

    /** Milliseconds of cooldown remaining for [pkg], 0 when it may be killed. */
    fun cooldownRemainingMs(lastKillMs: Long, nowMs: Long): Long {
        if (lastKillMs <= 0L) return 0L
        val remaining = KILL_COOLDOWN_MS - (nowMs - lastKillMs)
        return if (remaining > 0) remaining else 0L
    }

    /** Resolves the protected packages on this device (best effort). */
    fun protectedPackages(ctx: Context): Triple<String?, String?, String?> {
        var dialer: String? = null
        var sms: String? = null
        var launcher: String? = null
        try {
            dialer = ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (_: Exception) {
        }
        try {
            sms = Telephony.Sms.getDefaultSmsPackage(ctx)
        } catch (_: Exception) {
        }
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            launcher = ctx.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        } catch (_: Exception) {
        }
        return Triple(dialer, sms, launcher)
    }

    /**
     * Kills one package's background processes, then verifies by re-scan
     * after 5 s and reports [KillResult] via [onResult] (main thread).
     * Blast-gated, denylist-checked and cooldown-checked, in that order.
     */
    fun kill(ctx: Context, pkg: String, onResult: (KillResult) -> Unit) {
        val appCtx = ctx.applicationContext
        val gate = BlastGate.forContext(appCtx)
        if (!gate.checkWrite("ram.kill $pkg") { reason ->
                EventLog.append(appCtx, "ram", "RAM_KILL_SKIPPED", reason)
            }) {
            onResult(KillResult(KillOutcome.BLOCKED_BLAST_ACTIVE, "blocked: blast active"))
            return
        }
        val (dialer, sms, launcher) = protectedPackages(appCtx)
        val denied = denialReason(
            pkg, appCtx.packageName, Prefs.ramProtectedList(appCtx), dialer, sms, launcher
        )
        if (denied != null) {
            EventLog.append(appCtx, "ram", "RAM_KILL_DENIED", "Kill denied for $pkg: $denied")
            onResult(KillResult(KillOutcome.DENIED, denied))
            return
        }
        val now = System.currentTimeMillis()
        val remaining = cooldownRemainingMs(Prefs.ramLastKillMs(appCtx, pkg), now)
        if (remaining > 0) {
            onResult(
                KillResult(
                    KillOutcome.COOLDOWN,
                    "already killed ${remaining / 1000}s ago in cooldown (1 h)"
                )
            )
            return
        }
        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pidsBefore = runningPids(am, pkg)
        try {
            am.killBackgroundProcesses(pkg)
        } catch (e: Exception) {
            EventLog.append(
                appCtx, "ram", "RAM_KILL_FAILED",
                "Kill failed for $pkg: ${e.message}"
            )
            onResult(KillResult(KillOutcome.FAILED, "kill call failed: ${e.message}"))
            return
        }
        Prefs.recordRamKill(appCtx, pkg, now)
        Handler(Looper.getMainLooper()).postDelayed({
            val pidsAfter = runningPids(am, pkg)
            val result = when {
                pidsAfter.isEmpty() ->
                    KillResult(KillOutcome.KILLED, "process gone after 5 s re-scan")
                pidsAfter != pidsBefore ->
                    KillResult(KillOutcome.RESTARTED, "package came back with new process(es)")
                else ->
                    KillResult(KillOutcome.FAILED, "process still running with the same pid")
            }
            EventLog.append(
                appCtx, "ram", "RAM_KILL_${result.outcome}",
                "Kill $pkg: ${result.outcome.name.lowercase()} — ${result.detail}"
            )
            onResult(result)
        }, VERIFY_DELAY_MS)
    }

    private fun runningPids(am: ActivityManager, pkg: String): Set<Int> {
        return try {
            am.runningAppProcesses
                ?.filter { it.processName == pkg }
                ?.map { it.pid }
                ?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
