package com.transcripto.stream.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcripto.stream.R
import com.transcripto.stream.stt.ModelState
import com.transcripto.stream.data.RecordingItem
import com.transcripto.stream.CrashLog
import com.transcripto.stream.stt.ModelCatalog
import com.transcripto.stream.summary.ClaudeSummarizer
import com.transcripto.stream.ui.theme.AppIcons
import com.transcripto.stream.update.AutoUpdater
import com.transcripto.stream.update.UpdateManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Réglages, en sections : transcription locale, reconnaissance, comportement,
 * stockage & confidentialité, sauvegarde, sécurité, mises à jour, apparence, à propos.
 */
@Composable
fun SettingsScreen(vm: StreamViewModel) {
    val scrollState = rememberScrollState()
    // Ouverture depuis le bandeau de rappel : défiler jusqu'à la carte Sauvegarde, dont la
    // position dans la colonne est relevée à la mise en page
    val settingsTarget by vm.settingsTarget.collectAsStateWithLifecycle()
    var backupCardY by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settingsTarget, backupCardY) {
        val y = backupCardY
        if (settingsTarget == "backup" && y != null) {
            vm.consumeSettingsTarget()
            scrollState.animateScrollTo((y - 16).coerceAtLeast(0))
        }
    }
    val settings = vm.settings
    val context = LocalContext.current
    val storageBytes by vm.storageBytes.collectAsStateWithLifecycle()
    val activeModelId by vm.activeModelId.collectAsStateWithLifecycle()
    val downloadedModels by vm.downloadedModels.collectAsStateWithLifecycle()
    val modelDownloads by vm.modelDownloads.collectAsStateWithLifecycle()
    val modelStorageBytes by vm.modelStorageBytes.collectAsStateWithLifecycle()
    val modelState by vm.modelState.collectAsStateWithLifecycle()
    val modelBusy = modelState is ModelState.Loading
    var vocab by remember { mutableStateOf(settings.vocabulary) }
    var pinDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(0.dp))

        // ================= TRANSCRIPTION LOCALE =================
        SectionCard(title = "Transcription locale (Whisper)", icon = AppIcons.Waveform) {
            ModelCatalog.MODELS.forEachIndexed { index, model ->
                val isActive = model.id == activeModelId
                val isDownloaded = model.url == null || model.id in downloadedModels
                val progress = modelDownloads[model.id]
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        progress != null -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        isActive -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Modèle actif",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        isDownloaded -> Icon(
                            Icons.Filled.Check,
                            contentDescription = "Téléchargé",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        else -> Icon(
                            AppIcons.Download,
                            contentDescription = "À télécharger",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = model.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress != null) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        progress != null -> TextButton(onClick = { vm.cancelModelDownload() }) {
                            Text("${(progress * 100).toInt()} % · Annuler")
                        }
                        isActive -> MetaChip(
                            text = "Actif",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            container = MaterialTheme.colorScheme.primaryContainer,
                        )
                        isDownloaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { vm.selectModel(model.id) },
                                enabled = !modelBusy,
                            ) { Text(stringResource(R.string.s_activer)) }
                            if (model.url != null) {
                                IconButton(
                                    onClick = { vm.deleteModel(model.id) },
                                    enabled = !modelBusy,
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Supprimer le modèle ${model.label}",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        else -> TextButton(onClick = { vm.downloadModel(model.id) }) {
                            Text("${model.approxMb} Mo", maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            HintText(
                "« Transcrire » utilise le modèle actif : active un meilleur modèle puis relance " +
                    "la transcription d'un enregistrement pour améliorer son compte rendu.",
            )
        }

        // ================= RECONNAISSANCE =================
        SectionCard(title = "Reconnaissance", icon = AppIcons.Language) {
            // Miroirs locaux : les SharedPreferences ne sont pas observables par Compose —
            // sans eux, sélecteurs/curseurs/interrupteurs ne bougent pas visuellement au tap.
            var language by remember { mutableStateOf(settings.language) }
            SettingLabel("Langue")
            Spacer(Modifier.height(6.dp))
            SegmentedChoice(
                options = listOf("fr" to "Français", "en" to "Anglais", "auto" to "Auto"),
                selected = language,
                onSelect = {
                    language = it
                    vm.setLanguage(it)
                },
            )
            Spacer(Modifier.height(16.dp))

            var micGain by remember { mutableStateOf(settings.micGain) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingLabel("Gain du micro")
                Spacer(Modifier.weight(1f))
                MetaChip(text = "${"%.1f".format(Locale.FRANCE, micGain)}×")
            }
            Slider(
                value = micGain,
                onValueChange = {
                    micGain = it
                    vm.setMicGain(it)
                },
                valueRange = 0.5f..4.0f,
            )
            HintText("Augmente si la voix est trop faible (bout de table, salle de réunion).")
            Spacer(Modifier.height(16.dp))

            SettingLabel("Vocabulaire personnalisé")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = vocab,
                onValueChange = { vocab = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text(stringResource(R.string.s_cac_commissaire_aux_comptes_exercice)) },
                supportingText = { Text(stringResource(R.string.s_termes_separes_par_des_virgules_souffles)) },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { vm.setVocabulary(vocab) }) {
                    Text(stringResource(R.string.s_enregistrer_le_vocabulaire))
                }
            }
        }

        // ================= COMPORTEMENT =================
        SectionCard(title = "Comportement", icon = AppIcons.Tune, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)) {
            var useTimestamps by remember { mutableStateOf(settings.useTimestamps) }
            SettingSwitchRow(
                title = "Horodatage par segment",
                subtitle = "[mm:ss] dans la transcription différée et le .txt.",
                checked = useTimestamps,
                onChange = {
                    useTimestamps = it
                    vm.setUseTimestamps(it)
                },
                icon = AppIcons.Clock,
            )
            var vadSilero by remember { mutableStateOf(settings.vadSilero) }
            SettingSwitchRow(
                title = "Détection de parole neuronale (Silero)",
                subtitle = "En mode Whisper : les phrases sont transcrites dès qu'elles se terminent, les silences et " +
                    "bruits ignorés. Désactivé ou indisponible : détection par volume.",
                checked = vadSilero,
                onChange = {
                    vadSilero = it
                    vm.setVadSilero(it)
                },
                icon = AppIcons.Waveform,
            )
            var reviewByDefault by remember { mutableStateOf(settings.reviewByDefault) }
            SettingSwitchRow(
                title = "Vérification par défaut sur la fiche",
                subtitle = "Ouvre chaque transcription en mode Vérification : passages sous 60 % de confiance teintés, " +
                    "montants et dates soulignés, bouton « Suivant ».",
                checked = reviewByDefault,
                onChange = {
                    reviewByDefault = it
                    settings.reviewByDefault = it
                },
                icon = AppIcons.Document,
            )
            var muteListening by remember { mutableStateOf(settings.muteWhileListening) }
            SettingSwitchRow(
                title = "Écoute silencieuse (Google)",
                subtitle = "Coupe les bips du système de reconnaissance pendant l'écoute ; volume rétabli à l'arrêt.",
                checked = muteListening,
                onChange = {
                    muteListening = it
                    vm.setMuteWhileListening(it)
                },
                icon = AppIcons.VolumeOff,
            )
            var dictation by remember { mutableStateOf(settings.dictationMode) }
            SettingSwitchRow(
                title = "Mode dictée",
                subtitle = "« point », « virgule », « à la ligne »… dits à la voix deviennent de la ponctuation. Pour les notes dictées, pas les réunions.",
                checked = dictation,
                onChange = {
                    dictation = it
                    vm.setDictationMode(it)
                },
                icon = AppIcons.Dictation,
            )
        }

        // ================= SYNTHÈSE =================
        SectionCard(
            title = "Synthèse de fin d'enregistrement",
            icon = AppIcons.Sparkle,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
        ) {
            var propose by remember { mutableStateOf(settings.proposeSummary) }
            SettingSwitchRow(
                title = "Proposer une synthèse",
                subtitle = "À la fin de chaque enregistrement, un message propose de générer la synthèse : points clés, décisions, actions, chiffres cités.",
                checked = propose,
                onChange = {
                    propose = it
                    vm.setProposeSummary(it)
                },
                icon = AppIcons.Sparkle,
            )
            var aiEnabled by remember { mutableStateOf(settings.aiSummaryEnabled) }
            SettingSwitchRow(
                title = "Synthèse rédigée par l'IA (Claude)",
                subtitle = "Via l'API Anthropic avec ta clé : seul le texte de la transcription est envoyé, jamais l'audio ; facturé sur ton compte Anthropic. Sans clé, hors ligne ou en cas d'erreur : synthèse locale.",
                checked = aiEnabled,
                onChange = {
                    aiEnabled = it
                    vm.setAiSummaryEnabled(it)
                },
                icon = AppIcons.Cloud,
            )
            if (aiEnabled) {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    Spacer(Modifier.height(8.dp))
                    var hasKey by remember { mutableStateOf(vm.hasAiApiKey()) }
                    var keyInput by remember { mutableStateOf("") }
                    var showKey by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (hasKey) "Clé API enregistrée (chiffrée sur l'appareil)" else "Aucune clé API enregistrée",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (hasKey) "Remplacer la clé" else "Clé API Anthropic (sk-ant-…)") },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { showKey = !showKey }) {
                                Text(if (showKey) "Masquer" else "Voir")
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = {
                                if (vm.setAiApiKey(keyInput)) {
                                    hasKey = keyInput.isNotBlank()
                                    keyInput = ""
                                    vm.showMessage(if (hasKey) "Clé API enregistrée" else "Clé API effacée")
                                } else {
                                    vm.showMessage("Impossible de chiffrer la clé sur cet appareil")
                                }
                            },
                            enabled = keyInput.isNotBlank(),
                        ) { Text(stringResource(R.string.s_enregistrer_la_cle)) }
                        if (hasKey) {
                            TextButton(onClick = {
                                vm.setAiApiKey("")
                                hasKey = false
                                vm.showMessage("Clé API effacée")
                            }) { Text(stringResource(R.string.s_effacer), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    var aiModel by remember { mutableStateOf(settings.aiModel) }
                    SettingLabel("Modèle Claude")
                    Spacer(Modifier.height(6.dp))
                    SegmentedChoice(
                        options = ClaudeSummarizer.MODELS,
                        selected = aiModel,
                        onSelect = {
                            aiModel = it
                            vm.setAiModel(it)
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    HintText(
                        "Opus 5 : la meilleure rédaction (par défaut). Sonnet 5 : rapide et moins cher. " +
                            "Haiku 4.5 : le plus économique. La clé se crée sur console.anthropic.com.",
                    )
                }
            }
        }

        // ================= STOCKAGE & CONFIDENTIALITÉ =================
        SectionCard(title = "Stockage et confidentialité", icon = AppIcons.Storage) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaChip(
                    text = "${"%.1f".format(Locale.FRANCE, storageBytes / (1024f * 1024f))} Mo d'enregistrements",
                    icon = AppIcons.Waveform,
                )
                MetaChip(
                    text = "${"%.0f".format(Locale.FRANCE, modelStorageBytes / (1024f * 1024f))} Mo de modèles",
                    icon = AppIcons.Download,
                )
            }
            Spacer(Modifier.height(4.dp))
            HintText("Un WAV pèse environ 100 Mo par heure d'enregistrement.")
            Spacer(Modifier.height(16.dp))

            var retentionDays by remember { mutableStateOf(settings.retentionDays) }
            SettingLabel("Rétention automatique (RGPD)")
            Spacer(Modifier.height(6.dp))
            SegmentedChoice(
                options = listOf("0" to "Jamais", "30" to "30 j", "60" to "60 j", "90" to "90 j"),
                selected = retentionDays.toString(),
                onSelect = {
                    val days = it.toIntOrNull() ?: 0
                    retentionDays = days
                    vm.setRetentionDays(days)
                },
            )
            Spacer(Modifier.height(4.dp))
            HintText("Les enregistrements plus anciens que cette durée sont supprimés automatiquement.")
            Spacer(Modifier.height(8.dp))

            var encryptWav by remember { mutableStateOf(settings.encryptWav) }
            SettingSwitchRow(
                title = "Chiffrer les WAV (AES-256)",
                subtitle = "Clé dans le stockage sécurisé Android ; lecture, partage et export déchiffrent à la volée.",
                checked = encryptWav,
                onChange = {
                    encryptWav = it
                    vm.setEncryptWav(it)
                },
                icon = Icons.Filled.Lock,
            )
            var encryptTexts by remember { mutableStateOf(settings.encryptTexts) }
            SettingSwitchRow(
                title = "Chiffrer aussi les textes",
                subtitle = "Transcriptions, sous-titres, synthèses et métadonnées scellés au repos (même clé) ; " +
                    "les fichiers existants sont convertis immédiatement.",
                checked = encryptTexts,
                onChange = {
                    if (vm.setEncryptTexts(it)) encryptTexts = it
                },
                icon = Icons.Filled.Lock,
            )
        }

        // ================= SAUVEGARDE =================
        SectionCard(
            title = "Sauvegarde",
            icon = AppIcons.Backup,
            modifier = Modifier.onGloballyPositioned { backupCardY = it.positionInParent().y.toInt() },
        ) {
            val backupBusy by vm.backupBusy.collectAsStateWithLifecycle()
            var backupMode by remember { mutableStateOf<String?>(null) } // "export" | "restore"
            var backupUri by remember { mutableStateOf<Uri?>(null) }
            val backupExportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri ->
                if (uri != null) {
                    backupUri = uri
                    backupMode = "export"
                }
            }
            val backupRestoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    backupUri = uri
                    backupMode = "restore"
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    enabled = !backupBusy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // Garde AVANT d'ouvrir le sélecteur : sinon SAF crée un document
                        // que exportBackup refuserait ensuite (fichier fantôme de 0 octet)
                        if (!vm.backupBlocked()) {
                            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                            backupExportLauncher.launch("transcripto-sauvegarde-$stamp.tsbk")
                        }
                    },
                ) {
                    Icon(AppIcons.Backup, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(if (backupBusy) "En cours…" else "Exporter", maxLines = 1)
                }
                OutlinedButton(
                    enabled = !backupBusy,
                    modifier = Modifier.weight(1f),
                    onClick = { if (!vm.backupBlocked()) backupRestoreLauncher.launch("*/*") },
                ) {
                    Icon(AppIcons.Restore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.s_restaurer), maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            HintText(
                "Archive chiffrée par une phrase de passe, restaurable sur un autre appareil " +
                    "(les WAV chiffrés y sont inclus). Sans la phrase de passe, la sauvegarde est " +
                    "illisible : note-la précieusement.",
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.s_me_rappeler_de_sauvegarder), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            var reminderDays by remember { mutableStateOf(vm.settings.backupReminderDays.toString()) }
            SegmentedChoice(
                options = listOf("0" to "Jamais", "7" to "7 jours", "30" to "30 jours"),
                selected = reminderDays,
                onSelect = { reminderDays = it; vm.setBackupReminderDays(it.toInt()) },
            )
            Spacer(Modifier.height(4.dp))
            val lastBackup = vm.lastBackupAt()
            HintText(
                if (lastBackup > 0) {
                    "Dernière sauvegarde : " + SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE).format(Date(lastBackup)) +
                        ". Un bandeau apparaît en haut de la liste quand le délai est dépassé — rien ne tourne en arrière-plan."
                } else {
                    "Aucune sauvegarde exportée pour l'instant. Un bandeau apparaît en haut de la liste quand le délai est dépassé — rien ne tourne en arrière-plan."
                },
            )

            backupMode?.let { mode ->
                PassphraseDialog(
                    isExport = mode == "export",
                    onConfirm = { passphrase ->
                        val uri = backupUri
                        backupMode = null
                        backupUri = null
                        if (uri != null) {
                            if (mode == "export") vm.exportBackup(uri, passphrase)
                            else vm.restoreBackup(uri, passphrase)
                        }
                    },
                    onDismiss = {
                        // Export annulé : ne supprimer le document créé par le sélecteur SAF
                        // que s'il est VIDE. « Remplacer » un .tsbk existant renvoie l'URI du
                        // fichier INTACT — le supprimer détruirait la sauvegarde précédente.
                        if (mode == "export") {
                            backupUri?.let { uri ->
                                try {
                                    val size = context.contentResolver.query(
                                        uri, arrayOf(OpenableColumns.SIZE), null, null, null
                                    )?.use { c ->
                                        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                                    } ?: -1L
                                    if (size == 0L) {
                                        DocumentsContract.deleteDocument(context.contentResolver, uri)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                        backupMode = null
                        backupUri = null
                    },
                )
            }
        }

        // ================= SÉCURITÉ =================
        SectionCard(title = "Sécurité", icon = AppIcons.Security) {
            val pinActive = settings.pinHash.isNotEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.s_verrouillage_pin), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (pinActive) "Actif — code demandé au lancement et après le délai choisi" else "Désactivé",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!pinActive) {
                    FilledTonalButton(onClick = { pinDialog = true }) { Text(stringResource(R.string.s_activer)) }
                }
            }
            if (pinActive) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.lockNow() }) { Text(stringResource(R.string.s_verrouiller_maintenant)) }
                    TextButton(onClick = { vm.disablePin() }) {
                        Text(stringResource(R.string.s_desactiver), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(12.dp))
                var autoLock by remember { mutableStateOf(settings.autoLockMinutes) }
                SettingLabel("Verrouiller après un passage en arrière-plan")
                Spacer(Modifier.height(6.dp))
                SegmentedChoice(
                    options = listOf("-1" to "Jamais", "1" to "1 min", "5" to "5 min", "15" to "15 min"),
                    selected = autoLock.toString(),
                    onSelect = {
                        val m = it.toIntOrNull() ?: -1
                        autoLock = m
                        vm.setAutoLockMinutes(m)
                    },
                )
                Spacer(Modifier.height(4.dp))
                HintText("« Jamais » : code demandé seulement au lancement de l'app.")
                val biometricAvailable = remember(context) { Biometrics.available(context) }
                if (biometricAvailable) {
                    Spacer(Modifier.height(8.dp))
                    var biometric by remember { mutableStateOf(settings.biometricUnlock) }
                    SettingSwitchRow(
                        title = "Déverrouillage biométrique",
                        subtitle = "Empreinte ou visage proposés à la place du PIN (qui reste utilisable). " +
                            "À défaut, le code de verrouillage de l'appareil est accepté : qui le connaît " +
                            "contourne le PIN de l'app.",
                        checked = biometric,
                        onChange = {
                            biometric = it
                            vm.setBiometricUnlock(it)
                        },
                        icon = AppIcons.Security,
                    )
                }
            }
        }

        // ================= MISES À JOUR =================
        SectionCard(title = "Mises à jour", icon = AppIcons.Update, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)) {
            var autoUpdate by remember { mutableStateOf(UpdateManager.autoUpdateEnabled(context)) }
            SettingSwitchRow(
                title = "Mise à jour automatique",
                subtitle = "Vérifie GitHub au lancement et chaque jour à 14 h, puis télécharge et installe la nouvelle version.",
                checked = autoUpdate,
                onChange = {
                    autoUpdate = it
                    UpdateManager.setAutoUpdate(context, it)
                },
                icon = AppIcons.Download,
            )
            var canInstall by remember { mutableStateOf(AutoUpdater.canRequestInstalls(context)) }
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                if (!canInstall) {
                    Spacer(Modifier.height(4.dp))
                    InlineBanner(
                        text = "Sans l'autorisation système « installer des apps inconnues », l'app peut télécharger une mise à jour mais pas l'installer.",
                        icon = Icons.Filled.Warning,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        action = {
                            TextButton(
                                onClick = { AutoUpdater.openInstallSettings(context) },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                ),
                            ) { Text(stringResource(R.string.s_autoriser)) }
                        },
                    )
                }
                var checkingUpdate by remember { mutableStateOf(false) }
                val updateScope = rememberCoroutineScope()
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        checkingUpdate = true
                        updateScope.launch {
                            val message = UpdateManager.checkNowAndReport(context)
                            vm.showMessage(message)
                            canInstall = AutoUpdater.canRequestInstalls(context)
                            checkingUpdate = false
                        }
                    },
                    enabled = !checkingUpdate,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(if (checkingUpdate) "Vérification…" else "Vérifier maintenant")
                }
            }
        }

        // ================= APPARENCE =================
        SectionCard(title = "Apparence", icon = AppIcons.Palette) {
            var theme by remember { mutableStateOf(settings.theme) }
            SettingLabel("Thème")
            Spacer(Modifier.height(6.dp))
            SegmentedChoice(
                options = listOf("system" to "Système", "light" to "Clair", "dark" to "Sombre"),
                selected = theme,
                onSelect = {
                    theme = it
                    vm.setTheme(it)
                },
            )
        }

        // ================= À PROPOS =================
        SectionCard(title = "À propos", icon = Icons.Filled.Info) {
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (e: Exception) {
                    "?"
                }
            }
            Text("Transcripto Stream $versionName", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            HintText(
                "Indicateur local/cloud sur l'écran principal : Google envoie l'audio au fournisseur, " +
                    "Whisper est 100 % local.",
            )
            Spacer(Modifier.height(6.dp))
            HintText(
                "Astuces : tuile « Transcrire » dans les réglages rapides, raccourci d'appui long sur " +
                    "l'icône, « Partager vers Transcripto » depuis n'importe quelle app pour transcrire " +
                    "un audio reçu.",
            )

            var crashCount by remember { mutableStateOf(CrashLog.count(context)) }
            var showCrashLog by remember { mutableStateOf(false) }
            Spacer(Modifier.height(10.dp))
            Text(
                if (crashCount > 0) "Journal des incidents : $crashCount plantage(s) enregistré(s)" else "Journal des incidents : aucun plantage enregistré",
                style = MaterialTheme.typography.bodySmall,
                color = if (crashCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (crashCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showCrashLog = true }) { Text(stringResource(R.string.s_voir)) }
                    TextButton(onClick = {
                        // Pièce jointe via FileProvider : un journal de 200 000 caractères en
                        // EXTRA_TEXT frôlerait la limite des transactions Binder (1 Mo)
                        val f = CrashLog.file(context)
                        if (f != null) {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Transcripto Stream — journal des incidents")
                                putExtra(Intent.EXTRA_TEXT, "Journal des incidents en pièce jointe (${crashCount} plantage(s)).")
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Partager le journal"))
                        }
                    }) { Text(stringResource(R.string.s_partager)) }
                    TextButton(onClick = {
                        CrashLog.clear(context)
                        crashCount = 0
                    }) { Text(stringResource(R.string.s_effacer), color = MaterialTheme.colorScheme.error) }
                }
            }
            if (showCrashLog) {
                AlertDialog(
                    onDismissRequest = { showCrashLog = false },
                    title = { Text(stringResource(R.string.s_journal_des_incidents)) },
                    text = {
                        Text(
                            CrashLog.read(context),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                        )
                    },
                    confirmButton = { TextButton(onClick = { showCrashLog = false }) { Text(stringResource(R.string.s_fermer)) } },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (pinDialog) {
        PinSetupDialog(
            onConfirm = { pin ->
                vm.enablePin(pin)
                pinDialog = false
            },
            onDismiss = { pinDialog = false },
        )
    }
}

@Composable
private fun PassphraseDialog(
    isExport: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isExport) "Protéger la sauvegarde" else "Phrase de passe") },
        text = {
            Column {
                if (isExport) {
                    Text(
                        "Au moins 8 caractères. Elle sera exigée à la restauration — sans elle, la sauvegarde est définitivement illisible.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = pass1,
                    onValueChange = { pass1 = it },
                    label = { Text(stringResource(R.string.s_phrase_de_passe)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (isExport) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass2,
                        onValueChange = { pass2 = it },
                        label = { Text(stringResource(R.string.s_confirmer)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    isExport && pass1.length < 8 -> error = "Au moins 8 caractères"
                    isExport && pass1 != pass2 -> error = "Les deux saisies ne correspondent pas"
                    !isExport && pass1.isEmpty() -> error = "Saisis la phrase de passe"
                    else -> onConfirm(pass1)
                }
            }) { Text(if (isExport) "Exporter" else "Restaurer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s_annuler)) }
        },
    )
}

@Composable
private fun PinSetupDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.s_definir_un_pin_4_chiffres)) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin1,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin1 = it },
                    label = { Text(stringResource(R.string.s_nouveau_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin2,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin2 = it },
                    label = { Text(stringResource(R.string.s_confirmer_le_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin1.length < 4 -> error = "Le PIN doit faire 4 chiffres"
                    pin1 != pin2 -> error = "Les deux saisies ne correspondent pas"
                    else -> onConfirm(pin1)
                }
            }) { Text(stringResource(R.string.s_activer)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s_annuler)) }
        },
    )
}
