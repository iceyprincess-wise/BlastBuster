// Deliberately uses deprecated APIs: FingerprintManager is the only
// no-UI fingerprint path (the whole point of this screen), and its
// callback overrides are deprecated members. Suppressed at file level.
@file:Suppress("DEPRECATION")

package com.splendor.blastbuster

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Red lockout screen shown OVER the lock screen while the siren blasts.
 *
 * - Full-screen red background, big 🚨, no password field anywhere.
 * - Volume up/down and Back are swallowed (onKeyDown/onKeyUp return true).
 * - Unlock is fingerprint ONLY: the owner touches the fingerprint sensor
 *   and the blast stops. No scanner UI is shown: the primary path is
 *   FingerprintManager.authenticate() with no system UI. That API is
 *   deprecated and best-effort on Android 16 — whether it works on the
 *   Redmi 15C is UNVERIFIED until the owner tests it. If it is unavailable
 *   or errors, BiometricPrompt is the fallback, and ITS system prompt WILL
 *   show. The stop SMS code also dismisses this screen, so the owner can
 *   never be locked out of his own phone.
 * - The fingerprint sensor on the Redmi 15C is the SIDE power button, not
 *   under the screen: the thumb goes on the power button.
 * - The power button CANNOT be blocked by any app: pressing it turns the
 *   screen off. The service re-shows this screen the moment the screen
 *   turns back on, and the siren keeps blasting through it.
 * - A top-edge overlay (TYPE_APPLICATION_OVERLAY) consumes the pull-down
 *   gesture, on top of immersive sticky mode, so the notification shade
 *   cannot be pulled down to stop the blast. Needs the "Display over
 *   other apps" permission, granted as a setup step.
 */
class LockoutActivity : Activity() {

    companion object {
        private const val TAG = "LockoutActivity"
    }

    private var cancellationSignal: CancellationSignal? = null
    private var fingerprintActive = false
    private var fallbackUsed = false
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val blastWatch = object : Runnable {
        override fun run() {
            // The stop SMS dismisses this screen: if the blast is over, go.
            if (!Prefs.isBlasting(this@LockoutActivity)) {
                finish()
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "\uD83D\uDEA8"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "PHONE STOLEN"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "This phone is blasting its alarm."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Owner: touch the fingerprint sensor\n(power button) to unlock."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.parseColor("#FFCDD2"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, 0)
        })
        setContentView(root)

        applyImmersive()
        addTopOverlay()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        handler.post(blastWatch)
        if (!fingerprintActive) startFingerprintAuth()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(blastWatch)
        cancelFingerprint()
    }

    override fun onDestroy() {
        cancelFingerprint()
        removeTopOverlay()
        handler.removeCallbacks(blastWatch)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // ---------- keys: swallow volume + back ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Swallowed: no way out of the lockout except fingerprint or stop SMS.
    }

    // ---------- immersive sticky ----------

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    // ---------- top-edge overlay: eats the pull-down gesture ----------

    private fun addTopOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = View(this)
            view.setOnTouchListener { _, _ -> true }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(36),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP
            wm.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            Log.w(TAG, "top overlay failed: ${e.message}")
        }
    }

    private fun removeTopOverlay() {
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
    }

    // ---------- fingerprint: no-UI primary, system prompt fallback ----------

    @Suppress("DEPRECATION")
    private fun startFingerprintAuth() {
        val fm = try {
            getSystemService(FingerprintManager::class.java)
        } catch (_: Exception) {
            null
        }
        val usable = try {
            fm != null && fm.isHardwareDetected && fm.hasEnrolledFingerprints()
        } catch (_: Exception) {
            false
        }
        if (!usable) {
            startBiometricPrompt()
            return
        }
        val cs = CancellationSignal()
        cancellationSignal = cs
        fingerprintActive = true
        try {
            fm!!.authenticate(null, cs, 0,
                object : FingerprintManager.AuthenticationCallback() {
                    @Deprecated("Overrides a deprecated FingerprintManager callback — the no-UI fingerprint path requires it.")
                    override fun onAuthenticationSucceeded(
                        result: FingerprintManager.AuthenticationResult
                    ) {
                        fingerprintActive = false
                        unlock()
                    }

                    @Deprecated("Overrides a deprecated FingerprintManager callback — the no-UI fingerprint path requires it.")
                    override fun onAuthenticationFailed() {
                        // Wrong finger: keep listening, no UI shown.
                    }

                    @Deprecated("Overrides a deprecated FingerprintManager callback — the no-UI fingerprint path requires it.")
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        fingerprintActive = false
                        Log.w(TAG, "fingerprint error $errorCode: $errString")
                        startBiometricPrompt()
                    }
                }, null)
        } catch (e: Exception) {
            fingerprintActive = false
            Log.w(TAG, "fingerprint auth failed: ${e.message}")
            startBiometricPrompt()
        }
    }

    private fun cancelFingerprint() {
        fingerprintActive = false
        try {
            cancellationSignal?.cancel()
        } catch (_: Exception) {
        }
        cancellationSignal = null
    }

    private fun startBiometricPrompt() {
        // Framework BiometricPrompt needs API 28+. Its system prompt WILL
        // show — that is the honest fallback when the no-UI path is not
        // available. Guarded so it runs at most once per lockout session:
        // after it, the no-UI path is not retried, and the stop SMS remains
        // the guaranteed way out.
        if (fallbackUsed) return
        fallbackUsed = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Blast Buster — owner fingerprint")
                .setSubtitle("Touch the fingerprint sensor to stop the siren")
                .setNegativeButton(
                    "Cancel",
                    mainExecutor,
                    { _, _ -> /* stays locked; stop SMS still works */ }
                )
                .build()
            prompt.authenticate(
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        unlock()
                    }

                    override fun onAuthenticationFailed() {}
                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "biometric prompt failed: ${e.message}")
        }
    }

    private fun unlock() {
        try {
            BlastService.stopBlast(this)
        } catch (_: Exception) {
        }
        finish()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
