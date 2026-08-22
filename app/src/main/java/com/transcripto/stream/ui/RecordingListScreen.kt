package com.transcripto.stream.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Liste des enregistrements passés : durée, taille, date, recherche, renommage,
 * suppression. Un clic sélectionne l'enregistrement et revient à l'écran principal.
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

    val filtered = if (query.isBlank()) {
        recordings
    } else {
        recordings.filter {
            it.baseName.contains(query, ignoreCase = true) ||
                it.transcript.contains(query, ignoreCase = true)
        }
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
                    "Aucun enregistrement pour l'instant.\n\nAppuie sur l'onglet « Enregistrer » puis sur le micro pour créer ta première transcription."
                } else {
                    "Aucun résultat pour « $query »."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.file.absolutePath }) { rec ->
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
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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

@Composable
private fun RecordingCard(
    item: RecordingItem,
    formatHms: (Long) -> String,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
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
            }
            Spacer(Modifier.height(4.dp))
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
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.height(32.dp)) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("Partager", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = onRename, modifier = Modifier.height(32.dp)) {
                    Text("Renommer", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.height(32.dp)) {
                    Text("Supprimer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
            TextButton(onClick = { onConfirm(name) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
