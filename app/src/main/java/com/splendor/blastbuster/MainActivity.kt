package com.splendor.blastbuster

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Blast Buster setup screen. Plain android widgets, no AndroidX — mirrors
 * the proven SplendorCam approach.
 *
 * Status (armed / blasting) is read from SharedPreferences and refreshed
 * once a second while this screen is visible.
 *
 * v1.1: while a blast is active this screen redirects straight to the red
 * lockout screen — there is no reachable Stop button in the app while
 * blasting. The owner stops a blast with a fingerprint on the lockout
 * screen, or with the stop SMS.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var armButton: Button
    private lateinit var triggerEdit: EditText
    private lateinit var stopEdit: EditText
    private lateinit var trustedEdit: EditText
    private lateinit var voiceEdit: EditText
    private lateinit var voiceToggleButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val PERM_REQUEST = 1
        private val PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No in-app controls while blasting: straight to the lockout.
        if (Prefs.isBlasting(this)) {
            startLockout()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        // Header: siren mark + name.
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_siren)
            setColorFilter(Color.parseColor("#D32F2F"))
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(icon)
        root.addView(TextView(this).apply {
            text = "Blast Buster"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        })
        root.addView(TextView(this).apply {
            text = "Theft alarm — triggered by SMS, works offline (no internet needed)."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(16))
        })

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(statusView)

        // Codes.
        root.addView(sectionTitle("Trigger code (SMS text that starts the siren)"))
        triggerEdit = editText(Prefs.triggerCode(this))
        root.addView(triggerEdit)
        root.addView(sectionTitle("Stop code (SMS text that stops the siren)"))
        stopEdit = editText(Prefs.stopCode(this))
        root.addView(stopEdit)
        root.addView(sectionTitle("Trusted numbers (comma-separated, optional)"))
        trustedEdit = EditText(this).apply {
            setText(Prefs.trustedNumbersRaw(this@MainActivity))
            hint = "e.g. 09042535961 — empty = any sender with the code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(trustedEdit)
        root.addView(TextView(this).apply {
            text = "If trusted numbers are set, only those numbers can trigger or stop " +
                "the siren. If empty, anyone who knows the code can — keep the code secret."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        })

        // Voice trigger (v1.1).
        root.addView(sectionTitle("Voice trigger phrase (optional, OFF by default)"))
        voiceEdit = editText(Prefs.voicePhrase(this))
        root.addView(voiceEdit)
        root.addView(TextView(this).apply {
            text = "Saying this phrase near the phone starts the siren. It matches " +
                "the WORDS, not your voice — anyone saying the phrase can trigger " +
                "it. Listening all the time costs battery."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(8))
        })
        voiceToggleButton = Button(this).apply {
            setOnClickListener { toggleVoiceTrigger() }
        }
        root.addView(voiceToggleButton)
        refreshVoiceToggle()

        root.addView(Button(this).apply {
            text = "Save settings"
            setOnClickListener { saveSettings() }
        })

        armButton = Button(this).apply { setOnClickListener { toggleArm() } }
        root.addView(armButton)

        root.addView(Button(this).apply {
            text = "Test siren (plays now)"
            setOnClickListener {
                BlastService.startBlast(this@MainActivity, null)
                Toast.makeText(this@MainActivity, "Siren on — stop it with your fingerprint", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Stop siren"
            setOnClickListener { BlastService.stopBlast(this@MainActivity) }
        })

        // Permissions.
        root.addView(sectionTitle("Permissions"))
        root.addView(Button(this).apply {
            text = "Grant permissions (SMS, location, notifications, camera, microphone, phone state)"
            setOnClickListener {
                requestPermissions(PERMISSIONS, PERM_REQUEST)
            }
        })
        root.addView(Button(this).apply {
            text = "Allow Do Not Disturb access (lets the siren force DND off)"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Open Settings > Apps > Special app access > Do Not Disturb access", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Allow display over other apps (blocks the notification shade during a blast)"
            setOnClickListener {
                try {
                    val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Open Settings > Apps > Special app access > Display over other apps", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Enable device admin (locks the screen when the siren starts)"
            setOnClickListener {
                try {
                    val admin = ComponentName(this@MainActivity, BlastAdminReceiver::class.java)
                    val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    i.putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Blast Buster locks the screen when the theft siren starts, so the red lockout screen appears over the lock screen."
                    )
                    startActivity(i)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Open Settings > Security > Device admin apps", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Battery: ask for No restrictions"
            setOnClickListener {
                try {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (e: Exception) {
                    try {
                        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        i.data = Uri.parse("package:$packageName")
                        startActivity(i)
                    } catch (_: Exception) {
                    }
                }
            }
        })

        // Setup checklist.
        root.addView(sectionTitle("Setup checklist — do all of these"))
        root.addView(TextView(this).apply {
            text = "1. Grant the permissions above (SMS, location, notifications, " +
                "camera, microphone, phone state).\n" +
                "2. Do Not Disturb access: allow Blast Buster (button above, or " +
                "Settings > Apps > Special app access > Do Not Disturb access). " +
                "Fixed in v1.1 — the app now appears in that list.\n" +
                "3. Display over other apps: allow it (button above) — this is " +
                "what blocks the notification shade during a blast.\n" +
                "4. Device admin: enable it (button above) — this is what locks " +
                "the screen when the siren starts.\n" +
                "5. Autostart ON: Security app > Autostart > Blast Buster ON.\n" +
                "6. Battery: Settings > Apps > Blast Buster > Battery saver > " +
                "No restrictions (button above asks the system for this).\n" +
                "7. Lock in recents: open the recents screen, long-press the " +
                "Blast Buster card and lock it, so HyperOS cannot swipe it away.\n" +
                "8. Keep the phone's screen lock ON — a thief who cannot unlock " +
                "the phone cannot force-stop the app.\n" +
                "9. Voice trigger is OFF by default. Turn it on only if you want " +
                "it: it listens all the time (battery) and matches the phrase, " +
                "not your voice.\n" +
                "10. Test it: text the trigger code to this phone from your " +
                "second number, then text the stop code. Test the lockout too: " +
                "start a test blast, and stop it with your fingerprint on the " +
                "sensor (the side power button) — no scanner shows on screen."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Limits, honestly: the power button cannot be blocked by any app " +
                "— pressing it turns the screen off, but the red lockout comes " +
                "straight back when the screen turns on. If the phone is unlocked, " +
                "force-stop in Settings cannot be blocked either — the screen lock " +
                "plus this lockout is what protects that path. The fingerprint " +
                "sensor on this phone is the side power button, not under the " +
                "screen — place your thumb there. If the SIM is pulled while " +
                "armed, the phone now blasts by itself (no SMS needed). The siren " +
                "is as loud as the phone's single speaker allows — v1.1 maximises " +
                "perceived loudness within that cap. The voice trigger matches " +
                "words, not your voice."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
        })

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isBlasting(this)) {
            startLockout()
            finish()
            return
        }
        refreshStatus()
        handler.post(statusTick)
        // Start the guard whenever the app is opened and armed.
        if (Prefs.isArmed(this) && !Prefs.isBlasting(this)) {
            try {
                BlastService.startGuard(this)
            } catch (_: Exception) {
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusTick)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            val denied = permissions.filterIndexed { i, _ ->
                i >= grantResults.size || grantResults[i] != PackageManager.PERMISSION_GRANTED
            }
            if (denied.isEmpty()) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Missing: ${denied.joinToString(", ")} — the siren needs SMS most of all.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startLockout() {
        try {
            val i = Intent(this, LockoutActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun refreshStatus() {
        val dnd = try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) "DND: granted" else "DND: NOT granted"
        } catch (_: Exception) {
            "DND: unknown"
        }
        val admin = try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val c = ComponentName(this, BlastAdminReceiver::class.java)
            if (dpm.isAdminActive(c)) "Admin: granted" else "Admin: NOT granted"
        } catch (_: Exception) {
            "Admin: unknown"
        }
        val overlay = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                "Overlay: granted"
            } else {
                "Overlay: NOT granted"
            }
        } catch (_: Exception) {
            "Overlay: unknown"
        }
        val extra = "$dnd · $admin · $overlay"
        if (Prefs.isBlasting(this)) {
            statusView.text = "🔴 BLASTING — siren on\n$extra"
            statusView.setTextColor(Color.parseColor("#D32F2F"))
            armButton.text = "Disarm"
        } else if (Prefs.isArmed(this)) {
            statusView.text = "Armed — listening for your SMS trigger\n$extra"
            statusView.setTextColor(Color.parseColor("#2E7D32"))
            armButton.text = "Disarm"
        } else {
            statusView.text = "Disarmed — SMS trigger ignored\n$extra"
            statusView.setTextColor(Color.GRAY)
            armButton.text = "Arm"
        }
    }

    private fun refreshVoiceToggle() {
        voiceToggleButton.text = if (Prefs.voiceTriggerEnabled(this)) {
            "Voice trigger: ON — tap to turn off"
        } else {
            "Voice trigger: OFF — tap to turn on"
        }
    }

    private fun toggleVoiceTrigger() {
        val nowOn = !Prefs.voiceTriggerEnabled(this)
        Prefs.setVoiceTriggerEnabled(this, nowOn)
        refreshVoiceToggle()
        if (nowOn) {
            // Starting the guard (re)starts the voice listener.
            try {
                BlastService.startGuard(this)
            } catch (_: Exception) {
            }
            Toast.makeText(this, "Voice trigger on — saying the phrase starts the siren", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Voice trigger off", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSettings() {
        val trigger = triggerEdit.text.toString().trim()
        val stop = stopEdit.text.toString().trim()
        if (trigger.isEmpty() || stop.isEmpty()) {
            Toast.makeText(this, "Trigger and stop codes cannot be empty.", Toast.LENGTH_SHORT).show()
            return
        }
        if (trigger.equals(stop, ignoreCase = true)) {
            Toast.makeText(this, "Trigger and stop codes must be different.", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setTriggerCode(this, trigger)
        Prefs.setStopCode(this, stop)
        Prefs.setTrustedNumbersRaw(this, trustedEdit.text.toString())
        Prefs.setVoicePhrase(this, voiceEdit.text.toString())
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun toggleArm() {
        val nowArmed = !Prefs.isArmed(this)
        Prefs.setArmed(this, nowArmed)
        if (nowArmed) {
            try {
                BlastService.startGuard(this)
            } catch (_: Exception) {
            }
            Toast.makeText(this, "Armed", Toast.LENGTH_SHORT).show()
        } else {
            BlastService.stopBlast(this)
            try {
                stopService(Intent(this, BlastService::class.java))
            } catch (_: Exception) {
            }
            Toast.makeText(this, "Disarmed — SMS trigger ignored", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun editText(value: String): EditText = EditText(this).apply {
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
