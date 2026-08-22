package com.transcripto.stream.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Liste des enregistrements groupée par jour : recherche, sélection, et menu
 * par élément (partager, exporter l'audio, renommer, supprimer).
 * L'import d'audio externe passe par le bouton « Importer » (FAB du Scaffold).
 */
@Composable
fun RecordingListScreen(
    vm: StreamViewModel,
    onSelect: (RecordingItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<RecordingItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RecordingItem?>(null) }
    var exportTarget by remember { mutableStateOf<RecordingItem?>(null) }

    // Export SAF : l'utilisateur choisit l'emplacement (Téléchargements, Drive…)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) vm.exportAudio(target, uri)
    }

    val filtered = if (query.isBlank()) {
        recordings
    } else {
        recordings.filter {
            it.baseName.contains(query, ignoreCase = true) ||
                it.transcript.contains(query, ignoreCase = true)
        }
    }

    // Groupes par jour (la liste est déjà triée par date décroissante)
    val grouped = remember(filtered) {
        val today = LocalDate.now()
        filtered.groupBy { dayLabel(it.modifiedAt, today) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setSearchQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Rechercher (nom ou contenu)…") },
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${filtered.size} enregistrement${if (filtered.size > 1) "s" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Text(
                if (query.isBlank()) {
                    "Aucun enregistrement pour l'instant.\n\n" +
                        "Appuie sur l'onglet « Enregistrer » puis sur le micro pour créer ta " +
                        "première transcription, ou sur « Importer » pour transcrire un audio " +
                        "existant (WhatsApp, dictaphone…)."
                } else {
                    "Aucun résultat pour « $query »."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                grouped.forEach { (label, items) ->
                    item(key = "header-$label") {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
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
                                exportTarget = rec
                                exportLauncher.launch("${rec.baseName}.wav")
                            },
                        )
                    }
                }
                item(key = "fab-spacer") {
                    // La dernière carte ne doit pas rester cachée sous le FAB « Importer »
                    Spacer(Modifier.height(88.dp))
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

@Composable
private fun RecordingCard(
    item: RecordingItem,
    formatHms: (Long) -> String,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("HH:mm", Locale.FRANCE) }
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 10.dp, end = 4.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = item.baseName,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (item.encrypted) {
                    Text(
                        text = "🔒",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "Chiffré" },
                    )
                }
                if (!item.hasAudio) {
                    Text(
                        text = "📝",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "Texte seul, sans audio" },
                    )
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
                                onClick = {
                                    menuOpen = false
                                    onExport()
                                },
                            )
                        }
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
            Text(
                text = "${formatHms(item.durationMs)} · ${item.sizeBytes / 1024} Ko · ${dateFmt.format(Date(item.modifiedAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.transcript.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.transcript.take(120),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(end = 8.dp),
                )
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
            TextButton(onClick = { onConfirm(name) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
