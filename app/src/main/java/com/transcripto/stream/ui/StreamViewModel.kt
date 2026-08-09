package com.transcripto.stream.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transcripto.stream.audio.PcmAudioRecorder
import com.transcripto.stream.stt.WhisperStreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed interface ModelState {
    data object Loading : ModelState
    data class Ready(val engine: WhisperStreamEngine) : ModelState
    data class Error(val message: String) : ModelState
}

/**
 * Transcription en temps réel : capture PCM 16 kHz continue, fenêtre glissante de 4 s,
 * transcription toutes les 1,5 s, déduplication du chevauchement à l'affichage.
 */
class StreamViewModel(
    private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "StreamVM"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SECONDS = 4
        private const val TICK_MS = 1500L
        private const val MODEL_ASSET = "models/ggml-tiny.bin"
    }

    // ---- État exposé à l'UI ----
    var modelState: ModelState = ModelState.Loading
        private set

    var isStreaming: Boolean = false
        private set

    var liveText: String = ""
        private set

    var lastError: String? = null
        private set

    // ---- Internes ----
    private val engine = WhisperStreamEngine()
    private var recorder: PcmAudioRecorder? = null
    private var streamJob: Job? = null

    // Ring buffer 4 s (16 kHz * 4 s = 64 000 shorts)
    private val ring = ShortArray(SAMPLE_RATE * WINDOW_SECONDS)
    private var writePos = 0
    private var filled = false
    private var validatedText = ""

    init {
        viewModelScope.launch {
            modelState = ModelState.Loading
            val result = ensureModelExtracted().flatMap { engine.loadModel(it) }
            modelState = if (result.isSuccess) {
                ModelState.Ready(engine)
            } else {
                ModelState.Error(result.exceptionOrNull()?.message ?: "Erreur inconnue")
            }
        }
    }

    fun toggleStreaming() {
        if (isStreaming) stopStreaming() else startStreaming()
    }

    fun startStreaming() {
        if (modelState !is ModelState.Ready || isStreaming) return
        validatedText = ""
        liveText = ""
        lastError = null
        writePos = 0
        filled = false

        val rec = PcmAudioRecorder(SAMPLE_RATE) { buf, n -> appendSamples(buf, n) }
        if (!rec.start()) {
            lastError = "Impossible de démarrer l'enregistrement (micro ?)"
            return
        }
        recorder = rec
        isStreaming = true

        streamJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (transcribing) continue
                transcribing = true
                val pcm = snapshotWindow()
                val res = engine.transcribeBuffer(pcm, "fr")
                transcribing = false
                if (res.error != null) {
                    lastError = res.error
                } else if (res.fullText.isNotBlank()) {
                    mergeLive(res.fullText)
                }
            }
        }
    }

    @Volatile
    private var transcribing = false

    fun stopStreaming() {
        isStreaming = false
        streamJob?.cancel()
        streamJob = null
        recorder?.stop()
        recorder = null
    }

    // ---- Ring buffer ----
    private fun appendSamples(buf: ShortArray, n: Int) {
        synchronized(ring) {
            var i = 0
            while (i < n) {
                ring[writePos] = buf[i]
                writePos = (writePos + 1) % ring.size
                if (writePos == 0) filled = true
                i++
            }
        }
    }

    private fun snapshotWindow(): ByteArray {
        synchronized(ring) {
            val out = ShortArray(ring.size)
            val start = if (filled) writePos else 0
            for (i in ring.indices) {
                out[i] = ring[(start + i) % ring.size]
            }
            val bytes = ByteArray(out.size * 2)
            for (i in out.indices) {
                val v = out[i].toInt()
                bytes[2 * i] = (v and 0xFF).toByte()
                bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return bytes
        }
    }

    // ---- Déduplication du chevauchement ----
    private fun mergeLive(full: String) {
        val cleaned = full.trim()
        val words = validatedText.split(" ").filter { it.isNotBlank() }
        var matched = 0
        for (n in minOf(3, words.size) downTo 1) {
            val suffix = words.takeLast(n).joinToString(" ")
            if (suffix.isNotBlank() && cleaned.startsWith(suffix)) {
                matched = suffix.length
                break
            }
        }
        val extra = if (matched > 0) cleaned.substring(matched).trim() else cleaned
        if (extra.isNotEmpty()) {
            validatedText = (validatedText.trim() + " " + extra).trim()
        }
        liveText = validatedText
    }

    // ---- Modèle ----
    private suspend fun ensureModelExtracted(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(appContext.filesDir, "models").apply { mkdirs() }
            val dest = File(dir, "ggml-tiny.bin")
            if (!dest.exists() || dest.length() == 0L) {
                Log.i(TAG, "Extraction du modèle depuis assets…")
                appContext.assets.open(MODEL_ASSET).use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                Log.i(TAG, "Modèle extrait : ${dest.length()} octets")
            }
            Result.success(dest.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun onCleared() {
        stopStreaming()
        viewModelScope.launch {
            engine.unloadModel()
        }
        super.onCleared()
    }
}
