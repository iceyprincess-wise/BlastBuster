package com.splendor.blastbuster.notif

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.splendor.blastbuster.companion.EventLog

/**
 * Notification capture (v1.3 notification module) — a second
 * `NotificationListenerService` alongside `dnd.DndListener`.
 *
 * Two listener services are allowed: the system binds each one
 * independently, and the user enables each separately in
 * Settings > Notification access. This listener does NOT touch DND —
 * it only reads posted notifications. Capturing is a READ, so it
 * continues during a blast; replying is a WRITE and is gated by
 * [NotifReply] / BlastGate, never done here.
 *
 * Enable/disable: [setEnabled] flips both the persisted toggle
 * (SharedPreferences key [PREF_KEY_ENABLED], default false — the
 * integration agent adds `Prefs.notifEnabled()` on this exact key) and
 * the component state, so a disabled listener is never bound by the
 * system and nothing is captured. Notification access itself is still
 * granted by the user in Settings; the activity deep-links there.
 *
 * Only notifications posted while the feature is on are captured —
 * there is no backfill of notifications posted before enabling.
 * Blast Buster's own notifications are skipped (they are the guard's
 * own noise, not the user's messages).
 */
class NotifListener : NotificationListenerService() {

    companion object {
        /**
         * Prefs key for the hub toggle. Default false (feature OFF until
         * the user turns it on). Lives in the shared "blast_buster"
         * preferences file, like every other module key. The integration
         * agent adds `Prefs.notifEnabled()` / `Prefs.setNotifEnabled()`
         * on this key; until then this class reads/writes the key
         * directly so it compiles standalone.
         */
        const val PREF_KEY_ENABLED = "notif_enabled"
        const val PREFS_NAME = "blast_buster"

        /** Max re-bind attempts after a disconnect, then stop trying. */
        const val MAX_REBIND_ATTEMPTS = 3
        const val REBIND_DELAY_MS = 30L * 1000L

        @Volatile
        private var instance: NotifListener? = null

        /**
         * Hub entry point (wired by the integration agent): turns
         * capture on/off. Persists the toggle AND flips the component
         * state, so a disabled listener is never bound and captures
         * nothing. Does not touch notification-access grant — the user
         * grants that in Settings (see [NotifActivity]).
         */
        fun setEnabled(ctx: Context, enabled: Boolean) {
            com.splendor.blastbuster.Prefs.setNotifEnabled(ctx, enabled)
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                ctx.packageManager.setComponentEnabledSetting(
                    ComponentName(ctx, NotifListener::class.java),
                    state, PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {
            }
        }

        /** Hub toggle state. False on a fresh install. */
        fun isEnabled(ctx: Context): Boolean =
            com.splendor.blastbuster.Prefs.notifEnabled(ctx)

        /** True when the user granted this listener notification access. */
        fun isListenerGranted(ctx: Context): Boolean {
            val enabled = try {
                Settings.Secure.getString(
                    ctx.contentResolver, "enabled_notification_listeners"
                )
            } catch (_: Exception) {
                null
            } ?: return false
            return enabled.contains(
                ComponentName(ctx, NotifListener::class.java).flattenToString()
            )
        }

        /** Live action for a captured entry (reply/fire path only). */
        fun liveAction(key: String, index: Int): Notification.Action? =
            instance?.liveActionNow(key, index)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rebindAttempts = 0

    // Live actions, keyed by StatusBarNotification key. In-memory only,
    // pruned to the keys still in the ring — a PendingIntent must never
    // be persisted. Guarded by [liveLock].
    private val liveLock = Any()
    private val liveActions = HashMap<String, List<Notification.Action>>()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        rebindAttempts = 0
    }

    override fun onListenerDisconnected() {
        // Same back-off as DndListener: re-bind up to 3 times, then stop
        // trying until the next app launch.
        if (rebindAttempts >= MAX_REBIND_ATTEMPTS) return
        rebindAttempts++
        handler.postDelayed({
            try {
                requestRebind(ComponentName(this, NotifListener::class.java))
            } catch (_: Exception) {
            }
        }, REBIND_DELAY_MS)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isEnabled(this)) return
        if (sbn.packageName == packageName) return
        try {
            val notif = sbn.notification
            val extras = notif.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.trim().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            val actions = (notif.actions ?: emptyArray()).mapIndexed { i, a ->
                NotifStore.NotifAction(
                    index = i,
                    title = a.title?.toString().orEmpty(),
                    hasRemoteInput = a.remoteInputs?.isNotEmpty() == true
                )
            }
            val entry = NotifStore.add(
                NotifStore.CapturedNotif(
                    id = 0L,
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appLabel = appLabel(sbn.packageName),
                    title = title,
                    text = text,
                    postedTimeMs = sbn.postTime,
                    actions = actions
                )
            )
            synchronized(liveLock) {
                liveActions[sbn.key] = notif.actions?.toList() ?: emptyList()
                val kept = NotifStore.snapshot().map { it.key }.toSet()
                liveActions.keys.retainAll(kept)
            }
            // App + title only — never the message body (see NotifStore).
            EventLog.append(
                this, "notif", "NOTIF_POSTED", notifSummaryForLog(entry),
                mapOf("package" to sbn.packageName)
            )
        } catch (_: Exception) {
            // A bad notification must never crash the listener.
        }
    }

    private fun liveActionNow(key: String, index: Int): Notification.Action? {
        synchronized(liveLock) {
            val actions = liveActions[key] ?: return null
            return if (index in actions.indices) actions[index] else null
        }
    }

    private fun appLabel(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
