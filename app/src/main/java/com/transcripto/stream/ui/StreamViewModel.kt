package com.transcripto.stream.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transcripto.stream.RecordingService
import com.transcripto.stream.RecordingState
import com.transcripto.stream.audio.AudioImporter
import com.transcripto.stream.audio.LiveSettings
import com.transcripto.stream.audio.LiveTranscriber
import com.transcripto.stream.audio.PcmAudioRecorder
import com.transcripto.stream.audio.PcmDigest
import com.transcripto.stream.audio.PitchDiarizer
import com.transcripto.stream.audio.PlaybackController
import com.transcripto.stream.audio.SileroVad
import com.transcripto.stream.audio.WavPcm
import com.transcripto.stream.audio.WavFileWriter
import com.transcripto.stream.data.BackupCrypto
import com.transcripto.stream.data.Chapter
import com.transcripto.stream.data.CryptoManager
import com.transcripto.stream.data.RecordingItem
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.RecordingRepository
import com.transcripto.stream.data.RenameResult
import com.transcripto.stream.data.StoredSegment
import com.transcripto.stream.data.SpeakerNames
import com.transcripto.stream.data.SegmentsCodec
import com.transcripto.stream.data.SearchHit
import com.transcripto.stream.data.SearchIndex
import com.transcripto.stream.data.SettingsStore
import com.transcripto.stream.data.TextVault
import com.transcripto.stream.export.DocxWriter
import com.transcripto.stream.export.ExportComposer
import com.transcripto.stream.export.ExportDocument
import com.transcripto.stream.export.ExportFormat
import com.transcripto.stream.export.ExportSegment
import com.transcripto.stream.export.PdfWriter
import com.transcripto.stream.export.TranscriptExporter
import com.transcripto.stream.stt.GoogleSpeechEngine
import com.transcripto.stream.stt.ModelCatalog
import com.transcripto.stream.stt.SegmentData
import com.transcripto.stream.summary.AiAnswer
import com.transcripto.stream.summary.AiChaptersResult
import com.transcripto.stream.summary.AiSummaryResult
import com.transcripto.stream.summary.ChapterDetector
import com.transcripto.stream.summary.ClaudeChapters
import com.transcripto.stream.summary.ClaudeQa
import com.transcripto.stream.summary.ClaudeSummarizer
import com.transcripto.stream.summary.LocalSummarizer
import com.transcripto.stream.summary.MarkdownLite
import com.transcripto.stream.summary.QaTurn
import com.transcripto.stream.summary.SummaryInput
import com.transcripto.stream.summary.SummaryTemplates
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
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.Date
import java.util.Locale

sealed interface ModelState {
    data object Loading : ModelState
    data class Ready(val engine: WhisperStreamEngine) : ModelState
    data class Error(val message: String) : ModelState
}

