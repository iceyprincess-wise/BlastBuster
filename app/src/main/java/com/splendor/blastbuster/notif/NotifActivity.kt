package com.splendor.blastbuster.notif

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.splendor.blastbuster.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notification screen (v1.3 notification module): the captured list
 * (newest first), entry detail with its actions, and the reply box.
 *
 * Honest limits, shown next to the feature (not buried in help):
 * notification access must be granted in Settings; only content a
 * notification actually carries can be shown; some apps (e.g. banking)
 * may not expose content; replies work only where the app exposes a
 * reply action (WhatsApp does); nothing is ever auto-replied; the
 * EventLog records app + title only, never the message body; only the
 * latest 50 notifications are kept.
 *
 * Like the other module screens, this screen is unreachable during a
 * blast (MainActivity redirects to the lockout screen while blasting,
 * and this activity finishes if it is somehow opened mid-blast).
 */
class NotifActivity : Activity() {

    companion object {
        private const val TITLE = "Notifications"

        private const val HONEST_LIMIT =
            "Shows notifications as they arrive, and lets you reply " +
                "through the app's own reply action. Requires Notification " +
                "access granted in Settings. Only content a notification " +
                "actually carries can be shown — some apps (e.g. banking) " +
                "may not expose content. Replies work only where the app " +
                "exposes a reply action (WhatsApp does). Nothing is ever " +
                "auto-replied. The log records app + title only, never the " +
                "message body. Only the latest 50 notifications are kept."

        /** Hub entry point (wired by the integration agent). */
        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, NotifActivity::class.java))
        }
    }

    private lateinit var statusView: TextView
    private lateinit var toggleButton: Button
    private lateinit var listView: LinearLayout
    private lateinit var detailView: LinearLayout
    private var selectedId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.isBlasting(this)) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = TITLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = HONEST_LIMIT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(12))
        })

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusView)

        toggleButton = Button(this).apply {
            setOnClickListener { onToggleClicked() }
        }
        root.addView(toggleButton)

        root.addView(Button(this).apply {
            text = "Open Notification access settings"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    )
                } catch (_: Exception) {
                    Toast.makeText(
                        this@NotifActivity, "Could not open settings.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Refresh"
            setOnClickListener { refresh() }
        })

        detailView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(detailView)

        root.addView(TextView(this).apply {
            text = "Captured notifications (newest first)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(6))
        })
        listView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listView)

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!Prefs.isBlasting(this)) refresh()
    }

    private fun onToggleClicked() {
        val enable = !NotifListener.isEnabled(this)
        NotifListener.setEnabled(this, enable)
        Toast.makeText(
            this,
            if (enable) {
                "Notifications on. Grant Notification access in Settings " +
                    "if you have not already."
            } else {
                "Notifications off. Nothing more will be captured."
            },
            Toast.LENGTH_LONG
        ).show()
        refresh()
    }

    private fun refresh() {
        val enabled = NotifListener.isEnabled(this)
        val granted = NotifListener.isListenerGranted(this)
        val count = NotifStore.size()
        statusView.text = "Capture: ${if (enabled) "ON" else "OFF"}\n" +
            "Notification access: " +
            if (granted) "granted" else "NOT granted — open settings above" +
            "\nKept: $count of 50 latest"
        toggleButton.text = if (enabled) {
            "Turn notifications off"
        } else {
            "Turn notifications on"
        }
        renderDetail()
        renderList()
    }

    private fun renderList() {
        listView.removeAllViews()
        val entries = NotifStore.snapshot()
        if (entries.isEmpty()) {
            listView.addView(TextView(this).apply {
                text = "No notifications captured yet. Turn the feature on " +
                    "and grant Notification access, then new notifications " +
                    "will appear here."
            })
            return
        }
        for (entry in entries) {
            val row = TextView(this).apply {
                text = "${formatTime(entry.postedTimeMs)}  ${entry.appLabel}\n" +
                    entry.title +
                    (if (entry.text.isNotEmpty()) "\n${entry.text.take(120)}" else "") +
                    (if (entry.hasReplyAction()) "\n↩ can reply" else "")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(6), 0, dp(6))
                typeface = if (entry.id == selectedId) {
                    Typeface.DEFAULT_BOLD
                } else {
                    Typeface.DEFAULT
                }
                setOnClickListener {
                    selectedId = entry.id
                    renderDetail()
                    renderList()
                }
            }
            listView.addView(row)
        }
    }

    private fun renderDetail() {
        detailView.removeAllViews()
        val entry = if (selectedId >= 0) NotifStore.find(selectedId) else null
        if (entry == null) {
            if (selectedId >= 0) selectedId = -1L
            return
        }
        detailView.addView(TextView(this).apply {
            text = "${entry.appLabel} — ${formatTime(entry.postedTimeMs)}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (entry.title.isNotEmpty()) {
            detailView.addView(TextView(this).apply {
                text = entry.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (entry.text.isNotEmpty()) {
            detailView.addView(TextView(this).apply {
                text = entry.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(4), 0, dp(8))
            })
        }
        if (entry.actions.isEmpty()) {
            detailView.addView(TextView(this).apply {
                text = "This notification carries no actions, so there is " +
                    "nothing to tap and no reply to send."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            return
        }
        detailView.addView(TextView(this).apply {
            text = "Actions"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        })
        for (action in entry.actions) {
            if (action.hasRemoteInput) {
                addReplyRow(entry, action)
            } else {
                detailView.addView(Button(this).apply {
                    text = action.title.ifEmpty { "Open" }
                    setOnClickListener {
                        val result = NotifReply.fireAction(
                            this@NotifActivity, entry, action
                        )
                        Toast.makeText(
                            this@NotifActivity,
                            when (result) {
                                NotifReply.ReplyResult.SENT ->
                                    "Action sent."
                                NotifReply.ReplyResult.BLOCKED_BLAST_ACTIVE ->
                                    "Blocked — a blast is active."
                                NotifReply.ReplyResult.NO_ACTION ->
                                    "That action is no longer available."
                                else -> "Action failed."
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
        }
    }

    private fun addReplyRow(entry: NotifStore.CapturedNotif, action: NotifStore.NotifAction) {
        val replyBox = EditText(this).apply {
            hint = "Reply to ${entry.appLabel}…"
            visibility = View.GONE
        }
        val sendButton = Button(this).apply {
            text = "Send reply"
            visibility = View.GONE
            setOnClickListener {
                val text = replyBox.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(
                        this@NotifActivity, "Type a reply first.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val result = NotifReply.sendReply(
                    this@NotifActivity, entry, action, text
                )
                Toast.makeText(
                    this@NotifActivity,
                    when (result) {
                        NotifReply.ReplyResult.SENT -> "Reply sent."
                        NotifReply.ReplyResult.BLOCKED_BLAST_ACTIVE ->
                            "Blocked — a blast is active."
                        NotifReply.ReplyResult.NO_ACTION,
                        NotifReply.ReplyResult.NO_REMOTE_INPUT ->
                            "That reply action is no longer available."
                        NotifReply.ReplyResult.FAILED -> "Reply failed."
                    },
                    Toast.LENGTH_SHORT
                ).show()
                if (result == NotifReply.ReplyResult.SENT) {
                    replyBox.setText("")
                    replyBox.visibility = View.GONE
                    visibility = View.GONE
                }
            }
        }
        detailView.addView(Button(this).apply {
            text = "Reply: ${action.title.ifEmpty { "Reply" }}"
            setOnClickListener {
                replyBox.visibility = View.VISIBLE
                sendButton.visibility = View.VISIBLE
                replyBox.requestFocus()
            }
        })
        detailView.addView(replyBox)
        detailView.addView(sendButton)
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(ms))

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
