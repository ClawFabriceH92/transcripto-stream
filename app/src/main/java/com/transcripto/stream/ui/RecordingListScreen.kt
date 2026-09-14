package com.transcripto.stream.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcripto.stream.data.SearchHit
import com.transcripto.stream.data.SpeakerNames
import com.transcripto.stream.data.TextFold
import com.transcripto.stream.export.ExportFormat
import com.transcripto.stream.ui.theme.AppIcons
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Liste des enregistrements groupée par jour (en-têtes épinglés) : recherche,
 * ouverture de la fiche, menu par élément (partager, exporter, renommer, supprimer).
 * L'import d'audio externe passe par le bouton « Importer » (FAB du Scaffold).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingListScreen(
    vm: StreamViewModel,
    onSelect: (RecordingItem) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val dossiers by vm.dossiers.collectAsStateWithLifecycle()
    val dossierFilter by vm.dossierFilter.collectAsStateWithLifecycle()
    val hits by vm.searchHits.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<RecordingItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RecordingItem?>(null) }
    // rememberSaveable : le picker SAF peut tuer le process ; au retour, le callback
    // doit encore savoir quel fichier exporter (sinon document créé vide en silence)
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }

    // Export SAF : l'utilisateur choisit l'emplacement (Téléchargements, Drive…)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        val path = exportPath
        exportPath = null
        if (uri != null && path != null) vm.exportAudio(File(path), uri)
    }
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

    // Recherche : nom / dossier (accents et casse ignorés) ou au moins un passage indexé
    val hitPaths = remember(hits) { hits.map { it.file.absolutePath }.toSet() }
    val foldedQuery = remember(query) { TextFold.fold(query.trim()) }
    val filtered = recordings.filter { item ->
        (dossierFilter == null || item.dossier == dossierFilter) &&
            (foldedQuery.isEmpty() ||
                TextFold.fold(item.baseName).contains(foldedQuery) ||
                TextFold.fold(item.dossier).contains(foldedQuery) ||
                item.file.absolutePath in hitPaths)
    }
    val visibleHits = remember(hits, dossierFilter) {
        if (dossierFilter == null) hits else hits.filter { it.dossier == dossierFilter }
    }

    // Groupes par jour (la liste est déjà triée par date décroissante).
    // `today` participe à la clé : les libellés restent justes après minuit.
    val today = LocalDate.now()
    val grouped = remember(filtered, today) {
        filtered.groupBy { dayLabel(it.modifiedAt, today) }
    }
    val totalMs = filtered.sumOf { it.durationMs }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setSearchQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Rechercher un nom ou un passage…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { vm.setSearchQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Effacer la recherche")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
            ),
        )
        if (dossiers.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = dossierFilter == null,
                    onClick = { vm.setDossierFilter(null) },
                    label = { Text("Tous") },
                )
                dossiers.forEach { d ->
                    FilterChip(
                        selected = dossierFilter == d,
                        onClick = { vm.setDossierFilter(if (dossierFilter == d) null else d) },
                        label = { Text(d, maxLines = 1) },
                        leadingIcon = {
                            Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${filtered.size} enregistrement${if (filtered.size > 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (totalMs > 0) MetaChip(text = vm.formatHms(totalMs), icon = AppIcons.Clock)
        }
        Spacer(Modifier.height(4.dp))

        if (filtered.isEmpty() && visibleHits.isEmpty()) {
            if (query.isBlank()) {
                EmptyState(
                    icon = AppIcons.Mic,
                    title = "Aucun enregistrement",
                    body = "Lance une transcription depuis l'onglet « Transcrire », ou importe un " +
                        "audio existant (WhatsApp, dictaphone, fichier).",
                    primaryLabel = "Transcrire",
                    onPrimary = { vm.navigate(0) },
                    secondaryLabel = "Importer un audio",
                    onSecondary = onImport,
                )
            } else {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = "Aucun résultat",
                    body = "Rien ne correspond à « $query » dans les noms ni dans les transcriptions.",
                    primaryLabel = "Effacer la recherche",
                    onPrimary = { vm.setSearchQuery("") },
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (query.isNotBlank() && visibleHits.isNotEmpty()) {
                    stickyHeader(key = "header-hits") {
                        DayHeader("Passages (${visibleHits.size}${if (visibleHits.size >= 80) "+" else ""})")
                    }
                    items(visibleHits, key = { "hit-${it.file.absolutePath}-${it.index}" }) { hit ->
                        HitCard(
                            hit = hit,
                            query = query,
                            formatHms = { vm.formatHms(it) },
                            onClick = { vm.openHit(hit) },
                        )
                    }
                    if (grouped.isNotEmpty()) {
                        item(key = "hits-spacer") { Spacer(Modifier.height(8.dp)) }
                    }
                }
                grouped.forEach { (label, items) ->
                    stickyHeader(key = "header-$label") {
                        DayHeader(label)
                    }
                    items(items, key = { it.file.absolutePath }) { rec ->
                        RecordingCard(
                            item = rec,
                            formatHms = { vm.formatHms(it) },
                            onSelect = { onSelect(rec) },
                            onRename = { renameTarget = rec },
                            onDelete = { deleteTarget = rec },
                            onShare = {
                                scope.launch {
                                    val intent = vm.buildShareIntentFor(rec)
                                    if (intent != null) {
                                        context.startActivity(
                                            Intent.createChooser(intent, "Partager la transcription")
                                        )
                                    }
                                }
                            },
                            onExport = {
                                exportPath = rec.file.absolutePath
                                exportLauncher.launch("${rec.baseName}.wav")
                            },
                            onExportDocument = { format ->
                                exportPath = rec.file.absolutePath
                                when (format) {
                                    ExportFormat.DOCX -> docxLauncher.launch("${rec.baseName}.docx")
                                    ExportFormat.PDF -> pdfLauncher.launch("${rec.baseName}.pdf")
                                }
                            },
                        )
                    }
                }
                item(key = "fab-spacer") {
                    // La dernière carte ne doit pas rester cachée sous le FAB « Importer »
                    Spacer(Modifier.height(96.dp))
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.baseName,
            onConfirm = { newName ->
                vm.renameRecording(target, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer ?") },
            text = { Text("« ${target.baseName} » (${target.sizeBytes / 1024} Ko) sera définitivement supprimé.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRecording(target)
                    deleteTarget = null
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Annuler") }
            },
        )
    }
}

/** « Aujourd'hui », « Hier », sinon « Vendredi 22 août 2026 ». */
/** Passage trouvé : enregistrement, intervenant, horodatage, extrait avec les termes en gras. */
@Composable
private fun HitCard(
    hit: SearchHit,
    query: String,
    formatHms: (Long) -> String,
    onClick: () -> Unit,
) {
    val terms = remember(query) { TextFold.terms(query) }
    val highlighted = remember(hit.text, terms) { highlight(hit.text, terms) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    hit.baseName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hit.startMs >= 0) MetaChip(text = formatHms(hit.startMs), icon = AppIcons.Clock)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                highlighted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val who = when {
                hit.speaker > 0 -> SpeakerNames.label(hit.speaker, hit.speakerNames)
                else -> ""
            }
            if (who.isNotEmpty() || hit.dossier.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(who, hit.dossier).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Met en gras chaque occurrence des termes (comparaison sans accents ni casse, longueurs identiques). */
private fun highlight(text: String, terms: List<String>) = buildAnnotatedString {
    // Le repli NFD peut changer la longueur : on replie caractère par caractère pour garder les index
    val foldedChars = text.map { c -> TextFold.fold(c.toString()).firstOrNull() ?: c }
    val folded = String(foldedChars.toCharArray())
    val bold = BooleanArray(text.length)
    for (t in terms) {
        if (t.isEmpty()) continue
        var i = folded.indexOf(t)
        while (i >= 0) {
            for (k in i until minOf(i + t.length, bold.size)) bold[k] = true
            i = folded.indexOf(t, i + t.length)
        }
    }
    var i = 0
    while (i < text.length) {
        val b = bold[i]
        var j = i
        while (j < text.length && bold[j] == b) j++
        if (b) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text, i, j) } else append(text, i, j)
        i = j
    }
}

private fun dayLabel(millis: Long, today: LocalDate): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (date) {
        today -> "Aujourd'hui"
        today.minusDays(1) -> "Hier"
        else -> date
            .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE))
            .replaceFirstChar { it.uppercase(Locale.FRANCE) }
    }
}

/** En-tête de jour épinglé : fond opaque pour passer au-dessus des cartes. */
@Composable
private fun DayHeader(label: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun RecordingCard(
    item: RecordingItem,
    formatHms: (Long) -> String,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onExportDocument: (ExportFormat) -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconAvatar(
                icon = if (item.hasAudio) AppIcons.Waveform else AppIcons.Document,
                container = if (item.hasAudio) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                tint = if (item.hasAudio) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.baseName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.dossier.isNotBlank()) {
                        MetaChip(
                            text = item.dossier,
                            icon = AppIcons.Folder,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    }
                    MetaChip(text = timeFmt.format(Date(item.modifiedAt)))
                    if (item.hasAudio) MetaChip(text = formatHms(item.durationMs), icon = AppIcons.Clock)
                    if (item.encrypted) {
                        MetaChip(text = "Chiffré", icon = Icons.Filled.Lock, contentDescription = "Chiffré")
                    }
                    if (!item.hasAudio) {
                        MetaChip(text = "Texte seul", icon = AppIcons.Document, contentDescription = "Texte seul, sans audio")
                    }
                }
                if (item.transcript.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.transcript.take(160),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Partager") },
                        leadingIcon = {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    if (item.hasAudio) {
                        DropdownMenuItem(
                            text = { Text("Exporter l'audio (WAV)") },
                            leadingIcon = {
                                Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            onClick = {
                                menuOpen = false
                                onExport()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Exporter en Word (.docx)") },
                        leadingIcon = {
                            Icon(AppIcons.Document, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            menuOpen = false
                            onExportDocument(ExportFormat.DOCX)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Exporter en PDF") },
                        leadingIcon = {
                            Icon(AppIcons.Document, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            menuOpen = false
                            onExportDocument(ExportFormat.PDF)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Renommer") },
                        leadingIcon = {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        onClick = {
                            menuOpen = false
                            onRename()
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
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommer l'enregistrement") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Nom") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("Renommer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
