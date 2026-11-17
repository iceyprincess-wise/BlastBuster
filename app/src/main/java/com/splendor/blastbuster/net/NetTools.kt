package com.splendor.blastbuster.net

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.EventLog

/**
 * Airplane-mode flick + network-mode lock (DESIGN.md §5.3).
 *
 * Both features need the ADB-only grant WRITE_SECURE_SETTINGS (P19).
 * Without it they degrade to guided manual steps — the Settings page
 * is opened with instructions, and the same verify step runs after.
 * Nothing here pretends a missing grant is present, and nothing here
 * claims to create signal: a flick or a mode lock can only make the
 * phone re-attach to a tower that exists.
 *
 * Blast rule (DESIGN.md §1.3): both actions are writes, so they are
 * blocked while a blast is active. MERGED: the check is routed through
 * [BlastGate] ([blastBlocked]) — one enforcement point, and a blocked
 * attempt is logged to EventLog as SKIPPED_BLAST_ACTIVE.
 *
 * All work runs on a fresh background thread; results are posted to
 * the main thread so activities can touch UI in the callback.
 */
object NetTools {

    /**
     * The Settings.Global key for the preferred network mode. There is
     * NO public Settings.Global constant for it (AOSP uses the literal
     * string "preferred_network_mode"), so the literal is used here —
     * verified by the build (a wrong key would silently write a
     * no-op setting, which the read-back step catches).
     */
    const val KEY_PREFERRED_NETWORK_MODE = "preferred_network_mode"

    // ---------- results ----------

    enum class FlickResult {
        OK_RESTORED, OK_NO_IMPROVEMENT, FAILED, NEEDS_ADB, COOLDOWN, BLOCKED_BLAST
    }

    data class FlickOutcome(
        val result: FlickResult,
        val detail: String,
        val remainingCooldownMs: Long = 0L
    )

    enum class ModeResult {
        APPLIED, WRITE_OK_NOT_APPLIED, FAILED, NEEDS_ADB, BLOCKED_BLAST
    }

    data class ModeOutcome(val result: ModeResult, val detail: String)

    // ---------- capability ----------

    /** True when the one-tap path is available (ADB grant present). */
    fun hasSecureSettingsGrant(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun airplaneSettingsIntent(): Intent =
        Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)

