package com.transcripto.stream.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.SegmentsCodec
import com.transcripto.stream.data.StoredSegment
import com.transcripto.stream.summary.MarkdownLite
import com.transcripto.stream.ui.theme.AppIcons
import com.transcripto.stream.ui.theme.AppTextStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun clock(ms: Long): String = "%02d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/**
 * Fiche d'un enregistrement : lecteur, actions, puis transcription synchronisée —
 * toucher un passage cale l'audio dessus, le passage en cours de lecture est
 * surligné et suivi automatiquement.
 */
@Composable
fun DetailScreen(vm: StreamViewModel) {
    val item by vm.detailItem.collectAsStateWithLifecycle()
    val current = item
    if (current == null) {
        EmptyState(
            icon = AppIcons.Folder,
            title = "Aucun enregistrement sélectionné",
            body = "Choisis un enregistrement dans la liste pour ouvrir sa fiche.",
        )
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val playingFile by vm.playingFile.collectAsStateWithLifecycle()
    val positionMs by vm.playbackPositionMs.collectAsStateWithLifecycle()
    val durationMs by vm.playbackDurationMs.collectAsStateWithLifecycle()
    val fileTranscript by vm.fileTranscript.collectAsStateWithLifecycle()
    val isTranscribing by vm.isTranscribingFile.collectAsStateWithLifecycle()
    val isStreaming by vm.isStreaming.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val summaryBusy by vm.summaryBusy.collectAsStateWithLifecycle()
    val summaryVersion by vm.summaryVersion.collectAsStateWithLifecycle()

    var summary by remember { mutableStateOf<String?>(null) }
    var summaryExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(current.file.absolutePath, summaryVersion, summaryBusy) {
        if (!summaryBusy) summary = withContext(Dispatchers.IO) { vm.readSummary(current.file) }
    }

    var segments by remember { mutableStateOf<List<StoredSegment>>(emptyList()) }
    LaunchedEffect(current.file.absolutePath, isTranscribing) {
        if (!isTranscribing) {
            segments = withContext(Dispatchers.IO) {
                val json = RecordingNames.jsonSibling(current.file)
                if (json.exists()) SegmentsCodec.fromJson(json.readText()) else emptyList()
            }
        }
    }

    val playingThis = isPlaying && playingFile == current.file
    val currentIndex = if (playingThis) segments.indexOfLast { it.startMs <= positionMs } else -1
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
    }
    val multiSpeaker = segments.distinctBy { it.speaker }.size > 1
    val totalMs = if (playingThis && durationMs > 0) durationMs else current.durationMs

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        // ---- En-tête : avatar, nom, métadonnées ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAvatar(
                icon = if (current.hasAudio) AppIcons.Waveform else AppIcons.Document,
                size = 46.dp,
                iconSize = 24.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    current.baseName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaChip(text = vm.formatHms(current.durationMs), icon = AppIcons.Clock)
                    MetaChip(text = "${current.sizeBytes / 1024} Ko")
                    if (current.encrypted) {
                        MetaChip(text = "Chiffré", icon = Icons.Filled.Lock, contentDescription = "Chiffré")
                    }
                    if (!current.hasAudio) MetaChip(text = "Texte seul", icon = AppIcons.Document)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Lecteur ----
        if (current.hasAudio) {
            SectionCard(container = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = { vm.togglePlaybackFor(current.file) },
                        enabled = !isStreaming,
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            if (playingThis) AppIcons.Stop else AppIcons.Play,
                            contentDescription = if (playingThis) "Arrêter" else "Écouter",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${clock(if (playingThis) positionMs else 0L)} / ${clock(totalMs)}",
                            style = AppTextStyles.timecode.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                        )
                        Text(
                            when {
                                playingThis -> "Lecture en cours"
                                isStreaming -> "Lecture indisponible pendant un enregistrement"
                                else -> "Prêt à lire"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    var speed by remember { mutableStateOf(vm.settings.playbackSpeed) }
                    SpeedChip(
                        speed = speed,
                        onClick = {
                            speed = nextSpeed(speed)
                            vm.setPlaybackSpeed(speed)
                        },
                    )
                }
                Slider(
                    value = if (playingThis && durationMs > 0) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onValueChange = { fraction ->
                        if (playingThis && durationMs > 0) {
                            vm.seekTo((fraction * durationMs).toLong())
                        }
                    },
                    enabled = playingThis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- Actions ----
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (current.hasAudio) {
                FilledTonalButton(
                    onClick = { vm.transcribeFile(current.file) },
                    enabled = !isTranscribing && !isStreaming,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(AppIcons.Waveform, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(
                        when {
                            isTranscribing -> "Transcription…"
                            segments.isEmpty() -> "Transcrire"
                            else -> "Re-transcrire"
                        },
                        maxLines = 1,
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val intent = vm.buildShareIntentFor(current)
                        if (intent != null) {
                            context.startActivity(
                                Intent.createChooser(intent, "Partager la transcription")
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Partager", maxLines = 1)
            }
        }
        if (isTranscribing) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        lastError?.let { err ->
            Spacer(Modifier.height(8.dp))
            InlineBanner(
                text = err,
                icon = Icons.Filled.Warning,
                container = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Spacer(Modifier.height(10.dp))

        // ---- Synthèse (locale ou IA) ----
        SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAvatar(
                    icon = AppIcons.Sparkle,
                    size = 30.dp,
                    iconSize = 17.dp,
                    shape = CircleShape,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(10.dp))
                Text("Synthèse", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (summary != null && !summaryBusy) {
                    TextButton(onClick = { summaryExpanded = !summaryExpanded }) {
                        Text(if (summaryExpanded) "Réduire" else "Afficher")
                    }
                }
            }
            val summaryText = summary
            when {
                summaryBusy -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    HintText(
                        if (vm.settings.aiSummaryEnabled && vm.hasAiApiKey()) {
                            "Rédaction par Claude en cours…"
                        } else {
                            "Génération locale en cours…"
                        }
                    )
                }
                summaryText == null -> {
                    Spacer(Modifier.height(6.dp))
                    HintText(
                        if (vm.settings.aiSummaryEnabled && vm.hasAiApiKey()) {
                            "Rédigée par Claude à partir de la transcription (texte seul envoyé, jamais l'audio)."
                        } else {
                            "Points clés, décisions, actions, chiffres cités — générée sur l'appareil, sans envoi de données."
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { vm.generateSummary(current.file) },
                        enabled = !isTranscribing,
                    ) {
                        Icon(AppIcons.Sparkle, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Générer la synthèse")
                    }
                }
                else -> {
                    Spacer(Modifier.height(6.dp))
                    if (summaryExpanded) {
                        MarkdownText(
                            summaryText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    } else {
                        MarkdownText(
                            summaryText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 104.dp)
                                .clipToBounds(),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            if (vm.copyText(MarkdownLite.toPlainText(summaryText))) vm.showMessage("Synthèse copiée")
                        }) { Text("Copier") }
                        TextButton(
                            onClick = { vm.generateSummary(current.file) },
                            enabled = !isTranscribing,
                        ) { Text("Regénérer") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Transcription ----
        if (segments.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transcription", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "Touche un passage pour l'écouter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f).heightIn(min = 120.dp)) {
                itemsIndexed(segments) { i, seg ->
                    val isCurrent = i == currentIndex
                    val speakerChanged = multiSpeaker &&
                        (i == 0 || segments[i - 1].speaker != seg.speaker)
                    if (speakerChanged) {
                        SpeakerLabel(seg.speaker, modifier = Modifier.padding(top = if (i == 0) 0.dp else 10.dp, bottom = 4.dp))
                    }
                    SegmentRow(
                        segment = seg,
                        isCurrent = isCurrent,
                        onClick = { vm.playFrom(current.file, seg.startMs) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            SectionCard(modifier = Modifier.weight(1f), contentPadding = PaddingValues(14.dp)) {
                Box(modifier = Modifier.fillMaxHeight()) {
                    when {
                        fileTranscript.isNotBlank() -> Text(
                            fileTranscript,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        )
                        current.hasAudio -> EmptyState(
                            icon = AppIcons.Waveform,
                            title = "Pas encore de transcription",
                            body = "« Transcrire » génère le texte et les passages interactifs " +
                                "(lecture synchronisée).",
                        )
                        current.transcript.isNotBlank() -> Text(
                            current.transcript,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        )
                        else -> EmptyState(
                            icon = AppIcons.Document,
                            title = "Aucune transcription",
                            body = "Cet enregistrement ne contient pas de texte.",
                        )
                    }
                }
            }
        }
    }
}

/** Couleurs par intervenant (1 = primaire, 2 = tertiaire, autres = secondaire). */
@Composable
private fun speakerColor(speaker: Int): Color = when (speaker) {
    1 -> MaterialTheme.colorScheme.primary
    2 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun SpeakerLabel(speaker: Int, modifier: Modifier = Modifier) {
    val color = speakerColor(speaker)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            "Intervenant $speaker",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun SegmentRow(segment: StoredSegment, isCurrent: Boolean, onClick: () -> Unit) {
    val accent = speakerColor(segment.speaker)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            clock(segment.startMs),
            style = AppTextStyles.timecode,
            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else accent,
            modifier = Modifier.padding(end = 10.dp, top = 3.dp),
        )
        Text(
            segment.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
