package com.transcripto.stream.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transcripto.stream.data.ActionItem
import com.transcripto.stream.data.RecordingItem
import com.transcripto.stream.export.ExportFormat
import com.transcripto.stream.ui.theme.AppIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fiche d'un dossier (client / mission) : en-tête (enregistrements, durée cumulée,
 * dernière date), actions ouvertes agrégées, intervenants nommés rencontrés, liste
 * des enregistrements ; renommer, fusionner, exporter le dossier d'un bloc.
 */
@Composable
fun DossierScreen(vm: StreamViewModel) {
    val name by vm.openDossier.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val dossiers by vm.dossiers.collectAsStateWithLifecycle()
    val summaryBusy by vm.summaryBusy.collectAsStateWithLifecycle()
    val current = name
    if (current == null) {
        EmptyState(
            icon = AppIcons.Folder,
            title = "Aucun dossier ouvert",
            body = "Touche la puce de dossier d'un enregistrement pour ouvrir sa fiche.",
        )
        return
    }
    val items = remember(recordings, current) { recordings.filter { it.dossier.equals(current, ignoreCase = true) } }
    val totalMs = items.sumOf { it.durationMs }
    val openActions = remember(items) { items.flatMap { item -> item.actions.filter { !it.done }.map { item to it } } }
    val speakers = remember(items) { items.flatMap { it.speakerNames.values }.distinct().sortedBy { it.lowercase(Locale.FRANCE) } }
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy", Locale.FRANCE) }

    var includeTranscripts by rememberSaveable { mutableStateOf(false) }
    val docxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.DOCX.mime)) { uri ->
        if (uri != null) vm.exportDossier(current, uri, ExportFormat.DOCX, includeTranscripts)
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDF.mime)) { uri ->
        if (uri != null) vm.exportDossier(current, uri, ExportFormat.PDF, includeTranscripts)
    }
    var menu by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconAvatar(
                        icon = AppIcons.Folder,
                        size = 36.dp,
                        iconSize = 20.dp,
                        shape = CircleShape,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(current, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Actions du dossier") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Renommer le dossier") }, onClick = { menu = false; renameDialog = true })
                        DropdownMenuItem(
                            text = { Text("Fusionner dans un autre dossier") },
                            enabled = dossiers.any { !it.equals(current, ignoreCase = true) },
                            onClick = { menu = false; mergeDialog = true },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetaChip(text = "${items.size} enregistrement${if (items.size > 1) "s" else ""}", icon = AppIcons.Document)
                    if (totalMs > 0) MetaChip(text = vm.formatHms(totalMs), icon = AppIcons.Clock)
                    items.maxOfOrNull { it.modifiedAt }?.let { MetaChip(text = "dernier : " + dateFmt.format(Date(it))) }
                }
                if (speakers.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Intervenants rencontrés", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(speakers.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { docxLauncher.launch("Dossier $current.docx") }, enabled = items.isNotEmpty()) { Text("Exporter Word") }
                    FilledTonalButton(onClick = { pdfLauncher.launch("Dossier $current.pdf") }, enabled = items.isNotEmpty()) { Text("Exporter PDF") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = includeTranscripts, onCheckedChange = { includeTranscripts = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Inclure les transcriptions complètes", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item(key = "actions") {
            SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    if (openActions.isEmpty()) "Actions à faire" else "Actions à faire (${openActions.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (openActions.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    HintText("Aucune action ouverte dans ce dossier.")
                } else {
                    Spacer(Modifier.height(4.dp))
                    openActions.forEach { (item, a) ->
                        DossierActionRow(item, a, enabled = !summaryBusy, onToggle = { vm.setActionDone(item.file, a.id, it) }, onOpen = { vm.openDetail(item) })
                    }
                }
            }
        }
        item(key = "list-title") {
            Text("Enregistrements", style = MaterialTheme.typography.titleSmall)
        }
        if (items.isEmpty()) {
            item(key = "empty") { HintText("Aucun enregistrement rattaché à ce dossier.") }
        }
        items(items, key = { it.file.absolutePath }) { rec ->
            DossierRecordingRow(rec, dateFmt, formatHms = { vm.formatHms(it) }, onOpen = { vm.openDetail(rec) })
        }
    }

    if (renameDialog) {
        var value by rememberSaveable { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Renommer le dossier") },
            text = {
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Nom du dossier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.renameDossier(current, value); renameDialog = false },
                    enabled = value.isNotBlank() && value.trim() != current,
                ) { Text("Renommer") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Annuler") } },
        )
    }
    if (mergeDialog) {
        val targets = dossiers.filter { !it.equals(current, ignoreCase = true) }
        var target by rememberSaveable { mutableStateOf(targets.firstOrNull() ?: "") }
        var pick by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { mergeDialog = false },
            title = { Text("Fusionner « $current »") },
            text = {
                Column {
                    Text("Les enregistrements de ce dossier seront rattachés au dossier choisi.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { pick = true }) { Text("Vers : ${target.ifBlank { "(choisir)" }}") }
                    DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                        targets.forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { target = d; pick = false }) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.mergeDossier(current, target); mergeDialog = false }, enabled = target.isNotBlank()) { Text("Fusionner") }
            },
            dismissButton = { TextButton(onClick = { mergeDialog = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun DossierActionRow(item: RecordingItem, a: ActionItem, enabled: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onOpen)
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = a.done, onCheckedChange = onToggle, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(a.text, style = MaterialTheme.typography.bodyMedium)
            val details = listOfNotNull(
                a.owner.takeIf { it.isNotBlank() },
                a.dueLabel.takeIf { it.isNotBlank() }?.let { "échéance $it" },
                item.baseName,
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DossierRecordingRow(rec: RecordingItem, dateFmt: SimpleDateFormat, formatHms: (Long) -> String, onOpen: () -> Unit) {
    SectionCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp), modifier = Modifier.clickable(onClick = onOpen)) {
        Text(rec.baseName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MetaChip(text = dateFmt.format(Date(rec.modifiedAt)))
            if (rec.hasAudio && rec.durationMs > 0) MetaChip(text = formatHms(rec.durationMs), icon = AppIcons.Clock)
            val open = rec.openActionCount
            if (open > 0) MetaChip(text = "$open action${if (open > 1) "s" else ""} à faire", tint = MaterialTheme.colorScheme.onTertiaryContainer, container = MaterialTheme.colorScheme.tertiaryContainer)
            if (rec.chapters.isNotEmpty()) MetaChip(text = "${rec.chapters.size} chapitres", icon = AppIcons.Document)
        }
        if (rec.transcript.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(rec.transcript, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
