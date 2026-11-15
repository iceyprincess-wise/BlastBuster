package com.splendor.blastbuster

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.SmsManager
import android.util.Log
import java.util.Locale

/**
 * Foreground service with two modes:
 * - GUARD: quiet, persistent notification, keeps the app alive on HyperOS
 *   so the SMS receiver stays reliable. Started when the app is armed and
 *   on boot. While armed it also runs the SIM watcher and (if enabled) the
 *   voice trigger listener.
 * - BLAST: max-volume looping siren on STREAM_ALARM + flashlight strobe +
 *   vibration + screen wake + TextToSpeech shouting, under a red lockout
 *   screen, until stopped by fingerprint on the lockout screen or the SMS
 *   stop code. There is NO Stop action in the notification any more, and
 *   no in-app Stop button is reachable while blasting.
 *
 * v1.1 changes:
 * - Audio focus (AUDIOFOCUS_GAIN, transient loss ignored) + a 5-second
 *   timer re-asserting STREAM_ALARM at max, so HyperOS cannot duck it.
 * - DevicePolicyManager.lockNow() on trigger (needs device admin, a setup
 *   step) + the red LockoutActivity over the lock screen; the screen-on
 *   receiver re-shows the lockout when the screen turns back on, because
 *   the power button cannot be blocked by any app.
 * - SIM watcher: SIM pulled while armed fires the blast by itself; SIM
 *   pulled during a blast escalates it (faster strobe + shouting on).
 * - TextToSpeech shouting ("Thief! Thief! This phone is stolen!") on a
 *   loop on the alarm stream — a second sound source, and the escalation
 *   layer. Needs the device's TTS voice data installed.
 * - Voice trigger listener (phrase match only, not voice verification).
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
    @Volatile
    private var escalated = false
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchThread: Thread? = null
    @Volatile
    private var torchStop = false
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var shouting = false
    private var simWatcher: SimWatcher? = null
    private var voiceTrigger: VoiceTrigger? = null
    private var screenOnReceiver: BroadcastReceiver? = null

    private val handler = Handler(Looper.getMainLooper())
    private val volumeRunnable = object : Runnable {
        override fun run() {
            if (!blasting) return
            reassertVolume()
            // Keep the shouting loop alive even if the TTS onDone callback
            // is missed: re-speak when the engine has gone quiet.
            if (ttsReady && tts?.isSpeaking != true) speakShout()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initTts()
        if (Prefs.isArmed(this)) {
            startSimWatcher()
            startVoiceListener()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GUARD -> {
                startForeground(NOTIF_ID, buildNotification(false))
                startSimWatcher()
                startVoiceListener()
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
        stopSimWatcher()
        stopVoiceListener()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        super.onDestroy()
    }

    // ---------- blast ----------

    private fun startBlasting(sender: String?) {
        if (blasting) return
        blasting = true
        escalated = false
        Prefs.setBlasting(this, true)

        // Foreground FIRST: startForegroundService gives the service only a
        // few seconds to call startForeground, so do it before any setup.
        startForeground(NOTIF_ID, buildNotification(true))

        // 1. Alarm stream to max — STREAM_ALARM bypasses silent mode and
        //    (except total-silence DND) Do Not Disturb by design. Re-assert
        //    on a timer: HyperOS may duck it, and the timer puts it back.
        reassertVolume()
        handler.postDelayed(volumeRunnable, 5000)

        // 1b. Audio focus with gain; transient focus loss is deliberately
        //     ignored while blasting (the listener is a no-op).
        requestAudioFocus()

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

        // 3b. Lock the device to the system lock screen (needs device admin,
        //     a setup step). The red lockout activity shows over it.
        lockDeviceNow()

        // 3c. Red lockout screen over the lock screen. The power button
        //     cannot be blocked: pressing it turns the screen off, and the
        //     screen-on receiver below re-shows the lockout at once.
        showLockout()
        registerScreenOnReceiver()

        // 4. Looping siren on the alarm stream (v1.1: harmonic-rich wave).
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

        // 4b. Shouting layer: a second sound source on the alarm stream.
        ensureShouting()

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

        // 6. Flashlight strobe on its own thread. Escalated blasts strobe
        //    faster (100 ms instead of 250 ms).
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
                    val interval = if (escalated) 100L else 250L
                    try {
                        if (torchId != null) cm.setTorchMode(torchId, true)
                    } catch (_: Exception) {
                    }
                    Thread.sleep(interval)
                    try {
                        if (torchId != null) cm.setTorchMode(torchId, false)
                    } catch (_: Exception) {
                    }
                    Thread.sleep(interval)
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

        // 8. The voice listener pauses during a blast (nothing to trigger).
        stopVoiceListener()

        Log.i(TAG, "blasting (triggered by $sender)")
    }

    /** SIM pulled during an active blast: faster strobe + shouting on. */
    private fun escalate() {
        if (!blasting || escalated) return
        escalated = true
        reassertVolume()
        ensureShouting()
        Log.i(TAG, "blast escalated (SIM removed during blast)")
    }

    private fun handleSimAbsent() {
        if (!Prefs.isArmed(this)) return
        if (blasting) {
            escalate()
        } else {
            Log.i(TAG, "SIM removed while armed — firing blast")
            startBlast(this, null)
        }
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
            startVoiceListener()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        Log.i(TAG, "blast stopped")
    }

    private fun stopBlastingInternal() {
        blasting = false
        escalated = false
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
        abandonAudioFocus()
        handler.removeCallbacks(volumeRunnable)
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        shouting = false
        unregisterScreenOnReceiver()
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

    // ---------- volume / focus ----------

    private fun reassertVolume() {
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
    }

    private fun requestAudioFocus() {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setOnAudioFocusChangeListener {
                    // Deliberately ignored while blasting: transient focus
                    // loss must not duck or stop the siren.
                }
                .build()
            audioFocusRequest = req
            audio.requestAudioFocus(req)
        } catch (e: Exception) {
            Log.w(TAG, "audio focus failed: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioFocusRequest?.let { audio.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
        audioFocusRequest = null
    }

    // ---------- device lock + lockout screen ----------

    private fun lockDeviceNow() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, BlastAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
            } else {
                Log.i(TAG, "device admin not active — skipping lockNow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "lockNow failed: ${e.message}")
        }
    }

    private fun showLockout() {
        try {
            val i = Intent(this, LockoutActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "showLockout failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // The power button turns the screen off; nothing can stop
                // that. The moment the screen comes back on, the lockout
                // comes straight back with it.
                if (intent.action == Intent.ACTION_SCREEN_ON && blasting) {
                    showLockout()
                }
            }
        }
        screenOnReceiver = receiver
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "screen-on receiver failed: ${e.message}")
            screenOnReceiver = null
        }
    }

    private fun unregisterScreenOnReceiver() {
        try {
            screenOnReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        screenOnReceiver = null
    }

    // ---------- TextToSpeech shouting ----------

    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        val t = tts ?: return@TextToSpeech
                        t.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        val lang = t.setLanguage(Locale.US)
                        ttsReady = lang != TextToSpeech.LANG_MISSING_DATA &&
                            lang != TextToSpeech.LANG_NOT_SUPPORTED
                        t.setOnUtteranceProgressListener(
                            // Single-arg onError is deprecated (two-arg
                            // version replaced it) but is still abstract,
                            // so it must be overridden.
                            @Suppress("DEPRECATION")
                            object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {}
                                override fun onDone(utteranceId: String?) {
                                    if (blasting && utteranceId == "shout") {
                                        handler.post { speakShout() }
                                    }
                                }

                                @Deprecated("Overrides a deprecated UtteranceProgressListener callback — the single-arg onError is abstract and must be overridden.")
                                override fun onError(utteranceId: String?) {}
                            }
                        )
                        Log.i(TAG, "TTS ready=$ttsReady")
                    } catch (e: Exception) {
                        ttsReady = false
                        Log.w(TAG, "TTS setup failed: ${e.message}")
                    }
                } else {
                    ttsReady = false
                    Log.w(TAG, "TTS init failed: $status")
                }
            }
        } catch (e: Exception) {
            ttsReady = false
            Log.w(TAG, "TTS init failed: ${e.message}")
        }
    }

    private fun ensureShouting() {
        if (blasting && ttsReady && !shouting) speakShout()
    }

    private fun speakShout() {
        if (!blasting || !ttsReady) return
        try {
            tts?.speak(
                "Thief! Thief! This phone is stolen!",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "shout"
            )
            shouting = true
        } catch (e: Exception) {
            Log.w(TAG, "speak failed: ${e.message}")
        }
    }

    // ---------- SIM watcher + voice trigger ----------

    private fun startSimWatcher() {
        if (simWatcher != null) return
        try {
            simWatcher = SimWatcher(this) { handleSimAbsent() }.also { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "SIM watcher start failed: ${e.message}")
            simWatcher = null
        }
    }

    private fun stopSimWatcher() {
        try {
            simWatcher?.stop()
        } catch (_: Exception) {
        }
        simWatcher = null
    }

    private fun startVoiceListener() {
        if (!Prefs.voiceTriggerEnabled(this)) return
        if (voiceTrigger != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "voice trigger: RECORD_AUDIO not granted")
            return
        }
        try {
            voiceTrigger = VoiceTrigger(this) {
                if (Prefs.isArmed(this) && !blasting) startBlast(this, null)
            }.also { it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "voice trigger start failed: ${e.message}")
            voiceTrigger = null
        }
    }

    private fun stopVoiceListener() {
        try {
            voiceTrigger?.stop()
        } catch (_: Exception) {
        }
        voiceTrigger = null
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
            .setAutoCancel(false)
            .setOngoing(true)
        if (blastingNow) {
            // Deliberately NO Stop action and NO way to dismiss this
            // notification: a thief pulling the shade down must not be able
            // to stop the blast. The owner stops it by fingerprint on the
            // red lockout screen, or by the stop SMS.
            builder.setContentTitle("BLAST BUSTER — PHONE STOLEN")
                .setContentText(
                    "Siren blasting. Owner: fingerprint, or text the stop code."
                )
        } else {
            builder.setContentTitle("Blast Buster armed")
                .setContentText("Listening for your SMS trigger code.")
        }
        return builder.build()
    }
}
