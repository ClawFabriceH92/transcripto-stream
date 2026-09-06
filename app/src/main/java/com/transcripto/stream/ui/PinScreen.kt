package com.transcripto.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.transcripto.stream.ui.theme.AppIcons

/**
 * Écran de verrouillage PIN (4 chiffres) : marque, points de saisie, pavé numérique.
 * Valide automatiquement au 4e chiffre.
 */
@Composable
fun PinScreen(
    onUnlock: (String) -> Unit,
    pinError: String?,
    onClearError: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(88.dp))
        IconAvatar(
            icon = AppIcons.Mic,
            size = 72.dp,
            iconSize = 34.dp,
            shape = CircleShape,
            container = MaterialTheme.colorScheme.primary,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.height(18.dp))
        Text("Transcripto Stream", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Saisis ton code pour continuer",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))

        // Points du PIN
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.semantics {
                contentDescription = "${pin.length} chiffre(s) sur 4 saisi(s)"
            },
        ) {
            repeat(4) { i ->
                val filled = i < pin.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                        .border(
                            width = 2.dp,
                            color = if (filled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            pinError ?: " ",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        // Clavier numérique
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "⌫"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            keys.forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    rowKeys.forEach { key ->
                        val isDigit = key.all(Char::isDigit)
                        val keyLabel = when (key) {
                            "C" -> "Effacer tout"
                            "⌫" -> "Retour arrière"
                            else -> key
                        }
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isDigit) {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .clickable(role = Role.Button, onClickLabel = keyLabel) {
                                    when (key) {
                                        "C" -> {
                                            pin = ""
                                            onClearError()
                                        }
                                        "⌫" -> {
                                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                        }
                                        else -> {
                                            if (pin.length < 4) {
                                                pin += key
                                                onClearError()
                                                if (pin.length == 4) onUnlock(pin)
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = key,
                                style = if (isDigit) {
                                    MaterialTheme.typography.headlineSmall
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                color = if (isDigit) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "L'app se verrouille au lancement tant que le PIN est actif.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
