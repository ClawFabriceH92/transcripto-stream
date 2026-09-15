package com.transcripto.stream.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcripto.stream.data.ActionItem
import com.transcripto.stream.data.RecordingItem
import com.transcripto.stream.data.Chapter
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.ReviewMarks
import com.transcripto.stream.data.SegmentsCodec
import com.transcripto.stream.export.TranscriptExporter
import com.transcripto.stream.data.SpeakerNames
import com.transcripto.stream.export.ExportFormat
import com.transcripto.stream.data.StoredSegment
import com.transcripto.stream.data.TextVault
import com.transcripto.stream.summary.MarkdownLite
import com.transcripto.stream.summary.SummaryTemplates
import com.transcripto.stream.ui.theme.AppIcons
import com.transcripto.stream.ui.theme.AppTextStyles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** « mm:ss », ou « hh:mm:ss » au-delà d'une heure — le même format que les fichiers et les exports. */
private fun clock(ms: Long): String = TranscriptExporter.formatHms(ms)

/**
 * Fiche d'un enregistrement : lecteur, actions, puis transcription synchronisée —
 * toucher un passage cale l'audio dessus, le passage en cours de lecture est
 * surligné et suivi automatiquement.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val transcriptVersion by vm.transcriptVersion.collectAsStateWithLifecycle()
    val dossiers by vm.dossiers.collectAsStateWithLifecycle()
    val qa by vm.qa.collectAsStateWithLifecycle()
    val chaptersBusy by vm.chaptersBusy.collectAsStateWithLifecycle()
    var exportMenu by remember { mutableStateOf(false) }
    // rememberSaveable : le sélecteur SAF peut tuer le process ; au retour, le callback
    // doit encore savoir quel enregistrement exporter
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val docxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.DOCX.mime)
    ) { uri ->
        val path = exportPath
        exportPath = null
        if (uri != null && path != null) vm.exportDocument(File(path), uri, ExportFormat.DOCX)
    }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.PDF.mime)
    ) { uri ->
        val path = exportPath
        exportPath = null
        if (uri != null && path != null) vm.exportDocument(File(path), uri, ExportFormat.PDF)
    }
    var dossierDialog by remember { mutableStateOf(false) }
    var speakerDialog by remember { mutableStateOf<Int?>(null) }
    var editSegment by remember { mutableStateOf<Int?>(null) }
    var reviewMode by rememberSaveable(current.file.absolutePath) { mutableStateOf(vm.settings.reviewByDefault) }
    var reviewCursor by remember(current.file.absolutePath) { mutableStateOf(-1) }

    var summary by remember { mutableStateOf<String?>(null) }
    var summaryExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(current.file.absolutePath, summaryVersion, summaryBusy) {
        if (!summaryBusy) summary = withContext(Dispatchers.IO) { vm.readSummary(current.file) }
    }

    var segments by remember { mutableStateOf<List<StoredSegment>>(emptyList()) }
    LaunchedEffect(current.file.absolutePath, isTranscribing, transcriptVersion) {
        if (!isTranscribing) {
            segments = withContext(Dispatchers.IO) {
                val json = RecordingNames.jsonSibling(current.file)
                try {
                    if (json.exists()) SegmentsCodec.fromJson(TextVault.read(json)) else emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
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
                Spacer(Modifier.height(6.dp))
                MetaChip(
                    text = current.dossier.ifBlank { "Dossier / client…" },
                    icon = AppIcons.Folder,
                    tint = if (current.dossier.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    container = if (current.dossier.isBlank()) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.clickable { dossierDialog = true },
                )
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
            Box {
                FilledTonalIconButton(
                    onClick = { exportMenu = true },
                    enabled = !isTranscribing,
                ) {
                    Icon(AppIcons.Download, contentDescription = "Exporter en Word ou PDF")
                }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Exporter en Word (.docx)") },
                        leadingIcon = { Icon(AppIcons.Document, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            exportMenu = false
                            exportPath = current.file.absolutePath
                            docxLauncher.launch("${current.baseName}.docx")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Exporter en PDF") },
                        leadingIcon = { Icon(AppIcons.Document, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            exportMenu = false
                            exportPath = current.file.absolutePath
                            pdfLauncher.launch("${current.baseName}.pdf")
                        },
                    )
                }
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
            if (summaryText == null || summaryExpanded) {
                Spacer(Modifier.height(8.dp))
                TemplateChips(
                    selected = current.template,
                    enabled = !summaryBusy,
                    onSelect = { vm.setSummaryTemplate(current.file, it) },
                )
            }
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
                        ) { Text("Régénérer") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Questions à l'IA (seulement si l'IA est configurée) ----
        if (vm.aiAvailable()) {
            QaCard(
                state = qa,
                file = current.file,
                enabled = !isTranscribing && !summaryBusy,
                onAsk = { vm.askQuestion(current.file, it) },
                onClear = { vm.clearQuestions() },
            )
            Spacer(Modifier.height(12.dp))
        }

        // ---- Actions à mener (issues de la synthèse ou saisies) ----
        ActionsCard(
            actions = current.actions,
            enabled = !summaryBusy,
            onToggle = { a, done -> vm.setActionDone(current.file, a.id, done) },
            onSave = { vm.saveAction(current.file, it) },
            onDelete = { vm.deleteAction(current.file, it.id) },
        )
        Spacer(Modifier.height(12.dp))

        // ---- Chapitres (segments horodatés requis) ----
        if (segments.isNotEmpty()) {
            ChaptersCard(
                chapters = current.chapters,
                busy = chaptersBusy,
                enabled = !isTranscribing,
                ai = vm.aiAvailable(),
                onGenerate = { vm.generateChapters(current.file) },
                onOpen = { chapter ->
                    if (current.hasAudio) vm.playFrom(current.file, chapter.startMs)
                    val idx = segments.indexOfFirst { it.startMs >= chapter.startMs }.let { if (it < 0) segments.lastIndex else it }
                    scope.launch { listState.animateScrollToItem(idx) }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        // ---- Transcription ----
        if (segments.isNotEmpty()) {
            // Relecture assistée : passages sous le seuil de confiance teintés, chiffres et dates
            // soulignés, « Suivant » saute de passage douteux en passage douteux
            val doubtful = remember(segments) { segments.indices.filter { ReviewMarks.isDoubtful(segments[it].confidence) } }
            val hasConfidence = remember(segments) { segments.any { it.confidence >= 0f } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transcription", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = reviewMode,
                    onClick = { reviewMode = !reviewMode },
                    label = { Text(if (doubtful.isEmpty()) "Vérifier" else "Vérifier (${doubtful.size})", maxLines = 1) },
                )
                Spacer(Modifier.weight(1f))
                if (reviewMode && doubtful.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val next = doubtful.firstOrNull { it > reviewCursor } ?: doubtful.first()
                            reviewCursor = next
                            scope.launch { listState.animateScrollToItem(next) }
                            if (current.hasAudio) vm.playFrom(current.file, segments[next].startMs)
                        },
                    ) { Text("Suivant") }
                } else {
                    Text(
                        "Toucher : écouter · appui long : corriger",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reviewMode) {
                Text(
                    when {
                        !hasConfidence -> "Confiance du moteur indisponible pour cette transcription — relance « Transcrire » pour l'obtenir."
                        doubtful.isEmpty() -> "Aucun passage sous 60 % de confiance. Montants, pourcentages et dates soulignés : à relire."
                        else -> "${doubtful.size} passage${if (doubtful.size > 1) "s" else ""} sous 60 % de confiance (teinté${if (doubtful.size > 1) "s" else ""}) ; montants et dates soulignés."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.segmentsStale) {
                Text(
                    "Passages non synchronisés avec le texte corrigé — relance « Transcrire » pour les réaligner.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f).heightIn(min = 120.dp)) {
                itemsIndexed(segments) { i, seg ->
                    val isCurrent = i == currentIndex
                    val speakerChanged = multiSpeaker &&
                        (i == 0 || segments[i - 1].speaker != seg.speaker)
                    if (speakerChanged) {
                        SpeakerLabel(
                            speaker = seg.speaker,
                            label = SpeakerNames.label(seg.speaker, current.speakerNames),
                            modifier = Modifier
                                .padding(top = if (i == 0) 0.dp else 10.dp, bottom = 4.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .clickable { speakerDialog = seg.speaker }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    SegmentRow(
                        segment = seg,
                        isCurrent = isCurrent,
                        doubtful = reviewMode && ReviewMarks.isDoubtful(seg.confidence),
                        highlightFigures = reviewMode,
                        onClick = { vm.playFrom(current.file, seg.startMs) },
                        onLongClick = { editSegment = i },
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

    DetailDialogs(
        vm = vm,
        current = current,
        segments = segments,
        dossiers = dossiers,
        dossierDialog = dossierDialog,
        onDossierDismiss = { dossierDialog = false },
        speakerDialog = speakerDialog,
        onSpeakerDismiss = { speakerDialog = null },
        editSegment = editSegment,
        onEditDismiss = { editSegment = null },
    )
}

@Composable
private fun DetailDialogs(
    vm: StreamViewModel,
    current: RecordingItem,
    segments: List<StoredSegment>,
    dossiers: List<String>,
    dossierDialog: Boolean,
    onDossierDismiss: () -> Unit,
    speakerDialog: Int?,
    onSpeakerDismiss: () -> Unit,
    editSegment: Int?,
    onEditDismiss: () -> Unit,
) {
    if (dossierDialog) {
        DossierDialog(
            current = current.dossier,
            suggestions = dossiers,
            onConfirm = {
                vm.setDossier(current.file, it)
                onDossierDismiss()
            },
            onDismiss = onDossierDismiss,
        )
    }
    speakerDialog?.let { speaker ->
        SpeakerNameDialog(
            speaker = speaker,
            current = current.speakerNames[speaker] ?: "",
            onConfirm = {
                vm.setSpeakerName(current.file, speaker, it)
                onSpeakerDismiss()
            },
            onDismiss = onSpeakerDismiss,
        )
    }
    editSegment?.let { index ->
        val seg = segments.getOrNull(index)
        if (seg != null) {
            EditSegmentDialog(
                initial = seg.text,
                onSave = { text ->
                    vm.updateSegmentText(current.file, index, text)
                    onEditDismiss()
                },
                onAddVocab = { term ->
                    vm.showMessage(
                        if (vm.addVocabularyTerm(term)) "« $term » ajouté au vocabulaire" else "Terme vide ou déjà présent"
                    )
                },
                onDismiss = onEditDismiss,
            )
        }
    }
}

@Composable
private fun DossierDialog(
    current: String,
    suggestions: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dossier / client") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Nom du dossier") },
                    placeholder = { Text("SARL Martin, Audit 2025…") },
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { d ->
                            AssistChip(onClick = { value = d }, label = { Text(d, maxLines = 1) })
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                HintText("Sert à filtrer la liste et figure sur les exports.")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Enregistrer") } },
        dismissButton = {
            Row {
                if (current.isNotBlank()) {
                    TextButton(onClick = { onConfirm("") }) { Text("Retirer", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

@Composable
private fun SpeakerNameDialog(
    speaker: Int,
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Intervenant $speaker") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Nom affiché") },
                    placeholder = { Text("M. Martin (DG)") },
                )
                Spacer(Modifier.height(4.dp))
                HintText("Appliqué à la fiche, au partage, à la synthèse et aux exports ; la transcription brute reste inchangée.")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Enregistrer") } },
        dismissButton = {
            Row {
                if (current.isNotBlank()) {
                    TextButton(onClick = { onConfirm("") }) { Text("Retirer", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

@Composable
private fun EditSegmentDialog(
    initial: String,
    onSave: (String) -> Unit,
    onAddVocab: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var term by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corriger le passage") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("Texte du passage") },
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = term,
                        onValueChange = { term = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Terme mal reconnu") },
                        placeholder = { Text("Nom propre, sigle…") },
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            onAddVocab(term)
                            term = ""
                        },
                        enabled = term.isNotBlank(),
                    ) { Text("Vocabulaire") }
                }
                HintText("Les termes ajoutés au vocabulaire sont soufflés aux moteurs pour les prochaines transcriptions.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && text.trim() != initial.trim()) {
                Text("Enregistrer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Chapitres de l'enregistrement : liste navigable + détection (locale ou IA). */
