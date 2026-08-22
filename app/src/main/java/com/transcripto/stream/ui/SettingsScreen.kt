package com.transcripto.stream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcripto.stream.stt.ModelCatalog
import com.transcripto.stream.update.AutoUpdater
import com.transcripto.stream.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * Réglages : langue, gain micro, vocabulaire personnalisé, horodatage,
 * rétention RGPD, PIN, chiffrement, thème.
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
    ) {
        Spacer(Modifier.height(8.dp))

        SectionTitle("Qualité de transcription")

        Text("Modèle Whisper (transcription locale)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        ModelCatalog.MODELS.forEach { model ->
            val isActive = model.id == activeModelId
            val isDownloaded = model.url == null || model.id in downloadedModels
            val progress = modelDownloads[model.id]
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isActive) "${model.label} — actif" else model.label,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        model.description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (progress != null) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                when {
                    progress != null -> {
                        TextButton(onClick = { vm.cancelModelDownload() }) {
                            Text("${(progress * 100).toInt()} % · Annuler", fontSize = 12.sp)
                        }
                    }
                    isActive -> Unit
                    isDownloaded -> {
                        TextButton(
                            onClick = { vm.selectModel(model.id) },
                            enabled = !modelBusy,
                        ) {
                            Text("Activer", fontSize = 12.sp)
                        }
                        if (model.url != null) {
                            IconButton(
                                onClick = { vm.deleteModel(model.id) },
                                enabled = !modelBusy,
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Supprimer le modèle ${model.label}",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.height(18.dp),
                                )
                            }
                        }
                    }
                    else -> {
                        TextButton(onClick = { vm.downloadModel(model.id) }) {
                            Text("Télécharger (${model.approxMb} Mo)", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Text(
            "La transcription différée (« Transcrire ») utilise le modèle actif : active un meilleur modèle puis relance « Transcrire » sur un enregistrement pour améliorer son compte rendu.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // Miroirs locaux : les SharedPreferences ne sont pas observables par Compose —
        // sans eux, chips/curseurs/interrupteurs ne bougent pas visuellement au tap.
        var language by remember { mutableStateOf(settings.language) }
        Text("Langue", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("fr" to "Français", "en" to "Anglais", "auto" to "Auto").forEach { (code, label) ->
                FilterChip(
                    selected = language == code,
                    onClick = {
                        language = code
                        vm.setLanguage(code)
                    },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        var micGain by remember { mutableStateOf(settings.micGain) }
        Text("Gain du micro : ${"%.1f".format(micGain)}x", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Slider(
            value = micGain,
            onValueChange = {
                micGain = it
                vm.setMicGain(it)
            },
            valueRange = 0.5f..4.0f,
        )
        Text("Augmente si la voix est trop faible (bout de table, salle de réunion).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        Text("Vocabulaire personnalisé (séparé par des virgules)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = vocab,
            onValueChange = { vocab = it },
            modifier = Modifier.fillMaxWidth().height(140.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("CAC, commissaire aux comptes, exercice, noms de clients…") },
        )
        OutlinedButton(onClick = { vm.setVocabulary(vocab) }, modifier = Modifier.padding(top = 4.dp)) {
            Text("Enregistrer le vocabulaire")
        }
        Spacer(Modifier.height(8.dp))

        var useTimestamps by remember { mutableStateOf(settings.useTimestamps) }
        SwitchRow(
            title = "Horodatage [mm:ss] par segment",
            subtitle = "Dans la transcription différée et le .txt",
            checked = useTimestamps,
            onChange = {
                useTimestamps = it
                vm.setUseTimestamps(it)
            },
        )

        var muteListening by remember { mutableStateOf(settings.muteWhileListening) }
        SwitchRow(
            title = "Écoute silencieuse (moteur Google)",
            subtitle = "Coupe les bips du système de reconnaissance pendant l'écoute ; le volume est rétabli à l'arrêt.",
            checked = muteListening,
            onChange = {
                muteListening = it
                vm.setMuteWhileListening(it)
            },
        )

        SectionTitle("Gestion des enregistrements")

        Text(
            "Espace utilisé : ${"%.1f".format(storageBytes / (1024f * 1024f))} Mo d'enregistrements (~100 Mo par heure de WAV) · " +
                "${"%.0f".format(modelStorageBytes / (1024f * 1024f))} Mo de modèles Whisper",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        var retentionDays by remember { mutableStateOf(settings.retentionDays) }
        Text("Rétention automatique (RGPD)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Jamais", 30 to "30 j", 60 to "60 j", 90 to "90 j").forEach { (days, label) ->
                FilterChip(
                    selected = retentionDays == days,
                    onClick = {
                        retentionDays = days
                        vm.setRetentionDays(days)
                    },
                    label = { Text(label) },
                )
            }
        }
        Text("Les enregistrements plus vieux que cette durée sont supprimés automatiquement.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        var encryptWav by remember { mutableStateOf(settings.encryptWav) }
        SwitchRow(
            title = "Chiffrer les WAV (AES-256)",
            subtitle = "Clé dans le stockage sécurisé Android. Lecture/écoute/email déchiffrent à la volée.",
            checked = encryptWav,
            onChange = {
                encryptWav = it
                vm.setEncryptWav(it)
            },
        )

        SectionTitle("Sécurité")

        if (settings.pinHash.isEmpty()) {
            OutlinedButton(onClick = { pinDialog = true }) {
                Text("Activer le verrouillage PIN")
            }
        } else {
            Text("Verrouillage PIN actif", fontWeight = FontWeight.Medium)
            OutlinedButton(onClick = { vm.disablePin() }) {
                Text("Désactiver le PIN", color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { vm.lockNow() }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Verrouiller maintenant")
            }
        }

        SectionTitle("Mises à jour")

        var autoUpdate by remember { mutableStateOf(UpdateManager.autoUpdateEnabled(context)) }
        SwitchRow(
            title = "Mise à jour automatique",
            subtitle = "Vérifie GitHub au lancement et chaque jour à 14 h, puis télécharge et installe la nouvelle version.",
            checked = autoUpdate,
            onChange = {
                autoUpdate = it
                UpdateManager.setAutoUpdate(context, it)
            },
        )
        var canInstall by remember { mutableStateOf(AutoUpdater.canRequestInstalls(context)) }
        if (!canInstall) {
            OutlinedButton(onClick = { AutoUpdater.openInstallSettings(context) }) {
                Text("Autoriser l'installation automatique")
            }
            Text(
                "Sans cette autorisation système, l'app peut télécharger une mise à jour mais pas l'installer.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        var checkingUpdate by remember { mutableStateOf(false) }
        val updateScope = rememberCoroutineScope()
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
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(if (checkingUpdate) "Vérification…" else "Vérifier maintenant")
        }

        SectionTitle("Apparence")

        var theme by remember { mutableStateOf(settings.theme) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Système", "light" to "Clair", "dark" to "Sombre").forEach { (code, label) ->
                FilterChip(
                    selected = theme == code,
                    onClick = {
                        theme = code
                        vm.setTheme(code)
                    },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(
            "Indicateur local/cloud : visible sur l'écran principal. Google = audio au fournisseur ; Whisper = 100% local.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        val versionName = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
        }
        Text(
            "Version $versionName · Astuces : tuile « Transcrire » dans les réglages rapides, raccourci sur l'icône de l'app, " +
                "« Partager vers Transcripto » depuis n'importe quelle app pour transcrire un audio reçu.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
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
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
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
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
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
