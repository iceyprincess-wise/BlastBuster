package com.splendor.blastbuster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.util.Log

/**
 * Foreground service with two modes:
 * - GUARD: quiet, persistent notification, keeps the app alive on HyperOS
 *   so the SMS receiver stays reliable. Started when the app is armed and
 *   on boot.
 * - BLAST: max-volume looping siren on STREAM_ALARM + flashlight strobe +
 *   vibration + screen wake, until stopped by SMS stop code or the Stop
 *   action/button.
 *
 * The guard-always-running design is what makes the SMS trigger reliable:
 * while the guard service is in the foreground the app is in a foreground
 * state, so the receiver can command the already-running service without
 * hitting Android 12+ background-start restrictions. If the guard has
 * somehow died, the receiver falls back to startForegroundService with a
 * try/catch (see SmsReceiver).
 */
class BlastService : Service() {

    companion object {
        const val ACTION_START_GUARD = "com.splendor.blastbuster.START_GUARD"
        const val ACTION_START_BLAST = "com.splendor.blastbuster.START_BLAST"
        const val ACTION_STOP_BLAST = "com.splendor.blastbuster.STOP_BLAST"
        const val EXTRA_SENDER = "sender"
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "blast_buster_guard"
        private const val TAG = "BlastService"

        fun startGuard(ctx: Context) {
            val i = Intent(ctx, BlastService::class.java).setAction(ACTION_START_GUARD)
            ctx.startForegroundService(i)
        }

        fun startBlast(ctx: Context, sender: String?) {
            val i = Intent(ctx, BlastService::class.java).setAction(ACTION_START_BLAST)
            i.putExtra(EXTRA_SENDER, sender)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException on Android 12+ if
                // the guard is dead and the app is fully in the background.
                Log.w(TAG, "startForegroundService failed: ${e.message}")
            }
        }

        fun stopBlast(ctx: Context) {
            val i = Intent(ctx, BlastService::class.java).setAction(ACTION_STOP_BLAST)
            try {
                ctx.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "stopBlast failed: ${e.message}")
            }
        }
    }

    @Volatile
    private var blasting = false
    private var audioTrack: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchThread: Thread? = null
    @Volatile
    private var torchStop = false
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GUARD -> {
                startForeground(NOTIF_ID, buildNotification(false))
            }
            ACTION_START_BLAST -> {
                startBlasting(intent.getStringExtra(EXTRA_SENDER))
            }
            ACTION_STOP_BLAST -> {
                stopBlasting()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBlastingInternal()
        super.onDestroy()
    }

    // ---------- blast ----------

    private fun startBlasting(sender: String?) {
        if (blasting) return
        blasting = true
        Prefs.setBlasting(this, true)

        // Foreground FIRST: startForegroundService gives the service only a
        // few seconds to call startForeground, so do it before any setup.
        startForeground(NOTIF_ID, buildNotification(true))

        // 1. Alarm stream to max — STREAM_ALARM bypasses silent mode and
        //    (except total-silence DND) Do Not Disturb by design.
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        } catch (e: Exception) {
            Log.w(TAG, "setStreamVolume failed: ${e.message}")
        }

        // 2. If the user granted Do Not Disturb access, force DND off so
        //    even total-silence mode cannot mute the alarm stream.
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (e: Exception) {
            Log.w(TAG, "DND off failed: ${e.message}")
        }

        // 3. Wake the screen and keep the CPU awake while blasting.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "BlastBuster:blast"
            )
            wl.acquire()
            wakeLock = wl
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed: ${e.message}")
        }

        // 4. Looping siren on the alarm stream.
        try {
            val pcm = Siren.generateCycle()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Siren.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setLoopPoints(0, pcm.size, -1)
            track.setVolume(1.0f)
            track.play()
            audioTrack = track
        } catch (e: Exception) {
            Log.e(TAG, "siren failed: ${e.message}")
        }

        // 5. Vibration, repeating.
        try {
            val vib = getSystemService(Vibrator::class.java)
            vibrator = vib
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200), 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 400, 200), 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed: ${e.message}")
        }

        // 6. Flashlight strobe on its own thread.
        torchStop = false
        torchThread = Thread({
            var torchId: String? = null
            try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                for (id in cm.cameraIdList) {
                    val chars = cm.getCameraCharacteristics(id)
                    if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                        torchId = id
                        break
                    }
                }
                while (!torchStop) {
                    try {
                        if (torchId != null) cm.setTorchMode(torchId, true)
                    } catch (_: Exception) {
                    }
                    Thread.sleep(250)
                    try {
                        if (torchId != null) cm.setTorchMode(torchId, false)
                    } catch (_: Exception) {
                    }
                    Thread.sleep(250)
                }
                if (torchId != null) {
                    try {
                        cm.setTorchMode(torchId, false)
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "torch failed: ${e.message}")
            }
        }, "BlastBuster-torch").also { it.start() }

        // 7. One SMS back to the sender with the last-known location.
        if (sender != null) sendLocationReply(sender)

        Log.i(TAG, "blasting (triggered by $sender)")
    }

    private fun sendLocationReply(sender: String) {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var loc = try {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (e: SecurityException) {
                null
            }
            if (loc == null) {
                try {
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (_: SecurityException) {
                    // keep loc null
                }
            }
            val text = if (loc != null) {
                "BLAST BUSTER: your phone is blasting its siren. Last location: " +
                    "${loc.latitude}, ${loc.longitude} " +
                    "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
            } else {
                "BLAST BUSTER: your phone is blasting its siren. No GPS fix available yet."
            }
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            sms.sendTextMessage(sender, null, text, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "location reply failed: ${e.message}")
        }
    }

    private fun stopBlasting() {
        if (!blasting) {
            // Not blasting: just make sure we are a quiet guard (or stop).
            if (Prefs.isArmed(this)) {
                startForeground(NOTIF_ID, buildNotification(false))
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        stopBlastingInternal()
        if (Prefs.isArmed(this)) {
            startForeground(NOTIF_ID, buildNotification(false))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        Log.i(TAG, "blast stopped")
    }

    private fun stopBlastingInternal() {
        blasting = false
        Prefs.setBlasting(this, false)
        torchStop = true
        try {
            torchThread?.interrupt()
        } catch (_: Exception) {
        }
        torchThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ---------- notification ----------

    private fun buildNotification(blastingNow: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Blast Buster guard",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openIntent)
            .setOngoing(true)
        if (blastingNow) {
            val stopIntent = PendingIntent.getService(
                this, 1,
                Intent(this, BlastService::class.java).setAction(ACTION_STOP_BLAST),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentTitle("BLAST BUSTER — PHONE ALARM")
                .setContentText("Siren blasting. Tap Stop or text the stop code.")
                .addAction(
                    Notification.Action.Builder(null, "Stop", stopIntent).build()
                )
        } else {
            builder.setContentTitle("Blast Buster armed")
                .setContentText("Listening for your SMS trigger code.")
        }
        return builder.build()
    }
}
