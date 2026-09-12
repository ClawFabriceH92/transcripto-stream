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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.ui.theme.AppIcons
import com.transcripto.stream.ui.theme.AppTextStyles
import com.transcripto.stream.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** « mm:ss », ou « h:mm:ss » au-delà d'une heure (réunions longues). */
private fun formatTime(sec: Long): String {
    val h = sec / 3600
    val mm = (sec % 3600) / 60
    val ss = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, mm, ss) else "%02d:%02d".format(mm, ss)
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
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (locked) {
                PinScreen(
                    onUnlock = { vm.unlock(it) },
                    pinError = pinError,
                    onClearError = { vm.setPinError(null) },
                )
            } else {
                // Bouton retour système : détail → liste, sinon retour à l'onglet Transcrire
                BackHandler(enabled = screen != 0) {
                    vm.navigate(if (screen == 3) 1 else 0)
                }

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

                // Fin d'enregistrement (nom choisi) : proposer la synthèse
                val summaryProposal by vm.summaryProposal.collectAsStateWithLifecycle()
                LaunchedEffect(summaryProposal) {
                    val file = summaryProposal
                    if (file != null) {
                        val result = snackbarHostState.showSnackbar(
                            message = "Enregistrement terminé — générer une synthèse ?",
                            actionLabel = "Synthèse",
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            vm.generateSummary(file)
                        } else {
                            vm.dismissSummaryProposal()
                        }
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.surface,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                when (screen) {
                                    1 -> Text("Enregistrements")
                                    2 -> Text("Réglages")
                                    3 -> Text("Fiche")
                                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconAvatar(
                                            icon = AppIcons.Mic,
                                            size = 32.dp,
                                            iconSize = 18.dp,
                                            shape = CircleShape,
                                            container = MaterialTheme.colorScheme.primary,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text("Transcripto Stream")
                                    }
                                }
                            },
                            navigationIcon = {
                                if (screen == 3) {
                                    IconButton(onClick = { vm.navigate(1) }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour à la liste")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                    floatingActionButton = {
                        if (screen == 1) {
                            ExtendedFloatingActionButton(
                                onClick = { importLauncher.launch("audio/*") },
                                icon = { Icon(AppIcons.Upload, contentDescription = null) },
                                text = { Text("Importer") },
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            NavigationBarItem(
                                selected = screen == 0,
                                onClick = { vm.navigate(0) },
                                icon = { Icon(AppIcons.Mic, contentDescription = "Transcrire") },
                                label = { Text("Transcrire") },
                            )
                            NavigationBarItem(
                                selected = screen == 1 || screen == 3,
                                onClick = { vm.navigate(1) },
                                icon = { Icon(AppIcons.Folder, contentDescription = "Enregistrements") },
                                label = { Text("Enregistrements") },
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
                        Crossfade(targetState = screen, label = "screen") { current ->
                            when (current) {
                                1 -> RecordingListScreen(
                                    vm,
                                    onSelect = { item -> vm.openDetail(item) },
                                    onImport = { importLauncher.launch("audio/*") },
                                )
                                2 -> SettingsScreen(vm)
                                3 -> DetailScreen(vm)
                                else -> MainScreen(vm, snackbarHostState)
                            }
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
                    "Donne un nom à cet enregistrement (client, dossier, réunion…) :",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Nom") },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Par défaut : date + heures de début et de fin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Plus tard") }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val summaryBusy by vm.summaryBusy.collectAsStateWithLifecycle()
    val summaryVersion by vm.summaryVersion.collectAsStateWithLifecycle()
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

    val state = modelState
    val whisperReady = state is ModelState.Ready
    val canStart = selectedEngine == "google" || whisperReady

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- Bandeaux : import en cours, état du modèle Whisper ----
        AnimatedVisibility(visible = isImporting) {
            Column {
                val progress = importProgress
                InlineBanner(
                    text = "Import de l'audio…" +
                        if (progress != null) " ${(progress * 100).toInt()} %" else "",
                    icon = AppIcons.Upload,
                    progress = progress,
                    indeterminate = progress == null,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        when (state) {
            is ModelState.Loading -> {
                val progress = extractionProgress
                InlineBanner(
                    text = buildString {
                        append(loadMessage.ifBlank { "Chargement du modèle Whisper…" })
                        if (progress != null) append(" ${(progress * 100).toInt()} %")
                    },
                    icon = AppIcons.Waveform,
                    progress = progress,
                    indeterminate = progress == null,
                )
                Spacer(Modifier.height(8.dp))
            }
            is ModelState.Error -> {
                InlineBanner(
                    text = "Modèle Whisper indisponible : ${state.message} — le moteur Google reste utilisable.",
                    icon = Icons.Filled.Warning,
                    container = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        TextButton(
                            onClick = { vm.retryModelLoad() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) { Text("Réessayer") }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            is ModelState.Ready -> Unit
        }

        // ---- Moteur de transcription ----
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selectedEngine == "google",
                onClick = { vm.setEngine("google") },
                enabled = !isStreaming,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    Icon(
                        AppIcons.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                    )
                },
                label = { Text("Google", maxLines = 1) },
            )
            SegmentedButton(
                selected = selectedEngine == "whisper",
                onClick = { vm.setEngine("whisper") },
                enabled = !isStreaming && whisperReady,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    Icon(
                        AppIcons.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                    )
                },
                label = { Text("Whisper local", maxLines = 1) },
            )
        }
        Spacer(Modifier.height(10.dp))

        // ---- Carte de session : état, chrono, confidentialité ----
        SectionCard(
            container = if (isStreaming) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    isStreaming && isPaused -> StatusPill(
                        text = "En pause",
                        color = MaterialTheme.colorScheme.tertiary,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                    isStreaming -> StatusPill(
                        text = "Enregistrement",
                        color = MaterialTheme.colorScheme.error,
                        container = MaterialTheme.colorScheme.errorContainer,
                        pulsing = true,
                    )
                    else -> StatusPill(
                        text = if (canStart) "Prêt" else "Modèle en chargement",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                Spacer(Modifier.weight(1f))
                MetaChip(
                    text = if (selectedEngine == "google") "Google · cloud" else "Whisper · local",
                    icon = if (selectedEngine == "google") AppIcons.Cloud else AppIcons.Shield,
                    tint = if (selectedEngine == "google") {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    container = if (selectedEngine == "google") {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatTime(elapsedSec),
                style = AppTextStyles.chrono,
                color = if (isStreaming) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (selectedEngine == "google") {
                    "L'audio est traité par le service Google (cloud sauf pack hors-ligne) ; seule la transcription est conservée."
                } else {
                    "Traitement 100 % local : l'audio ne quitte jamais l'appareil, le WAV est conservé et transcrit."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (lastError != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        lastError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- Texte en direct : toujours prioritaire ----
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp),
        ) {
            if (liveText.isBlank()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (isStreaming) {
                        PulsingDot(color = MaterialTheme.colorScheme.primary, size = 12.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (isPaused) "En pause" else "Écoute en cours…",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Le texte apparaît ici au fil de la parole.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Icon(
                            AppIcons.Waveform,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "La transcription s'affichera ici en direct.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Text(
                    text = liveText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        LaunchedEffect(liveText) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        // ---- Dernier enregistrement : UNIQUEMENT après l'arrêt ----
        val recording = lastRecording
        AnimatedVisibility(visible = recording != null && !isStreaming) {
            if (recording != null) {
                var playbackSpeed by remember { mutableStateOf(vm.settings.playbackSpeed) }
                val summaryExists = remember(recording, summaryVersion) {
                    RecordingNames.mdSibling(recording).exists()
                }
                Column {
                    Spacer(Modifier.height(10.dp))
                    LastRecordingCard(
                        name = RecordingNames.baseName(recording.name),
                        hasAudio = RecordingNames.isAudio(recording.name),
                        encrypted = recording.name.endsWith(".enc"),
                        isPlaying = isPlaying,
                        isTranscribing = isTranscribingFile,
                        transcript = fileTranscript,
                        speed = playbackSpeed,
                        onSpeed = {
                            playbackSpeed = nextSpeed(playbackSpeed)
                            vm.setPlaybackSpeed(playbackSpeed)
                        },
                        onPlay = { vm.togglePlayback() },
                        onTranscribe = { vm.transcribeLastRecording() },
                        onOpen = { vm.openDetailForFile(recording) },
                        onCopy = {
                            val text = fileTranscript.ifBlank { liveText }
                            if (vm.copyText(text)) toast("Texte copié") else toast("Rien à copier pour l'instant")
                        },
                        onShare = {
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
                        onEdit = { editTranscript = true },
                        onDelete = { confirmDeleteLast = true },
                        summaryExists = summaryExists,
                        summaryBusy = summaryBusy,
                        onSummary = {
                            if (summaryExists) vm.openDetailForFile(recording) else vm.generateSummary(recording)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- Commandes principales ----
        AnimatedContent(targetState = isStreaming, label = "controls") { streaming ->
            if (streaming) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    RoundActionButton(
                        icon = AppIcons.Flag,
                        label = "Marqueur",
                        size = 58.dp,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = {
                            vm.addMarker()
                            toast("Marqueur posé à ${formatTime(elapsedSec)}")
                        },
                    )
                    if (isPaused) {
                        RoundActionButton(
                            icon = AppIcons.Mic,
                            label = "Reprendre",
                            size = 70.dp,
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            onClick = { vm.togglePause() },
                        )
                    } else {
                        RoundActionButton(
                            icon = AppIcons.Pause,
                            label = "Pause",
                            size = 70.dp,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                            onClick = { vm.togglePause() },
                        )
                    }
                    RoundActionButton(
                        icon = AppIcons.Stop,
                        label = "Arrêter",
                        size = 70.dp,
                        container = MaterialTheme.colorScheme.error,
                        content = MaterialTheme.colorScheme.onError,
                        onClick = { vm.stopStreaming() },
                    )
                }
            } else {
                RecordButton(
                    onClick = { ensurePermissionsAndStart() },
                    enabled = canStart,
                    label = when {
                        canStart -> "Appuyer pour transcrire"
                        state is ModelState.Error -> "Modèle indisponible — choisis Google"
                        else -> "Chargement du modèle…"
                    },
                )
            }
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
                toast("Transcription corrigée et sauvegardée")
            },
            onDismiss = { editTranscript = false },
        )
    }
}

/**
 * Carte du dernier enregistrement : deux actions principales (écouter, transcrire),
 * vitesse, aperçu de la transcription et menu pour le reste — plus de rangées
 * de six boutons.
 */
@Composable
private fun LastRecordingCard(
    name: String,
    hasAudio: Boolean,
    encrypted: Boolean,
    isPlaying: Boolean,
    isTranscribing: Boolean,
    transcript: String,
    speed: Float,
    onSpeed: () -> Unit,
    onPlay: () -> Unit,
    onTranscribe: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    summaryExists: Boolean,
    summaryBusy: Boolean,
    onSummary: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SectionCard(contentPadding = PaddingValues(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAvatar(
                icon = if (hasAudio) AppIcons.Waveform else AppIcons.Document,
                size = 36.dp,
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaChip(text = "Dernier enregistrement")
                    if (encrypted) MetaChip(text = "Chiffré", icon = Icons.Filled.Lock, contentDescription = "Chiffré")
                    if (!hasAudio) MetaChip(text = "Texte seul", icon = AppIcons.Document)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Copier le texte") },
                        leadingIcon = { Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onCopy()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Partager") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Corriger la transcription") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        enabled = !isTranscribing && !isPlaying,
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        ) {
            if (hasAudio) {
                FilledTonalButton(
                    onClick = onPlay,
                    enabled = !isTranscribing,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        if (isPlaying) AppIcons.Stop else AppIcons.Play,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "Arrêter" else "Écouter", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onTranscribe,
                    enabled = !isTranscribing,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(AppIcons.Waveform, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isTranscribing) "En cours…" else "Transcrire", maxLines = 1)
                }
                SpeedChip(speed = speed, onClick = onSpeed)
            } else {
                FilledTonalButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(6.dp))
                    Text("Partager", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(6.dp))
                    Text("Corriger", maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = onSummary,
            enabled = !summaryBusy && !isTranscribing,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(AppIcons.Sparkle, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    summaryBusy -> "Synthèse en cours…"
                    summaryExists -> "Voir la synthèse"
                    else -> "Générer la synthèse"
                },
                maxLines = 1,
            )
        }
        if (isTranscribing || summaryBusy) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
        }
        if (transcript.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                transcript,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Ouvrir la fiche", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
