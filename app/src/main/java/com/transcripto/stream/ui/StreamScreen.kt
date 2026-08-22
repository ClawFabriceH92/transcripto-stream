package com.transcripto.stream.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.transcripto.stream.MainActivity
import com.transcripto.stream.R
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.ui.theme.AppTheme
import kotlinx.coroutines.launch

private fun formatTime(sec: Long): String {
    val mm = sec / 60
    val ss = sec % 60
    return "%02d:%02d".format(mm, ss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScreen() {
    val context = LocalContext.current
    val vm: StreamViewModel = viewModel(
        factory = viewModelFactory {
            initializer { StreamViewModel(context.applicationContext) }
        }
    )

    val screen by vm.screen.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val pinError by vm.pinError.collectAsStateWithLifecycle()
    val pendingName by vm.pendingName.collectAsStateWithLifecycle()
    val pendingNameDefault by vm.pendingNameDefault.collectAsStateWithLifecycle()

    // Thème (réglage système/clair/sombre)
    val darkTheme = when (vm.settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    // Raccourci / tuile « Enregistrer » : on ramène l'onglet principal,
    // MainScreen déclenche ensuite le démarrage une fois permissions et modèle OK.
    val recordRequested by MainActivity.recordRequest.collectAsStateWithLifecycle()
    LaunchedEffect(recordRequested) {
        if (recordRequested) vm.navigate(0)
    }

    AppTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (locked) {
                PinScreen(
                    onUnlock = { vm.unlock(it) },
                    pinError = pinError,
                    onClearError = { vm.setPinError(null) },
                )
            } else {
                // Bouton retour système : revient à l'onglet Enregistrer au lieu de quitter
                BackHandler(enabled = screen != 0) { vm.navigate(0) }

                val snackbarHostState = remember { SnackbarHostState() }

                // Sélecteur de fichier pour l'import d'audio externe (FAB de la liste)
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> if (uri != null) vm.importAudio(uri) }

                // Audio partagé vers l'app (WhatsApp, Fichiers…) — consommé une fois déverrouillé
                val importRequested by MainActivity.importRequest.collectAsStateWithLifecycle()
                LaunchedEffect(importRequested) {
                    val uri = importRequested
                    if (uri != null) {
                        MainActivity.importRequest.value = null
                        vm.importAudio(uri)
                    }
                }

                // Messages ponctuels du ViewModel (résultat d'import/export)
                val uiMessage by vm.uiMessage.collectAsStateWithLifecycle()
                LaunchedEffect(uiMessage) {
                    val message = uiMessage
                    if (message != null) {
                        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                        vm.clearUiMessage()
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    when (screen) {
                                        1 -> "Enregistrements"
                                        2 -> "Réglages"
                                        else -> "Transcripto Stream"
                                    }
                                )
                            },
                        )
                    },
                    floatingActionButton = {
                        if (screen == 1) {
                            ExtendedFloatingActionButton(
                                onClick = { importLauncher.launch("audio/*") },
                                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                text = { Text("Importer") },
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = screen == 0,
                                onClick = { vm.navigate(0) },
                                icon = {
                                    Icon(
                                        painterResource(R.drawable.ic_mic),
                                        contentDescription = "Enregistrer",
                                    )
                                },
                                label = { Text("Enregistrer") },
                            )
                            NavigationBarItem(
                                selected = screen == 1,
                                onClick = { vm.navigate(1) },
                                icon = { Icon(Icons.Filled.List, contentDescription = "Enregistrements") },
                                label = { Text("Liste") },
                            )
                            NavigationBarItem(
                                selected = screen == 2,
                                onClick = { vm.navigate(2) },
                                icon = { Icon(Icons.Filled.Settings, contentDescription = "Réglages") },
                                label = { Text("Réglages") },
                            )
                        }
                    },
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (screen) {
                            1 -> RecordingListScreen(vm, onSelect = { item ->
                                vm.selectRecording(item)
                                vm.navigate(0)
                            })
                            2 -> SettingsScreen(vm)
                            else -> MainScreen(vm, snackbarHostState)
                        }
                    }
                }

                // Proposition de nommage à l'arrêt d'un enregistrement
                if (pendingName != null) {
                    NameRecordingDialog(
                        defaultName = pendingNameDefault,
                        onConfirm = { vm.confirmPendingName(it) },
                        onDismiss = { vm.dismissPendingName() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NameRecordingDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enregistrement terminé") },
        text = {
            Column {
                Text(
                    "Donne un nom à cet enregistrement (ex: client, dossier, réunion) :",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nom") },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Par défaut : date + heure de début et de fin (20260809_1435-1530).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun EditTranscriptDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corriger la transcription") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(280.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

/** Gros bouton rond (Enregistrer / Pause / Stop / Marqueur) accessible à TalkBack. */
@Composable
private fun RoundActionButton(
    icon: Painter,
    label: String,
    size: Dp,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(container)
                .clickable(role = Role.Button, onClickLabel = label) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = label,
                tint = content,
                modifier = Modifier.size(size * 0.42f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MainScreen(vm: StreamViewModel, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val view = LocalView.current

    val modelState by vm.modelState.collectAsStateWithLifecycle()
    val isStreaming by vm.isStreaming.collectAsStateWithLifecycle()
    val isPaused by vm.isPaused.collectAsStateWithLifecycle()
    val elapsedSec by vm.elapsedSec.collectAsStateWithLifecycle()
    val selectedEngine by vm.selectedEngine.collectAsStateWithLifecycle()
    val liveText by vm.liveText.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val loadMessage by vm.loadMessage.collectAsStateWithLifecycle()
    val extractionProgress by vm.extractionProgress.collectAsStateWithLifecycle()
    val lastRecording by vm.lastRecording.collectAsStateWithLifecycle()
    val isTranscribingFile by vm.isTranscribingFile.collectAsStateWithLifecycle()
    val fileTranscript by vm.fileTranscript.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val isImporting by vm.isImporting.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var confirmDeleteLast by remember { mutableStateOf(false) }
    var editTranscript by remember { mutableStateOf(false) }

    fun toast(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotifPermission by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // La notification n'est pas bloquante : accordée ou pas, on démarre.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
        vm.startStreaming()
    }
    // Micro accordé → on enchaîne (notifications puis démarrage) sans re-taper le bouton.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            if (!hasNotifPermission) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                vm.startStreaming()
            }
        } else {
            val activity = context as? Activity
            val permanent = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.RECORD_AUDIO
            )
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = if (permanent) {
                        "Micro refusé — autorise-le dans les paramètres de l'app"
                    } else {
                        "Le micro est indispensable pour transcrire"
                    },
                    actionLabel = if (permanent) "Paramètres" else null,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                }
            }
        }
    }

    fun ensurePermissionsAndStart() {
        when {
            !hasMicPermission -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !hasNotifPermission -> notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> vm.startStreaming()
        }
    }

    // Démarrage demandé par le raccourci ou la tuile de réglages rapides
    val recordRequested by MainActivity.recordRequest.collectAsStateWithLifecycle()
    LaunchedEffect(recordRequested, modelState, selectedEngine) {
        if (recordRequested) {
            if (isStreaming) {
                MainActivity.recordRequest.value = false
            } else if (selectedEngine == "google" || modelState is ModelState.Ready) {
                MainActivity.recordRequest.value = false
                ensurePermissionsAndStart()
            } else if (modelState is ModelState.Error) {
                // Ne pas laisser la demande armée indéfiniment : un enregistrement
                // surprise bien plus tard serait pire qu'un démarrage manqué.
                MainActivity.recordRequest.value = false
                toast("Modèle Whisper indisponible — passe sur Google ou réessaie le chargement")
            }
            // Loading + Whisper : on attend le modèle (l'effect se relance à son changement)
        }
    }

    // Plein écran + écran toujours allumé pendant l'enregistrement
    LaunchedEffect(isStreaming) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            if (isStreaming) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(window, view).hide(
                    WindowInsetsCompat.Type.systemBars()
                )
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(window, view).show(
                    WindowInsetsCompat.Type.systemBars()
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- Import d'audio externe en cours ----
        if (isImporting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                val progress = importProgress
                Text(
                    text = "Import de l'audio…" +
                        if (progress != null) " ${(progress * 100).toInt()} %" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- Bannière d'état du modèle Whisper : n'empêche plus d'utiliser Google ----
        val state = modelState
        when (state) {
            is ModelState.Loading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    val progress = extractionProgress
                    Text(
                        text = buildString {
                            append(loadMessage.ifBlank { "Chargement du modèle Whisper…" })
                            if (progress != null) append(" ${(progress * 100).toInt()} %")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            is ModelState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.shapes.medium,
                        )
                        .padding(10.dp),
                ) {
                    Text(
                        "Modèle Whisper indisponible : ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { vm.retryModelLoad() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Réessayer")
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Le moteur Google reste utilisable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            is ModelState.Ready -> Unit
        }

        // ---- Sélecteur de moteur (Google dispo dès le lancement) ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedEngine == "google",
                onClick = { vm.setEngine("google") },
                enabled = !isStreaming,
                label = { Text("Google") },
            )
            FilterChip(
                selected = selectedEngine == "whisper",
                onClick = { vm.setEngine("whisper") },
                enabled = !isStreaming && state is ModelState.Ready,
                label = { Text("Whisper (local)") },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (selectedEngine == "google") {
                "Google : moteur système — local si pack hors-ligne, sinon cloud · transcription sauvegardée, pas l'audio"
            } else {
                "Whisper : 100% local · audio sauvegardé + transcrit"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (selectedEngine == "google") {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Spacer(Modifier.height(8.dp))

        // ---- Statut ----
        Text(
            text = when {
                isStreaming && isPaused -> "En pause — ${formatTime(elapsedSec)}"
                isStreaming -> "● Écoute… ${formatTime(elapsedSec)}"
                else -> "Prêt — appuie pour transcrire"
            },
            color = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (lastError != null) {
            Text(
                "⚠ $lastError",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---- Zone texte live : toujours prioritaire ----
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.medium,
                )
                .padding(14.dp),
        ) {
            Text(
                text = liveText.ifBlank { "Le texte apparaîtra ici en direct…" },
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        LaunchedEffect(liveText) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        // ---- Actions du dernier enregistrement : UNIQUEMENT après l'arrêt ----
        val recording = lastRecording
        if (recording != null && !isStreaming) {
            val hasAudio = RecordingNames.isAudio(recording.name)
            Spacer(Modifier.height(8.dp))
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${RecordingNames.baseName(recording.name)}${if (recording.name.endsWith(".enc")) " 🔒" else ""}${if (!hasAudio) " (texte seul)" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (hasAudio) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = { vm.togglePlayback() },
                                enabled = !isTranscribingFile,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (isPlaying) "■ Arrêter" else "▶ Écouter", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = { vm.transcribeLastRecording() },
                                enabled = !isTranscribingFile,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (isTranscribingFile) "…" else "Transcrire", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = { confirmDeleteLast = true },
                                enabled = !isTranscribingFile && !isPlaying,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Supprimer", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                val text = fileTranscript.ifBlank { liveText }
                                if (vm.copyText(text)) {
                                    toast("✓ Texte copié")
                                } else {
                                    toast("Rien à copier pour l'instant")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Copier")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val intent = vm.buildEmailIntent()
                                    if (intent != null) {
                                        context.startActivity(
                                            Intent.createChooser(intent, "Partager la transcription")
                                        )
                                    } else {
                                        toast("Rien à envoyer pour l'instant")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Partager")
                        }
                        OutlinedButton(
                            onClick = { editTranscript = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("Corriger")
                        }
                    }
                    if (hasAudio) {
                        Spacer(Modifier.height(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Vitesse :", style = MaterialTheme.typography.labelMedium)
                            listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { s ->
                                FilterChip(
                                    selected = vm.settings.playbackSpeed == s,
                                    onClick = { vm.setPlaybackSpeed(s) },
                                    label = { Text(if (s == 1.0f) "1x" else "${s}x", style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
                    if (isTranscribingFile) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (fileTranscript.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.shapes.medium,
                                )
                                .padding(10.dp),
                        ) {
                            Text(
                                text = fileTranscript,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Boutons principaux ----
        if (isStreaming) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RoundActionButton(
                    icon = painterResource(R.drawable.ic_flag),
                    label = "Marqueur",
                    size = 60.dp,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = {
                        vm.addMarker()
                        toast("Marqueur posé à ${formatTime(elapsedSec)}")
                    },
                )
                if (isPaused) {
                    RoundActionButton(
                        icon = painterResource(R.drawable.ic_mic),
                        label = "Reprendre",
                        size = 68.dp,
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                        onClick = { vm.togglePause() },
                    )
                } else {
                    RoundActionButton(
                        icon = painterResource(R.drawable.ic_pause),
                        label = "Pause",
                        size = 68.dp,
                        container = MaterialTheme.colorScheme.tertiary,
                        content = MaterialTheme.colorScheme.onTertiary,
                        onClick = { vm.togglePause() },
                    )
                }
                RoundActionButton(
                    icon = painterResource(R.drawable.ic_stop),
                    label = "Arrêter",
                    size = 68.dp,
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError,
                    onClick = { vm.stopStreaming() },
                )
            }
        } else {
            RoundActionButton(
                icon = painterResource(R.drawable.ic_mic),
                label = "Appuyer pour parler",
                size = 88.dp,
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = { ensurePermissionsAndStart() },
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    if (confirmDeleteLast) {
        AlertDialog(
            onDismissRequest = { confirmDeleteLast = false },
            title = { Text("Supprimer ?") },
            text = {
                Text(
                    "« ${lastRecording?.let { RecordingNames.baseName(it.name) } ?: ""} » " +
                        "et sa transcription seront définitivement supprimés."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteLastRecording()
                    confirmDeleteLast = false
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteLast = false }) { Text("Annuler") }
            },
        )
    }

    if (editTranscript) {
        EditTranscriptDialog(
            initial = fileTranscript.ifBlank { liveText },
            onConfirm = { newText ->
                vm.saveEditedTranscript(newText)
                editTranscript = false
                toast("✓ Transcription corrigée et sauvegardée")
            },
            onDismiss = { editTranscript = false },
        )
    }
}