@Composable
private fun ChaptersCard(
    chapters: List<Chapter>,
    busy: Boolean,
    enabled: Boolean,
    ai: Boolean,
    onGenerate: () -> Unit,
    onOpen: (Chapter) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAvatar(
                icon = AppIcons.Document,
                size = 30.dp,
                iconSize = 17.dp,
                shape = CircleShape,
                container = MaterialTheme.colorScheme.primaryContainer,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (chapters.isEmpty()) "Chapitres" else "Chapitres (${chapters.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (chapters.isNotEmpty() && !busy) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Réduire" else "Afficher") }
            }
        }
        when {
            busy -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                HintText(if (ai) "Chapitrage par Claude en cours…" else "Détection des changements de sujet…")
            }
            chapters.isEmpty() -> {
                Spacer(Modifier.height(6.dp))
                HintText(
                    if (ai) {
                        "Découpage thématique titré, rédigé par Claude (texte seul envoyé)."
                    } else {
                        "Découpage par changement de vocabulaire, sur l'appareil — pour naviguer dans un long enregistrement."
                    }
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onGenerate, enabled = enabled) {
                    Text("Détecter les chapitres")
                }
            }
            else -> {
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    chapters.forEach { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onOpen(c) }
                                .padding(horizontal = 6.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                clock(c.startMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(52.dp),
                            )
                            Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onGenerate, enabled = enabled) { Text("Redétecter") }
                }
            }
        }
    }
}

