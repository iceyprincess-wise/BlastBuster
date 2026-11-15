package com.splendor.blastbuster

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Continuous phrase listener for the owner's voice trigger phrase.
 *
 * PHRASE detection only — it matches WORDS, not the speaker. Anyone saying
 * the phrase near the phone triggers the blast; reliable speaker
 * verification (proving it is the owner's voice) is not available in a
 * free on-device build. Say so in the app UI and the report.
 *
 * Best-effort by design: it needs RECORD_AUDIO, a speech recognizer on
 * the device, and either the offline speech pack or network. Listening
 * continuously costs battery, so it runs only while the guard is armed
 * and the toggle is on, and it pauses during a blast.
 */
class VoiceTrigger(
    private val context: Context,
    private val onPhraseHeard: () -> Unit
) {
    companion object {
        private const val TAG = "VoiceTrigger"
        private const val RESTART_MS = 800L
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var stopped = false
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        if (listening || stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "no speech recognizer on this device")
            return
        }
        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    scheduleRestart()
                }

                override fun onError(error: Int) {
                    // ERROR_NO_MATCH, ERROR_SPEECH_TIMEOUT and the rest are
                    // normal for a listener that hears nothing: just restart.
                    scheduleRestart()
                }

                override fun onResults(results: Bundle?) {
                    checkResults(results)
                    scheduleRestart()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    checkResults(partialResults)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            listening = true
            listenOnce()
            Log.i(TAG, "voice trigger listening")
        } catch (e: Exception) {
            Log.w(TAG, "voice trigger start failed: ${e.message}")
            listening = false
        }
    }

    fun stop() {
        stopped = true
        listening = false
        handler.removeCallbacksAndMessages(null)
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun listenOnce() {
        val sr = recognizer ?: return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed: ${e.message}")
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (stopped) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!stopped) listenOnce()
        }, RESTART_MS)
    }

    private fun checkResults(results: Bundle?) {
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return
        val phrase = Prefs.voicePhrase(context)
        for (candidate in heard) {
            if (VoicePhrase.matches(candidate, phrase)) {
                Log.i(TAG, "voice phrase heard: \"$candidate\"")
                onPhraseHeard()
                return
            }
        }
    }
}