/** Questions posées à l'IA sur un enregistrement : fil d'échanges, en mémoire seulement. */
data class QaState(
    val file: File? = null,
    val turns: List<QaTurn> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
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
        private const val SAMPLE_RATE = LiveTranscriber.SAMPLE_RATE
        private const val SEARCH_DEBOUNCE_MS = 150L
        private const val MODEL_ASSET = "models/ggml-base.bin"
        private val REC_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val REC_START_END_FORMAT = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
        private val SUMMARY_DATE_FORMAT = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)
    }

    val settings = SettingsStore(appContext)

    /** Dossier des enregistrements et de leurs fichiers frères (E/S bloquantes). */
    private val repo = RecordingRepository(File(appContext.filesDir, "recordings"))

    init {
        // Avant toute écriture : les textes suivent le réglage de chiffrement au repos
        TextVault.enabled = settings.encryptTexts
    }

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

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastRecording = MutableStateFlow<File?>(null)
    val lastRecording: StateFlow<File?> = _lastRecording.asStateFlow()

    private val _isTranscribingFile = MutableStateFlow(false)
    val isTranscribingFile: StateFlow<Boolean> = _isTranscribingFile.asStateFlow()

    private val _fileTranscript = MutableStateFlow("")
    val fileTranscript: StateFlow<String> = _fileTranscript.asStateFlow()

    // ---- Liste des enregistrements + recherche ----
    private val _recordings = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordings: StateFlow<List<RecordingItem>> = _recordings.asStateFlow()

    /** Espace total occupé par les enregistrements (WAV + .txt/.srt/.json), pour les Réglages. */
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

    /** Affiche un message ponctuel (snackbar) depuis un écran (ex: résultat de « Vérifier maintenant »). */
    fun showMessage(message: String) {
        _uiMessage.value = message
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Index en mémoire de tous les passages (rien n'est persisté en clair). */
    private val searchIndex = SearchIndex()
    private val _searchHits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchHits: StateFlow<List<SearchHit>> = _searchHits.asStateFlow()
    /** Chemins de TOUS les enregistrements dont un passage correspond (sans le plafond des passages affichés). */
    private val _searchFiles = MutableStateFlow<Set<String>>(emptySet())
    val searchFiles: StateFlow<Set<String>> = _searchFiles.asStateFlow()
    private var searchJob: Job? = null

    private suspend fun runSearch(q: String) {
        val (hits, files) = withContext(Dispatchers.Default) { searchIndex.search(q) to searchIndex.matchingFiles(q) }
        _searchHits.value = hits
        _searchFiles.value = files
    }

    // ---- Écran détail (écran 3) : enregistrement ouvert + lecture synchronisée ----
    private val _detailItem = MutableStateFlow<RecordingItem?>(null)
    val detailItem: StateFlow<RecordingItem?> = _detailItem.asStateFlow()

    /** Lecture (MediaPlayer, position, vitesse, déchiffrement temporaire) — flux relayés ci-dessous. */
    private val playback = PlaybackController(
        cacheDir = appContext.cacheDir,
        scope = viewModelScope,
        speed = { settings.playbackSpeed },
        // Pendant un enregistrement, le haut-parleur serait recapté par le micro
        canStart = { !_isStreaming.value },
        onError = { _lastError.value = it },
    )
    val isPlaying: StateFlow<Boolean> = playback.isPlaying
    val playingFile: StateFlow<File?> = playback.playingFile
    val playbackPositionMs: StateFlow<Long> = playback.positionMs
    val playbackDurationMs: StateFlow<Long> = playback.durationMs

    // ---- Sauvegarde / restauration chiffrée ----
    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    // ---- Nommage proposé à l'arrêt de l'enregistrement ----
    private val _pendingName = MutableStateFlow<File?>(null)
    val pendingName: StateFlow<File?> = _pendingName.asStateFlow()
    private val _pendingNameDefault = MutableStateFlow("")
    val pendingNameDefault: StateFlow<String> = _pendingNameDefault.asStateFlow()

    // ---- Synthèse de fin d'enregistrement (déclaré AVANT init) ----
    private val _summaryBusy = MutableStateFlow(false)
    val summaryBusy: StateFlow<Boolean> = _summaryBusy.asStateFlow()

    /** Incrémenté à chaque .md écrit : les écrans relisent la synthèse. */
    private val _summaryVersion = MutableStateFlow(0)
    val summaryVersion: StateFlow<Int> = _summaryVersion.asStateFlow()

    /** Fichier pour lequel une synthèse est proposée (message de fin d'enregistrement). */
    private val _summaryProposal = MutableStateFlow<File?>(null)
    val summaryProposal: StateFlow<File?> = _summaryProposal.asStateFlow()

    // ---- Dossiers / intervenants (déclaré AVANT init) ----
    private val _dossiers = MutableStateFlow<List<String>>(emptyList())
    val dossiers: StateFlow<List<String>> = _dossiers.asStateFlow()

    private val _dossierFilter = MutableStateFlow<String?>(null)
    val dossierFilter: StateFlow<String?> = _dossierFilter.asStateFlow()

    /** Incrémenté quand le .txt/.json d'un enregistrement change hors transcription (correction). */
    private val _transcriptVersion = MutableStateFlow(0)
    val transcriptVersion: StateFlow<Int> = _transcriptVersion.asStateFlow()

    private val _qa = MutableStateFlow(QaState())
    val qa: StateFlow<QaState> = _qa.asStateFlow()

    private val _chaptersBusy = MutableStateFlow(false)
    val chaptersBusy: StateFlow<Boolean> = _chaptersBusy.asStateFlow()

    // ---- Internes ----
    // Le contexte whisper.cpp n'est pas thread-safe : un seul transcribeBuffer à la fois
    // (streaming ET transcription différée passent par ce verrou).
    private val whisperLock = Mutex()
    private val engine = WhisperStreamEngine()

    /** Transcription en direct (ring buffer, VAD, fenêtres, fusion du texte) — flux relayés ci-dessous. */
    private val transcriber = LiveTranscriber(
        engine = { pcm, language, prompt -> engine.transcribeBuffer(pcm, language, prompt) },
        lock = whisperLock,
        settings = object : LiveSettings {
            override val language: String get() = settings.language
            override val vadSilero: Boolean get() = settings.vadSilero
            override val dictationMode: Boolean get() = settings.dictationMode
        },
        vadFactory = { SileroVad.create(appContext) },
        scope = viewModelScope,
        onError = { _lastError.value = it },
    )
    val liveText: StateFlow<String> = transcriber.liveText
    val transcriptionCount: StateFlow<Int> = transcriber.windowCount
    val lastWindowText: StateFlow<String> = transcriber.lastWindowText

    private var googleEngine: GoogleSpeechEngine? = null
    private var recorder: PcmAudioRecorder? = null
    private var wavWriter: WavFileWriter? = null
    /** Empreinte SHA-256 du flux PCM (valeur probante notée dans le .txt). */
    private var pcmDigest: PcmDigest? = null
    private var activeRecordingFile: File? = null
    private var activeStartTime: Long = 0L
    private var chronoJob: Job? = null

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
        viewModelScope.launch(Dispatchers.IO) {
            // Copies en clair du cache (partage, écoute, export) et temporaires d'écriture :
            // purgés au démarrage — rien ne doit survivre en clair à côté des textes scellés
            purgeClearCopies()
            TextVault.purgeTemporaries(repo.dir)
            // Migration interrompue par une mort du process : reprise (idempotente)
            if (settings.encryptTexts) TextVault.migrate(repo.dir, true)
        }
        if (settings.vadSilero) transcriber.loadVad()
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

    /** Ouvre l'écran détail d'un enregistrement (et le sélectionne pour les actions). */
    fun openDetail(item: RecordingItem) {
        selectRecording(item)
        _detailItem.value = item
        _lastError.value = null // pas d'erreur périmée d'un autre contexte sur la fiche
        navigate(3)
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
        // Pendant un enregistrement, on ne touche pas au fichier actif : l'arrêt doit
        // finaliser le WAV en cours (renommage, .txt, chiffrement), sinon le transcript
        // live serait perdu. La consultation du détail reste possible.
        if (!_isStreaming.value) {
            _lastRecording.value = item.file
            activeRecordingFile = null
        }
        // Relit le .txt COMPLET (pas l'aperçu tronqué de la liste), noms d'intervenants appliqués
        refreshFileTranscript(item.file, item.transcript)
    }

    /** Recharge l'encart transcription pour [file] avec les noms d'intervenants (I/O hors main). */
    private fun refreshFileTranscript(file: File, fallback: String = "") {
        viewModelScope.launch {
            _fileTranscript.value = withContext(Dispatchers.IO) {
                val raw = repo.readTranscript(file)?.let { RecordingRepository.bodyOf(it) } ?: fallback
                SpeakerNames.apply(raw, repo.readMeta(file).speakers)
            }
        }
    }

    // ================= MÉTADONNÉES (intervenants, dossier) =================

    fun setDossierFilter(dossier: String?) {
        _dossierFilter.value = dossier
    }

    /** Nomme (ou dé-nomme si vide) un intervenant ; le .txt garde « [Intervenant N] ». */
    fun setSpeakerName(file: File, speaker: Int, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.setSpeakerName(file, speaker, name) }
            if (_lastRecording.value == file) refreshFileTranscript(file)
            refreshRecordings()
        }
    }

    fun setDossier(file: File, dossier: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.updateMeta(file) { it.copy(dossier = dossier.trim()) } }
            refreshRecordings()
        }
    }

    /** Ajoute un terme au vocabulaire personnalisé (noms propres mal reconnus). */
    fun addVocabularyTerm(term: String): Boolean {
        val t = term.trim().trim(',', ';').trim()
        if (t.isEmpty()) return false
        if (settings.vocabularyList.any { it.equals(t, ignoreCase = true) }) return false
        val current = settings.vocabulary.trim().trimEnd(',', ';').trim()
        settings.vocabulary = if (current.isEmpty()) t else "$current, $t"
        return true
    }

    /**
     * Correction d'un passage sur la fiche : met à jour les segments (.json), puis
     * le .txt (horodatages, intervenants, temps de parole) et le .srt.
     */
    fun updateSegmentText(file: File, index: Int, newText: String) {
        if (_isTranscribingFile.value || _summaryBusy.value || _backupBusy.value) {
            _uiMessage.value = "Opération en cours — réessaie ensuite"
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                repo.updateSegmentText(file, index, newText, settings.useTimestamps)
            }
            if (ok) {
                if (_lastRecording.value == file) refreshFileTranscript(file)
                _transcriptVersion.value = _transcriptVersion.value + 1
                refreshRecordings()
                _uiMessage.value = "Passage corrigé"
            } else {
                _uiMessage.value = "Correction impossible (segments introuvables)"
            }
        }
    }

    fun lockNow() {
        if (settings.pinHash.isNotEmpty()) _locked.value = true
    }

    // ---- Verrouillage automatique / biométrie ----
    private var backgroundedAt = 0L

    /** Activité arrêtée (app en arrière-plan, sélecteur système…) : on note l'instant. */
    fun onAppBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /** Retour au premier plan : verrouille si le délai réglé est dépassé. */
    fun onAppForeground() {
        val since = backgroundedAt
        backgroundedAt = 0L
        if (since == 0L || _locked.value || settings.pinHash.isEmpty()) return
        val minutes = settings.autoLockMinutes
        if (minutes < 0) return
        if (SystemClock.elapsedRealtime() - since >= minutes * 60_000L) _locked.value = true
    }

    fun setAutoLockMinutes(minutes: Int) {
        settings.autoLockMinutes = minutes
    }

    fun setBiometricUnlock(enabled: Boolean) {
        settings.biometricUnlock = enabled
    }

    /** Déverrouillage réussi par BiometricPrompt (empreinte, visage ou code de l'appareil). */
    fun unlockWithBiometrics() {
        _locked.value = false
        _pinError.value = null
    }

    /**
     * Chiffrement des textes au repos : bascule le réglage puis migre tous les fichiers
     * texte (scellés ↔ clairs). Refusé pendant une opération qui écrit des fichiers.
     * Retourne false si la bascule n'a pas eu lieu.
     */
    fun setEncryptTexts(enabled: Boolean): Boolean {
        if (_backupBusy.value || _isTranscribingFile.value || _summaryBusy.value ||
            _isStreaming.value || _isImporting.value
        ) {
            _uiMessage.value = "Opération en cours — réessaie ensuite"
            return false
        }
        settings.encryptTexts = enabled
        TextVault.enabled = enabled
        viewModelScope.launch {
            _backupBusy.value = true // aucune écriture concurrente pendant la migration
            val r = try {
                withContext(Dispatchers.IO) { TextVault.migrate(repo.dir, enabled) }
            } finally {
                _backupBusy.value = false
            }
            _uiMessage.value = buildString {
                append(if (enabled) "Textes chiffrés (${r.rewritten} fichiers)" else "Textes déchiffrés (${r.rewritten} fichiers)")
                if (r.unreadable > 0) append(" — ${r.unreadable} illisible(s) : clé perdue ou fichier altéré")
            }
            refreshRecordings()
        }
        return true
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
        settings.biometricUnlock = false // n'a de sens qu'avec un PIN
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

    fun setMuteWhileListening(enabled: Boolean) {
        settings.muteWhileListening = enabled
    }

    fun setDictationMode(enabled: Boolean) {
        settings.dictationMode = enabled
    }

    fun setPlaybackSpeed(speed: Float) {
        settings.playbackSpeed = speed
        playback.setSpeed(speed)
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        // Les résultats de la requête précédente ne restent pas affichés sous la nouvelle
        _searchHits.value = emptyList()
        _searchFiles.value = emptySet()
        if (q.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS) // pas une recherche par frappe
            runSearch(q)
        }
    }

    /** Résultat de recherche : ouvre la fiche et cale la lecture sur le passage. */
    fun openHit(hit: SearchHit) {
        openDetailForFile(hit.file)
        if (hit.hasAudio && hit.startMs >= 0) playback.playFrom(hit.file, hit.startMs)
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
        transcriber.addMarker(_elapsedSec.value)
    }

    fun togglePause() {
        if (!_isStreaming.value) return
        // Pause/reprise MANUELLE : annule toute reprise automatique en attente
        // (le listener de focus repose le drapeau juste après son togglePause()).
        pausedByFocusLoss = false
        if (_isPaused.value) {
            _isPaused.value = false
            RecordingState.isPaused = false
            googleEngine?.resume()
            transcriber.resume()
        } else {
            _isPaused.value = true
            RecordingState.isPaused = true
            googleEngine?.pause()
            transcriber.pause()
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
        if (_backupBusy.value) {
            _lastError.value = "Sauvegarde en cours — réessaie quand elle est terminée"
            return
        }
        // Seul Whisper a besoin du modèle : Google (moteur système) fonctionne
        // dès le lancement, même pendant le chargement ou en cas d'erreur du modèle.
        if (_selectedEngine.value == "whisper" && _modelState.value !is ModelState.Ready) return
        transcriber.reset()
        _fileTranscript.value = ""
        _lastError.value = null
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
            // Focus audio en mode Whisper seulement : c'est notre AudioRecord qui capte.
            // En mode Google, le SpeechRecognizer gère lui-même le micro — demander le
            // focus en plus déclenche des boucles pause/reprise sur certains OEM.
            requestAudioFocus()
        }
        _isStreaming.value = true

        if (_selectedEngine.value == "google") {
            if (!startGoogleStreaming()) {
                stopStreaming()
            } else if (settings.muteWhileListening) {
                // Le SpeechRecognizer système émet des bips à chaque cycle d'écoute :
                // on coupe les flux sonores concernés le temps de la session.
                muteSystemSounds()
            }
        } else {
            startWhisperStreaming()
        }
    }

    // ---- Résilience audio : pause automatique quand le micro est interrompu ----
    // (appel entrant, autre app qui prend le focus), reprise à la fin.
    private var audioFocusRequest: AudioFocusRequest? = null
    private var pausedByFocusLoss = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Perte DÉFINITIVE : aucun AUDIOFOCUS_GAIN ne suivra — on ne promet
                // pas une reprise automatique qui n'arrivera jamais.
                if (_isStreaming.value && !_isPaused.value) {
                    togglePause()
                    _lastError.value = "Micro interrompu — enregistrement en pause, appuie sur Reprendre"
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_isStreaming.value && !_isPaused.value) {
                    togglePause() // remet pausedByFocusLoss à false : le drapeau se pose après
                    pausedByFocusLoss = true
                    _lastError.value = "Micro interrompu (appel en cours ?) — reprise automatique à la fin"
                }
            }
            // CAN_DUCK (bip de notification…) : les autres apps baissent le volume,
            // le micro n'est pas préempté — on continue d'enregistrer sans coupure.
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (_isStreaming.value && _isPaused.value && pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    togglePause()
                    _lastError.value = null
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            am.requestAudioFocus(request)
            audioFocusRequest = request
        } catch (e: Exception) {
            Log.e(TAG, "requestAudioFocus : ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        audioFocusRequest = null
        pausedByFocusLoss = false
        try {
            (appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
                ?.abandonAudioFocusRequest(request)
        } catch (e: Exception) {
            Log.e(TAG, "abandonAudioFocus : ${e.message}")
        }
    }

    // ---- Écoute silencieuse : coupe les bips du SpeechRecognizer Google ----
    // On mémorise les flux réellement coupés pour ne rétablir que ceux-là.
    private val mutedStreams = mutableListOf<Int>()

    private fun muteSystemSounds() {
        if (mutedStreams.isNotEmpty()) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        for (stream in intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
        )) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                mutedStreams.add(stream)
            } catch (e: Exception) {
                // NOTIFICATION/SYSTEM peuvent exiger l'accès « Ne pas déranger » :
                // on coupe ce qu'on peut, sans planter
            }
        }
    }

    private fun unmuteSystemSounds() {
        if (mutedStreams.isEmpty()) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            for (stream in mutedStreams) {
                try {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "unmute stream $stream : ${e.message}")
                }
            }
        }
        mutedStreams.clear()
    }

    /** Crée le fichier WAV + le recorder (commun aux deux moteurs). */
    private fun startAudioCapture(): Boolean {
        activeStartTime = System.currentTimeMillis()

        val recDir = repo.dir
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
        val digest = PcmDigest()
        pcmDigest = digest

        val rec = PcmAudioRecorder(
            SAMPLE_RATE,
            onStopped = {
                // La capture est morte sans stop() (micro préempté, erreur matérielle)
                viewModelScope.launch {
                    if (_isStreaming.value && _selectedEngine.value == "whisper") {
                        _lastError.value = "Micro perdu — enregistrement arrêté et sauvegardé"
                        stopStreaming()
                    }
                }
            },
        ) { buf, n ->
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
                digest.update(buf, n)
                // Le ring buffer ne sert qu'au moteur Whisper (transcription locale)
                if (_selectedEngine.value == "whisper") transcriber.feed(buf, n)
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

    private fun startGoogleStreaming(): Boolean {
        val g = GoogleSpeechEngine(
            appContext,
            onPartial = { partial -> transcriber.showPartial(partial) },
            onFinal = { final -> transcriber.commitFinal(final) },
            onError = { msg -> _lastError.value = msg },
            language = settings.language,
            hints = settings.vocabularyList,
        )
        if (!g.start()) return false
        googleEngine = g
        return true
    }

    private fun startWhisperStreaming() {
        transcriber.start(settings.vocabularyList.joinToString(", "))
    }

    fun stopStreaming() {
        _isStreaming.value = false
        _isPaused.value = false
        RecordingState.isActive = false
        RecordingState.isPaused = false
        unmuteSystemSounds()
        abandonAudioFocus()
        RecordingService.stop(appContext)
        chronoJob?.cancel()
        chronoJob = null
        googleEngine?.stop()
        googleEngine = null
        recorder?.stop()
        recorder = null
        transcriber.stop()
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
            val pcmHash = pcmDigest?.hex()
            pcmDigest = null
            // .txt auto à côté du WAV — rien ne se perd, même sans transcription différée
            if (liveText.value.isNotBlank() || pcmHash != null) {
                writeTranscript(finalFile, liveText.value, _elapsedSec.value * 1000, pcmHash)
            }
            // L'encart transcription doit refléter CE fichier : consulter le détail d'un
            // autre enregistrement pendant la captation laissait ici son texte —
            // « Corriger » aurait alors écrasé le .txt tout juste écrit avec ce texte-là.
            _fileTranscript.value = liveText.value
            // Chiffrement optionnel du WAV
            _lastRecording.value = maybeEncrypt(finalFile)
            // Proposer de donner un nom (le défaut date-début-fin est déjà appliqué)
            _pendingName.value = _lastRecording.value
            _pendingNameDefault.value = defaultName
        } else if (_selectedEngine.value == "google" && liveText.value.isNotBlank()) {
            // Mode Google : pas de WAV (conflit micro), mais la transcription ne se perd
            // plus — sauvegardée en entrée « texte seul », visible dans la liste.
            var name = buildDefaultName(null)
            var txtFile = File(repo.dir, "$name.txt")
            var suffix = 2
            while (txtFile.exists()) { // deux enregistrements dans la même minute
                name = "${buildDefaultName(null)} ($suffix)"
                txtFile = File(repo.dir, "$name.txt")
                suffix++
            }
            writeTranscript(txtFile, liveText.value, _elapsedSec.value * 1000)
            if (txtFile.exists()) {
                _lastRecording.value = txtFile
                _fileTranscript.value = liveText.value
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

    fun confirmPendingName(newName: String, dossier: String = "", template: String = "") {
        val f = _pendingName.value ?: return
        _pendingName.value = null
        // Cible du .meta : le fichier renommé si le renommage a abouti, sinon l'original
        val target = renameFile(f, newName) ?: f
        viewModelScope.launch {
            if (dossier.isNotBlank() || template.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    repo.updateMeta(target) { meta ->
                        meta.copy(
                            dossier = dossier.trim().ifBlank { meta.dossier },
                            template = template.trim().ifBlank { meta.template },
                        )
                    }
                }
            }
            // Après l'écriture du .meta : la synthèse proposée lit le gabarit choisi
            refreshRecordings()
            proposeSummaryIfEnabled()
        }
    }

    /**
     * Renommage commun (dialog de fin d'enregistrement + liste) : conserve le
     * suffixe (.wav / .wav.enc / .txt) et renomme les fichiers frères.
     * Retourne le fichier renommé, ou null si rien n'a changé (message d'erreur posé).
     */
    private fun renameFile(f: File, newName: String): File? {
        if (_summaryBusy.value) {
            _lastError.value = "Synthèse en cours — renommage possible ensuite"
            return null
        }
        return when (val r = repo.rename(f, newName)) {
            is RenameResult.Renamed -> {
                if (_lastRecording.value == f) _lastRecording.value = r.dest
                r.dest
            }
            RenameResult.Unchanged -> null
            RenameResult.Clash -> {
                _lastError.value = "Un enregistrement porte déjà ce nom"
                null
            }
            RenameResult.Failed -> {
                _lastError.value = "Renommage impossible"
                null
            }
        }
    }

    fun dismissPendingName() {
        _pendingName.value = null
        refreshRecordings()
        proposeSummaryIfEnabled()
    }

    // ================= SYNTHÈSE =================

    /** Fin d'enregistrement (nom choisi) : proposer la synthèse s'il y a du texte. */
    private fun proposeSummaryIfEnabled() {
        if (!settings.proposeSummary) return
        val f = _lastRecording.value ?: return
        if (liveText.value.isBlank() && _fileTranscript.value.isBlank()) return
        _summaryProposal.value = f
    }

    fun dismissSummaryProposal() {
        _summaryProposal.value = null
    }

    fun setProposeSummary(enabled: Boolean) {
        settings.proposeSummary = enabled
    }

    fun setAiSummaryEnabled(enabled: Boolean) {
        settings.aiSummaryEnabled = enabled
    }

    fun setAiModel(id: String) {
        settings.aiModel = id
    }

    fun hasAiApiKey(): Boolean = settings.aiApiKeyEncrypted.isNotBlank()

    /** Enregistre la clé API Anthropic chiffrée (AndroidKeyStore) ; vide = l'efface. */
    fun setAiApiKey(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            settings.aiApiKeyEncrypted = ""
            return true
        }
        val enc = CryptoManager.encryptString(trimmed) ?: return false
        settings.aiApiKeyEncrypted = enc
        return true
    }

    private fun aiApiKey(): String? =
        CryptoManager.decryptString(settings.aiApiKeyEncrypted)?.takeIf { it.isNotBlank() }

    /**
     * Synthèse existante (Markdown, noms d'intervenants appliqués) ou null — I/O : à
     * appeler hors du thread principal. Une synthèse rédigée avant le nommage suit
     * ainsi les noms choisis ensuite.
     */
    fun readSummary(file: File): String? = repo.readSummary(file)

    /**
     * Génère la synthèse d'un enregistrement — par Claude si l'option est active et
     * qu'une clé est enregistrée (repli local en cas d'échec), sinon localement —
     * et l'écrit en « base.md » à côté du fichier.
     */
    fun generateSummary(file: File) {
        _summaryProposal.value = null // la proposition est consommée, même si on refuse
        if (_summaryBusy.value) {
            _uiMessage.value = "Une synthèse est déjà en cours"
            return
        }
        if (_backupBusy.value) {
            _uiMessage.value = "Sauvegarde en cours — réessaie quand elle est terminée"
            return
        }
        viewModelScope.launch {
            _summaryBusy.value = true
            try {
                val message = withContext(Dispatchers.IO) { buildSummaryFor(file) }
                _uiMessage.value = message
            } catch (t: Throwable) {
                _uiMessage.value = "Synthèse impossible : ${t.message}"
            } finally {
                // Jamais d'état « en cours » figé, quoi qu'il arrive
                _summaryBusy.value = false
                _summaryVersion.value = _summaryVersion.value + 1
            }
        }
    }

    /** Lit la meilleure transcription disponible, génère, écrit le .md ; retourne le message. */
    private fun buildSummaryFor(file: File): String {
        val text = repo.transcriptBody(file)
        if (text.isBlank()) return "Pas de transcription à synthétiser — lance d'abord « Transcrire »"
        val meta = repo.readMeta(file)
        val names = meta.speakers
        val template = SummaryTemplates.byId(meta.template)
        val input = SummaryInput(
            title = RecordingNames.baseName(file.name),
            transcript = text,
            durationMs = repo.durationMsOf(file),
            dateLabel = SUMMARY_DATE_FORMAT.format(Date(file.lastModified())),
        )
        val key = if (settings.aiSummaryEnabled) aiApiKey() else null
        var failure: String? = null
        if (settings.aiSummaryEnabled && key == null && settings.aiApiKeyEncrypted.isNotBlank()) {
            // Blob présent mais indéchiffrable (clé KeyStore perdue) : le dire, pas se taire
            failure = "Clé API illisible — ressaisis-la dans les Réglages"
        }
        val markdown = if (key != null) {
            // L'IA reçoit les vrais noms (meilleure rédaction) ; le local les applique en sortie
            val named = input.copy(transcript = SpeakerNames.apply(text, names))
            when (val r = ClaudeSummarizer.summarize(key, settings.aiModel, named, template)) {
                is AiSummaryResult.Ok -> wrapAiSummary(input, r)
                is AiSummaryResult.Failed -> {
                    failure = r.message
                    SpeakerNames.apply(LocalSummarizer.summarize(input, template), names)
                }
            }
        } else {
            SpeakerNames.apply(LocalSummarizer.summarize(input, template), names)
        }
        return try {
            repo.writeSummary(file, markdown)
            when {
                failure != null -> "$failure — synthèse locale générée à la place"
                key != null -> "Synthèse IA prête"
                else -> "Synthèse prête"
            }
        } catch (e: Exception) {
            "Écriture de la synthèse impossible : ${e.message}"
        }
    }

    private fun wrapAiSummary(input: SummaryInput, r: AiSummaryResult.Ok): String = buildString {
        append("# Synthèse — ").append(input.title).append('\n')
        append('_').append(input.dateLabel)
        if (input.durationMs > 0) append(" · ").append(formatHms(input.durationMs))
        append(" · synthèse IA (").append(ClaudeSummarizer.modelLabel(r.model)).append(")_\n\n")
        append(r.markdown.trim()).append("\n\n")
        append("_Synthèse rédigée par Claude (").append(r.model).append(", ")
            .append(r.inputTokens + r.outputTokens)
            .append(" tokens) à partir de la transcription — à relire avant diffusion._\n")
    }

    /** Gabarit de synthèse de l'enregistrement (stocké dans le .meta) ; la fiche se met à jour. */
    fun setSummaryTemplate(file: File, templateId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.updateMeta(file) { it.copy(template = templateId.trim()) } }
            refreshRecordings()
        }
    }

    // ================= QUESTIONS À L'IA =================

    /** L'IA est utilisable (option active et clé enregistrée) — sans I/O. */
    fun aiAvailable(): Boolean = settings.aiSummaryEnabled && settings.aiApiKeyEncrypted.isNotBlank()

    /**
     * Pose une question à Claude sur [file] : transcription (noms appliqués) + synthèse
     * existante en contexte, historique des échanges du même enregistrement conservé
     * (préfixe mis en cache côté API). Une seule question à la fois.
     */
    fun askQuestion(file: File, question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val current = _qa.value
        if (current.busy) {
            _uiMessage.value = "Une réponse est déjà en cours"
            return
        }
        if (_isTranscribingFile.value) {
            _uiMessage.value = "Transcription en cours — pose ta question ensuite"
            return
        }
        val turns = if (current.file == file) current.turns else emptyList()
        _qa.value = QaState(file = file, turns = turns, busy = true, error = null)
        viewModelScope.launch {
            val result: AiAnswer = try {
                withContext(Dispatchers.IO) {
                    val key = aiApiKey()
                    if (key == null) {
                        val why = if (settings.aiApiKeyEncrypted.isBlank()) {
                            "Clé API absente — Réglages › Synthèse"
                        } else {
                            "Clé API illisible — ressaisis-la dans les Réglages"
                        }
                        return@withContext AiAnswer.Failed(why)
                    }
                    val raw = repo.transcriptBody(file)
                    val names = repo.readMeta(file).speakers
                    ClaudeQa.ask(
                        apiKey = key,
                        modelId = settings.aiModel,
                        title = RecordingNames.baseName(file.name),
                        transcript = SpeakerNames.apply(raw, names),
                        summaryMarkdown = readSummary(file),
                        history = turns,
                        question = q,
                    )
                }
            } catch (t: Throwable) {
                AiAnswer.Failed("Réponse impossible : ${t.message}")
            }
            val st = _qa.value
            if (st.file != file) return@launch // fil effacé entre-temps : réponse ignorée
            _qa.value = when (result) {
                is AiAnswer.Ok -> st.copy(turns = turns + QaTurn(q, result.text), busy = false, error = null)
                is AiAnswer.Failed -> st.copy(busy = false, error = result.message)
            }
        }
    }

    fun clearQuestions() {
        if (_qa.value.busy) return
        _qa.value = QaState()
    }

    // ================= CHAPITRES =================

    /**
     * Détecte les chapitres d'un enregistrement — par Claude si l'IA est configurée
     * (repli local en cas d'échec), sinon par bascule de vocabulaire — et les écrit
     * dans le .meta. Requiert les segments horodatés (« Transcrire »).
     */
    fun generateChapters(file: File) {
        if (_chaptersBusy.value) return
        if (_isTranscribingFile.value || _backupBusy.value) {
            _uiMessage.value = "Opération en cours — réessaie ensuite"
            return
        }
        viewModelScope.launch {
            _chaptersBusy.value = true
            try {
                val message = withContext(Dispatchers.IO) {
                    if (!repo.hasSegments(file)) return@withContext "Chapitres : lance d'abord « Transcrire » (segments horodatés requis)"
                    val segs = repo.readSegments(file)
                    if (segs.size < 8) return@withContext "Enregistrement trop court pour des chapitres"
                    val meta = repo.readMeta(file)
                    var failure: String? = null
                    val key = if (settings.aiSummaryEnabled) aiApiKey() else null
                    val detected = if (key != null) {
                        // Horodatages hh:mm:ss au-delà d'une heure (réunions longues) : pas de repli à 00:00
                        val timed = segs.joinToString("\n") { s ->
                            "[${formatHms(s.startMs)}] ${SpeakerNames.label(s.speaker, meta.speakers)} : ${s.text.trim()}"
                        }
                        when (val r = ClaudeChapters.detect(key, settings.aiModel, RecordingNames.baseName(file.name), timed)) {
                            is AiChaptersResult.Ok -> r.chapters
                            is AiChaptersResult.Failed -> {
                                failure = r.message
                                ChapterDetector.detect(segs)
                            }
                        }
                    } else {
                        ChapterDetector.detect(segs)
                    }
                    // Bornes : pas de chapitre après la fin ; le premier commence avec le premier passage
                    val lastMs = segs.last().endMs.coerceAtLeast(segs.last().startMs)
                    val chapters = detected.filter { it.startMs <= lastMs }
                        .sortedBy { it.startMs }
                        .mapIndexed { i, c -> if (i == 0) c.copy(startMs = minOf(c.startMs, segs.first().startMs)) else c }
                    if (chapters.isEmpty()) {
                        return@withContext failure?.let { "$it — et pas de changement de sujet net détecté localement" }
                            ?: "Pas de changement de sujet assez net pour des chapitres"
                    }
                    // Relu au moment d'écrire : un nom d'intervenant ou un dossier saisi pendant
                    // l'appel (jusqu'à plusieurs minutes) n'est pas écrasé
                    repo.updateMeta(file) { it.copy(chapters = chapters) }
                    when {
                        failure != null -> "$failure — ${chapters.size} chapitres détectés localement"
                        key != null -> "${chapters.size} chapitres (IA)"
                        else -> "${chapters.size} chapitres détectés"
                    }
                }
                _uiMessage.value = message
            } catch (t: Throwable) {
                _uiMessage.value = "Chapitrage impossible : ${t.message}"
            } finally {
                _chaptersBusy.value = false
            }
            refreshRecordings()
        }
    }

    // ================= LECTURE =================

    /** Supprime les copies en clair du cache : dossier « exports » et WAV déchiffrés temporaires. */
    private fun purgeClearCopies() {
        try {
            File(appContext.cacheDir, "exports").listFiles()?.forEach { it.delete() }
            appContext.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".wav") && Regex("_(dec|pb|tr|exp_\\d+|bak_\\d+)\\.wav$").containsMatchIn(f.name)) {
                    f.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "purgeClearCopies: ${e.message}")
        }
    }

    /**
     * Retourne le fichier WAV en clair (déchiffre .enc vers un temp si besoin).
     * [suffix] distingue les usages simultanés (transcription "_tr", partage "_dec") :
     * la suppression du temp d'un flux n'invalide pas les autres.
     */
    private fun resolvedAudioFile(file: File, suffix: String = "_dec"): File? {
        if (file.extension != "enc") return file
        return CryptoManager.decryptToTemp(file, appContext.cacheDir, suffix)
    }

    fun togglePlayback() {
        val file = _lastRecording.value ?: return
        playback.toggle(file)
    }

    /** Lance/arrête la lecture d'un fichier (écran principal ou écran détail). */
    fun togglePlaybackFor(file: File) = playback.toggle(file)

    /** Tap sur un segment de l'écran détail : cale la lecture sur [positionMs]. */
    fun playFrom(file: File, positionMs: Long) = playback.playFrom(file, positionMs)

    /** Curseur de position de l'écran détail. */
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    // ================= TRANSCRIPTION DIFFÉRÉE + .txt =================

    fun transcribeLastRecording() {
        val file = _lastRecording.value ?: return
        transcribeFile(file)
    }

    /** Transcription différée d'un fichier précis (écran détail, liste). */
    fun transcribeFile(file: File) {
        if (_isTranscribingFile.value) return
        if (_isStreaming.value) {
            _lastError.value = "Transcription impossible pendant un enregistrement"
            return
        }
        if (_backupBusy.value) {
            // La transcription réécrit .txt/.srt/.json — pendant que le zip de
            // sauvegarde copie peut-être ces mêmes fichiers (entrée tronquée).
            _lastError.value = "Sauvegarde en cours — réessaie quand elle est terminée"
            return
        }
        if (!RecordingNames.isAudio(file.name)) return
        val engineRef = (modelState.value as? ModelState.Ready)?.engine
        if (engineRef == null) {
            _lastError.value = "Modèle Whisper non chargé — transcription différée indisponible"
            return
        }
        viewModelScope.launch {
            _isTranscribingFile.value = true
            _fileTranscript.value = ""
            val clear = withContext(Dispatchers.IO) { resolvedAudioFile(file, "_tr") }
            val pcm = if (clear != null) {
                withContext(Dispatchers.IO) { WavPcm.read(clear) }
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
                    val (text, srt, segmentsJson) = withContext(Dispatchers.IO) {
                        val shorts = PitchDiarizer.toShorts(pcm)
                        val speakerIds = PitchDiarizer.detectSpeakers(shorts, res.segments)
                        Triple(
                            repo.buildSpeakerMarkedTranscript(res, speakerIds, settings.useTimestamps),
                            TranscriptExporter.buildSrt(res.segments),
                            if (res.segments.isEmpty()) "" else SegmentsCodec.toJson(res.segments, speakerIds),
                        )
                    }
                    _fileTranscript.value = SpeakerNames.apply(text, withContext(Dispatchers.IO) { repo.readMeta(file).speakers })
                    // Sauvegarde .txt auto + .srt + segments .json à côté du fichier
                    val durationMs = pcm.size / 32L // 16 kHz × 2 octets = 32 octets/ms
                    withContext(Dispatchers.IO) {
                        writeTranscript(file, text, durationMs)
                        repo.writeSidecars(file, srt, segmentsJson)
                    }
                    refreshRecordings()
                }
            }
            _isTranscribingFile.value = false
        }
    }

    /** Écrit le .txt d'un enregistrement ; signale un scellement impossible (texte gardé en clair). */
    private fun writeTranscript(audioFile: File, text: String, durationMs: Long, sha256: String? = null) {
        if (!repo.writeTranscriptFile(audioFile, text, durationMs, sha256)) {
            _lastError.value = "Chiffrement du texte impossible — transcription conservée en clair"
        }
    }

    fun formatHms(ms: Long): String = TranscriptExporter.formatHms(ms)

    /** Sauvegarde une transcription corrigée à la main dans le .txt de l'enregistrement. */
    fun saveEditedTranscript(newText: String) {
        val file = _lastRecording.value ?: return
        _fileTranscript.value = newText
        viewModelScope.launch {
            // L'encart affiche les noms d'intervenants : le dépôt les ramène aux libellés
            // génériques avant d'écrire, le .txt ne fige jamais un nom
            val sealed = withContext(Dispatchers.IO) { repo.saveEditedTranscript(file, newText) }
            if (!sealed) _lastError.value = "Chiffrement du texte impossible — transcription conservée en clair"
            refreshRecordings()
        }
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
        buildShareIntent(file, _fileTranscript.value.ifBlank { liveText.value })
    }

    /** Partage direct d'un élément de la liste, sans passer par l'écran principal. */
    suspend fun buildShareIntentFor(item: RecordingItem): Intent? = withContext(Dispatchers.IO) {
        // .txt absent ou scellé illisible : on partage au moins l'aperçu
        val text = repo.readTranscript(item.file)?.let { RecordingRepository.bodyOf(it) } ?: item.transcript
        buildShareIntent(item.file, SpeakerNames.apply(text, item.speakerNames))
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
            // .srt et .md : copies en clair dans le cache (les originaux peuvent être scellés)
            val srt = RecordingNames.srtSibling(file)
            if (srt.exists()) {
                val srtCopy = File(exportDir, "$base.srt")
                srtCopy.writeText(TextVault.read(srt))
                attachments.add(
                    FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", srtCopy)
                )
            }
            // Synthèse : en corps de message (la transcription complète reste jointe) + .md joint
            val summaryText = readSummary(file)
            if (summaryText != null) {
                val mdCopy = File(exportDir, "$base.md")
                mdCopy.writeText(summaryText)
                attachments.add(
                    FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", mdCopy)
                )
            }
            val body = if (summaryText != null) {
                MarkdownLite.toPlainText(summaryText) +
                    "\n\n— Transcription complète en pièce jointe (.txt)."
            } else {
                transcriptText
            }

            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(""))
                putExtra(Intent.EXTRA_SUBJECT, if (summaryText != null) "Synthèse — $base" else "Transcription $base")
                putExtra(Intent.EXTRA_TEXT, body)
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
        if (_backupBusy.value) {
            _uiMessage.value = "Sauvegarde en cours — réessaie quand elle est terminée"
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
                    val dest = File(repo.dir, "$base.wav")
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
                transcriber.clearText()
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
        return repo.uniqueBase(base)
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

    // ================= EXPORTS STRUCTURÉS (WORD, PDF) =================

    /** Assemble le document d'un enregistrement : page de garde, synthèse, transcription (I/O). */
    private fun buildExportDocument(file: File): ExportDocument {
        val meta = repo.readMeta(file)
        val content = repo.readTranscript(file) ?: ""
        val raw = RecordingRepository.bodyOf(content)
        val segments = repo.readSegments(file).map { ExportSegment(it.speaker, it.startMs, it.endMs, it.text) }
        val version = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        return ExportDocument(
            title = RecordingNames.baseName(file.name),
            dateLabel = SUMMARY_DATE_FORMAT.format(Date(file.lastModified())),
            durationMs = repo.durationMsOf(file),
            dossier = meta.dossier,
            missionLabel = SummaryTemplates.ALL.firstOrNull { it.id == meta.template }?.label ?: "",
            speakerNames = meta.speakers,
            sha256 = RecordingRepository.HASH_LINE.find(content)?.groupValues?.get(1),
            encrypted = file.name.endsWith(".enc"),
            summaryMarkdown = readSummary(file),
            segments = segments,
            chapters = meta.chapters,
            transcriptText = SpeakerNames.apply(raw, meta.speakers),
            timestamps = if (raw.isEmpty()) settings.useTimestamps else RecordingRepository.CLOCK_TAG.containsMatchIn(raw),
            appVersion = version,
            generatedLabel = SUMMARY_DATE_FORMAT.format(Date()),
        )
    }

    /** Écrit le document Word ou PDF de [file] dans [destUri] (emplacement choisi via SAF). */
    fun exportDocument(file: File, destUri: Uri, format: ExportFormat) {
        if (_isTranscribingFile.value) {
            _uiMessage.value = "Transcription en cours — exporte ensuite"
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val doc = buildExportDocument(file)
                    val blocks = ExportComposer.compose(doc)
                    val out = try {
                        appContext.contentResolver.openOutputStream(destUri, "wt")
                    } catch (e: Exception) {
                        appContext.contentResolver.openOutputStream(destUri)
                    } ?: return@withContext false
                    out.use { o ->
                        when (format) {
                            ExportFormat.DOCX -> o.write(DocxWriter.write(blocks, doc.title))
                            ExportFormat.PDF -> PdfWriter.write(
                                blocks,
                                o,
                                footer = "Transcripto Stream" +
                                    (if (doc.appVersion.isNotBlank()) " v${doc.appVersion}" else "") +
                                    " · ${doc.title}",
                            )
                        }
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "exportDocument: ${e.message}")
                    false
                }
            }
            _uiMessage.value = if (ok) {
                "${format.label} « ${RecordingNames.baseName(file.name)} » exporté"
            } else {
                "Export ${format.label} impossible"
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

    // ================= SAUVEGARDE / RESTAURATION CHIFFRÉE =================

    /**
     * Exporte tous les enregistrements dans une archive chiffrée par phrase de passe
     * (format .tsbk : zip AES-256-GCM, clé PBKDF2) — restaurable sur un autre
     * appareil, contrairement aux WAV chiffrés avec la clé AndroidKeyStore.
     */
    /**
     * Garde UI appelée AVANT d'ouvrir le sélecteur SAF d'export/restauration :
     * évite de créer un document qui serait refusé juste après (fichier fantôme).
     */
    fun backupBlocked(): Boolean {
        val blocked = _backupBusy.value || _isStreaming.value ||
            _isImporting.value || _isTranscribingFile.value || _summaryBusy.value
        if (blocked) _uiMessage.value = "Sauvegarde impossible pendant une opération en cours"
        return blocked
    }

    fun exportBackup(destUri: Uri, passphrase: String) {
        if (_backupBusy.value) return
        // Un WAV en cours d'écriture (enregistrement) ou en cours de création
        // (import/transcription) partirait tronqué dans l'archive.
        if (_isStreaming.value || _isImporting.value || _isTranscribingFile.value || _summaryBusy.value) {
            _uiMessage.value = "Sauvegarde impossible pendant une opération en cours"
            return
        }
        viewModelScope.launch {
            _backupBusy.value = true
            val message = withContext(Dispatchers.IO) {
                try {
                    val out = try {
                        appContext.contentResolver.openOutputStream(destUri, "wt")
                    } catch (e: Exception) {
                        appContext.contentResolver.openOutputStream(destUri)
                    } ?: return@withContext "Impossible d'écrire la sauvegarde"
                    var count = 0
                    var unreadable = 0
                    // out.use englobe tout : même un échec avant le flux chiffrant ferme le SAF
                    out.use { rawOut ->
                    BackupCrypto.encryptingStream(rawOut, passphrase.toCharArray()).use { enc ->
                        ZipOutputStream(enc).use { zip ->
                            val files = repo.dir.listFiles()?.sortedBy { it.name }
                                ?: emptyList()
                            // « a.wav » et « a.wav.enc » donneraient la même entrée
                            // « a.wav » — un doublon ferait planter tout l'export (ZipException)
                            val usedNames = HashSet<String>()
                            for (f in files) {
                                if (!f.isFile) continue
                                if (f.name.endsWith(".enc")) {
                                    val entryName = RecordingNames.baseName(f.name) + ".wav"
                                    if (!usedNames.add(entryName)) continue
                                    // Ré-encodé en clair DANS l'archive (elle-même chiffrée
                                    // par la phrase de passe) : la clé KeyStore ne voyage pas
                                    val clear = CryptoManager.decryptToTemp(
                                        f, appContext.cacheDir, "_bak_${System.currentTimeMillis()}"
                                    ) ?: continue
                                    try {
                                        zip.putNextEntry(ZipEntry(entryName))
                                        clear.inputStream().use { it.copyTo(zip, 64 * 1024) }
                                        zip.closeEntry()
                                    } finally {
                                        clear.delete()
                                    }
                                } else {
                                    if (!usedNames.add(f.name)) continue
                                    // Textes scellés (chiffrement au repos) : en clair DANS l'archive,
                                    // comme les WAV — la clé KeyStore ne voyage pas. Un texte scellé
                                    // illisible (clé perdue) est ignoré : il ne serait lisible nulle part.
                                    val sealed = TextVault.isTextFile(f.name) && TextVault.isSealed(f)
                                    val plain = if (sealed) {
                                        try {
                                            TextVault.read(f)
                                        } catch (e: Exception) {
                                            unreadable++
                                            continue
                                        }
                                    } else {
                                        null
                                    }
                                    zip.putNextEntry(ZipEntry(f.name))
                                    if (plain != null) {
                                        zip.write(plain.toByteArray(Charsets.UTF_8))
                                    } else {
                                        f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                                    }
                                    zip.closeEntry()
                                }
                                count++
                            }
                        }
                    }
                    } // out.use
                    "Sauvegarde exportée ($count fichiers) — garde précieusement la phrase de passe" +
                        (if (unreadable > 0) " ($unreadable texte(s) illisible(s) ignoré(s))" else "")
                } catch (e: Exception) {
                    Log.e(TAG, "exportBackup", e)
                    "Sauvegarde échouée : ${e.message}"
                }
            }
            _uiMessage.value = message
            _backupBusy.value = false
        }
    }

    private val allowedBackupSuffixes = setOf(".wav", ".txt", ".srt", ".json", ".md", ".meta")

    /** Restaure une archive .tsbk : jamais destructif (les doublons sont suffixés). */
    fun restoreBackup(srcUri: Uri, passphrase: String) {
        if (_backupBusy.value) return
        if (_isStreaming.value || _isImporting.value || _isTranscribingFile.value || _summaryBusy.value) {
            _uiMessage.value = "Restauration impossible pendant une opération en cours"
            return
        }
        viewModelScope.launch {
            _backupBusy.value = true
            val message = withContext(Dispatchers.IO) {
                try {
                    val inp = appContext.contentResolver.openInputStream(srcUri)
                        ?: return@withContext "Impossible de lire ce fichier"
                    var count = 0
                    val renames = HashMap<String, String>() // base d'origine → base locale
                    val dir = repo.dir
                    // inp.use englobe tout : un en-tête invalide (exception AVANT la
                    // création du flux déchiffrant) ferme quand même le flux SAF
                    inp.use { rawIn ->
                    BackupCrypto.decryptingStream(rawIn, passphrase.toCharArray()).use { dec ->
                        ZipInputStream(dec).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                val rawName = entry.name
                                if (!entry.isDirectory &&
                                    !rawName.contains('/') && !rawName.contains('\\')
                                ) {
                                    val base = RecordingNames.sanitize(
                                        RecordingNames.baseName(rawName)
                                    )
                                    val suffixPart = rawName.substring(
                                        RecordingNames.baseName(rawName).length
                                    )
                                    if (base.isNotEmpty() && suffixPart in allowedBackupSuffixes) {
                                        val target = renames.getOrPut(base) {
                                            repo.uniqueBase(base, renames.values)
                                        }
                                        val destFile = File(dir, target + suffixPart)
                                        destFile.outputStream().use { zip.copyTo(it, 64 * 1024) }
                                        if (suffixPart == ".wav") {
                                            maybeEncrypt(destFile)
                                        } else if (TextVault.enabled) {
                                            // Textes restaurés en clair : scellés si le réglage est actif
                                            try {
                                                TextVault.write(destFile, TextVault.read(destFile))
                                            } catch (e: Exception) {
                                                Log.e(TAG, "restore seal ${destFile.name}: ${e.message}")
                                            }
                                        }
                                        count++
                                    }
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    }
                    } // inp.use
                    refreshRecordings()
                    "Restauration terminée : $count fichiers"
                } catch (e: BackupCrypto.InvalidBackupException) {
                    e.message ?: "Fichier de sauvegarde invalide"
                } catch (e: Exception) {
                    Log.e(TAG, "restoreBackup", e)
                    "Restauration échouée — phrase de passe incorrecte ou fichier corrompu"
                }
            }
            _uiMessage.value = message
            _backupBusy.value = false
        }
    }

    // ================= LISTE DES ENREGISTREMENTS =================

    fun refreshRecordings() {
        viewModelScope.launch {
            val listing = withContext(Dispatchers.IO) { repo.list() }
            val items = listing.items
            val totalBytes = listing.totalBytes
            _recordings.value = items
            // Index de recherche APRÈS publication de la liste (relecture des seuls textes modifiés)
            withContext(Dispatchers.IO) {
                searchIndex.sync(items.map { repo.indexSource(it) }) { f -> repo.segmentsForIndex(f) }
            }
            val q = _searchQuery.value
            if (q.isNotBlank()) runSearch(q)
            _storageBytes.value = totalBytes
            _dossiers.value = items.map { it.dossier }.filter { it.isNotBlank() }.distinct()
                .sortedBy { it.lowercase(Locale.FRANCE) }
            if (_dossierFilter.value != null && _dossierFilter.value !in _dossiers.value) {
                _dossierFilter.value = null
            }
            // La fiche ouverte reflète les métadonnées à jour (nom d'intervenant, dossier)
            _detailItem.value?.let { d ->
                items.firstOrNull { it.file == d.file }?.let { _detailItem.value = it }
            }
        }
    }

    /** Ouvre la fiche d'un fichier (écran principal → détail du dernier enregistrement). */
    fun openDetailForFile(file: File) {
        viewModelScope.launch {
            val item = _recordings.value.firstOrNull { it.file == file }
                ?: withContext(Dispatchers.IO) { if (file.exists()) repo.item(file) else null }
            if (item != null) openDetail(item)
        }
    }

    fun renameRecording(old: RecordingItem, newBaseName: String) {
        renameFile(old.file, newBaseName)
        refreshRecordings()
    }

    fun deleteRecording(item: RecordingItem) {
        if (_summaryBusy.value) {
            _uiMessage.value = "Synthèse en cours — suppression possible ensuite"
            return
        }
        if (_summaryProposal.value == item.file) _summaryProposal.value = null
        repo.delete(item.file)
        if (_lastRecording.value == item.file) {
            _lastRecording.value = null
            _fileTranscript.value = ""
            activeRecordingFile = null
        }
        refreshRecordings()
    }

    fun deleteLastRecording() {
        if (_isStreaming.value) return
        if (_summaryBusy.value) {
            _uiMessage.value = "Synthèse en cours — suppression possible ensuite"
            return
        }
        val f = _lastRecording.value ?: return
        if (_summaryProposal.value == f) _summaryProposal.value = null
        repo.delete(f)
        _lastRecording.value = null
        activeRecordingFile = null
        _fileTranscript.value = ""
        refreshRecordings()
    }

    /** Rétention RGPD : supprime les enregistrements plus vieux que N jours (0 = désactivé). */
    fun cleanupExpired() {
        val days = settings.retentionDays
        if (days <= 0) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.cleanupExpired(days) }
            refreshRecordings()
        }
    }

    // ================= VAD =================

    fun setVadSilero(enabled: Boolean) {
        settings.vadSilero = enabled
        transcriber.setVadEnabled(enabled, _isStreaming.value)
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
        transcriber.close()
        playback.stop()
        viewModelScope.launch {
            engine.unloadModel()
        }
        super.onCleared()
    }
}
