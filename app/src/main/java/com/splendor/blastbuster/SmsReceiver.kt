package com.splendor.blastbuster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Listens for incoming SMS. When the phone is armed and a message matches
 * the trigger code from a trusted sender, the siren starts; when it matches
 * the stop code, the siren stops.
 *
 * Reliability design: when the phone is armed, the guard foreground service
 * is already running, so this receiver only has to command that service —
 * no background-start problem. If the guard has died, startBlast falls back
 * to startForegroundService inside a try/catch; on Android 12+ that call
 * can be refused when the app is fully in the background, in which case the
 * blast does not start (logged). Keeping the guard alive is therefore part
 * of the setup checklist in the app.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.isArmed(context)) return
        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.w(TAG, "could not read SMS: ${e.message}")
            return
        }
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress
            val body = msg.messageBody ?: continue
            if (!Prefs.isTrusted(context, sender)) {
                Log.i(TAG, "ignored SMS from untrusted sender $sender")
                continue
            }
            if (Prefs.matchesCode(body, Prefs.triggerCode(context))) {
                Log.i(TAG, "trigger code from $sender — starting blast")
                BlastService.startBlast(context, sender)
            } else if (Prefs.matchesCode(body, Prefs.stopCode(context))) {
                Log.i(TAG, "stop code from $sender — stopping blast")
                BlastService.stopBlast(context)
            }
        }
    }
}