/**
 * Actions à mener : case à cocher, responsable et échéance, modification par touche,
 * ajout manuel. Les actions viennent de la synthèse (rubrique « Actions à mener ») ou de la saisie.
 */
@Composable
private fun ActionsCard(
    actions: List<ActionItem>,
    enabled: Boolean,
    onToggle: (ActionItem, Boolean) -> Unit,
    onSave: (ActionItem) -> Unit,
    onDelete: (ActionItem) -> Unit,
) {
    var editing by remember { mutableStateOf<ActionItem?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val open = actions.filter { !it.done }
    val done = actions.filter { it.done }
    SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAvatar(
                icon = Icons.Filled.Check,
                size = 30.dp,
                iconSize = 17.dp,
                shape = CircleShape,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    actions.isEmpty() -> "Actions"
                    open.isEmpty() -> "Actions (toutes faites)"
                    else -> "Actions (${open.size} à faire)"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editing = ActionItem(id = "", text = "") }, enabled = enabled) { Text("Ajouter") }
        }
        if (actions.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            HintText("Les actions de la rubrique « Actions à mener » de la synthèse apparaissent ici, à cocher au fil du dossier.")
        } else {
            Spacer(Modifier.height(4.dp))
            open.forEach { a -> ActionRow(a, enabled, onToggle = { onToggle(a, it) }, onEdit = { editing = a }) }
            if (done.isNotEmpty()) {
                TextButton(onClick = { showDone = !showDone }) {
                    Text(if (showDone) "Masquer les actions faites (${done.size})" else "Actions faites (${done.size})")
                }
                if (showDone) done.forEach { a -> ActionRow(a, enabled, onToggle = { onToggle(a, it) }, onEdit = { editing = a }) }
            }
        }
    }
    editing?.let { a ->
        ActionDialog(
            initial = a,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = if (a.id.isBlank()) null else ({ onDelete(a); editing = null }),
        )
    }
}

