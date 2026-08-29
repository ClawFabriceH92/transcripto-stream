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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.SegmentsCodec
import com.transcripto.stream.data.StoredSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun clock(ms: Long): String = "%02d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/**
 * Écran détail d'un enregistrement : lecture synchronisée avec les segments
 * horodatés — toucher un passage cale l'audio dessus, le passage en cours de
 * lecture est surligné et suivi automatiquement.
 */
@Composable
fun DetailScreen(vm: StreamViewModel) {
    val item by vm.detailItem.collectAsStateWithLifecycle()
    val current = item
    if (current == null) {
        Text(
            "Aucun enregistrement sélectionné.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(
            current.baseName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
        )
        Text(
            buildString {
                append(vm.formatHms(current.durationMs))
                append(" · ${current.sizeBytes / 1024} Ko")
                if (current.encrypted) append(" · chiffré")
                if (!current.hasAudio) append(" · texte seul")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (current.hasAudio) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { vm.togglePlaybackFor(current.file) },
                    enabled = !isStreaming,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(if (playingThis) "■" else "▶")
                }
                Text(
                    "${clock(if (playingThis) positionMs else 0L)} / " +
                        clock(if (playingThis && durationMs > 0) durationMs else current.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedButton(
                    onClick = { vm.transcribeFile(current.file) },
                    enabled = !isTranscribing && !isStreaming,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (isTranscribing) "…" else if (segments.isEmpty()) "Transcrire" else "Re-transcrire",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text("Partager", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
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
            )
        }
        if (isTranscribing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        lastError?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        if (segments.isNotEmpty()) {
            Text(
                "Touche un passage pour l'écouter à cet endroit.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(segments) { i, seg ->
                    val isCurrent = i == currentIndex
                    val speakerChanged = multiSpeaker &&
                        (i == 0 || segments[i - 1].speaker != seg.speaker)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isCurrent) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { vm.playFrom(current.file, seg.startMs) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        if (speakerChanged) {
                            Text(
                                "Intervenant ${seg.speaker}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Row {
                            Text(
                                clock(seg.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                            )
                            Text(seg.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        fileTranscript.isNotBlank() -> fileTranscript
                        current.hasAudio ->
                            "Appuie sur « Transcrire » pour générer la transcription et les " +
                                "segments interactifs (lecture synchronisée)."
                        current.transcript.isNotBlank() -> current.transcript
                        else -> "Aucune transcription."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
