package com.transcripto.stream.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Moteur de reconnaissance vocale système (Google / Samsung…) — la même qualité
 * que Gboard / l'assistant Android.
 *
 * ⚠️ Confidentialité : par défaut l'audio est envoyé au service de reconnaissance
 * du fournisseur (cloud Google). Hors-ligne possible si le pack de langue est
 * téléchargé dans les réglages du téléphone.
 *
 * Le moteur relance automatiquement une session à chaque résultat final → flux continu.
 */
class GoogleSpeechEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {

    companion object {
        private const val TAG = "GoogleSpeech"
        private const val MAX_RESTARTS_ON_BUSY = 5
    }

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var running = false
    private var busyRestarts = 0

    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Reconnaissance vocale indisponible sur cet appareil")
            return false
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            onError("Impossible de créer le moteur de reconnaissance")
            return false
        }
        recognizer?.setRecognitionListener(listener)
        running = true
        startListening()
        return true
    }

    fun stop() {
        running = false
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
    }

    private fun startListening() {
        if (!running) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening: ${e.message}")
            onError("Erreur de démarrage de la reconnaissance")
            running = false
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: ""
            onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?: ""
            if (text.isNotBlank()) onFinal(text)
            busyRestarts = 0
            if (running) startListening() // flux continu
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> if (running) startListening() // silence : on relance

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    if (running && busyRestarts < MAX_RESTARTS_ON_BUSY) {
                        busyRestarts++
                        startListening()
                    } else if (running) {
                        onError("Moteur occupé")
                        running = false
                    }
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onError("Permission micro manquante")
                    running = false
                }

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> {
                    onError("Réseau requis (ou pack de langue hors-ligne non téléchargé)")
                    running = false
                }

                else -> {
                    onError("Erreur reconnaissance (code $error)")
                    running = false
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
