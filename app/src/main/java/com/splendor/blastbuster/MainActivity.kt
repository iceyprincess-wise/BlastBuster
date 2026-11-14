package com.splendor.blastbuster

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
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
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var armButton: Button
    private lateinit var triggerEdit: EditText
    private lateinit var stopEdit: EditText
    private lateinit var trustedEdit: EditText

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
            Manifest.permission.CAMERA
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                Toast.makeText(this@MainActivity, "Siren on — tap Stop siren to end it", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "Stop siren"
            setOnClickListener { BlastService.stopBlast(this@MainActivity) }
        })

        // Permissions.
        root.addView(sectionTitle("Permissions"))
        root.addView(Button(this).apply {
            text = "Grant permissions (SMS, location, notifications, camera)"
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
            text = "1. Grant the permissions above (SMS, location, notifications, camera).\n" +
                "2. Do Not Disturb access: allow Blast Buster (button above, or " +
                "Settings > Apps > Special app access > Do Not Disturb access).\n" +
                "3. Autostart ON: Security app > Autostart > Blast Buster ON.\n" +
                "4. Battery: Settings > Apps > Blast Buster > Battery saver > " +
                "No restrictions (button above asks the system for this).\n" +
                "5. Lock in recents: open the recents screen, long-press the " +
                "Blast Buster card and lock it, so HyperOS cannot swipe it away.\n" +
                "6. Keep the phone's screen lock ON — a thief who cannot unlock " +
                "the phone cannot force-stop the app.\n" +
                "7. Test it: text the trigger code to this phone from your " +
                "second number, then text the stop code."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Limits, honestly: if the thief removes the SIM or powers the " +
                "phone off, no SMS can reach it and no app can help. The siren " +
                "makes a stolen phone scream the moment you trigger it — " +
                "useless to keep, hard to hide with."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
        })

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
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

    private fun refreshStatus() {
        val dnd = try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) "DND access: granted" else "DND access: NOT granted"
        } catch (_: Exception) {
            "DND access: unknown"
        }
        if (Prefs.isBlasting(this)) {
            statusView.text = "🔴 BLASTING — siren on\n$dnd"
            statusView.setTextColor(Color.parseColor("#D32F2F"))
            armButton.text = "Disarm"
        } else if (Prefs.isArmed(this)) {
            statusView.text = "Armed — listening for your SMS trigger\n$dnd"
            statusView.setTextColor(Color.parseColor("#2E7D32"))
            armButton.text = "Disarm"
        } else {
            statusView.text = "Disarmed — SMS trigger ignored\n$dnd"
            statusView.setTextColor(Color.GRAY)
            armButton.text = "Arm"
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
