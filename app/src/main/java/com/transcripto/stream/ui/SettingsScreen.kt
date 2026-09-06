package com.transcripto.stream.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcripto.stream.stt.ModelCatalog
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
            .verticalScroll(rememberScrollState())
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
                            ) { Text("Activer") }
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
                placeholder = { Text("CAC, commissaire aux comptes, exercice, noms de clients…") },
                supportingText = { Text("Termes séparés par des virgules, soufflés aux moteurs de reconnaissance.") },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { vm.setVocabulary(vocab) }) {
                    Text("Enregistrer le vocabulaire")
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
        }

        // ================= SAUVEGARDE =================
        SectionCard(title = "Sauvegarde", icon = AppIcons.Backup) {
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
                    Text("Restaurer", maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            HintText(
                "Archive chiffrée par une phrase de passe, restaurable sur un autre appareil " +
                    "(les WAV chiffrés y sont inclus). Sans la phrase de passe, la sauvegarde est " +
                    "illisible : note-la précieusement.",
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
                    Text("Verrouillage PIN", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (pinActive) "Actif — code demandé à chaque lancement" else "Désactivé",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!pinActive) {
                    FilledTonalButton(onClick = { pinDialog = true }) { Text("Activer") }
                }
            }
            if (pinActive) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.lockNow() }) { Text("Verrouiller maintenant") }
                    TextButton(onClick = { vm.disablePin() }) {
                        Text("Désactiver", color = MaterialTheme.colorScheme.error)
                    }
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
                            ) { Text("Autoriser") }
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
                    label = { Text("Phrase de passe") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (isExport) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass2,
                        onValueChange = { pass2 = it },
                        label = { Text("Confirmer") },
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
            TextButton(onClick = onDismiss) { Text("Annuler") }
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
        title = { Text("Définir un PIN (4 chiffres)") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin1,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin1 = it },
                    label = { Text("Nouveau PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin2,
                    onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) pin2 = it },
                    label = { Text("Confirmer le PIN") },
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
            }) { Text("Activer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
