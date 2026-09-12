package com.transcripto.stream.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transcripto.stream.summary.MarkdownLite
import com.transcripto.stream.summary.MdBlock
import com.transcripto.stream.ui.theme.AppIcons
import java.util.Locale

/*
 * Composants partagés du système visuel : cartes de section, pastilles d'état,
 * puces de métadonnées, états vides, boutons ronds d'action, lignes de réglage.
 * Tous n'utilisent que les tokens du thème (couleurs, typographie, formes).
 */

/** Carte de section : fond tonal, coins larges, en-tête optionnel (icône + titre). */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            if (title != null) {
                SectionHeader(title = title, icon = icon)
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** En-tête de section : icône dans une pastille primaire + titre. */
@Composable
fun SectionHeader(title: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            IconAvatar(
                icon = icon,
                size = 30.dp,
                iconSize = 17.dp,
                shape = CircleShape,
                container = MaterialTheme.colorScheme.primaryContainer,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** Sous-titre à l'intérieur d'une section (« Langue », « Gain du micro »…). */
@Composable
fun SettingLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Texte d'aide discret sous un contrôle. */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Icône dans une pastille colorée (avatar de carte, en-tête de section, état vide). */
@Composable
fun IconAvatar(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    shape: Shape = MaterialTheme.shapes.medium,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Point d'état pulsant (enregistrement en cours). */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/** Pastille d'état : point (pulsant ou fixe) + libellé court. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    container: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pulsing) {
            PulsingDot(color = color)
        } else {
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Métadonnée compacte (icône + texte) : durée, taille, heure, état chiffré… */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentDescription: String? = null,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(container)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(13.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** Bandeau en ligne : information, progression ou erreur, avec action optionnelle. */
@Composable
fun InlineBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    progress: Float? = null,
    indeterminate: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = contentColor,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (action != null) {
                    Spacer(Modifier.width(8.dp))
                    action()
                }
            }
            if (indeterminate || progress != null) {
                Spacer(Modifier.height(8.dp))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.2f),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

/** État vide : illustration, titre, explication et actions. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconAvatar(
            icon = icon,
            size = 76.dp,
            iconSize = 36.dp,
            shape = CircleShape,
            container = MaterialTheme.colorScheme.primaryContainer,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onPrimary) { Text(primaryLabel) }
                if (secondaryLabel != null && onSecondary != null) {
                    OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
                }
            }
        }
    }
}

/** Gros bouton rond (Pause / Stop / Marqueur) accessible à TalkBack. */
@Composable
fun RoundActionButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (enabled) container else container.copy(alpha = 0.4f))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = content,
                modifier = Modifier.size(size * 0.42f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Bouton d'enregistrement principal : disque primaire dans un halo, libellé dessous. */
@Composable
fun RecordButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "Appuyer pour transcrire",
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .background(primary.copy(alpha = if (enabled) 0.12f else 0.05f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .clip(CircleShape)
                    .background(if (enabled) primary else primary.copy(alpha = 0.4f))
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { onClick() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Mic,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Ligne de réglage à interrupteur (toute la ligne est cliquable, sémantique « Switch »). */
@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = if (icon != null) {
            {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            null
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** « 1×  », « 1,5× »… */
fun speedLabel(speed: Float): String {
    val s = if (speed % 1f == 0f) speed.toInt().toString() else String.format(Locale.FRANCE, "%.1f", speed)
    return "$s×"
}

/** Puce de vitesse de lecture : un tap fait défiler 1× → 1,5× → 2× → 0,5×. */
@Composable
fun SpeedChip(speed: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(speedLabel(speed), style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Icon(
                AppIcons.Speed,
                contentDescription = "Vitesse de lecture",
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}

/** Vitesse suivante dans le cycle des puces de lecture. */
fun nextSpeed(current: Float): Float = when (current) {
    1.0f -> 1.5f
    1.5f -> 2.0f
    2.0f -> 0.5f
    else -> 1.0f
}

/** Choix exclusif en boutons segmentés (langue, rétention, thème…) : code → libellé. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedChoice(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (code, label) ->
            SegmentedButton(
                selected = selected == code,
                onClick = { onSelect(code) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

/** Rendu d'une synthèse Markdown (titres, puces, gras, italique) avec les styles du thème. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownLite.parse(markdown) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    if (block.level > 1) Spacer(Modifier.height(8.dp))
                    Text(
                        richText(block.text),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleMedium
                            2 -> MaterialTheme.typography.titleSmall
                            else -> MaterialTheme.typography.labelLarge
                        },
                        color = if (block.level == 1) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.Bullet -> Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(richText(block.text), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Paragraph -> {
                    val spans = MarkdownLite.spans(block.text)
                    val meta = spans.isNotEmpty() && spans.all { it.italic }
                    Text(
                        richText(block.text),
                        style = if (meta) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = if (meta) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                MdBlock.Blank -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun richText(text: String): AnnotatedString = remember(text) {
    buildAnnotatedString {
        MarkdownLite.spans(text).forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                )
            ) { append(span.text) }
        }
    }
}
