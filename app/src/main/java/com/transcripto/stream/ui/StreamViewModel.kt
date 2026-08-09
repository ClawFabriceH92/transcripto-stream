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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * Tous les états sont exposés en [StateFlow] pour que Compose se recompose.
 */
class StreamViewModel(
    private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "StreamVM"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SECONDS = 4
        private const val TICK_MS = 1500L
        private const val MODEL_ASSET = "models/ggml-base.bin"
    }

    // ---- État exposé à l'UI (observable par Compose) ----
    private val _modelState = MutableStateFlow<ModelState>(ModelState.Loading)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _extractionProgress = MutableStateFlow<Float?>(null) // 0..1 pendant l'extraction
    val extractionProgress: StateFlow<Float?> = _extractionProgress.asStateFlow()

    private val _loadMessage = MutableStateFlow("Chargement du modèle Whisper…")
    val loadMessage: StateFlow<String> = _loadMessage.asStateFlow()

    private val _modelLoadMs = MutableStateFlow(0L)
    val modelLoadMs: StateFlow<Long> = _modelLoadMs.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

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
            _loadMessage.value = "Extraction du modèle depuis l'APK…"
            val extracted = ensureModelExtracted()
            if (extracted.isFailure) {
                _modelState.value = ModelState.Error(
                    "Extraction : ${extracted.exceptionOrNull()?.message ?: "erreur inconnue"}"
                )
                return@launch
            }
            _loadMessage.value = "Chargement du modèle Whisper (142 Mo)…"
            val t0 = System.currentTimeMillis()
            val loaded = withTimeoutOrNull(120_000L) {
                engine.loadModel(extracted.getOrThrow())
            }
            if (loaded == null) {
                _modelState.value = ModelState.Error(
                    "Chargement trop long (>120 s). Modèle ou mémoire insuffisante ?"
                )
                return@launch
            }
            _modelLoadMs.value = System.currentTimeMillis() - t0
            _modelState.value = if (loaded.isSuccess) {
                _loadMessage.value = ""
                ModelState.Ready(engine)
            } else {
                ModelState.Error(loaded.exceptionOrNull()?.message ?: "Erreur de chargement")
            }
        }
    }

    fun toggleStreaming() {
        if (_isStreaming.value) stopStreaming() else startStreaming()
    }

    fun startStreaming() {
        if (_modelState.value !is ModelState.Ready || _isStreaming.value) return
        validatedText = ""
        _liveText.value = ""
        _lastError.value = null
        writePos = 0
        filled = false

        val rec = PcmAudioRecorder(SAMPLE_RATE) { buf, n -> appendSamples(buf, n) }
        if (!rec.start()) {
            _lastError.value = "Impossible de démarrer l'enregistrement (micro ?)"
            return
        }
        recorder = rec
        _isStreaming.value = true

        streamJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (transcribing) continue
                transcribing = true
                val pcm = snapshotWindow()
                val res = engine.transcribeBuffer(pcm, "fr")
                transcribing = false
                if (res.error != null) {
                    _lastError.value = res.error
                } else if (res.fullText.isNotBlank()) {
                    mergeLive(res.fullText)
                }
            }
        }
    }

    @Volatile
    private var transcribing = false

    fun stopStreaming() {
        _isStreaming.value = false
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
        _liveText.value = validatedText
    }

    // ---- Modèle ----
    private suspend fun ensureModelExtracted(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(appContext.filesDir, "models").apply { mkdirs() }
            val dest = File(dir, "ggml-base.bin")
            if (!dest.exists() || dest.length() == 0L) {
                Log.i(TAG, "Extraction du modèle depuis assets…")
                val total = appContext.assets.open(MODEL_ASSET).use { it.available() }
                appContext.assets.open(MODEL_ASSET).use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                _extractionProgress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
                _extractionProgress.value = null
                Log.i(TAG, "Modèle extrait : ${dest.length()} octets")
            }
            Result.success(dest.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Extraction échouée", e)
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