    fun networkOperatorSettingsIntent(): Intent =
        Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)

    // ---------- airplane flick ----------

    /**
     * Airplane-mode flick (DESIGN.md §5.3): radio off 8 s, radio on,
     * then verify for up to 60 s.
     *
     * [verifySignal] is called repeatedly after the radio comes back
     * and must return true once the phone can pass data again (the
     * caller wires it to NetWatch — e.g. any SIM grade != NONE).
     * Pass null to skip verification (outcome is then
     * OK_NO_IMPROVEMENT with detail "not verified").
     *
     * With the grant this is one tap. Without it the outcome is
     * NEEDS_ADB and the caller must show the guided steps (open the
     * airplane settings, on, wait 8 s, off) — this function never
     * opens Settings itself, so the UI stays in control of the flow.
     */
    fun flickAirplane(
        ctx: Context,
        verifySignal: (() -> Boolean)?,
        callback: (FlickOutcome) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        Thread {
            val outcome = runFlick(appCtx, verifySignal)
            postToMain { callback(outcome) }
        }.apply { name = "NetTools-flick" }.start()
    }

    private fun runFlick(ctx: Context, verifySignal: (() -> Boolean)?): FlickOutcome {
        if (blastBlocked(ctx, "net.flick")) {
            return FlickOutcome(
                FlickResult.BLOCKED_BLAST,
                NetOutcomeText.flickMessage(FlickResult.BLOCKED_BLAST.name)
            )
        }
        val sinceLast = System.currentTimeMillis() - Prefs.lastFlickAt(ctx)
        if (sinceLast < Prefs.FLICK_COOLDOWN_MS) {
            val remaining = Prefs.FLICK_COOLDOWN_MS - sinceLast
            return FlickOutcome(
                FlickResult.COOLDOWN,
                NetOutcomeText.flickMessage(FlickResult.COOLDOWN.name, remaining),
                remaining
            )
        }
        if (!hasSecureSettingsGrant(ctx)) {
            return FlickOutcome(
                FlickResult.NEEDS_ADB,
                NetOutcomeText.flickMessage(FlickResult.NEEDS_ADB.name)
            )
        }
        return try {
            setAirplaneMode(ctx, true)
            Thread.sleep(Prefs.FLICK_OFFLINE_MS)
            setAirplaneMode(ctx, false)
            Prefs.setLastFlickAt(ctx, System.currentTimeMillis())
            EventLog.append(ctx, "net", "NET_FLICK", "Airplane flick done; verifying signal.")
            val restored = verifyAfterFlick(verifySignal)
            if (restored) {
                FlickOutcome(
                    FlickResult.OK_RESTORED,
                    NetOutcomeText.flickMessage(FlickResult.OK_RESTORED.name)
                )
            } else {
                FlickOutcome(
                    FlickResult.OK_NO_IMPROVEMENT,
                    NetOutcomeText.flickMessage(FlickResult.OK_NO_IMPROVEMENT.name)
                )
            }
        } catch (e: SecurityException) {
            FlickOutcome(
                FlickResult.FAILED,
                NetOutcomeText.flickMessage(
                    FlickResult.FAILED.name,
                    cause = "System refused the airplane write: ${e.message}"
                )
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            FlickOutcome(
                FlickResult.FAILED,
                NetOutcomeText.flickMessage(FlickResult.FAILED.name, cause = "Flick interrupted.")
            )
        } catch (e: Exception) {
            FlickOutcome(
                FlickResult.FAILED,
                NetOutcomeText.flickMessage(
                    FlickResult.FAILED.name, cause = "Flick failed: ${e.message}"
                )
            )
        }
    }

    /** Poll [verifySignal] every 5 s for up to 60 s. True on first success. */
    private fun verifyAfterFlick(verifySignal: (() -> Boolean)?): Boolean {
        if (verifySignal == null) return false
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                if (verifySignal()) return true
            } catch (_: Exception) {
                // A failed verify read is "not restored yet", not a crash.
            }
            try {
                Thread.sleep(5_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun setAirplaneMode(ctx: Context, on: Boolean) {
        Settings.Global.putInt(
            ctx.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            if (on) 1 else 0
        )
        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        intent.putExtra("state", on)
        ctx.sendBroadcast(intent)
    }

    // ---------- network-mode lock ----------

    /**
     * Network-mode lock (DESIGN.md §5.3): write the preferred mode for
     * one subscription, read it back, then check the live network type
     * after 30 s.
     *
     * Outcomes: APPLIED (write stuck AND live type matches), or
     * WRITE_OK_NOT_APPLIED — the §3.4 trap: HyperOS may accept the
     * write and still ignore it, so the write alone is never reported
     * as success. Without the grant the outcome is NEEDS_ADB and the
     * caller's UI shows the guided path; the user's stated choice is
     * still recorded so the screen shows what was asked for.
     */
    fun setNetworkMode(
        ctx: Context,
        subscriptionId: Int,
        slotIndex: Int,
        mode: NetMode,
        callback: (ModeOutcome) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        Thread {
            val outcome = runSetMode(appCtx, subscriptionId, slotIndex, mode)
            postToMain { callback(outcome) }
        }.apply { name = "NetTools-mode" }.start()
    }

    private fun runSetMode(
        ctx: Context,
        subscriptionId: Int,
        slotIndex: Int,
        mode: NetMode
    ): ModeOutcome {
        if (blastBlocked(ctx, "net.mode")) {
            return ModeOutcome(
                ModeResult.BLOCKED_BLAST,
                NetOutcomeText.modeMessage(ModeResult.BLOCKED_BLAST.name)
            )
        }
        Prefs.setPreferredMode(ctx, subscriptionId, mode)
        if (!hasSecureSettingsGrant(ctx)) {
            return ModeOutcome(
                ModeResult.NEEDS_ADB,
                NetOutcomeText.modeMessage(ModeResult.NEEDS_ADB.name, mode.displayName)
            )
        }
        return try {
            val value = NetModeValues.settingValue(mode)
            Settings.Global.putInt(
                ctx.contentResolver,
                KEY_PREFERRED_NETWORK_MODE,
                value
            )
            // Per-slot key (DESIGN.md §5.3): preferred_network_mode1/2.
            Settings.Global.putInt(
                ctx.contentResolver,
                "preferred_network_mode${slotIndex + 1}",
                value
            )
            val readBack = readNetworkModeSetting(ctx, slotIndex)
            if (readBack != value) {
                return ModeOutcome(
                    ModeResult.FAILED,
                    NetOutcomeText.modeMessage(
                        ModeResult.FAILED.name,
                        extra = "Mode write did not stick (read back $readBack, wrote $value)."
                    )
                )
            }
            Thread.sleep(30_000L)
            val live = liveNetworkType(ctx, subscriptionId)
            val expected = NetModeValues.expectedNetworkType(mode)
            val applied = when {
                expected == null -> live != null && live != TelephonyManager.NETWORK_TYPE_UNKNOWN
                else -> live == expected
            }
            if (applied) {
                EventLog.append(
                    ctx, "net", "NET_MODE",
                    "Mode lock applied: ${mode.displayName} (live type $live)."
                )
                ModeOutcome(
                    ModeResult.APPLIED,
                    NetOutcomeText.modeMessage(ModeResult.APPLIED.name, mode.displayName)
                )
            } else {
                EventLog.append(
                    ctx, "net", "NET_MODE",
                    "Mode write ok but not applied: ${mode.displayName} (live type $live)."
                )
                ModeOutcome(
                    ModeResult.WRITE_OK_NOT_APPLIED,
                    NetOutcomeText.modeMessage(
                        ModeResult.WRITE_OK_NOT_APPLIED.name,
                        mode.displayName,
                        "(live type $live)"
                    )
                )
            }
        } catch (e: SecurityException) {
            ModeOutcome(
                ModeResult.FAILED,
                NetOutcomeText.modeMessage(
                    ModeResult.FAILED.name,
                    extra = "System refused the mode write: ${e.message}"
                )
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ModeOutcome(
                ModeResult.FAILED,
                NetOutcomeText.modeMessage(
                    ModeResult.FAILED.name, extra = "Mode change interrupted."
                )
            )
        } catch (e: Exception) {
            ModeOutcome(
                ModeResult.FAILED,
                NetOutcomeText.modeMessage(
                    ModeResult.FAILED.name, extra = "Mode change failed: ${e.message}"
                )
            )
        }
    }

    /** Read-back of the written mode value (global key preferred; slot key second). Null when unreadable. */
    fun readNetworkModeSetting(ctx: Context, slotIndex: Int): Int? {
        return try {
            val global = Settings.Global.getInt(ctx.contentResolver, KEY_PREFERRED_NETWORK_MODE, -1)
            if (global != -1) return global
            val slot = Settings.Global.getInt(
                ctx.contentResolver, "preferred_network_mode${slotIndex + 1}", -1
            )
            if (slot == -1) null else slot
        } catch (_: Exception) {
            null
        }
    }

    /** Live network type for one subscription (TelephonyManager NETWORK_TYPE_*). Null when unreadable. */
    fun liveNetworkType(ctx: Context, subscriptionId: Int): Int? {
        return try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            // dataNetworkType needs API 24+; minSdk is 26, so no fallback branch.
            tm.createForSubscriptionId(subscriptionId).dataNetworkType
        } catch (_: Exception) {
            null
        }
    }

    // ---------- shared ----------

    /**
     * Blast-gate for both writes (MERGED): the single enforcement point
     * is [BlastGate]; a blocked attempt is logged to EventLog as
     * SKIPPED_BLAST_ACTIVE. [Prefs.isBlasting] is never read here
     * directly — BlastGate owns that read.
     */
    internal fun blastBlocked(ctx: Context, action: String): Boolean =
        !BlastGate.forContext(ctx).checkWrite(action) { reason ->
            EventLog.append(ctx, "net", "NET_SKIPPED_BLAST_ACTIVE", reason)
        }

    private fun postToMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
