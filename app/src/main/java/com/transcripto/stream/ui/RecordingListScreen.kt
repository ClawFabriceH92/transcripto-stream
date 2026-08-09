package com.transcripto.stream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Text(
                "Aucun enregistrement.",
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
) {
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }
    Card(onClick = onSelect, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.baseName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (item.encrypted) "🔒" else "",
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${formatHms(item.durationMs)} · ${item.sizeBytes / 1024} Ko · ${dateFmt.format(Date(item.modifiedAt))}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.transcript.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.transcript.take(120),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRename, modifier = Modifier.height(32.dp)) {
                    Text("Renommer", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.height(32.dp)) {
                    Text("Supprimer", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
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
