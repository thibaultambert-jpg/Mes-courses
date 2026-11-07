package fr.mescourses.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import fr.mescourses.app.ui.theme.gradientFor
import fr.mescourses.core.menu.PlateBalance
import fr.mescourses.core.visual.DishVisuals

/** Illustration d'un plat : photo si disponible, sinon grand emoji sur un dégradé de sa catégorie. */
@Composable
fun DishArt(
    title: String,
    ingredients: List<String> = emptyList(),
    imageUrl: String? = null,
    modifier: Modifier = Modifier,
    emojiSize: Int = 44,
) {
    val visual = DishVisuals.of(title, ingredients)
    val dark = isSystemInDarkTheme()
    Box(
        modifier.background(Brush.linearGradient(gradientFor(visual.category, dark))),
        contentAlignment = Alignment.Center,
    ) {
        Text(visual.emoji, fontSize = emojiSize.sp)
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Compteur en pilule : « 🧑 Adultes  (−) 2 (+) ». */
@Composable
fun PillStepper(
    emoji: String,
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    min: Int = 0,
    max: Int = 30,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 20.sp)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
        FilledTonalIconButton(onClick = { onChange(value - 1) }, enabled = enabled && value > min, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, "Moins")
        }
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        FilledTonalIconButton(onClick = { onChange(value + 1) }, enabled = enabled && value < max, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, "Plus")
        }
    }
}

/** Petit compteur compact (pour les convives d'un plat). */
@Composable
fun MiniStepper(emoji: String, value: Int, onChange: (Int) -> Unit, enabled: Boolean = true, contentDescription: String) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallRoundButton(Icons.Filled.Remove, "$contentDescription : moins", enabled && value > 0) { onChange(value - 1) }
        Text("$emoji $value", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 2.dp))
        SmallRoundButton(Icons.Filled.Add, "$contentDescription : plus", enabled) { onChange(value + 1) }
    }
}

@Composable
private fun SmallRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) { Icon(icon, description, Modifier.size(16.dp)) }
}

/** Ingrédient en pastille ; ♻ et couleur verte s'il sert à plusieurs plats. */
@Composable
fun IngredientChip(name: String, shared: Boolean) {
    val bg by animateColorAsState(
        if (shared) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "chip",
    )
    Text(
        (if (shared) "♻ " else "") + name,
        style = MaterialTheme.typography.labelMedium,
        color = if (shared) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Trois pastilles protéine / légumes / féculent, allumées si présentes. */
@Composable
fun BalanceDots(present: Set<PlateBalance.Component>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        PlateBalance.Component.entries.forEach { c ->
            val on = c in present
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .then(if (on) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                Text(c.emoji, fontSize = 13.sp, modifier = Modifier.then(if (on) Modifier else Modifier.background(Color.Transparent)), color = if (on) Color.Unspecified else Color.Gray)
            }
        }
    }
}

/** Texte expliquant ce qui manque à l'assiette. */
fun balanceHint(present: Set<PlateBalance.Component>): String? {
    val missing = PlateBalance.Component.entries - present
    return if (missing.isEmpty()) null else "Il manque " + missing.joinToString(" et ") { it.missingLabel }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, emoji: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (emoji != null) Text(emoji, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun StatChip(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** État vide illustré, avec une action facultative. */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 44.sp) }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) { Text(actionLabel) }
        }
    }
}

/** Indicateur d'étapes « ① Réglages — ② Menu — ③ Semaine ». */
@Composable
fun StepIndicator(steps: List<String>, current: Int, onStep: (Int) -> Unit, enabled: (Int) -> Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, label ->
            val done = i < current
            val active = i == current
            val color = when {
                active -> MaterialTheme.colorScheme.primary
                done -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled(i)) { onStep(i) }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    else Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 6.dp),
                    maxLines = 1,
                )
            }
            if (i < steps.lastIndex) {
                HorizontalDivider(Modifier.weight(1f).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Barre d'actions collée en bas de l'écran. */
@Composable
fun BottomActionBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Carte arrondie de section. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, contentPadding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Bannière d'information colorée (saison, conseil…). */
@Composable
fun InfoBanner(emoji: String, text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.tertiaryContainer) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(color).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(emoji, fontSize = 20.sp)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit) = TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(text) }


/** Carte « le chef travaille » : chargement du modèle, réflexion, écriture d'une recette. */
@Composable
fun ChefCard(title: String, text: String, modifier: Modifier = Modifier, emoji: String = "👨‍🍳") {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    Modifier.size(34.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary,
                )
                Text(emoji, fontSize = 16.sp)
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** Le modèle se charge en mémoire. */
@Composable
fun ModelLoadingCard(modifier: Modifier = Modifier) =
    ChefCard("Le chef enfile son tablier…", "L'assistant se prépare sur ton téléphone, quelques secondes.", modifier)
