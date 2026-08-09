package com.transcripto.stream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Écran de verrouillage PIN (4 chiffres). Valide automatiquement à 4 chiffres.
 */
@Composable
fun PinScreen(
    onUnlock: (String) -> Unit,
    pinError: String?,
    onClearError: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(120.dp))
        Text("Transcripto Stream", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Entrez votre code", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        // Points du PIN
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(4) { i ->
                val filled = i < pin.length
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(
                            color = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = CircleShape,
                        )
                        .padding(4.dp)
                        .background(
                            color = if (filled) Color.Transparent else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        ),
                )
            }
        }

        if (pinError != null) {
            Spacer(Modifier.height(12.dp))
            Text(pinError, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        }

        Spacer(Modifier.height(40.dp))

        // Clavier numérique
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("C", "0", "⌫"),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            keys.forEach { rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    rowKeys.forEach { key ->
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                )
                                .clickable {
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
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "L'app se verrouille au lancement tant que le PIN est actif",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
