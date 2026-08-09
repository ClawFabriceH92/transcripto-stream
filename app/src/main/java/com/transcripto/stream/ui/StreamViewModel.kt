package com.transcripto.stream.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transcripto.stream.audio.PcmAudioRecorder
import com.transcripto.stream.audio.WavFileWriter
import com.transcripto.stream.stt.GoogleSpeechEngine
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ModelState {
    data object Loading : ModelState
    data class Ready(val engine: WhisperStreamEngine) : ModelState
    data class Error(val message: String) : ModelState
}

/**
 * Transcription en temps réel : capture PCM 16 kHz continue, fenêtre glissante avec
 * avance réelle (on transcrit tout le nouvel audio, chevauchement 1 s), conservation
 * de l'audio en WAV, et transcription différée du fichier enregistré.
 */
class StreamViewModel(
    private val appContext: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "StreamVM"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SECONDS = 4
        private const val OVERLAP_SECONDS = 1
        private const val TICK_MS = 1000L
        private const val MODEL_ASSET = "models/ggml-base.bin"
        private const val MIN_NEW_MS = 500L // minimum de nouvel audio pour transcrire
        private val REC_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    // ---- État exposé à l'UI (observable par Compose) ----
    private val _modelState = MutableStateFlow<ModelState>(ModelState.Loading)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _extractionProgress = MutableStateFlow<Float?>(null)
    val extractionProgress: StateFlow<Float?> = _extractionProgress.asStateFlow()

    private val _loadMessage = MutableStateFlow("Chargement du modèle Whisper…")
    val loadMessage: StateFlow<String> = _loadMessage.asStateFlow()

    private val _modelLoadMs = MutableStateFlow(0L)
    val modelLoadMs: StateFlow<Long> = _modelLoadMs.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // Moteur sélectionné : "google" (qualité Android, cloud) ou "whisper" (100% local)
    private val _selectedEngine = MutableStateFlow("google")
    val selectedEngine: StateFlow<String> = _selectedEngine.asStateFlow()

    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Debug / feedback : combien de transcriptions, et le dernier texte de fenêtre brut
    private val _transcriptionCount = MutableStateFlow(0)
    val transcriptionCount: StateFlow<Int> = _transcriptionCount.asStateFlow()

    private val _lastWindowText = MutableStateFlow("")
    val lastWindowText: StateFlow<String> = _lastWindowText.asStateFlow()

    // Dernier enregistrement conservé
    private val _lastRecording = MutableStateFlow<File?>(null)
    val lastRecording: StateFlow<File?> = _lastRecording.asStateFlow()

    private val _isTranscribingFile = MutableStateFlow(false)
    val isTranscribingFile: StateFlow<Boolean> = _isTranscribingFile.asStateFlow()

    private val _fileTranscript = MutableStateFlow("")
    val fileTranscript: StateFlow<String> = _fileTranscript.asStateFlow()

    // ---- Internes ----
    private val engine = WhisperStreamEngine()
    private var googleEngine: GoogleSpeechEngine? = null
    private var recorder: PcmAudioRecorder? = null
    private var wavWriter: WavFileWriter? = null
    private var activeRecordingFile: File? = null
    private var streamJob: Job? = null

    // Ring buffer (WINDOW_SECONDS + marge) — on garde un peu plus pour le chevauchement
    private val ringSize = SAMPLE_RATE * (WINDOW_SECONDS + OVERLAP_SECONDS)
    private val ring = ShortArray(ringSize)
    private var writePos = 0
    private var filled = false
    private var windowStart = 0          // position du premier échantillon non encore transcrit
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

    fun setEngine(engine: String) {
        if (!_isStreaming.value && (engine == "google" || engine == "whisper")) {
            _selectedEngine.value = engine
        }
    }

    fun startStreaming() {
        if (_modelState.value !is ModelState.Ready || _isStreaming.value) return
        validatedText = ""
        _liveText.value = ""
        _fileTranscript.value = ""
        _lastError.value = null
        _transcriptionCount.value = 0
        _lastWindowText.value = ""

        if (_selectedEngine.value == "google") {
            startGoogleStreaming()
        } else {
            startWhisperStreaming()
        }
    }

    private fun startGoogleStreaming() {
        val g = GoogleSpeechEngine(
            appContext,
            onPartial = { partial ->
                _liveText.value = (validatedText + " " + partial).trim()
            },
            onFinal = { final ->
                if (final != validatedText) {
                    validatedText = (validatedText + " " + final).trim()
                }
                _liveText.value = validatedText
            },
            onError = { msg -> _lastError.value = msg },
        )
        if (!g.start()) return
        googleEngine = g
        _isStreaming.value = true
    }

    private fun startWhisperStreaming() {
        writePos = 0
        filled = false
        windowStart = 0

        // Fichier WAV pour conserver l'audio
        val recDir = File(appContext.filesDir, "recordings").apply { mkdirs() }
        val recFile = File(recDir, "rec_${REC_DATE_FORMAT.format(Date())}.wav")
        val writer = try {
            WavFileWriter(recFile)
        } catch (e: Exception) {
            _lastError.value = "Impossible de créer le fichier audio : ${e.message}"
            return
        }
        wavWriter = writer
        activeRecordingFile = recFile
        _lastRecording.value = recFile

        val rec = PcmAudioRecorder(SAMPLE_RATE) { buf, n ->
            appendSamples(buf, n)
            writer.write(buf, n)
        }
        if (!rec.start()) {
            _lastError.value = "Impossible de démarrer l'enregistrement (micro ?)"
            try { writer.close() } catch (_: Exception) {}
            wavWriter = null
            return
        }
        recorder = rec
        _isStreaming.value = true

        streamJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (transcribing) continue
                val (pcm, newMs) = snapshotNewAudio()
                if (newMs < MIN_NEW_MS) continue
                transcribing = true
                val res = engine.transcribeBuffer(pcm, "fr")
                transcribing = false
                if (res.error != null) {
                    _lastError.value = res.error
                } else {
                    _transcriptionCount.value++
                    _lastWindowText.value = res.fullText.trim()
                    if (res.fullText.isNotBlank()) {
                        mergeLive(res.fullText)
                    }
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
        googleEngine?.stop()
        googleEngine = null
        recorder?.stop()
        recorder = null
        try {
            wavWriter?.close()
        } catch (_: Exception) {}
        wavWriter = null
        _lastRecording.value = activeRecordingFile
    }

    /**
     * Transcription différée du dernier fichier WAV enregistré.
     */
    fun transcribeLastRecording() {
        val file = _lastRecording.value ?: return
        if (_isTranscribingFile.value) return
        val engineRef = (modelState.value as? ModelState.Ready)?.engine ?: return
        viewModelScope.launch {
            _isTranscribingFile.value = true
            _fileTranscript.value = ""
            val pcm = withContext(Dispatchers.IO) { readWavPcm(file) }
            if (pcm == null) {
                _lastError.value = "Fichier audio illisible"
            } else {
                val res = engineRef.transcribeBuffer(pcm, "fr")
                if (res.error != null) {
                    _lastError.value = res.error
                } else {
                    _fileTranscript.value = res.fullText.trim()
                }
            }
            _isTranscribingFile.value = false
        }
    }

    private fun readWavPcm(file: File): ByteArray? {
        return try {
            val bytes = file.readBytes()
            // Cherche le chunk "data"
            var idx = 12
            while (idx + 8 <= bytes.size) {
                val id = String(bytes, idx, 4)
                val size = (bytes[idx + 4].toInt() and 0xFF) or
                    ((bytes[idx + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[idx + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[idx + 7].toInt() and 0xFF) shl 24)
                if (id == "data") {
                    return bytes.copyOfRange(idx + 8, minOf(idx + 8 + size, bytes.size))
                }
                idx += 8 + size
            }
            null
        } catch (e: Exception) {
            null
        }
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

    /**
     * Copie le nouvel audio depuis [windowStart] jusqu'à [writePos].
     * Retourne (pcmBytes, durée en ms). Après transcription, windowStart avance
     * avec un chevauchement de OVERLAP_SECONDS pour la continuité.
     */
    private fun snapshotNewAudio(): Pair<ByteArray, Long> {
        synchronized(ring) {
            val end = writePos
            var start = windowStart
            // Longueur en échantillons (gère le wrap)
            var len = (end - start + ring.size) % ring.size
            if (len == 0 && filled) len = ring.size // buffer plein : tout est nouveau
            if (len == 0) return ByteArray(0) to 0L // rien de nouveau

            val out = ShortArray(len)
            for (i in 0 until len) {
                out[i] = ring[(start + i) % ring.size]
            }
            val bytes = ByteArray(len * 2)
            for (i in 0 until len) {
                val v = out[i].toInt()
                bytes[2 * i] = (v and 0xFF).toByte()
                bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            // Avance la fenêtre : tout le transcrit moins le chevauchement
            val overlap = SAMPLE_RATE * OVERLAP_SECONDS
            windowStart = if (!filled) {
                maxOf(0, end - overlap)
            } else {
                (end - overlap + ring.size) % ring.size
            }
            val newMs = (len * 1000L) / SAMPLE_RATE
            return bytes to newMs
        }
    }

    // ---- Déduplication du chevauchement ----
    private fun mergeLive(full: String) {
        val cleaned = full.trim()
        val words = validatedText.split(" ").filter { it.isNotBlank() }
        var matched = 0
        val maxMatch = minOf(words.size, 8)
        for (n in maxMatch downTo 1) {
            val suffix = words.takeLast(n).joinToString(" ")
            if (suffix.isNotBlank() && cleaned.startsWith(suffix)) {
                matched = suffix.length
                break
            }
        }
        val extra = if (matched > 0) cleaned.substring(matched).trim() else cleaned
        if (extra.isNotEmpty()) {
            validatedText = (validatedText.trim() + " " + extra).trim()
            _liveText.value = validatedText
        }
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