@Composable
private fun ActionRow(a: ActionItem, enabled: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onEdit)
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = a.done, onCheckedChange = { onToggle(it) }, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                a.text,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (a.done) TextDecoration.LineThrough else null,
                color = if (a.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val details = listOfNotNull(a.owner.takeIf { it.isNotBlank() }, a.dueLabel.takeIf { it.isNotBlank() }?.let { "échéance $it" })
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionDialog(initial: ActionItem, onDismiss: () -> Unit, onSave: (ActionItem) -> Unit, onDelete: (() -> Unit)?) {
    var text by rememberSaveable(initial.id) { mutableStateOf(initial.text) }
    var owner by rememberSaveable(initial.id) { mutableStateOf(initial.owner) }
    var due by rememberSaveable(initial.id) { mutableStateOf(initial.dueLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "Nouvelle action" else "Modifier l'action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Quoi") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("Qui (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = due,
                    onValueChange = { due = it },
                    label = { Text("Échéance (facultatif)") },
                    placeholder = { Text("avant le 30 septembre, fin juin, T2 2026…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(text = text, owner = owner, dueLabel = due)) },
                enabled = text.isNotBlank(),
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Annuler") }
            }
        },
    )
}

/** Choix du gabarit de synthèse (type de mission) + description du gabarit courant. */
@Composable
private fun TemplateChips(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val current = SummaryTemplates.byId(selected)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SummaryTemplates.ALL.forEach { t ->
            FilterChip(
                selected = t.id == current.id,
                onClick = { onSelect(t.id) },
                enabled = enabled,
                label = { Text(t.label, maxLines = 1) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    HintText(current.description)
}

/** Fil de questions/réponses avec Claude sur l'enregistrement ouvert. */
@Composable
private fun QaCard(
    state: QaState,
    file: File,
    enabled: Boolean,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
) {
    var question by rememberSaveable(file.absolutePath) { mutableStateOf("") }
    val mine = state.file == file
    val turns = if (mine) state.turns else emptyList()
    val busy = mine && state.busy
    val error = if (mine && !busy) state.error else null
    // Le champ n'est vidé qu'une fois la réponse arrivée : en cas de refus (opération en
    // cours) ou d'échec, la question reste à l'écran pour être renvoyée
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty() && question.trim() == turns.last().question) question = ""
    }
    SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAvatar(
                icon = AppIcons.Sparkle,
                size = 30.dp,
                iconSize = 17.dp,
                shape = CircleShape,
                container = MaterialTheme.colorScheme.secondaryContainer,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text("Questions à l'IA", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (turns.isNotEmpty() && !busy) {
                TextButton(onClick = onClear) { Text("Effacer") }
            }
        }
        if (turns.isEmpty() && !busy) {
            Spacer(Modifier.height(4.dp))
            HintText(
                "« Quel montant a été évoqué pour la provision ? », « Qui envoie la convention ? » — " +
                    "réponses tirées de la transcription (texte seul envoyé à Claude, jamais l'audio)."
            )
        }
        turns.forEach { t ->
            Spacer(Modifier.height(10.dp))
            Text(t.question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            MarkdownText(t.answer, modifier = Modifier.fillMaxWidth())
        }
        if (busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            HintText("Claude lit la transcription…")
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Poser une question sur l'enregistrement…") },
                maxLines = 3,
                enabled = !busy,
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = { onAsk(question) },
                enabled = enabled && !busy && question.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer la question")
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
private fun SpeakerLabel(speaker: Int, label: String, modifier: Modifier = Modifier) {
    val color = speakerColor(speaker)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Spacer(Modifier.width(4.dp))
        Text(
            "✎",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(
    segment: StoredSegment,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    doubtful: Boolean = false,
    highlightFigures: Boolean = false,
) {
    val accent = speakerColor(segment.speaker)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                when {
                    isCurrent -> MaterialTheme.colorScheme.primaryContainer
                    doubtful -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Corriger le passage")
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            clock(segment.startMs),
            style = AppTextStyles.timecode,
            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else accent,
            modifier = Modifier.padding(end = 10.dp, top = 3.dp),
        )
        val textColor = when {
            isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
            doubtful -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
        if (highlightFigures) {
            val annotated = remember(segment.text) {
                buildAnnotatedString {
                    append(segment.text)
                    ReviewMarks.figureRanges(segment.text).forEach { r ->
                        addStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
                    }
                }
            }
            Text(annotated, style = MaterialTheme.typography.bodyMedium, color = textColor)
        } else {
            Text(segment.text, style = MaterialTheme.typography.bodyMedium, color = textColor)
        }
    }
}
