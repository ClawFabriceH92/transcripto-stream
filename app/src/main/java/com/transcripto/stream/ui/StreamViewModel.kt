package com.transcripto.stream.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transcripto.stream.RecordingService
import com.transcripto.stream.RecordingState
import com.transcripto.stream.audio.PcmAudioRecorder
import com.transcripto.stream.audio.WavFileWriter
import com.transcripto.stream.data.CryptoManager
import com.transcripto.stream.data.SettingsStore
import com.transcripto.stream.stt.GoogleSpeechEngine
import com.transcripto.stream.stt.SegmentData
import com.transcripto.stream.stt.StreamResult
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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

sealed interface ModelState {
    data object Loading : ModelState
    data class Ready(val engine: WhisperStreamEngine) : ModelState
    data class Error(val message: String) : ModelState
}

/** Un enregistrement listé (WAV ou .enc chiffré). */
data class RecordingItem(
    val file: File,
    val baseName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val modifiedAt: Long,
    val encrypted: Boolean,
    val transcript: String,
)

/**
 * Transcription en temps réel — capture PCM 16 kHz continue, fenêtre glissante,
 * conservation WAV + transcription différée. Fonctionnalités CAC :
 * vocabulaire personnalisé, VAD, langue fr/en/auto, gain micro, horodatage,
 * .txt auto, liste/renommage/recherche, rétention RGPD, PIN, chiffrement WAV,
 * vitesse de lecture, export email + presse-papiers.
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
        private const val VAD_THRESHOLD = 300.0 // RMS int16 : silence ~<50, parole >500
        private const val REC_DIR = "recordings"
        private val REC_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val TXT_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }

    val settings = SettingsStore(appContext)

    // ---- Écran affiché : 0 = principal, 1 = liste, 2 = réglages ----
    private val _screen = MutableStateFlow(0)
    val screen: StateFlow<Int> = _screen.asStateFlow()

    // ---- Verrouillage PIN ----
    private val _locked = MutableStateFlow(settings.pinHash.isNotEmpty())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private val _pinError = MutableStateFlow<String?>(null)
    val pinError: StateFlow<String?> = _pinError.asStateFlow()

    // ---- État du modèle + streaming (inchangé, observable par Compose) ----
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

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _elapsedSec = MutableStateFlow(0L)
    val elapsedSec: StateFlow<Long> = _elapsedSec.asStateFlow()

    private val _selectedEngine = MutableStateFlow("google")
    val selectedEngine: StateFlow<String> = _selectedEngine.asStateFlow()

    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _transcriptionCount = MutableStateFlow(0)
    val transcriptionCount: StateFlow<Int> = _transcriptionCount.asStateFlow()

    private val _lastWindowText = MutableStateFlow("")
    val lastWindowText: StateFlow<String> = _lastWindowText.asStateFlow()

    private val _lastRecording = MutableStateFlow<File?>(null)
    val lastRecording: StateFlow<File?> = _lastRecording.asStateFlow()

    private val _isTranscribingFile = MutableStateFlow(false)
    val isTranscribingFile: StateFlow<Boolean> = _isTranscribingFile.asStateFlow()

    private val _fileTranscript = MutableStateFlow("")
    val fileTranscript: StateFlow<String> = _fileTranscript.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // ---- Liste des enregistrements + recherche ----
    private val _recordings = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordings: StateFlow<List<RecordingItem>> = _recordings.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ---- Internes ----
    private val engine = WhisperStreamEngine()
    private var googleEngine: GoogleSpeechEngine? = null
    private var recorder: PcmAudioRecorder? = null
    private var wavWriter: WavFileWriter? = null
    private var activeRecordingFile: File? = null
    private var streamJob: Job? = null
    private var chronoJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackFile: File? = null // temp décrypté en cours de lecture
    private var playbackIsTemp = false

    // Ring buffer (WINDOW_SECONDS + marge)
    private val ringSize = SAMPLE_RATE * (WINDOW_SECONDS + OVERLAP_SECONDS)
    private val ring = ShortArray(ringSize)
    private var writePos = 0
    private var filled = false
    private var windowStart = 0
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
        cleanupExpired()
        refreshRecordings()
    }

    // ================= NAVIGATION =================

    fun navigate(screenIndex: Int) {
        if (_screen.value == screenIndex) return
        if (screenIndex == 1) refreshRecordings()
        _screen.value = screenIndex
    }

    // ================= PIN =================

    fun unlock(pin: String) {
        if (settings.verifyPin(pin)) {
            _locked.value = false
            _pinError.value = null
        } else {
            _pinError.value = "Code incorrect"
        }
    }

    fun setPinError(e: String?) {
        _pinError.value = e
    }

    /** Sélectionne un enregistrement de la liste pour l'écran principal. */
    fun selectRecording(item: RecordingItem) {
        _lastRecording.value = item.file
        activeRecordingFile = null
        _fileTranscript.value = item.transcript
            .substringAfter("----\n")
            .trim()
    }

    fun lockNow() {
        if (settings.pinHash.isNotEmpty()) _locked.value = true
    }

    fun enablePin(pin: String) {
        if (pin.length >= 4) {
            settings.setPin(pin)
            _locked.value = true
        }
    }

    fun disablePin() {
        settings.clearPin()
        _locked.value = false
    }

    // ================= RÉGLAGES =================

    fun setLanguage(lang: String) {
        settings.language = lang
    }

    fun setMicGain(gain: Float) {
        settings.micGain = gain
    }

    fun setVocabulary(text: String) {
        settings.vocabulary = text
    }

    fun setRetentionDays(days: Int) {
        settings.retentionDays = days
        cleanupExpired()
    }

    fun setEncryptWav(enabled: Boolean) {
        settings.encryptWav = enabled
    }

    fun setTheme(theme: String) {
        settings.theme = theme
    }

    fun setUseTimestamps(enabled: Boolean) {
        settings.useTimestamps = enabled
    }

    fun setPlaybackSpeed(speed: Float) {
        settings.playbackSpeed = speed
        try {
            mediaPlayer?.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (_: Exception) {}
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    // ================= STREAMING =================

    fun toggleStreaming() {
        if (_isStreaming.value) stopStreaming() else startStreaming()
    }

    fun setEngine(engine: String) {
        if (!_isStreaming.value && (engine == "google" || engine == "whisper")) {
            _selectedEngine.value = engine
        }
    }

    fun togglePause() {
        if (!_isStreaming.value) return
        if (_isPaused.value) {
            _isPaused.value = false
            RecordingState.isPaused = false
            googleEngine?.resume()
            writePos = 0
            filled = false
            windowStart = 0
        } else {
            _isPaused.value = true
            RecordingState.isPaused = true
            googleEngine?.pause()
        }
    }

    private fun startChrono() {
        _elapsedSec.value = 0L
        RecordingState.elapsedSec = 0L
        chronoJob?.cancel()
        chronoJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (!_isPaused.value) {
                    _elapsedSec.value++
                    RecordingState.elapsedSec = _elapsedSec.value
                }
            }
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
        _isPaused.value = false
        RecordingState.isActive = true
        RecordingState.isPaused = false
        startChrono()
        RecordingService.start(appContext)

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
            language = settings.language,
            hints = settings.vocabularyList,
        )
        if (!g.start()) return
        googleEngine = g
        _isStreaming.value = true
    }

    private fun startWhisperStreaming() {
        writePos = 0
        filled = false
        windowStart = 0

        val recDir = recordingsDir()
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
            if (!_isPaused.value) {
                // Gain micro : amplification avant écriture + transcription
                val gain = settings.micGain
                if (gain != 1.0f) {
                    for (i in 0 until n) {
                        val v = buf[i].toInt() * gain
                        buf[i] = v.coerceIn(
                            Short.MIN_VALUE.toFloat(),
                            Short.MAX_VALUE.toFloat()
                        ).toInt().toShort()
                    }
                }
                appendSamples(buf, n)
                writer.write(buf, n)
            }
        }
        if (!rec.start()) {
            _lastError.value = "Impossible de démarrer l'enregistrement (micro ?)"
            try { writer.close() } catch (_: Exception) {}
            wavWriter = null
            return
        }
        recorder = rec
        _isStreaming.value = true

        val prompt = settings.vocabularyList.joinToString(", ")
        streamJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (transcribing || _isPaused.value) continue
                val (pcm, newMs) = snapshotNewAudio()
                if (newMs < MIN_NEW_MS) continue
                // VAD : ignore les fenêtres de silence (coupe les blancs, moins d'hallucinations)
                if (rmsOf(pcm) < VAD_THRESHOLD) continue
                transcribing = true
                val res = engine.transcribeBuffer(pcm, settings.language, prompt)
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
        _isPaused.value = false
        RecordingState.isActive = false
        RecordingState.isPaused = false
        RecordingService.stop(appContext)
        streamJob?.cancel()
        streamJob = null
        chronoJob?.cancel()
        chronoJob = null
        googleEngine?.stop()
        googleEngine = null
        recorder?.stop()
        recorder = null
        try {
            wavWriter?.close()
        } catch (_: Exception) {}
        wavWriter = null

        val raw = activeRecordingFile
        activeRecordingFile = null
        _lastRecording.value = raw

        if (raw != null) {
            // .txt auto à côté du WAV — rien ne se perd, même sans transcription différée
            if (_liveText.value.isNotBlank()) {
                writeTranscriptFile(raw, _liveText.value, _elapsedSec.value * 1000)
            }
            // Chiffrement optionnel du WAV
            if (settings.encryptWav && raw.exists() && raw.extension == "wav") {
                val enc = File(raw.parentFile, raw.nameWithoutExtension + ".wav.enc")
                if (CryptoManager.encryptFile(raw, enc)) {
                    raw.delete()
                    _lastRecording.value = enc
                } else {
                    _lastError.value = "Chiffrement impossible — WAV conservé en clair"
                }
            }
        }
        refreshRecordings()
    }

    // ================= LECTURE + VITESSE =================

    /** Retourne le fichier WAV en clair (déchiffre .enc vers un temp si besoin). */
    private fun resolvedAudioFile(file: File): File? {
        if (file.extension != "enc") return file
        return CryptoManager.decryptToTemp(file, appContext.cacheDir)
    }

    fun togglePlayback() {
        val file = _lastRecording.value ?: return
        if (_isPlaying.value) {
            stopPlayback()
        } else {
            val clear = resolvedAudioFile(file) ?: run {
                _lastError.value = "Déchiffrement impossible"
                return
            }
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(clear.absolutePath)
                    setOnCompletionListener {
                        _isPlaying.value = false
                        release()
                        mediaPlayer = null
                        if (clear != file) clear.delete()
                    }
                    setOnErrorListener { _, _, _ ->
                        _isPlaying.value = false
                        release()
                        mediaPlayer = null
                        if (clear != file) clear.delete()
                        true
                    }
                    prepare()
                    playbackParams = PlaybackParams().setSpeed(settings.playbackSpeed)
                    start()
                }
                playbackFile = clear
                playbackIsTemp = clear != file
                _isPlaying.value = true
            } catch (e: Exception) {
                _lastError.value = "Lecture impossible : ${e.message}"
            }
        }
    }

    private fun stopPlayback() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        if (playbackIsTemp) playbackFile?.delete()
        playbackFile = null
        playbackIsTemp = false
        _isPlaying.value = false
    }

    // ================= TRANSCRIPTION DIFFÉRÉE + .txt =================

    fun transcribeLastRecording() {
        val file = _lastRecording.value ?: return
        if (_isTranscribingFile.value) return
        val engineRef = (modelState.value as? ModelState.Ready)?.engine ?: return
        viewModelScope.launch {
            _isTranscribingFile.value = true
            _fileTranscript.value = ""
            val clear = resolvedAudioFile(file)
            val pcm = if (clear != null) {
                withContext(Dispatchers.IO) { readWavPcm(clear) }
            } else null
            if (clear != null && clear != file) clear.delete()
            if (pcm == null) {
                _lastError.value = "Fichier audio illisible"
            } else {
                val res = engineRef.transcribeBuffer(pcm, settings.language, settings.vocabularyList.joinToString(", "))
                if (res.error != null) {
                    _lastError.value = res.error
                } else {
                    val text = withContext(Dispatchers.IO) {
                        buildSpeakerMarkedTranscript(pcm, res)
                    }
                    _fileTranscript.value = text
                    // Sauvegarde .txt auto à côté du fichier
                    writeTranscriptFile(file, text, wavDurationMs(file))
                }
            }
            _isTranscribingFile.value = false
        }
    }

    /** Durée en ms d'un WAV (clair) en lisant le header. */
    private fun wavDurationMs(file: File): Long {
        val clear = resolvedAudioFile(file) ?: return 0L
        val dur = readWavDuration(clear)
        if (clear != file) clear.delete()
        return dur
    }

    private fun readWavDuration(file: File): Long {
        return try {
            val raf = file.inputStream().use { inp ->
                val header = ByteArray(44)
                val n = inp.read(header)
                if (n < 44) return 0L
                header
            }
            val dataSize = (raf[40].toInt() and 0xFF) or
                ((raf[41].toInt() and 0xFF) shl 8) or
                ((raf[42].toInt() and 0xFF) shl 16) or
                ((raf[43].toInt() and 0xFF) shl 24)
            (dataSize.toLong() * 1000L) / 32000L
        } catch (e: Exception) {
            0L
        }
    }

    private fun recordingsDir(): File = File(appContext.filesDir, REC_DIR).apply { mkdirs() }

    /**
     * Écrit (ou écrase) le .txt d'un enregistrement : métadonnées + transcription.
     */
    fun writeTranscriptFile(audioFile: File, text: String, durationMs: Long) {
        try {
            val txt = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".txt")
            val sb = StringBuilder()
            sb.append("Transcripto Stream\n")
            sb.append("Date : ").append(TXT_DATE_FORMAT.format(Date(audioFile.lastModified()))).append("\n")
            sb.append("Durée : ").append(formatHms(durationMs)).append("\n")
            if (audioFile.extension == "enc") sb.append("Chiffré : oui\n")
            sb.append("----\n\n")
            sb.append(text)
            txt.writeText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "writeTranscriptFile: ${e.message}")
        }
    }

    fun formatHms(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /**
     * Reconstruction avec horodatage [mm:ss] + changements d'interlocuteur :
     * pause > 1,2 s → [pause Xs] ; variation de pitch F0 > 20 % → [changement d'interlocuteur].
     */
    private fun buildSpeakerMarkedTranscript(pcm: ByteArray, res: StreamResult): String {
        val segments = res.segments
        if (segments.isEmpty()) return res.fullText.trim()
        val shorts = byteArrayToShorts(pcm)
        val sb = StringBuilder()
        var prevF0: Double? = null
        for ((i, seg) in segments.withIndex()) {
            if (seg.text.isBlank()) continue
            if (i > 0) {
                val prev = segments[i - 1]
                val gap = seg.startMs - prev.endMs
                if (gap > 1200) {
                    sb.append(" [pause ${gap / 1000}s] ")
                }
            }
            if (settings.useTimestamps) {
                sb.append("[").append(formatClock(seg.startMs)).append("] ")
            }
            val s0 = ((seg.startMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, shorts.size - 1)
            val s1 = ((seg.endMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(s0 + 1, shorts.size)
            val f0 = averagePitch(shorts, s0, s1)
            if (prevF0 != null && f0 != null) {
                val ratio = abs(f0 - prevF0) / min(f0, prevF0)
                if (ratio > 0.20) {
                    sb.append(" [changement d'interlocuteur] ")
                }
            }
            prevF0 = f0 ?: prevF0
            sb.append(seg.text.trim()).append(" ")
        }
        return sb.toString().trim()
    }

    private fun formatClock(ms: Long): String {
        val totalSec = ms / 1000
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }

    // ================= PRESSE-PAPIERS + EMAIL =================

    fun copyText(text: String): Boolean {
        if (text.isBlank()) return false
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcription", text))
        return true
    }

    /** Construit l'intent email avec le .txt export + le WAV (déchiffré si besoin). */
    suspend fun buildEmailIntent(): Intent? = withContext(Dispatchers.IO) {
        val file = _lastRecording.value ?: return@withContext null
        try {
            val text = _fileTranscript.value.ifBlank { _liveText.value }
            val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val txt = File(exportDir, file.nameWithoutExtension + ".txt")
            txt.writeText(
                if (text.isBlank()) "Transcripto Stream — enregistrement sans transcription\n" else text
            )

            val attachments = arrayListOf<Uri>()
            val txtUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", txt)
            attachments.add(txtUri)

            val clear = resolvedAudioFile(file)
            if (clear != null) {
                val audioUri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", clear)
                attachments.add(audioUri)
            }

            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(""))
                putExtra(Intent.EXTRA_SUBJECT, "Transcription ${file.nameWithoutExtension}")
                putExtra(Intent.EXTRA_TEXT, text)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildEmailIntent: ${e.message}")
            null
        }
    }

    // ================= LISTE DES ENREGISTREMENTS =================

    fun refreshRecordings() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                val dir = recordingsDir()
                dir.listFiles()
                    ?.filter { it.extension == "wav" || it.extension == "enc" }
                    ?.map { f ->
                        val txt = File(dir, f.nameWithoutExtension + ".txt")
                        val transcript = if (txt.exists()) txt.readText().take(200) else ""
                        RecordingItem(
                            file = f,
                            baseName = f.nameWithoutExtension,
                            sizeBytes = f.length(),
                            durationMs = wavDurationMs(f),
                            modifiedAt = f.lastModified(),
                            encrypted = f.extension == "enc",
                            transcript = transcript,
                        )
                    }
                    ?.sortedByDescending { it.modifiedAt }
                    ?: emptyList()
            }
            _recordings.value = items
        }
    }

    fun renameRecording(old: RecordingItem, newBaseName: String) {
        val name = newBaseName.trim()
        if (name.isEmpty() || name == old.baseName) return
        val dir = old.file.parentFile ?: return
        val newFile = File(dir, name + old.file.extension)
        if (newFile.exists()) {
            _lastError.value = "Un enregistrement porte déjà ce nom"
            return
        }
        val ok = old.file.renameTo(newFile)
        if (ok) {
            val oldTxt = File(dir, old.baseName + ".txt")
            if (oldTxt.exists()) oldTxt.renameTo(File(dir, name + ".txt"))
            if (_lastRecording.value == old.file) _lastRecording.value = newFile
        }
        refreshRecordings()
    }

    fun deleteRecording(item: RecordingItem) {
        item.file.delete()
        File(item.file.parentFile, item.baseName + ".txt").delete()
        if (_lastRecording.value == item.file) {
            _lastRecording.value = null
            _fileTranscript.value = ""
            activeRecordingFile = null
        }
        refreshRecordings()
    }

    fun deleteLastRecording() {
        if (_isStreaming.value) return
        val f = _lastRecording.value ?: return
        f.delete()
        File(f.parentFile, f.nameWithoutExtension + ".txt").delete()
        _lastRecording.value = null
        activeRecordingFile = null
        _fileTranscript.value = ""
    }

    /** Rétention RGPD : supprime les enregistrements plus vieux que N jours (0 = désactivé). */
    fun cleanupExpired() {
        val days = settings.retentionDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dir = recordingsDir()
                dir.listFiles()?.forEach { f ->
                    if (f.extension == "wav" || f.extension == "enc") {
                        if (f.lastModified() < cutoff) {
                            f.delete()
                            File(dir, f.nameWithoutExtension + ".txt").delete()
                        }
                    }
                }
            }
            refreshRecordings()
        }
    }

    // ================= RING BUFFER + VAD =================

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

    private fun snapshotNewAudio(): Pair<ByteArray, Long> {
        synchronized(ring) {
            val end = writePos
            var start = windowStart
            var len = (end - start + ring.size) % ring.size
            if (len == 0 && filled) len = ring.size
            if (len == 0) return ByteArray(0) to 0L

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

    /** RMS d'un buffer PCM int16 — utilisé par la VAD. */
    private fun rmsOf(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += v * v
            n++
            i += 2
        }
        if (n == 0) return 0.0
        return sqrt(sum / n)
    }

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

    // ================= PITCH (diarisation approximative) =================

    private fun byteArrayToShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    private fun averagePitch(pcm: ShortArray, start: Int, end: Int): Double? {
        val win = 480
        val hop = 240
        val pitches = mutableListOf<Double>()
        var s = start
        while (s + win < end) {
            val f0 = f0Window(pcm, s, win)
            if (f0 != null && f0 in 60.0..300.0) pitches.add(f0)
            s += hop
        }
        if (pitches.size < 3) return null
        pitches.sort()
        return pitches[pitches.size / 2]
    }

    private fun f0Window(pcm: ShortArray, offset: Int, len: Int): Double? {
        var bestLag = -1
        var bestScore = 0.0
        var energy = 0.0
        for (i in 0 until len) {
            val v = pcm[offset + i].toDouble()
            energy += v * v
        }
        if (energy < 1e6) return null
        for (lag in 40..267) {
            var score = 0.0
            for (i in 0 until len - lag) {
                score += pcm[offset + i].toDouble() * pcm[offset + i + lag].toDouble()
            }
            score /= (len - lag)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestScore <= 0) return null
        return SAMPLE_RATE.toDouble() / bestLag
    }

    private fun readWavPcm(file: File): ByteArray? {
        return try {
            val bytes = file.readBytes()
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

    // ================= MODÈLE =================

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
        stopPlayback()
        viewModelScope.launch {
            engine.unloadModel()
        }
        super.onCleared()
    }
}
