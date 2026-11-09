package fr.mescourses.app.ui.retail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.mescourses.core.retail.Retailer

/** Couleur repère de chaque enseigne (pastille), sans logo ni marque graphique. */
fun retailerColor(r: Retailer): Color = when (r) {
    Retailer.CARREFOUR -> Color(0xFF1E5BC6)
    Retailer.LECLERC -> Color(0xFF0B6FB8)
    Retailer.AUCHAN -> Color(0xFFD6001C)
}

/** Pastille ronde avec l'initiale de l'enseigne. */
@Composable
fun RetailerDot(r: Retailer, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(retailerColor(r)), contentAlignment = Alignment.Center) {
        Text(r.label.removePrefix("E.").take(1), color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** Choix de l'enseigne (★ = enseigne préférée des Réglages). */
@Composable
fun RetailerChips(selected: Retailer, preferred: Retailer?, enabled: Boolean = true, onSelect: (Retailer) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Retailer.entries.forEach { r ->
            FilterChip(
                selected = r == selected,
                onClick = { onSelect(r) },
                enabled = enabled,
                label = { Text(r.label + if (r == preferred) " ★" else "") },
                leadingIcon = { RetailerDot(r, 18.dp) },
            )
        }
    }
}
