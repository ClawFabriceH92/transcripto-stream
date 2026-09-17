package com.transcripto.stream.stt

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.transcripto.stream.R
import com.transcripto.stream.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
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

sealed interface ModelState {
    data object Loading : ModelState
    data class Ready(val engine: WhisperStreamEngine) : ModelState
    data class Error(val message: String) : ModelState
}

/**
 * Modèles Whisper : chargement du modèle actif (embarqué extrait de l'APK ou
 * téléchargé, repli automatique sur l'embarqué), catalogue, téléchargements via
 * DownloadManager avec reprise après redémarrage, sélection et suppression.
 * Les flux d'état sont relayés par le ViewModel. Appels depuis le thread principal.
 */
class ModelManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val engine: WhisperStreamEngine,
    /** Verrou du contexte whisper.cpp : jamais de (dé)chargement pendant une transcription. */
    private val lock: Mutex,
    private val scope: CoroutineScope,
    /** Vrai pendant une opération qui utilise le moteur (enregistrement, transcription, import). */
    private val busy: () -> Boolean,
    private val onMessage: (String) -> Unit,
) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODEL_ASSET = "models/ggml-base.bin"
    }

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Loading)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _extractionProgress = MutableStateFlow<Float?>(null)
    val extractionProgress: StateFlow<Float?> = _extractionProgress.asStateFlow()

    private val _loadMessage = MutableStateFlow("Chargement du modèle Whisper…")
    val loadMessage: StateFlow<String> = _loadMessage.asStateFlow()

    private val _modelLoadMs = MutableStateFlow(0L)
    val modelLoadMs: StateFlow<Long> = _modelLoadMs.asStateFlow()

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

    /** Reprise du suivi d'un téléchargement de modèle lancé avant un redémarrage. */
    fun resumePendingDownload() {
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
     * Libère le modèle natif (fin de vie du ViewModel). Portée dédiée : viewModelScope est
     * déjà annulé quand onCleared() s'exécute, un launch dessus ne partirait jamais.
     */
    fun release() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock { engine.unloadModel() }
        }
    }

    /**
     * Charge (ou recharge) le modèle Whisper actif — embarqué ou téléchargé.
     * Le moteur Google reste utilisable pendant ce temps. En cas de modèle
     * téléchargé absent ou illisible, repli automatique sur le modèle embarqué.
     */
    fun load() {
        val previous = modelLoadJob
        _modelState.value = ModelState.Loading
        modelLoadJob = scope.launch {
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
                    onMessage(context.getString(R.string.m_modele_model_label_introuvable_retour_au, model.label))
                    fallbackToEmbedded()
                    return@launch
                }
                path = f.absolutePath
            }
            _loadMessage.value = "Chargement du modèle ${model.label} (${model.approxMb} Mo)…"
            val t0 = System.currentTimeMillis()
            // Sous lock : jamais de déchargement/chargement pendant un transcribeBuffer
            val loaded = lock.withLock {
                engine.unloadModel() // libère l'éventuel modèle précédent
                withTimeoutOrNull(120_000L) {
                    engine.loadModel(path)
                }
            }
            if (loaded == null) {
                // Le JNI n'est pas annulable : arrivé ici, le chargement tardif s'est
                // terminé — on le libère pour ne pas garder ~1,5 Go en état d'erreur.
                lock.withLock { engine.unloadModel() }
                if (model.url != null) {
                    onMessage(context.getString(R.string.m_model_label_trop_long_a_charger_retour, model.label))
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
                onMessage("Échec du chargement de « ${model.label} » — retour au modèle " +
                    "embarqué. Supprime-le puis retélécharge-le si ça persiste.")
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
        load()
    }

    /** Bouton « Réessayer » de la bannière d'erreur du modèle. */
    fun retry() {
        if (_modelState.value is ModelState.Error) load()
    }


    /** Dossier des modèles téléchargés (stockage externe applicatif, requis par DownloadManager). */
    private fun downloadedModelFile(model: WhisperModel): File? {
        val dir = context.getExternalFilesDir("models") ?: return null
        return File(dir, model.fileName)
    }

    fun refreshDownloaded() {
        scope.launch {
            val (set, bytes) = withContext(Dispatchers.IO) {
                val downloading = settings.modelDownloadModel
                val downloaded = ModelCatalog.MODELS
                    .filter { it.url != null && it.id != downloading }
                    .filter { downloadedModelFile(it)?.exists() == true }
                    .map { it.id }
                    .toSet()
                val externalBytes = context.getExternalFilesDir("models")
                    ?.listFiles()?.sumOf { it.length() } ?: 0L
                val embeddedBytes = File(context.filesDir, "models")
                    .listFiles()?.sumOf { it.length() } ?: 0L
                downloaded to (externalBytes + embeddedBytes)
            }
            _downloadedModels.value = set
            _modelStorageBytes.value = bytes
        }
    }

    /** Lance le téléchargement d'un modèle du catalogue (un seul à la fois). */
    fun download(id: String) {
        val model = ModelCatalog.byId(id)
        if (model.url == null) return
        if (settings.modelDownloadId >= 0) {
            onMessage(context.getString(R.string.m_un_telechargement_de_modele_est_deja_en))
            return
        }
        if (id in _downloadedModels.value) return
        val dest = downloadedModelFile(model)
        if (dest == null) {
            onMessage(context.getString(R.string.m_stockage_indisponible_pour_les_modeles))
            return
        }
        val usableMb = (dest.parentFile ?: context.filesDir).usableSpace / (1024L * 1024L)
        if (usableMb < model.approxMb + 100) {
            onMessage(context.getString(R.string.m_espace_insuffisant_usablemb_mo_libres, usableMb, model.approxMb))
            return
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            onMessage(context.getString(R.string.m_telechargement_indisponible_sur_cet))
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
            onMessage(context.getString(R.string.m_telechargement_impossible_e_message, e.message))
            return
        }
        settings.setModelDownload(dlId, id)
        _modelDownloads.value = mapOf(id to 0f)
        trackModelDownload(id, dlId)
    }

    fun cancelDownload() {
        if (settings.modelDownloadId < 0) return
        modelDlJob?.cancel()
        modelDlJob = null
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)
            ?.remove(settings.modelDownloadId)
        clearModelDownloadState(deletePartial = true)
        onMessage(context.getString(R.string.m_telechargement_annule))
    }

    private fun clearModelDownloadState(deletePartial: Boolean) {
        val id = settings.modelDownloadModel
        if (deletePartial && id.isNotEmpty()) {
            downloadedModelFile(ModelCatalog.byId(id))?.delete()
        }
        settings.setModelDownload(-1L, "")
        _modelDownloads.value = emptyMap()
        refreshDownloaded()
    }

    /** Suit la progression DownloadManager jusqu'au succès ou à l'échec. */
    private fun trackModelDownload(modelId: String, downloadId: Long) {
        modelDlJob?.cancel()
        modelDlJob = scope.launch {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
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
                        onMessage(context.getString(R.string.m_modele_telecharge_appuie_sur_activer))
                        break
                    }
                    !found || status == DownloadManager.STATUS_FAILED -> {
                        // remove() : sans lui, l'entrée FAILED garde le chemin de
                        // destination et sa purge future effacerait un modèle
                        // re-téléchargé au même endroit
                        dm.remove(downloadId)
                        clearModelDownloadState(deletePartial = true)
                        onMessage(
                            if (!found) {
                                "Téléchargement du modèle annulé" // retiré via la notification système
                            } else {
                                "Téléchargement du modèle échoué" +
                                    if (reason >= 0) " (code $reason)" else ""
                            },
                        )
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
    fun select(id: String) {
        if (id == _activeModelId.value && _modelState.value is ModelState.Ready) return
        if (_modelState.value is ModelState.Loading) {
            onMessage(context.getString(R.string.m_un_modele_est_deja_en_cours_de))
            return
        }
        if (busy()) {
            onMessage(context.getString(R.string.m_changement_de_modele_impossible_pendant))
            return
        }
        val model = ModelCatalog.byId(id)
        scope.launch {
            val available = model.url == null ||
                withContext(Dispatchers.IO) { downloadedModelFile(model)?.exists() == true }
            if (!available) {
                onMessage(context.getString(R.string.m_telecharge_d_abord_ce_modele))
                return@launch
            }
            settings.modelId = model.id
            _activeModelId.value = model.id
            load()
        }
    }

    /** Supprime un modèle téléchargé (bascule sur l'embarqué s'il était actif). */
    fun delete(id: String) {
        val model = ModelCatalog.byId(id)
        if (model.url == null) return
        if (_modelState.value is ModelState.Loading) {
            onMessage(context.getString(R.string.m_attends_la_fin_du_chargement_du_modele))
            return
        }
        if (busy()) {
            onMessage(context.getString(R.string.m_suppression_impossible_pendant_une))
            return
        }
        if (_activeModelId.value == id) {
            settings.modelId = ModelCatalog.EMBEDDED_ID
            _activeModelId.value = ModelCatalog.EMBEDDED_ID
            load()
        }
        scope.launch {
            // Suppression sûre même si le modèle vient d'être déchargé : sous Linux,
            // l'inode d'un fichier encore mmappé survit jusqu'à sa fermeture.
            withContext(Dispatchers.IO) { downloadedModelFile(model)?.delete() }
            refreshDownloaded()
            onMessage(context.getString(R.string.m_modele_model_label_supprime, model.label))
        }
    }

    private suspend fun ensureModelExtracted(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val dest = File(dir, "ggml-base.bin")
            if (!dest.exists() || dest.length() == 0L) {
                Log.i(TAG, "Extraction du modèle depuis assets…")
                val total = context.assets.open(MODEL_ASSET).use { it.available() }
                context.assets.open(MODEL_ASSET).use { input ->
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
}
