package com.splendor.blastbuster.notif

import android.app.RemoteInput
import android.content.Context
import android.os.Bundle
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.EventLog

/**
 * Replying to a captured notification (v1.3 notification module).
 *
 * A reply is sent through the notification's OWN action: the
 * [RemoteInput] result is delivered to the app's own PendingIntent, so
 * the reply appears exactly as if the user had typed it in the
 * notification shade. Nothing here composes a message on its own and
 * nothing is ever auto-replied — every send is one user tap in
 * [NotifActivity].
 *
 * Blast rule: replying is a WRITE, so it is blocked while a blast is
 * active ([BlastGate]) and the skip is logged. Reading/capturing is a
 * read and is not blocked.
 *
 * The pure [decide] gate is JVM-tested; the Android send path is
 * on-device only (it needs a live PendingIntent).
 */
object NotifReply {

    /** Pure reply-gate decision. */
    enum class ReplyDecision { ALLOWED, BLOCKED_NO_REPLY_ACTION, BLOCKED_BLAST_ACTIVE }

    /** Outcome of [sendReply]. */
    enum class ReplyResult {
        SENT,
        BLOCKED_BLAST_ACTIVE,
        NO_ACTION,
        NO_REMOTE_INPUT,
        FAILED
    }

    /**
     * Pure gate: a reply may be sent only when the notification exposes
     * a reply action and no blast is active. The blast check wins over
     * the action check — during a blast the answer is "blocked", never
     * a judgement about the action.
     */
    fun decide(hasReplyAction: Boolean, isBlasting: Boolean): ReplyDecision =
        when {
            isBlasting -> ReplyDecision.BLOCKED_BLAST_ACTIVE
            !hasReplyAction -> ReplyDecision.BLOCKED_NO_REPLY_ACTION
            else -> ReplyDecision.ALLOWED
        }

    /**
     * Sends [text] as the reply to [action] on [entry].
     *
     * The live [android.app.Notification.Action] is fetched from
     * [NotifListener] (only the listener holds the PendingIntent). The
     * reply text itself is never logged — the EventLog entry records
     * only app + title, via [notifSummaryForLog].
     */
    fun sendReply(
        ctx: Context,
        entry: NotifStore.CapturedNotif,
        action: NotifStore.NotifAction,
        text: String
    ): ReplyResult {
        val gate = BlastGate.forContext(ctx)
        if (!gate.checkWrite("notif_reply") { reason ->
                EventLog.append(ctx, "notif", "NOTIF_REPLY_SKIPPED", reason)
            }
        ) return ReplyResult.BLOCKED_BLAST_ACTIVE
        if (!action.hasRemoteInput) return ReplyResult.NO_ACTION
        val live = NotifListener.liveAction(entry.key, action.index)
            ?: return ReplyResult.NO_ACTION
        val remoteInputs = live.remoteInputs
        if (remoteInputs.isNullOrEmpty()) return ReplyResult.NO_REMOTE_INPUT
        return try {
            val remoteInput = remoteInputs[0]
            val results = Bundle()
            results.putCharSequence(remoteInput.resultKey, text)
            val intent = android.content.Intent()
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
            live.actionIntent.send(ctx, 0, intent)
            EventLog.append(
                ctx, "notif", "NOTIF_REPLIED",
                notifSummaryForLog(entry) + " — reply sent",
                mapOf("package" to entry.packageName)
            )
            ReplyResult.SENT
        } catch (_: Exception) {
            EventLog.append(
                ctx, "notif", "NOTIF_REPLY_FAILED",
                notifSummaryForLog(entry) + " — reply failed",
                mapOf("package" to entry.packageName)
            )
            ReplyResult.FAILED
        }
    }

    /**
     * Fires a non-reply action (e.g. "Mark as read", "Archive") through
     * its own PendingIntent. Same blast rule as a reply: firing an
     * action changes state, so it is a write and is blocked during a
     * blast.
     */
    fun fireAction(ctx: Context, entry: NotifStore.CapturedNotif, action: NotifStore.NotifAction): ReplyResult {
        val gate = BlastGate.forContext(ctx)
        if (!gate.checkWrite("notif_action") { reason ->
                EventLog.append(ctx, "notif", "NOTIF_ACTION_SKIPPED", reason)
            }
        ) return ReplyResult.BLOCKED_BLAST_ACTIVE
        val live = NotifListener.liveAction(entry.key, action.index)
            ?: return ReplyResult.NO_ACTION
        return try {
            live.actionIntent.send(ctx, 0, null)
            EventLog.append(
                ctx, "notif", "NOTIF_ACTION_FIRED",
                notifSummaryForLog(entry) + " — action fired: ${action.title}",
                mapOf("package" to entry.packageName)
            )
            ReplyResult.SENT
        } catch (_: Exception) {
            ReplyResult.FAILED
        }
    }
}
