// Deliberately uses deprecated APIs: PhoneStateListener is the legacy
// SIM-state path below API 31, TelephonyCallback's single-arg
// onServiceStateChanged and TelephonyManager.phoneCount are deprecated
// members. Suppressed at file level.
@file:Suppress("DEPRECATION")

package com.splendor.blastbuster

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Watches the SIM while the guard runs. Two paths, both funnelling into
 * SimPolicy.decide():
 *
 * - a service-state listener (TelephonyCallback on API 31+, the older
 *   PhoneStateListener below it), which fires when the SIM is pulled;
 * - a 10-second poll of the SIM slots, in case a callback is missed.
 *
 * Needs READ_PHONE_STATE (runtime permission, asked for in MainActivity).
 * The watch only arms when a SIM was present at start: a phone armed with
 * no SIM in it must not blast by itself.
 *
 * Honest limit: this runs inside the guard service, so SIM-removal
 * detection needs the guard alive — the same requirement as the SMS
 * trigger. The trigger SMS itself can never arrive after the SIM is out;
 * the auto-blast is the defence.
 */
class SimWatcher(
    private val context: Context,
    private val onSimAbsent: () -> Unit
) {
    companion object {
        private const val TAG = "SimWatcher"
        private const val POLL_MS = 10_000L
    }

    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var hadSim = false
    private var started = false

    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            checkSim()
            handler.postDelayed(this, POLL_MS)
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        if (started) return
        started = true
        val tm = try {
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        } catch (e: Exception) {
            Log.w(TAG, "no TelephonyManager: ${e.message}")
            return
        }
        telephonyManager = tm
        hadSim = simPresentNow()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(),
                    TelephonyCallback.ServiceStateListener {
                    @Deprecated("Overrides a deprecated TelephonyCallback callback.")
                    override fun onServiceStateChanged(serviceState: ServiceState) {
                        checkSim()
                    }
                }
                telephonyCallback = cb
                tm.registerTelephonyCallback(context.mainExecutor, cb)
            } else {
                val listener = object : PhoneStateListener() {
                    @Deprecated("Overrides a deprecated PhoneStateListener callback — the legacy SIM-state path below API 31.")
                    override fun onServiceStateChanged(serviceState: ServiceState) {
                        checkSim()
                    }
                }
                phoneStateListener = listener
                tm.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SIM listener failed (poll still runs): ${e.message}")
        }
        handler.post(pollRunnable)
        Log.i(TAG, "watching SIM (hadSim=$hadSim)")
    }

    @Suppress("DEPRECATION")
    fun stop() {
        started = false
        handler.removeCallbacks(pollRunnable)
        try {
            val tm = telephonyManager
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyCallback?.let { tm.unregisterTelephonyCallback(it) }
                } else {
                    phoneStateListener?.let {
                        tm.listen(it, PhoneStateListener.LISTEN_NONE)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SIM listener unregister failed: ${e.message}")
        }
        telephonyCallback = null
        phoneStateListener = null
        telephonyManager = null
    }

    /** True when at least one SIM slot reports READY. */
    private fun simPresentNow(): Boolean {
        val tm = telephonyManager ?: return false
        return try {
            if (tm.simState == TelephonyManager.SIM_STATE_READY) return true
            val slots = try {
                tm.phoneCount
            } catch (_: Exception) {
                0
            }
            for (slot in 0 until slots) {
                try {
                    if (tm.getSimState(slot) == TelephonyManager.SIM_STATE_READY) {
                        return true
                    }
                } catch (_: Exception) {
                    // A slot that cannot be read does not count as present.
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "simPresentNow failed: ${e.message}")
            false
        }
    }

    private fun checkSim() {
        val present = simPresentNow()
        if (!present && hadSim) {
            Log.i(TAG, "SIM gone (hadSim=true) — firing onSimAbsent")
            onSimAbsent()
        }
    }
}
