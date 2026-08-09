package com.transcripto.stream.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

@Composable
fun StreamScreen() {
    val context = LocalContext.current
    val vm: StreamViewModel = viewModel(
        factory = viewModelFactory {
            initializer { StreamViewModel(context.applicationContext) }
        }
    )

    val modelState by vm.modelState.collectAsStateWithLifecycle()
    val isStreaming by vm.isStreaming.collectAsStateWithLifecycle()
    val liveText by vm.liveText.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val loadMessage by vm.loadMessage.collectAsStateWithLifecycle()
    val extractionProgress by vm.extractionProgress.collectAsStateWithLifecycle()
    val modelLoadMs by vm.modelLoadMs.collectAsStateWithLifecycle()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    Surface(modifier = Modifier.fillMaxSize()) {
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

            // État du modèle
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
                    Text(
                        text = if (isStreaming) "● Écoute…" else "Prêt — appuie pour transcrire",
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

                    Spacer(Modifier.height(20.dp))

                    // Bouton micro : fond rond + symbole + clic
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .background(
                                color = if (isStreaming) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            )
                            .align(Alignment.CenterHorizontally)
                            .clickable {
                                if (!hasMicPermission) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    vm.toggleStreaming()
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isStreaming) "■" else "▶",
                            color = Color.White,
                            fontSize = 34.sp,
                        )
                    }
                }
            }
        }
    }
}
