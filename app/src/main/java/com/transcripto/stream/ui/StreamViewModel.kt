package com.transcripto.stream.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transcripto.stream.RecordingService
import com.transcripto.stream.RecordingState
import com.transcripto.stream.audio.AudioImporter
import com.transcripto.stream.audio.PcmAudioRecorder
import com.transcripto.stream.audio.WavFileWriter
import com.transcripto.stream.data.CryptoManager
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.SettingsStore
import com.transcripto.stream.export.TranscriptExporter
import com.transcripto.stream.stt.GoogleSpeechEngine
import com.transcripto.stream.stt.ModelCatalog
import com.transcripto.stream.stt.SegmentData
import com.transcripto.stream.stt.StreamResult
import com.transcripto.stream.stt.WhisperModel
import com.transcripto.stream.stt.WhisperStreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

/** Un enregistrement listé : WAV, .enc chiffré, ou .txt seul (transcription Google). */
data class RecordingItem(
    val file: File,
    val baseName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val modifiedAt: Long,
    val encrypted: Boolean,
    val hasAudio: Boolean,
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
        private const val VAD_THRESHOLD = 120.0 // RMS int16 : silence ~<50, parole >300 — seuil volontairement bas pour ne rien perdre
        private const val REC_DIR = "recordings"
        private val REC_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val REC_START_END_FORMAT = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        private val TXT_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        private val HASH_LINE = Regex("SHA-256 \\(PCM\\) : ([0-9a-f]{64})")
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
    // Moteur sélectionné : "google" (moteur système Android — cloud par défaut,
    // local si pack hors-ligne téléchargé) ou "whisper" (100% local + sauvegarde audio).
    // Google ne peut PAS cohabiter avec l'AudioRecord (conflit micro) → pas de WAV en Google.
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

    /** Espace total occupé par les enregistrements (WAV + .txt + .srt), pour les Réglages. */
    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    // ---- Import / export audio ----
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    /** Message ponctuel (snackbar) : résultat d'import/export, consommé puis effacé par l'UI. */
    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    fun clearUiMessage() {
        _uiMessage.value = null
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ---- Nommage proposé à l'arrêt de l'enregistrement ----
    private val _pendingName = MutableStateFlow<File?>(null)
    val pendingName: StateFlow<File?> = _pendingName.asStateFlow()
    private val _pendingNameDefault = MutableStateFlow("")
    val pendingNameDefault: StateFlow<String> = _pendingNameDefault.asStateFlow()

    // ---- Internes ----
    // Le contexte whisper.cpp n'est pas thread-safe : un seul transcribeBuffer à la fois
    // (streaming ET transcription différée passent par ce verrou).
    private val whisperLock = Mutex()
    private val engine = WhisperStreamEngine()
    private var googleEngine: GoogleSpeechEngine? = null
    private var recorder: PcmAudioRecorder? = null
    private var wavWriter: WavFileWriter? = null
    // Empreinte SHA-256 du flux PCM, calculée au fil de l'eau sur le thread audio
    // (valeur probante : l'empreinte est notée dans le .txt de l'enregistrement).
    private var pcmDigest: MessageDigest? = null
    private var digestScratch = ByteArray(0)
    private var activeRecordingFile: File? = null
    private var activeStartTime: Long = 0L
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

    // ---- État des modèles Whisper (déclaré AVANT init : le bloc init et
    // loadWhisperModel y accèdent — l'ordre de déclaration Kotlin est contraignant) ----
    private val _activeModelId = MutableStateFlow(settings.modelId)
    val activeModelId: StateFlow<String> = _activeModelId.asStateFlow()

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    /** id du modèle en cours de téléchargement → progression 0..1. */
    private val _modelDownloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val modelDownloads: StateFlow<Map<String, Float>> = _modelDownloads.asStateFlow()

    /** Octets occupés par les modèles (embarqué extrait + téléchargés), pour les Réglages. */
    private val _modelStorageBytes = MutableStateFlow(0L)
    val modelStorageBytes: StateFlow<Long> = _modelStorageBytes.asStateFlow()

    private var modelDlJob: Job? = null
    private var modelLoadJob: Job? = null

    init {
        loadWhisperModel()
        cleanupExpired()
        refreshRecordings()
        refreshDownloadedModels()
        // Reprise du suivi d'un téléchargement de modèle lancé avant un redémarrage
        val pendingDl = settings.modelDownloadId
        val pendingModel = settings.modelDownloadModel
        if (pendingDl >= 0 && pendingModel.isNotEmpty()) {
            _modelDownloads.value = mapOf(pendingModel to 0f)
            trackModelDownload(pendingModel, pendingDl)
        } else if (pendingDl >= 0 || pendingModel.isNotEmpty()) {
            // État incohérent (anciennes versions à double écriture) : on repart sain
            clearModelDownloadState(deletePartial = true)
        }
    }

    /**
     * Charge (ou recharge) le modèle Whisper actif — embarqué ou téléchargé.
     * Le moteur Google reste utilisable pendant ce temps. En cas de modèle
     * téléchargé absent ou illisible, repli automatique sur le modèle embarqué.
     */
    private fun loadWhisperModel() {
        val previous = modelLoadJob
        _modelState.value = ModelState.Loading
        modelLoadJob = viewModelScope.launch {
            previous?.join() // sérialise : jamais deux chargements natifs en vol
            val model = ModelCatalog.byId(settings.modelId)
            _activeModelId.value = model.id
            val path: String
            if (model.url == null) {
                _loadMessage.value = "Extraction du modèle depuis l'APK…"
                val extracted = ensureModelExtracted()
                if (extracted.isFailure) {
                    _modelState.value = ModelState.Error(
                        "Extraction : ${extracted.exceptionOrNull()?.message ?: "erreur inconnue"}"
                    )
                    return@launch
                }
                path = extracted.getOrThrow()
            } else {
                val f = downloadedModelFile(model)
                if (f == null || !withContext(Dispatchers.IO) { f.exists() }) {
                    // Supprimé ou stockage indisponible → repli sur l'embarqué
                    _uiMessage.value = "Modèle « ${model.label} » introuvable — retour au modèle embarqué"
                    fallbackToEmbedded()
                    return@launch
                }
                path = f.absolutePath
            }
            _loadMessage.value = "Chargement du modèle ${model.label} (${model.approxMb} Mo)…"
            val t0 = System.currentTimeMillis()
            // Sous whisperLock : jamais de déchargement/chargement pendant un transcribeBuffer
            val loaded = whisperLock.withLock {
                engine.unloadModel() // libère l'éventuel modèle précédent
                withTimeoutOrNull(120_000L) {
                    engine.loadModel(path)
                }
            }
            if (loaded == null) {
                // Le JNI n'est pas annulable : arrivé ici, le chargement tardif s'est
                // terminé — on le libère pour ne pas garder ~1,5 Go en état d'erreur.
                whisperLock.withLock { engine.unloadModel() }
                if (model.url != null) {
                    _uiMessage.value = "« ${model.label} » trop long à charger — retour au modèle embarqué"
                    fallbackToEmbedded()
                } else {
                    _modelState.value = ModelState.Error(
                        "Chargement trop long (>120 s). Modèle ou mémoire insuffisante ?"
                    )
                }
                return@launch
            }
            _modelLoadMs.value = System.currentTimeMillis() - t0
            if (loaded.isSuccess) {
                _loadMessage.value = ""
                _modelState.value = ModelState.Ready(engine)
            } else if (model.url != null) {
                // Fichier corrompu ou mémoire insuffisante → repli sur l'embarqué
                _uiMessage.value = "Échec du chargement de « ${model.label} » — retour au modèle " +
                    "embarqué. Supprime-le puis retélécharge-le si ça persiste."
                fallbackToEmbedded()
            } else {
                _modelState.value = ModelState.Error(
                    loaded.exceptionOrNull()?.message ?: "Erreur de chargement"
                )
            }
        }
    }

    /** Repli borné : l'échec du modèle embarqué lui-même finit en ModelState.Error. */
    private fun fallbackToEmbedded() {
        settings.modelId = ModelCatalog.EMBEDDED_ID
        _activeModelId.value = ModelCatalog.EMBEDDED_ID
        loadWhisperModel()
    }

    /** Bouton « Réessayer » de la bannière d'erreur du modèle. */
    fun retryModelLoad() {
        if (_modelState.value is ModelState.Error) loadWhisperModel()
    }

    // ================= NAVIGATION =================

    fun navigate(screenIndex: Int) {
        if (_screen.value == screenIndex) return
        if (screenIndex == 1) refreshRecordings()
        if (screenIndex == 2) refreshDownloadedModels()
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

    /**
     * .txt d'un enregistrement, avec repli sur l'ancienne convention v0.2.x
     * (« base.wav.enc » accompagné d'un « base.wav.txt »).
     */
    private fun transcriptFileFor(audioFile: File): File {
        val txt = RecordingNames.txtSibling(audioFile)
        if (txt.exists()) return txt
        val legacy = File(audioFile.parentFile, audioFile.nameWithoutExtension + ".txt")
        return if (legacy.exists()) legacy else txt
    }

    /** Sélectionne un enregistrement de la liste pour l'écran principal. */
    fun selectRecording(item: RecordingItem) {
        _lastRecording.value = item.file
        activeRecordingFile = null
        // Relit le .txt COMPLET (pas l'aperçu tronqué de la liste)
        val txt = transcriptFileFor(item.file)
        _fileTranscript.value = if (txt.exists()) {
            txt.readText().substringAfter("----\n").trim()
        } else {
            item.transcript
        }
    }

    fun lockNow() {
        if (settings.pinHash.isNotEmpty()) _locked.value = true
    }

    fun enablePin(pin: String) {
        if (pin.length >= 4) {
            settings.setPin(pin)
            // Pas de verrouillage immédiat : l'utilisateur est en train de régler l'app.
            // « Verrouiller maintenant » reste l'action explicite pour verrouiller.
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

    /**
     * Marqueur pendant l'enregistrement : insère « [⭐ mm:ss] » dans le texte en direct
     * pour retrouver un moment clé (décision, chiffre cité, point d'audit) à la relecture.
     */
    fun addMarker() {
        if (!_isStreaming.value) return
        val sec = _elapsedSec.value
        // Un seul token (pas d'espace interne) : le recoupement de fenêtres Whisper
        // filtre les mots contenant ⭐ — un espace couperait le marqueur en deux.
        val marker = "[⭐%02d:%02d]".format(sec / 60, sec % 60)
        validatedText = (validatedText.trim() + " " + marker).trim()
        _liveText.value = validatedText
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
        if (_isStreaming.value) return
        if (_isImporting.value) {
            _lastError.value = "Import audio en cours — réessaie dans un instant"
            return
        }
        // Seul Whisper a besoin du modèle : Google (moteur système) fonctionne
        // dès le lancement, même pendant le chargement ou en cas d'erreur du modèle.
        if (_selectedEngine.value == "whisper" && _modelState.value !is ModelState.Ready) return
        validatedText = ""
        _liveText.value = ""
        _fileTranscript.value = ""
        _lastError.value = null
        _transcriptionCount.value = 0
        _lastWindowText.value = ""
        _isPaused.value = false
        RecordingState.isActive = true
        RecordingState.isPaused = false
        activeStartTime = System.currentTimeMillis()
        startChrono()
        RecordingService.start(appContext)

        // Le WAV est conservé SEULEMENT en mode Whisper : le SpeechRecognizer Google
        // ne peut pas partager le micro avec l'AudioRecord (conflit → aucun texte).
        if (_selectedEngine.value == "whisper") {
            if (!startAudioCapture()) {
                RecordingService.stop(appContext)
                RecordingState.isActive = false
                chronoJob?.cancel()
                chronoJob = null
                return
            }
        }
        _isStreaming.value = true

        if (_selectedEngine.value == "google") {
            if (!startGoogleStreaming()) stopStreaming()
        } else {
            startWhisperStreaming()
        }
    }

    /** Crée le fichier WAV + le recorder (commun aux deux moteurs). */
    private fun startAudioCapture(): Boolean {
        writePos = 0
        filled = false
        windowStart = 0
        activeStartTime = System.currentTimeMillis()

        val recDir = recordingsDir()
        // Contrôle d'espace : mieux vaut refuser avant la réunion qu'échouer en silence pendant
        val usableMb = recDir.usableSpace / (1024L * 1024L)
        if (usableMb < 10) {
            _lastError.value = "Stockage plein ($usableMb Mo libres) — libère de l'espace avant d'enregistrer"
            return false
        }
        if (usableMb in 10 until 200) {
            _lastError.value = "Stockage presque plein ($usableMb Mo libres) — l'enregistrement peut s'interrompre"
        }
        val recFile = File(recDir, "rec_${REC_DATE_FORMAT.format(Date())}.wav")
        val writer = try {
            WavFileWriter(recFile)
        } catch (e: Exception) {
            _lastError.value = "Impossible de créer le fichier audio : ${e.message}"
            return false
        }
        wavWriter = writer
        activeRecordingFile = recFile
        _lastRecording.value = recFile
        pcmDigest = try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: Exception) {
            null
        }

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
                writer.write(buf, n)
                updateDigest(buf, n)
                // Le ring buffer ne sert qu'au moteur Whisper (transcription locale)
                if (_selectedEngine.value == "whisper") appendSamples(buf, n)
            }
        }
        if (!rec.start()) {
            _lastError.value = "Impossible de démarrer l'enregistrement (micro ?)"
            try { writer.close() } catch (_: Exception) {}
            wavWriter = null
            activeRecordingFile = null
            _lastRecording.value = null
            return false
        }
        recorder = rec
        return true
    }

    /** Alimente l'empreinte SHA-256 du flux PCM (appelé depuis le thread audio). */
    private fun updateDigest(buf: ShortArray, n: Int) {
        val digest = pcmDigest ?: return
        if (digestScratch.size < n * 2) digestScratch = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = buf[i].toInt()
            digestScratch[2 * i] = (v and 0xFF).toByte()
            digestScratch[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        digest.update(digestScratch, 0, n * 2)
    }

    private fun startGoogleStreaming(): Boolean {
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
        if (!g.start()) return false
        googleEngine = g
        return true
    }

    private fun startWhisperStreaming() {
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
                val res = whisperLock.withLock {
                    engine.transcribeBuffer(pcm, settings.language, prompt)
                }
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

        if (raw != null && raw.exists()) {
            // Enregistrements quasi vides (< 1 s) : on ne garde pas un WAV de 44 octets
            if (raw.length() < 1000L) {
                raw.delete()
                _lastRecording.value = null
                refreshRecordings()
                return
            }
            // Nom par défaut : date + heure de début - heure de fin (ex: 20260809_1435-1530)
            val defaultName = buildDefaultName(raw)
            val renamed = File(raw.parentFile, "$defaultName.wav")
            if (!renamed.exists() && raw.renameTo(renamed)) {
                _lastRecording.value = renamed
            }
            val finalFile = _lastRecording.value ?: raw

            // Empreinte SHA-256 du flux PCM, finalisée à l'arrêt (recorder déjà stoppé/join)
            val pcmHash = pcmDigest?.digest()?.joinToString("") { "%02x".format(it) }
            pcmDigest = null
            // .txt auto à côté du WAV — rien ne se perd, même sans transcription différée
            if (_liveText.value.isNotBlank() || pcmHash != null) {
                writeTranscriptFile(finalFile, _liveText.value, _elapsedSec.value * 1000, pcmHash)
            }
            // Chiffrement optionnel du WAV
            _lastRecording.value = maybeEncrypt(finalFile)
            // Proposer de donner un nom (le défaut date-début-fin est déjà appliqué)
            _pendingName.value = _lastRecording.value
            _pendingNameDefault.value = defaultName
        } else if (_selectedEngine.value == "google" && _liveText.value.isNotBlank()) {
            // Mode Google : pas de WAV (conflit micro), mais la transcription ne se perd
            // plus — sauvegardée en entrée « texte seul », visible dans la liste.
            var name = buildDefaultName(null)
            var txtFile = File(recordingsDir(), "$name.txt")
            var suffix = 2
            while (txtFile.exists()) { // deux enregistrements dans la même minute
                name = "${buildDefaultName(null)} ($suffix)"
                txtFile = File(recordingsDir(), "$name.txt")
                suffix++
            }
            writeTranscriptFile(txtFile, _liveText.value, _elapsedSec.value * 1000)
            if (txtFile.exists()) {
                _lastRecording.value = txtFile
                _fileTranscript.value = _liveText.value
                _pendingName.value = txtFile
                _pendingNameDefault.value = name
            }
        }
        refreshRecordings()
    }

    /** Chiffre un WAV si le réglage est actif ; retourne le fichier final (.wav ou .wav.enc). */
    private fun maybeEncrypt(file: File): File {
        if (!settings.encryptWav || !file.exists() || file.extension != "wav") return file
        val enc = File(file.parentFile, file.nameWithoutExtension + ".wav.enc")
        return if (CryptoManager.encryptFile(file, enc)) {
            file.delete()
            enc
        } else {
            _lastError.value = "Chiffrement impossible — WAV conservé en clair"
            file
        }
    }

    /** Nom par défaut : 20260809_1435-1530 (date, heure début, heure fin). */
    private fun buildDefaultName(file: File?): String {
        val start = Date(activeStartTime)
        val end = Date()
        return "${REC_START_END_FORMAT.format(start)}-${REC_START_END_FORMAT.format(end)}"
    }

    fun confirmPendingName(newName: String) {
        val f = _pendingName.value ?: return
        _pendingName.value = null
        renameFile(f, newName)
        refreshRecordings()
    }

    /**
     * Renommage commun (dialog de fin d'enregistrement + liste) : conserve le
     * suffixe (.wav / .wav.enc / .txt) et renomme les fichiers frères .txt/.srt.
     */
    private fun renameFile(f: File, newName: String): Boolean {
        val name = RecordingNames.sanitize(newName)
        if (name.isEmpty() || name == RecordingNames.baseName(f.name)) return false
        val dest = RecordingNames.renamed(f, name)
        // Collision sur le nom de base, tous types confondus (.wav, .wav.enc, .txt seul) :
        // un renameTo POSIX écraserait silencieusement la cible homonyme.
        val clash = f.parentFile?.listFiles()?.any { other ->
            other != f && RecordingNames.baseName(other.name) == name
        } == true
        if (clash || dest.exists()) {
            _lastError.value = "Un enregistrement porte déjà ce nom"
            return false
        }
        val oldTxt = RecordingNames.txtSibling(f)
        val oldSrt = RecordingNames.srtSibling(f)
        if (!f.renameTo(dest)) {
            _lastError.value = "Renommage impossible"
            return false
        }
        if (oldTxt.exists()) oldTxt.renameTo(RecordingNames.txtSibling(dest))
        if (oldSrt.exists()) oldSrt.renameTo(RecordingNames.srtSibling(dest))
        if (_lastRecording.value == f) _lastRecording.value = dest
        return true
    }

    fun dismissPendingName() {
        _pendingName.value = null
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
        if (!RecordingNames.isAudio(file.name)) return // entrée texte seul : rien à écouter
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
        if (!RecordingNames.isAudio(file.name)) return
        val engineRef = (modelState.value as? ModelState.Ready)?.engine
        if (engineRef == null) {
            _lastError.value = "Modèle Whisper non chargé — transcription différée indisponible"
            return
        }
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
                val res = whisperLock.withLock {
                    engineRef.transcribeBuffer(pcm, settings.language, settings.vocabularyList.joinToString(", "))
                }
                if (res.error != null) {
                    _lastError.value = res.error
                } else {
                    val (text, srt) = withContext(Dispatchers.IO) {
                        buildSpeakerMarkedTranscript(pcm, res) to TranscriptExporter.buildSrt(res.segments)
                    }
                    _fileTranscript.value = text
                    // Sauvegarde .txt auto + sous-titres .srt à côté du fichier
                    val durationMs = pcm.size / 32L // 16 kHz × 2 octets = 32 octets/ms
                    withContext(Dispatchers.IO) {
                        writeTranscriptFile(file, text, durationMs)
                        if (srt.isNotBlank()) {
                            try {
                                RecordingNames.srtSibling(file).writeText(srt)
                            } catch (e: Exception) {
                                Log.e(TAG, "écriture SRT : ${e.message}")
                            }
                        }
                    }
                    refreshRecordings()
                }
            }
            _isTranscribingFile.value = false
        }
    }

    /**
     * Durée en ms sans déchiffrement (la liste ne déchiffre plus chaque .enc à chaque
     * rafraîchissement) : header WAV en clair ; taille moins l'overhead AES-GCM
     * (IV 12 + tag 16) et le header WAV (44) pour les .enc ; en-tête « Durée : »
     * du .txt pour les entrées texte seul.
     */
    private fun durationMsOf(file: File): Long = when {
        file.name.endsWith(".enc") ->
            ((file.length() - 12 - 16 - 44).coerceAtLeast(0) * 1000L) / 32000L
        file.name.endsWith(".txt") ->
            try {
                TranscriptExporter.parseDurationMs(file.readText())
            } catch (e: Exception) {
                0L
            }
        else -> readWavDuration(file)
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
    fun writeTranscriptFile(audioFile: File, text: String, durationMs: Long, sha256: String? = null) {
        try {
            val txt = RecordingNames.txtSibling(audioFile)
            // L'empreinte PCM n'est calculée qu'à l'enregistrement : on la préserve
            // quand le .txt est réécrit (transcription différée, édition manuelle).
            val hash = sha256 ?: if (txt.exists()) {
                HASH_LINE.find(txt.readText())?.groupValues?.get(1)
            } else {
                null
            }
            val date = if (audioFile.exists()) audioFile.lastModified() else System.currentTimeMillis()
            val sb = StringBuilder()
            sb.append("Transcripto Stream\n")
            sb.append("Date : ").append(TXT_DATE_FORMAT.format(Date(date))).append("\n")
            sb.append("Durée : ").append(formatHms(durationMs)).append("\n")
            if (audioFile.extension == "enc") sb.append("Chiffré : oui\n")
            if (hash != null) sb.append("SHA-256 (PCM) : ").append(hash).append("\n")
            sb.append("----\n\n")
            sb.append(text)
            txt.writeText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "writeTranscriptFile: ${e.message}")
        }
    }

    fun formatHms(ms: Long): String = TranscriptExporter.formatHms(ms)

    /** Sauvegarde une transcription corrigée à la main dans le .txt de l'enregistrement. */
    fun saveEditedTranscript(newText: String) {
        val file = _lastRecording.value ?: return
        _fileTranscript.value = newText
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                writeTranscriptFile(file, newText, durationMsOf(file))
            }
            refreshRecordings()
        }
    }

    /**
     * Reconstruction avec horodatage [mm:ss], attribution [Intervenant N]
     * (variation de pitch F0 > 20 %), pauses > 1,2 s, et bloc « temps de parole »
     * par intervenant en fin de transcription (réunions, entretiens d'audit).
     */
    private fun buildSpeakerMarkedTranscript(pcm: ByteArray, res: StreamResult): String {
        val segments = res.segments
        if (segments.isEmpty()) return res.fullText.trim()
        val shorts = byteArrayToShorts(pcm)
        val speakerIds = detectSpeakers(shorts, segments)
        val sb = StringBuilder()
        var currentSpeaker = 0
        for ((i, seg) in segments.withIndex()) {
            if (seg.text.isBlank()) continue
            if (i > 0) {
                val gap = seg.startMs - segments[i - 1].endMs
                if (gap > 1200) {
                    sb.append(" [pause ${gap / 1000}s] ")
                }
            }
            if (speakerIds[i] != currentSpeaker) {
                currentSpeaker = speakerIds[i]
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append("[Intervenant $currentSpeaker] ")
            }
            if (settings.useTimestamps) {
                sb.append("[").append(formatClock(seg.startMs)).append("] ")
            }
            sb.append(seg.text.trim()).append(" ")
        }
        // Stats sur les seuls segments affichés (les blancs sautés plus haut fausseraient les %)
        val kept = segments.indices.filter { segments[it].text.isNotBlank() }
        val stats = TranscriptExporter.buildSpeakingStats(
            kept.map { segments[it] },
            kept.map { speakerIds[it] },
        )
        if (stats.isNotBlank()) {
            sb.append("\n\n").append(stats)
        }
        return sb.toString().trim()
    }

    /**
     * Attribue un intervenant (1 ou 2, alternance) à chaque segment via le pitch
     * médian F0 — estimation adaptée aux entretiens à deux voix.
     */
    private fun detectSpeakers(shorts: ShortArray, segments: List<SegmentData>): List<Int> {
        if (shorts.isEmpty()) return List(segments.size) { 1 }
        val ids = ArrayList<Int>(segments.size)
        var current = 1
        var prevF0: Double? = null
        for (seg in segments) {
            val s0 = ((seg.startMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, shorts.size - 1)
            val s1 = ((seg.endMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(s0 + 1, shorts.size)
            val f0 = averagePitch(shorts, s0, s1)
            if (prevF0 != null && f0 != null) {
                val ratio = abs(f0 - prevF0) / min(f0, prevF0)
                if (ratio > 0.20) current = if (current == 1) 2 else 1
            }
            prevF0 = f0 ?: prevF0
            ids.add(current)
        }
        return ids
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

    /** Intent de partage pour le dernier enregistrement : texte + .txt + audio + .srt. */
    suspend fun buildEmailIntent(): Intent? = withContext(Dispatchers.IO) {
        val file = _lastRecording.value ?: return@withContext null
        buildShareIntent(file, _fileTranscript.value.ifBlank { _liveText.value })
    }

    /** Partage direct d'un élément de la liste, sans passer par l'écran principal. */
    suspend fun buildShareIntentFor(item: RecordingItem): Intent? = withContext(Dispatchers.IO) {
        val txt = transcriptFileFor(item.file)
        val text = if (txt.exists()) {
            txt.readText().substringAfter("----\n").trim()
        } else {
            item.transcript
        }
        buildShareIntent(item.file, text)
    }

    /**
     * Construit l'intent de partage : transcription en corps de message, .txt joint,
     * audio (déchiffré à la volée) si l'entrée en a, sous-titres .srt s'ils existent.
     */
    private fun buildShareIntent(file: File, transcriptText: String): Intent? {
        return try {
            val base = RecordingNames.baseName(file.name)
            val exportDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val txt = File(exportDir, "$base.txt")
            txt.writeText(
                if (transcriptText.isBlank()) "Transcripto Stream — enregistrement sans transcription\n" else transcriptText
            )

            val attachments = arrayListOf<Uri>()
            attachments.add(
                FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", txt)
            )
            if (RecordingNames.isAudio(file.name)) {
                val clear = resolvedAudioFile(file)
                if (clear != null) {
                    attachments.add(
                        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", clear)
                    )
                }
            }
            val srt = RecordingNames.srtSibling(file)
            if (srt.exists()) {
                attachments.add(
                    FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", srt)
                )
            }

            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(""))
                putExtra(Intent.EXTRA_SUBJECT, "Transcription $base")
                putExtra(Intent.EXTRA_TEXT, transcriptText)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "buildShareIntent: ${e.message}")
            null
        }
    }

    // ================= IMPORT / EXPORT AUDIO =================

    /**
     * Importe un audio externe (partagé depuis WhatsApp, un dictaphone, le
     * gestionnaire de fichiers…) : décodage vers WAV 16 kHz mono, chiffrement
     * selon le réglage, puis sélection pour transcription différée.
     */
    fun importAudio(uri: Uri) {
        if (_isImporting.value) {
            _uiMessage.value = "Un import est déjà en cours"
            return
        }
        if (_isStreaming.value) {
            _uiMessage.value = "Import impossible pendant un enregistrement"
            return
        }
        if (_isTranscribingFile.value) {
            _uiMessage.value = "Transcription en cours — réessaie quand elle est terminée"
            return
        }
        viewModelScope.launch {
            _isImporting.value = true
            _importProgress.value = 0f
            val (err, file) = withContext(Dispatchers.IO) {
                val base = importBaseName(uri)
                // Décodage vers un temporaire du cache : pas de WAV partiel (et en clair)
                // dans recordings/ si le process meurt en plein import
                val tmp = File(appContext.cacheDir, "import_${System.currentTimeMillis()}.wav")
                var lastPct = -1
                val e = AudioImporter.importToWav(appContext, uri, tmp) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != lastPct) { // limite les recompositions à 1 par % affiché
                        lastPct = pct
                        _importProgress.value = pct / 100f
                    }
                }
                if (e != null) {
                    tmp.delete()
                    e to null
                } else {
                    val dest = File(recordingsDir(), "$base.wav")
                    val moved = tmp.renameTo(dest) || try {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                        true
                    } catch (ex: Exception) {
                        false
                    }
                    if (!moved) {
                        tmp.delete()
                        "Impossible d'enregistrer le fichier importé" to null
                    } else {
                        null to maybeEncrypt(dest)
                    }
                }
            }
            if (err != null || file == null) {
                _uiMessage.value = err ?: "Import impossible"
            } else {
                _lastRecording.value = file
                activeRecordingFile = null
                _fileTranscript.value = ""
                _liveText.value = ""
                _uiMessage.value =
                    "« ${RecordingNames.baseName(file.name)} » importé — appuie sur Transcrire"
                refreshRecordings()
                navigate(0)
            }
            _importProgress.value = null
            _isImporting.value = false
        }
    }

    /** Nom de base unique pour un import, dérivé du nom d'origine du fichier. */
    private fun importBaseName(uri: Uri): String {
        val display = try {
            appContext.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        var base = RecordingNames.sanitize(display?.substringBeforeLast('.') ?: "")
        if (base.isEmpty()) base = "import_${REC_DATE_FORMAT.format(Date())}"
        val taken = recordingsDir().listFiles()
            ?.map { RecordingNames.baseName(it.name) }
            ?.toSet() ?: emptySet()
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    /** Copie l'audio (déchiffré à la volée) vers l'emplacement choisi via SAF. */
    fun exportAudio(file: File, destUri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // Temp de déchiffrement propre à l'export : ne pas réutiliser le temp
                // « _dec » que le partage peut encore servir via FileProvider
                val clear = if (file.extension == "enc") {
                    CryptoManager.decryptToTemp(
                        file, appContext.cacheDir, "_exp_${System.currentTimeMillis()}"
                    )
                } else {
                    file
                }
                if (clear == null || !clear.exists()) return@withContext false
                try {
                    // « wt » tronque un document existant ; le mode par défaut « w » des
                    // DocumentsProviders ne tronque pas — remplacer un WAV plus long
                    // laisserait des octets résiduels après le flux copié
                    val out = try {
                        appContext.contentResolver.openOutputStream(destUri, "wt")
                    } catch (e: Exception) {
                        appContext.contentResolver.openOutputStream(destUri)
                    } ?: return@withContext false
                    out.use { o ->
                        clear.inputStream().use { it.copyTo(o, 64 * 1024) }
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "exportAudio: ${e.message}")
                    false
                } finally {
                    if (clear != file) clear.delete()
                }
            }
            _uiMessage.value = if (ok) {
                "Audio « ${RecordingNames.baseName(file.name)} » exporté"
            } else {
                "Export impossible"
            }
        }
    }

    // ================= MODÈLES WHISPER TÉLÉCHARGEABLES =================

    /** Dossier des modèles téléchargés (stockage externe applicatif, requis par DownloadManager). */
    private fun downloadedModelFile(model: WhisperModel): File? {
        val dir = appContext.getExternalFilesDir("models") ?: return null
        return File(dir, model.fileName)
    }

    fun refreshDownloadedModels() {
        viewModelScope.launch {
            val (set, bytes) = withContext(Dispatchers.IO) {
                val downloading = settings.modelDownloadModel
                val downloaded = ModelCatalog.MODELS
                    .filter { it.url != null && it.id != downloading }
                    .filter { downloadedModelFile(it)?.exists() == true }
                    .map { it.id }
                    .toSet()
                val externalBytes = appContext.getExternalFilesDir("models")
                    ?.listFiles()?.sumOf { it.length() } ?: 0L
                val embeddedBytes = File(appContext.filesDir, "models")
                    .listFiles()?.sumOf { it.length() } ?: 0L
                downloaded to (externalBytes + embeddedBytes)
            }
            _downloadedModels.value = set
            _modelStorageBytes.value = bytes
        }
    }

    /** Lance le téléchargement d'un modèle du catalogue (un seul à la fois). */
    fun downloadModel(id: String) {
        val model = ModelCatalog.byId(id)
        if (model.url == null) return
        if (settings.modelDownloadId >= 0) {
            _uiMessage.value = "Un téléchargement de modèle est déjà en cours"
            return
        }
        if (id in _downloadedModels.value) return
        val dest = downloadedModelFile(model)
        if (dest == null) {
            _uiMessage.value = "Stockage indisponible pour les modèles"
            return
        }
        val usableMb = (dest.parentFile ?: appContext.filesDir).usableSpace / (1024L * 1024L)
        if (usableMb < model.approxMb + 100) {
            _uiMessage.value = "Espace insuffisant ($usableMb Mo libres, ${model.approxMb} Mo requis)"
            return
        }
        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            _uiMessage.value = "Téléchargement indisponible sur cet appareil"
            return
        }
        dest.parentFile?.mkdirs()
        dest.delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Modèle Whisper — ${model.label}")
            .setDescription("Téléchargement (${model.approxMb} Mo)…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(dest))
            .setAllowedOverMetered(true)
        val dlId = try {
            dm.enqueue(request)
        } catch (e: Exception) {
            _uiMessage.value = "Téléchargement impossible : ${e.message}"
            return
        }
        settings.setModelDownload(dlId, id)
        _modelDownloads.value = mapOf(id to 0f)
        trackModelDownload(id, dlId)
    }

    fun cancelModelDownload() {
        if (settings.modelDownloadId < 0) return
        modelDlJob?.cancel()
        modelDlJob = null
        (appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)
            ?.remove(settings.modelDownloadId)
        clearModelDownloadState(deletePartial = true)
        _uiMessage.value = "Téléchargement annulé"
    }

    private fun clearModelDownloadState(deletePartial: Boolean) {
        val id = settings.modelDownloadModel
        if (deletePartial && id.isNotEmpty()) {
            downloadedModelFile(ModelCatalog.byId(id))?.delete()
        }
        settings.setModelDownload(-1L, "")
        _modelDownloads.value = emptyMap()
        refreshDownloadedModels()
    }

    /** Suit la progression DownloadManager jusqu'au succès ou à l'échec. */
    private fun trackModelDownload(modelId: String, downloadId: Long) {
        modelDlJob?.cancel()
        modelDlJob = viewModelScope.launch {
            val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return@launch
            while (isActive) {
                var found = false
                var status = -1
                var reason = -1
                var progress = 0f
                dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
                    if (c.moveToFirst()) {
                        found = true
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        val done = c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        val total = c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
                when {
                    found && status == DownloadManager.STATUS_SUCCESSFUL -> {
                        clearModelDownloadState(deletePartial = false)
                        _uiMessage.value = "Modèle téléchargé — appuie sur « Activer » pour l'utiliser"
                        break
                    }
                    !found || status == DownloadManager.STATUS_FAILED -> {
                        // remove() : sans lui, l'entrée FAILED garde le chemin de
                        // destination et sa purge future effacerait un modèle
                        // re-téléchargé au même endroit
                        dm.remove(downloadId)
                        clearModelDownloadState(deletePartial = true)
                        _uiMessage.value = if (!found) {
                            "Téléchargement du modèle annulé" // retiré via la notification système
                        } else {
                            "Téléchargement du modèle échoué" +
                                if (reason >= 0) " (code $reason)" else ""
                        }
                        break
                    }
                    else -> {
                        _modelDownloads.value = mapOf(modelId to progress)
                    }
                }
                delay(750)
            }
        }
    }

    /** Change le modèle Whisper actif et le recharge. */
    fun selectModel(id: String) {
        if (id == _activeModelId.value && _modelState.value is ModelState.Ready) return
        if (_modelState.value is ModelState.Loading) {
            _uiMessage.value = "Un modèle est déjà en cours de chargement"
            return
        }
        if (_isStreaming.value || _isTranscribingFile.value || _isImporting.value) {
            _uiMessage.value = "Changement de modèle impossible pendant une opération en cours"
            return
        }
        val model = ModelCatalog.byId(id)
        viewModelScope.launch {
            val available = model.url == null ||
                withContext(Dispatchers.IO) { downloadedModelFile(model)?.exists() == true }
            if (!available) {
                _uiMessage.value = "Télécharge d'abord ce modèle"
                return@launch
            }
            settings.modelId = model.id
            _activeModelId.value = model.id
            loadWhisperModel()
        }
    }

    /** Supprime un modèle téléchargé (bascule sur l'embarqué s'il était actif). */
    fun deleteModel(id: String) {
        val model = ModelCatalog.byId(id)
        if (model.url == null) return
        if (_modelState.value is ModelState.Loading) {
            _uiMessage.value = "Attends la fin du chargement du modèle"
            return
        }
        if (_isStreaming.value || _isTranscribingFile.value || _isImporting.value) {
            _uiMessage.value = "Suppression impossible pendant une opération en cours"
            return
        }
        if (_activeModelId.value == id) {
            settings.modelId = ModelCatalog.EMBEDDED_ID
            _activeModelId.value = ModelCatalog.EMBEDDED_ID
            loadWhisperModel()
        }
        viewModelScope.launch {
            // Suppression sûre même si le modèle vient d'être déchargé : sous Linux,
            // l'inode d'un fichier encore mmappé survit jusqu'à sa fermeture.
            withContext(Dispatchers.IO) { downloadedModelFile(model)?.delete() }
            refreshDownloadedModels()
            _uiMessage.value = "Modèle « ${model.label} » supprimé"
        }
    }

    // ================= LISTE DES ENREGISTREMENTS =================

    fun refreshRecordings() {
        viewModelScope.launch {
            val (items, totalBytes) = withContext(Dispatchers.IO) {
                val files = recordingsDir().listFiles()?.toList() ?: emptyList()
                val audio = files.filter { RecordingNames.isAudio(it.name) }
                val audioBases = audio.map { RecordingNames.baseName(it.name) }.toSet()
                // .txt hérités v0.2.x (« base.wav.txt » à côté de « base.wav.enc »)
                val legacyTxtNames = audio.map { it.nameWithoutExtension + ".txt" }.toSet()
                // Entrées « texte seul » : .txt sans audio associé (transcriptions Google)
                val textOnly = files.filter {
                    RecordingNames.isTextOnly(it.name) &&
                        RecordingNames.baseName(it.name) !in audioBases &&
                        it.name !in legacyTxtNames
                }
                val list = (audio + textOnly).map { f ->
                    val txt = transcriptFileFor(f)
                    val transcript = if (txt.exists()) {
                        txt.readText().substringAfter("----\n").trim().take(200)
                    } else ""
                    RecordingItem(
                        file = f,
                        baseName = RecordingNames.baseName(f.name),
                        sizeBytes = f.length(),
                        durationMs = durationMsOf(f),
                        modifiedAt = f.lastModified(),
                        encrypted = f.name.endsWith(".enc"),
                        hasAudio = RecordingNames.isAudio(f.name),
                        transcript = transcript,
                    )
                }.sortedByDescending { it.modifiedAt }
                list to files.sumOf { it.length() }
            }
            _recordings.value = items
            _storageBytes.value = totalBytes
        }
    }

    fun renameRecording(old: RecordingItem, newBaseName: String) {
        renameFile(old.file, newBaseName)
        refreshRecordings()
    }

    /** Supprime un enregistrement et ses fichiers frères (.txt, .srt + .txt hérité v0.2.x). */
    private fun deleteWithSiblings(f: File) {
        f.delete()
        RecordingNames.txtSibling(f).delete()
        RecordingNames.srtSibling(f).delete()
        // Ancienne convention (v0.2.x) : « base.wav.enc » avait parfois « base.wav.txt »
        File(f.parentFile, f.nameWithoutExtension + ".txt").delete()
    }

    fun deleteRecording(item: RecordingItem) {
        deleteWithSiblings(item.file)
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
        deleteWithSiblings(f)
        _lastRecording.value = null
        activeRecordingFile = null
        _fileTranscript.value = ""
        refreshRecordings()
    }

    /** Rétention RGPD : supprime les enregistrements plus vieux que N jours (0 = désactivé). */
    fun cleanupExpired() {
        val days = settings.retentionDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val files = recordingsDir().listFiles()?.toList() ?: emptyList()
                val audioBases = files.filter { RecordingNames.isAudio(it.name) }
                    .map { RecordingNames.baseName(it.name) }
                    .toSet()
                files.forEach { f ->
                    val expired = f.lastModified() < cutoff
                    if (!expired) return@forEach
                    if (RecordingNames.isAudio(f.name)) {
                        deleteWithSiblings(f)
                    } else if (
                        RecordingNames.isTextOnly(f.name) &&
                        RecordingNames.baseName(f.name) !in audioBases
                    ) {
                        // Entrée texte seul (Google) : soumise à la même rétention
                        f.delete()
                        RecordingNames.srtSibling(f).delete()
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
        // Les marqueurs [⭐ mm:ss] ne viennent pas de Whisper : on les exclut du
        // suffixe de recoupement, sinon plus rien ne matche et le texte se duplique.
        val words = validatedText.split(" ").filter { it.isNotBlank() && !it.contains("⭐") }
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
            // Filet de sécurité : pas de chunk "data" trouvé → PCM brut après un header de 44 octets
            if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else null
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
