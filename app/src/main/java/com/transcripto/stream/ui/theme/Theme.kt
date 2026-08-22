package com.transcripto.stream.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Palette construite autour du bleu de l'icône (#1565C0).
// tertiary = orange « pause », error = rouge « stop » (tokens, plus de couleurs en dur dans les écrans).
private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E2FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF9A4600),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBC8),
    onTertiaryContainer = Color(0xFF331200),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00458C),
    onPrimaryContainer = Color(0xFFD7E2FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFFFB68A),
    onTertiary = Color(0xFF532200),
    tertiaryContainer = Color(0xFF753400),
    onTertiaryContainer = Color(0xFFFFDBC8),
)

/**
 * Thème unique de l'app : palette bleue claire/sombre + barres système accordées
 * au thème choisi dans les réglages (et plus seulement au thème système).
 */
@Composable
fun AppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
