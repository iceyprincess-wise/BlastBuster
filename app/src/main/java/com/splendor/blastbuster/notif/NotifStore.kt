package com.splendor.blastbuster.notif

/**
 * Notification capture store (v1.3 notification module) — pure Kotlin,
 * no Android imports, JVM-tested.
 *
 * Holds a ring of the latest [MAX_NOTIFICATIONS] captured notifications
 * (newest first when read). The ring is the single source of truth for
 * [NotifActivity]; [NotifListener] writes to it, the activity reads it.
 *
 * Privacy: the store itself keeps title/text so the activity can show
 * them, but anything written to [com.splendor.blastbuster.companion.EventLog]
 * goes through [notifSummaryForLog], which is app + title only — the
 * message body is never logged. When the title itself looks sensitive
 * (a code / password / OTP), even the title is replaced by a marker.
 */
object NotifStore {

    /** Ring cap: only the latest 50 notifications are kept. */
    const val MAX_NOTIFICATIONS = 50

    /** One action carried by a captured notification. */
    data class NotifAction(
        /** Position in the notification's action list (for the live reply). */
        val index: Int,
        val title: String,
        /** True when the action carries a RemoteInput (a reply action). */
        val hasRemoteInput: Boolean
    )

    /** One captured notification. `id` is assigned by [NotifRing.add]. */
    data class CapturedNotif(
        val id: Long,
        /** StatusBarNotification key — links the entry to its live actions. */
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postedTimeMs: Long,
        val actions: List<NotifAction>
    ) {
        /** True when at least one action accepts a typed reply. */
        fun hasReplyAction(): Boolean = actions.any { it.hasRemoteInput }

        /** Only the actions that accept a typed reply. */
        fun replyActions(): List<NotifAction> = actions.filter { it.hasRemoteInput }
    }

    /** Pure ring: cap eviction (oldest first), ordering, filtering. */
    class NotifRing(private val maxSize: Int = MAX_NOTIFICATIONS) {
        private val items = ArrayList<CapturedNotif>()
        private var nextId = 1L

        /** Adds [entry], assigns it a monotonic id, evicts oldest first. */
        fun add(entry: CapturedNotif): CapturedNotif {
            val stored = entry.copy(id = nextId++)
            items.add(stored)
            while (items.size > maxSize) items.removeAt(0)
            return stored
        }

        /** All entries, newest first. */
        fun snapshot(): List<CapturedNotif> = items.asReversed()

        /** Entries from one package, newest first. */
        fun byPackage(packageName: String): List<CapturedNotif> =
            items.asReversed().filter { it.packageName == packageName }

        fun find(id: Long): CapturedNotif? = items.firstOrNull { it.id == id }

        fun size(): Int = items.size

        fun clear() {
            items.clear()
        }
    }

    // The single shared ring. All access is synchronised: the listener
    // writes from the system binder thread, the activity reads on the UI
    // thread. The pure logic lives in [NotifRing] and is tested directly.
    private val ring = NotifRing()

    fun add(entry: CapturedNotif): CapturedNotif = synchronized(ring) { ring.add(entry) }
    fun snapshot(): List<CapturedNotif> = synchronized(ring) { ring.snapshot() }
    fun byPackage(packageName: String): List<CapturedNotif> =
        synchronized(ring) { ring.byPackage(packageName) }
    fun find(id: Long): CapturedNotif? = synchronized(ring) { ring.find(id) }
    fun size(): Int = synchronized(ring) { ring.size() }
    fun clear() = synchronized(ring) { ring.clear() }
}

/**
 * One-line EventLog summary for a captured notification: app + title
 * only. The message body is never included. A title that looks
 * sensitive is replaced by a marker instead of being logged.
 */
fun notifSummaryForLog(entry: NotifStore.CapturedNotif): String {
    val title = entry.title.trim()
    val shown = if (title.isEmpty()) {
        "(no title)"
    } else if (looksSensitive(title)) {
        "(message hidden — looks sensitive)"
    } else if (title.length > 80) {
        title.take(80) + "…"
    } else {
        title
    }
    return "${entry.appLabel}: $shown"
}

/**
 * Heuristic, not a guarantee: true when [text] looks like it carries a
 * secret — a one-time code, a password/passcode, or a standalone 4–8
 * digit number. Used only to decide what may go into the log; the log
 * summary never contains the message body either way.
 */
fun looksSensitive(text: String): Boolean {
    val lower = text.lowercase()
    val words = listOf(
        "otp", "one-time", "one time", "verification code", "verification",
        "password", "passcode", "pin code", "2fa", "two-factor",
        "auth code", "login code"
    )
    if (words.any { lower.contains(it) }) return true
    // A standalone 4–8 digit run (a code) anywhere in the text.
    return Regex("\\b\\d{4,8}\\b").containsMatchIn(text)
}
