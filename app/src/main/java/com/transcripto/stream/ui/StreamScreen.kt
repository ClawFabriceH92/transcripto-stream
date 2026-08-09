package com.transcripto.stream.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch

private fun formatTime(sec: Long): String {
    val mm = sec / 60
    val ss = sec % 60
    return "%02d:%02d".format(mm, ss)
}

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

    // Thème (réglage système/clair/sombre)
    val darkTheme = when (vm.settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (locked) {
                PinScreen(
                    onUnlock = { vm.unlock(it) },
                    pinError = pinError,
                    onClearError = { vm.setPinError(null) },
                )
            } else {
                Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                    Box(modifier = Modifier.weight(1f)) {
                        when (screen) {
                            1 -> RecordingListScreen(vm, onSelect = { item ->
                                vm.selectRecording(item)
                                vm.navigate(0)
                            })
                            2 -> SettingsScreen(vm)
                            else -> MainScreen(vm)
                        }
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = screen == 0,
                            onClick = { vm.navigate(0) },
                            icon = { Icon(Icons.Filled.PlayArrow, contentDescription = "Enregistrer") },
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
                }
            }
        }
    }
}

@Composable
private fun MainScreen(vm: StreamViewModel) {
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
    val modelLoadMs by vm.modelLoadMs.collectAsStateWithLifecycle()
    val transcriptionCount by vm.transcriptionCount.collectAsStateWithLifecycle()
    val lastWindowText by vm.lastWindowText.collectAsStateWithLifecycle()
    val lastRecording by vm.lastRecording.collectAsStateWithLifecycle()
    val isTranscribingFile by vm.isTranscribingFile.collectAsStateWithLifecycle()
    val fileTranscript by vm.fileTranscript.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var feedback by remember { mutableStateOf<String?>(null) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    var hasNotifPermission by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotifPermission = granted }

    fun ensurePermissionsAndStart() {
        when {
            !hasMicPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            !hasNotifPermission -> notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> vm.toggleStreaming()
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
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Transcripto Stream",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 24.dp),
        )

        Spacer(Modifier.height(16.dp))

        val state = modelState
        when (state) {
            is ModelState.Loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(loadMessage.ifBlank { "Chargement du modèle Whisper…" })
                val progress = extractionProgress
                if (progress != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Extraction : ${(progress * 100).toInt()} %", fontSize = 12.sp)
                }
            }
            is ModelState.Error -> {
                Text(
                    "Erreur : ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is ModelState.Ready -> {
                // Sélecteur de moteur
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedEngine == "google",
                        onClick = { vm.setEngine("google") },
                        enabled = !isStreaming,
                        label = { Text("Google (qualité)") },
                    )
                    FilterChip(
                        selected = selectedEngine == "whisper",
                        onClick = { vm.setEngine("whisper") },
                        enabled = !isStreaming,
                        label = { Text("Whisper (100% local)") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (selectedEngine == "google") {
                        "⚠ Audio envoyé au service de reconnaissance (cloud). Mode Whisper = hors-ligne, mais qualité inférieure."
                    } else {
                        "100% local : l'audio ne quitte pas l'appareil."
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = when {
                        isStreaming && isPaused -> "⏸ En pause — ${formatTime(elapsedSec)}"
                        isStreaming -> "● Écoute… ${formatTime(elapsedSec)}"
                        else -> "Prêt — appuie pour transcrire"
                    },
                    color = if (isStreaming) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (modelLoadMs > 0) {
                    Text(
                        "Modèle chargé en ${modelLoadMs / 1000}s",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (lastError != null) {
                    Text(
                        "⚠ $lastError",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                if (transcriptionCount > 0) {
                    Text(
                        "$transcriptionCount transcription${if (transcriptionCount > 1) "s" else ""} · dernière : « ${lastWindowText.take(40)} »",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (feedback != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        feedback ?: "",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Texte transcrit en direct
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.medium,
                        )
                        .padding(16.dp),
                ) {
                    Text(
                        text = liveText.ifBlank { "Le texte apparaîtra ici en direct…" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                    )
                }
                LaunchedEffect(liveText) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }

                // ---- Audio conservé + transcription différée ----
                val recording = lastRecording
                if (recording != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Audio conservé : ${recording.name}${if (recording.extension == "enc") " 🔒 (chiffré)" else ""}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = { vm.togglePlayback() },
                            enabled = !isTranscribingFile && !isStreaming,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (isPlaying) "■ Arrêter" else "▶ Écouter")
                        }
                        Button(
                            onClick = { vm.transcribeLastRecording() },
                            enabled = !isTranscribingFile,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (isTranscribingFile) "Transcription…" else "Transcrire")
                        }
                        OutlinedButton(
                            onClick = { vm.deleteLastRecording() },
                            enabled = !isTranscribingFile && !isPlaying,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Supprimer")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // Vitesse de lecture (cycle 0.5 → 1 → 1.5 → 2)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Vitesse :", fontSize = 12.sp)
                        val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)
                        speeds.forEach { s ->
                            FilterChip(
                                selected = vm.settings.playbackSpeed == s,
                                onClick = { vm.setPlaybackSpeed(s) },
                                label = { Text(if (s == 1.0f) "1x" else "${s}x") },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val text = fileTranscript.ifBlank { liveText }
                                if (vm.copyText(text)) {
                                    feedback = "✓ Texte copié dans le presse-papiers"
                                } else {
                                    feedback = "Rien à copier pour l'instant"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("📋 Copier le texte")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val intent = vm.buildEmailIntent()
                                    if (intent != null) {
                                        context.startActivity(
                                            Intent.createChooser(intent, "Envoyer par email")
                                        )
                                    } else {
                                        feedback = "Rien à envoyer pour l'instant"
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("✉ Email texte + WAV")
                        }
                    }
                    if (isTranscribingFile) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (fileTranscript.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.shapes.medium,
                                )
                                .padding(12.dp),
                        ) {
                            Text(
                                text = fileTranscript,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (isStreaming) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(
                                        color = if (isPaused) MaterialTheme.colorScheme.primary else Color(0xFFF57C00),
                                        shape = CircleShape,
                                    )
                                    .clickable { vm.togglePause() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (isPaused) "▶" else "⏸",
                                    color = Color.White,
                                    fontSize = 30.sp,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (isPaused) "Reprendre" else "Pause",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(Color(0xFFD32F2F), CircleShape)
                                    .clickable { vm.toggleStreaming() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "■",
                                    color = Color.White,
                                    fontSize = 30.sp,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Arrêter",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            )
                            .clickable {
                                ensurePermissionsAndStart()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "▶",
                            color = Color.White,
                            fontSize = 40.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Appuyer pour parler",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
