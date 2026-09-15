package com.transcripto.stream.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transcripto.stream.BatchState
import com.transcripto.stream.RecordingService
import com.transcripto.stream.RecordingState
import com.transcripto.stream.audio.AudioFocusGuard
import com.transcripto.stream.audio.AudioTransfer
import com.transcripto.stream.audio.LiveSettings
import com.transcripto.stream.audio.LiveTranscriber
import com.transcripto.stream.audio.PcmAudioRecorder
import com.transcripto.stream.audio.PcmDigest
import com.transcripto.stream.audio.PitchDiarizer
import com.transcripto.stream.audio.PlaybackController
import com.transcripto.stream.audio.SileroVad
import com.transcripto.stream.audio.WavPcm
import com.transcripto.stream.audio.WavFileWriter
import com.transcripto.stream.data.ActionItem
import com.transcripto.stream.data.BackupCrypto
import com.transcripto.stream.data.BackupManager
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
import com.transcripto.stream.export.DocumentExporter
import com.transcripto.stream.export.ExportFormat
import com.transcripto.stream.export.ShareComposer
import com.transcripto.stream.export.TranscriptExporter
import com.transcripto.stream.stt.GoogleSpeechEngine
import com.transcripto.stream.stt.ModelManager
import com.transcripto.stream.stt.ModelState
import com.transcripto.stream.stt.SegmentData
import com.transcripto.stream.summary.ActionExtractor
import com.transcripto.stream.summary.AiAnswer
import com.transcripto.stream.summary.AiAssistant
import com.transcripto.stream.summary.AiSettings
import com.transcripto.stream.summary.QaTurn
import com.transcripto.stream.summary.SdkClaudeTransport
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        private val REC_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private val REC_START_END_FORMAT = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)
    }

    val settings = SettingsStore(appContext)

    /** Dossier des enregistrements et de leurs fichiers frères (E/S bloquantes). */
    private val repo = RecordingRepository(File(appContext.filesDir, "recordings"))

    /** Archive chiffrée par phrase de passe (export/restauration). */
    private val backup = BackupManager(repo, appContext.cacheDir, CryptoManager) { settings.encryptWav }

    /** Partage (intent avec .txt, audio, .srt, .md) et import/export d'audio via SAF. */
    private val share = ShareComposer(appContext, repo, CryptoManager)
    private val transfer = AudioTransfer(appContext, repo, CryptoManager) { settings.encryptWav }

    /** Document Word/PDF d'un enregistrement (page de garde, synthèse, transcription). */
    private val documents = DocumentExporter(
        repo,
        appVersion = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        },
    ) { settings.useTimestamps }

    /** Un seul client HTTP Anthropic pour la durée du ViewModel (recréé si la clé change). */
    private val claude = SdkClaudeTransport()

    /** Synthèse, questions et chapitres (Claude ou repli local), lecture/écriture des fichiers. */
    private val ai = AiAssistant(
        repo,
        object : AiSettings {
            override val aiSummaryEnabled: Boolean get() = settings.aiSummaryEnabled
            override val aiModel: String get() = settings.aiModel
            override val hasApiKey: Boolean get() = settings.aiApiKeyEncrypted.isNotBlank()
        },
        apiKey = { CryptoManager.decryptString(settings.aiApiKeyEncrypted)?.takeIf { it.isNotBlank() } },
        transport = claude,
    )

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

    // ---- État du streaming (observable par Compose) ----
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

    /** Jours écoulés depuis la dernière sauvegarde quand le rappel est dû (Int.MAX_VALUE = jamais sauvegardé) ; null sinon. */
    private val _backupReminder = MutableStateFlow<Int?>(null)
    val backupReminder: StateFlow<Int?> = _backupReminder.asStateFlow()

    /** Section à montrer en ouvrant les Réglages (« backup » : défiler jusqu'à la sauvegarde), consommée par l'écran. */
    private val _settingsTarget = MutableStateFlow<String?>(null)
    val settingsTarget: StateFlow<String?> = _settingsTarget.asStateFlow()

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

    /** Dossier ouvert sur la fiche dossier (écran 4). */
    private val _openDossier = MutableStateFlow<String?>(null)
    val openDossier: StateFlow<String?> = _openDossier.asStateFlow()

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

    /** Modèles Whisper (chargement, catalogue, téléchargements) — flux relayés ci-dessous. */
    private val models = ModelManager(
        context = appContext,
        settings = settings,
        engine = engine,
        lock = whisperLock,
        scope = viewModelScope,
        busy = { _isStreaming.value || _isTranscribingFile.value || _isImporting.value },
        onMessage = { _uiMessage.value = it },
    )
    val modelState: StateFlow<ModelState> = models.modelState
    val extractionProgress: StateFlow<Float?> = models.extractionProgress
    val loadMessage: StateFlow<String> = models.loadMessage
    val modelLoadMs: StateFlow<Long> = models.modelLoadMs
    val activeModelId: StateFlow<String> = models.activeModelId
    val downloadedModels: StateFlow<Set<String>> = models.downloadedModels
    val modelDownloads: StateFlow<Map<String, Float>> = models.modelDownloads
    val modelStorageBytes: StateFlow<Long> = models.modelStorageBytes

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
        models.load()
        cleanupExpired()
        refreshRecordings()
        models.refreshDownloaded()
        models.resumePendingDownload()
        refreshBackupReminder()
    }

    // ---- Rappel de sauvegarde (aucune tâche planifiée : évalué à l'ouverture et après chaque liste) ----

    fun refreshBackupReminder() {
        val days = settings.backupReminderDays
        if (days <= 0) {
            _backupReminder.value = null
            return
        }
        val last = settings.lastBackupAt
        val elapsed = if (last <= 0) Int.MAX_VALUE else ((System.currentTimeMillis() - last) / 86_400_000L).toInt()
        _backupReminder.value = if (elapsed >= days) elapsed else null
    }

    fun setBackupReminderDays(days: Int) {
        settings.backupReminderDays = days
        refreshBackupReminder()
    }

    fun lastBackupAt(): Long = settings.lastBackupAt

    /** Bandeau de rappel : ouvre les Réglages sur la section Sauvegarde. */
    fun openBackupSettings() {
        _settingsTarget.value = "backup"
        navigate(2)
    }

    fun consumeSettingsTarget() {
        _settingsTarget.value = null
    }

    /** Bouton « Réessayer » de la bannière d'erreur du modèle. */
    fun retryModelLoad() = models.retry()

    fun refreshDownloadedModels() = models.refreshDownloaded()

    /** Lance le téléchargement d'un modèle du catalogue (un seul à la fois). */
    fun downloadModel(id: String) = models.download(id)

    fun cancelModelDownload() = models.cancelDownload()

    /** Change le modèle Whisper actif et le recharge. */
    fun selectModel(id: String) = models.select(id)

    /** Supprime un modèle téléchargé (bascule sur l'embarqué s'il était actif). */
    fun deleteModel(id: String) = models.delete(id)

    // ================= NAVIGATION =================

    fun navigate(screenIndex: Int) {
        if (_screen.value == screenIndex) return
        if (screenIndex == 1 || screenIndex == 4) refreshRecordings()
        if (screenIndex == 2) models.refreshDownloaded()
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

    // ---- Fiche dossier ----

    /** Ouvre la fiche d'un dossier (puce de dossier sur une carte, filtre de la liste). */
    fun openDossier(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        _openDossier.value = n
        _dossierFilter.value = n // le filtre de la liste suit le dossier consulté
        navigate(4)
    }

    /** Renomme le dossier ouvert (réécrit le .meta de chacun de ses enregistrements). */
    fun renameDossier(oldName: String, newName: String) {
        if (_backupBusy.value || _summaryBusy.value) {
            _uiMessage.value = "Opération en cours — réessaie ensuite"
            return
        }
        val target = newName.trim()
        if (target.isEmpty() || target == oldName) return
        val merging = _dossiers.value.any { it.equals(target, ignoreCase = true) && !it.equals(oldName, ignoreCase = true) }
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { repo.renameDossier(oldName, target) }
            if (n > 0) {
                if (_openDossier.value == oldName) _openDossier.value = target
                if (_dossierFilter.value == oldName) _dossierFilter.value = target
                _uiMessage.value = if (merging) {
                    "Dossiers fusionnés : $n enregistrement${if (n > 1) "s" else ""} déplacé${if (n > 1) "s" else ""} vers « $target »"
                } else {
                    "Dossier renommé ($n enregistrement${if (n > 1) "s" else ""})"
                }
            } else {
                _uiMessage.value = "Aucun enregistrement à renommer"
            }
            refreshRecordings()
        }
    }

    /** Fusionne le dossier ouvert dans [target] (même mécanisme que le renommage). */
    fun mergeDossier(source: String, target: String) = renameDossier(source, target)

    /** Écrit le document Word ou PDF d'un dossier entier dans [destUri]. */
    fun exportDossier(name: String, destUri: Uri, format: ExportFormat, includeTranscripts: Boolean) {
        if (_isTranscribingFile.value) {
            _uiMessage.value = "Transcription en cours — exporte ensuite"
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val doc = documents.buildDossier(name, includeTranscripts)
                    val out = try {
                        appContext.contentResolver.openOutputStream(destUri, "wt")
                    } catch (e: Exception) {
                        appContext.contentResolver.openOutputStream(destUri)
                    } ?: return@withContext false
                    documents.writeDossier(doc, out, format)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "exportDossier: ${e.message}")
                    false
                }
            }
            _uiMessage.value = if (ok) "${format.label} du dossier « $name » exporté" else "Export ${format.label} impossible"
        }
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
            val r = withContext(Dispatchers.IO) {
                repo.updateSegmentText(file, index, newText, settings.useTimestamps)
            }
            if (r != null) {
                if (!r.sealed) _lastError.value = "Chiffrement du texte impossible — transcription conservée en clair"
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
        if (_selectedEngine.value == "whisper" && modelState.value !is ModelState.Ready) return
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
            focus.request()
        }
        _isStreaming.value = true

        if (_selectedEngine.value == "google") {
            if (!startGoogleStreaming()) {
                stopStreaming()
            } else if (settings.muteWhileListening) {
                // Le SpeechRecognizer système émet des bips à chaque cycle d'écoute :
                // on coupe les flux sonores concernés le temps de la session.
                focus.muteSystemSounds()
            }
        } else {
            startWhisperStreaming()
        }
    }

    // ---- Résilience audio : pause automatique quand le micro est interrompu ----
    // (appel entrant, autre app qui prend le focus), reprise à la fin.
    private var pausedByFocusLoss = false

    private val focus = AudioFocusGuard(
        appContext,
        onLoss = { transient ->
            if (_isStreaming.value && !_isPaused.value) {
                togglePause() // remet pausedByFocusLoss à false : le drapeau se pose après
                if (transient) {
                    pausedByFocusLoss = true
                    _lastError.value = "Micro interrompu (appel en cours ?) — reprise automatique à la fin"
                } else {
                    _lastError.value = "Micro interrompu — enregistrement en pause, appuie sur Reprendre"
                }
            }
        },
        onGain = {
            if (_isStreaming.value && _isPaused.value && pausedByFocusLoss) {
                pausedByFocusLoss = false
                togglePause()
                _lastError.value = null
            }
        },
    )

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
        focus.unmuteSystemSounds()
        focus.abandon()
        pausedByFocusLoss = false
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
                val message = withContext(Dispatchers.IO) { ai.summarize(file) }
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

    // ---- Actions à mener (suivies dans le .meta) ----

    /** Coche/décoche une action ; la fiche et la liste se mettent à jour. */
    fun setActionDone(file: File, id: String, done: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.updateMeta(file) { m -> m.copy(actions = m.actions.map { if (it.id == id) it.copy(done = done) else it }) }
            }
            refreshRecordings()
        }
    }

    /** Ajoute (identifiant vide) ou modifie une action saisie sur la fiche. */
    fun saveAction(file: File, action: ActionItem) {
        val text = action.text.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.updateMeta(file) { m ->
                    val cleaned = action.copy(text = text, owner = action.owner.trim(), dueLabel = action.dueLabel.trim())
                    if (cleaned.id.isBlank() || m.actions.none { it.id == cleaned.id }) {
                        val due = ActionExtractor.findDue(cleaned.dueLabel, System.currentTimeMillis())
                        m.copy(
                            actions = m.actions + cleaned.copy(
                                id = cleaned.id.ifBlank { UUID.randomUUID().toString() },
                                dueAt = due?.at ?: 0L,
                                createdAt = if (cleaned.createdAt > 0) cleaned.createdAt else System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        m.copy(
                            actions = m.actions.map {
                                if (it.id != cleaned.id) it else {
                                    val dueAt = if (it.dueLabel == cleaned.dueLabel) it.dueAt else ActionExtractor.findDue(cleaned.dueLabel, System.currentTimeMillis())?.at ?: 0L
                                    it.copy(text = cleaned.text, owner = cleaned.owner, dueLabel = cleaned.dueLabel, dueAt = dueAt)
                                }
                            },
                        )
                    }
                }
            }
            refreshRecordings()
        }
    }

    fun deleteAction(file: File, id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.updateMeta(file) { m -> m.copy(actions = m.actions.filter { it.id != id }) } }
            refreshRecordings()
        }
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
    fun aiAvailable(): Boolean = ai.available()

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
                withContext(Dispatchers.IO) { ai.ask(file, turns, q) }
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
                val message = withContext(Dispatchers.IO) { ai.chapters(file) }
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
        if (_isImporting.value) {
            _lastError.value = "Import en cours — réessaie quand il est terminé"
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
            val error = transcribeStored(file, engineRef, showInMain = true)
            if (error != null) _lastError.value = error else refreshRecordings()
            _isTranscribingFile.value = false
        }
    }

    /**
     * Transcription différée d'un fichier audio (déchiffré au besoin) : .txt, .srt et
     * segments .json écrits à côté. Retourne le message d'erreur, ou null si tout est écrit.
     * [showInMain] : l'encart de l'écran principal reflète le texte produit.
     */
    private suspend fun transcribeStored(file: File, engineRef: WhisperStreamEngine, showInMain: Boolean): String? {
        val clear = withContext(Dispatchers.IO) { resolvedAudioFile(file, "_tr") }
        val pcm = if (clear != null) withContext(Dispatchers.IO) { WavPcm.read(clear) } else null
        if (clear != null && clear != file) clear.delete()
        if (pcm == null) return "Fichier audio illisible"
        val res = whisperLock.withLock {
            engineRef.transcribeBuffer(pcm, settings.language, settings.vocabularyList.joinToString(", "))
        }
        if (res.error != null) return res.error
        val (text, srt, segmentsJson) = withContext(Dispatchers.IO) {
            val shorts = PitchDiarizer.toShorts(pcm)
            val speakerIds = PitchDiarizer.detectSpeakers(shorts, res.segments)
            Triple(
                repo.buildSpeakerMarkedTranscript(res, speakerIds, settings.useTimestamps),
                TranscriptExporter.buildSrt(res.segments),
                if (res.segments.isEmpty()) "" else SegmentsCodec.toJson(res.segments, speakerIds),
            )
        }
        if (showInMain) {
            _fileTranscript.value = SpeakerNames.apply(text, withContext(Dispatchers.IO) { repo.readMeta(file).speakers })
        }
        // Sauvegarde .txt auto + .srt + segments .json à côté du fichier
        val durationMs = pcm.size / 32L // 16 kHz × 2 octets = 32 octets/ms
        withContext(Dispatchers.IO) {
            writeTranscript(file, text, durationMs)
            repo.writeSidecars(file, srt, segmentsJson)
        }
        return null
    }

    // ---- Import par lots : décodage, transcription différée puis synthèse, un fichier à la fois ----

    /** Progression d'un lot (bandeau de la liste) : fichiers terminés, total, fichier en cours. */
    data class BatchProgress(val done: Int, val total: Int, val label: String, val cancelling: Boolean)

    private val _pendingBatch = MutableStateFlow<List<Uri>>(emptyList())
    /** Fichiers en attente du choix de dossier/gabarit (dialogue), vide sinon. */
    val pendingBatch: StateFlow<List<Uri>> = _pendingBatch.asStateFlow()

    private val _batch = MutableStateFlow<BatchProgress?>(null)
    val batch: StateFlow<BatchProgress?> = _batch.asStateFlow()

    /** Plusieurs fichiers choisis ou partagés : ouvre le dialogue du lot (refusé pendant un enregistrement). */
    fun requestBatch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_isStreaming.value) {
            _uiMessage.value = "Import impossible pendant un enregistrement"
            return
        }
        if (_isImporting.value || _isTranscribingFile.value || _backupBusy.value) {
            _uiMessage.value = "Opération en cours — réessaie ensuite"
            return
        }
        _pendingBatch.value = uris
    }

    fun dismissBatch() {
        _pendingBatch.value = emptyList()
    }

    /** Bouton Annuler (bandeau ou notification) : le lot s'arrête après le fichier en cours. */
    fun cancelBatch() {
        if (_batch.value == null) return
        BatchState.cancelRequested = true
        _batch.value = _batch.value?.copy(cancelling = true)
    }

    fun startBatch(dossier: String, template: String) {
        val uris = _pendingBatch.value
        _pendingBatch.value = emptyList()
        if (uris.isEmpty()) return
        if (_isStreaming.value || _isImporting.value || _isTranscribingFile.value || _backupBusy.value) {
            _uiMessage.value = "Opération en cours — réessaie ensuite"
            return
        }
        viewModelScope.launch {
            _isImporting.value = true
            BatchState.reset()
            BatchState.active = true
            BatchState.total = uris.size
            RecordingService.startBatch(appContext)
            val errors = ArrayList<String>()
            var imported = 0
            var transcribed = 0
            var summarized = 0
            try {
                for ((i, uri) in uris.withIndex()) {
                    if (BatchState.cancelRequested) break
                    BatchState.done = i
                    BatchState.label = "fichier ${i + 1}"
                    _batch.value = BatchProgress(i, uris.size, BatchState.label, false)
                    _importProgress.value = 0f
                    val r = withContext(Dispatchers.IO) { transfer.import(uri) { p -> _importProgress.value = p } }
                    val file = r.file
                    if (file == null) {
                        errors += "fichier ${i + 1} : ${r.error ?: "import impossible"}"
                        continue
                    }
                    imported++
                    if (r.unsealed) errors += "${RecordingNames.baseName(file.name)} : chiffrement impossible, WAV conservé en clair"
                    val base = RecordingNames.baseName(file.name)
                    BatchState.label = base
                    _batch.value = BatchProgress(i, uris.size, base, BatchState.cancelRequested)
                    // Le lot hérite du dossier et du gabarit choisis une fois au départ
                    if (dossier.isNotBlank() || template.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            repo.updateMeta(file) { it.copy(dossier = dossier.trim().ifBlank { it.dossier }, template = template.trim().ifBlank { it.template }) }
                        }
                    }
                    if (BatchState.cancelRequested) break
                    val engineRef = (modelState.value as? ModelState.Ready)?.engine
                    if (engineRef == null) {
                        errors += "$base : modèle Whisper non chargé, importé sans transcription"
                        continue
                    }
                    val err = transcribeStored(file, engineRef, showInMain = false)
                    if (err != null) {
                        errors += "$base : $err"
                        continue
                    }
                    transcribed++
                    if (BatchState.cancelRequested) break
                    try {
                        withContext(Dispatchers.IO) { ai.summarize(file) }
                        summarized++
                    } catch (t: Throwable) {
                        errors += "$base : synthèse impossible (${t.message})"
                    }
                    _summaryVersion.value = _summaryVersion.value + 1
                }
            } finally {
                BatchState.active = false
                if (!RecordingState.isActive) RecordingService.stop(appContext)
                _importProgress.value = null
                _isImporting.value = false
                _batch.value = null
            }
            val cancelled = BatchState.cancelRequested
            BatchState.reset()
            refreshRecordings()
            _uiMessage.value = buildString {
                append(if (cancelled) "Lot interrompu : " else "Lot terminé : ")
                append("$imported importé${if (imported > 1) "s" else ""}, $transcribed transcrit${if (transcribed > 1) "s" else ""}, $summarized synthèse${if (summarized > 1) "s" else ""}")
                if (errors.isNotEmpty()) {
                    append(" — ${errors.size} erreur${if (errors.size > 1) "s" else ""} : ")
                    append(errors.take(3).joinToString(" ; "))
                    if (errors.size > 3) append(" ; …")
                }
            }
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
            val r = withContext(Dispatchers.IO) { repo.saveEditedTranscript(file, newText) }
            if (!r.sealed) _lastError.value = "Chiffrement du texte impossible — transcription conservée en clair"
            if (r.segmentsStale) {
                _uiMessage.value = "Texte enregistré — passages horodatés non synchronisés : relance « Transcrire » pour les réaligner"
            }
            _transcriptVersion.value = _transcriptVersion.value + 1
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
        share.intentFor(file, _fileTranscript.value.ifBlank { liveText.value })
    }

    /** Partage direct d'un élément de la liste, sans passer par l'écran principal. */
    suspend fun buildShareIntentFor(item: RecordingItem): Intent? = withContext(Dispatchers.IO) {
        // .txt absent ou scellé illisible : on partage au moins l'aperçu
        val text = repo.readTranscript(item.file)?.let { RecordingRepository.bodyOf(it) } ?: item.transcript
        share.intentFor(item.file, SpeakerNames.apply(text, item.speakerNames))
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
            val r = withContext(Dispatchers.IO) {
                transfer.import(uri) { p -> _importProgress.value = p }
            }
            val err = r.error
            val file = r.file
            if (r.unsealed) _lastError.value = "Chiffrement impossible — WAV conservé en clair"
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

    /** Copie l'audio (déchiffré à la volée) vers l'emplacement choisi via SAF. */
    fun exportAudio(file: File, destUri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { transfer.export(file, destUri) }
            _uiMessage.value = if (ok) {
                "Audio « ${RecordingNames.baseName(file.name)} » exporté"
            } else {
                "Export impossible"
            }
        }
    }

    // ================= EXPORTS STRUCTURÉS (WORD, PDF) =================

    /** Écrit le document Word ou PDF de [file] dans [destUri] (emplacement choisi via SAF). */
    fun exportDocument(file: File, destUri: Uri, format: ExportFormat) {
        if (_isTranscribingFile.value) {
            _uiMessage.value = "Transcription en cours — exporte ensuite"
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    // Document assemblé avant d'ouvrir le flux : un échec de lecture ne laisse pas un flux ouvert
                    val doc = documents.build(file)
                    val out = try {
                        appContext.contentResolver.openOutputStream(destUri, "wt")
                    } catch (e: Exception) {
                        appContext.contentResolver.openOutputStream(destUri)
                    } ?: return@withContext false
                    documents.write(doc, out, format)
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
                    val r = backup.export(out, passphrase.toCharArray())
                    settings.lastBackupAt = System.currentTimeMillis()
                    "Sauvegarde exportée (${r.count} fichiers) — garde précieusement la phrase de passe" +
                        (if (r.unreadable > 0) " (${r.unreadable} texte(s) illisible(s) ignoré(s))" else "")
                } catch (e: Exception) {
                    Log.e(TAG, "exportBackup", e)
                    "Sauvegarde échouée : ${e.message}"
                }
            }
            _uiMessage.value = message
            _backupBusy.value = false
            refreshBackupReminder()
        }
    }

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
                    val r = backup.restore(inp, passphrase.toCharArray())
                    "Restauration terminée : ${r.count} fichiers" +
                        (if (r.unsealed > 0) " — ${r.unsealed} WAV conservé(s) en clair (chiffrement impossible)" else "")
                } catch (e: BackupCrypto.InvalidBackupException) {
                    e.message ?: "Fichier de sauvegarde invalide"
                } catch (e: Exception) {
                    Log.e(TAG, "restoreBackup", e)
                    "Restauration échouée — phrase de passe incorrecte ou fichier corrompu"
                }
            }
            refreshRecordings()
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

    override fun onCleared() {
        stopStreaming()
        transcriber.close()
        playback.stop()
        models.release()
        claude.close()
        super.onCleared()
    }
}
