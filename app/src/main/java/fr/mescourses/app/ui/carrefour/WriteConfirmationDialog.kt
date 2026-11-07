package fr.mescourses.app.ui.carrefour

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.carrefour.ConfirmationBroker
import fr.mescourses.app.ui.components.DishArt
import fr.mescourses.app.ui.components.StatChip

/** Carte « Confirmer / Annuler » pour chaque ajout ou modification de panier demandé par l'assistant. */
@Composable
fun WriteConfirmationDialog(broker: ConfirmationBroker) {
    val pending by broker.pending.collectAsStateWithLifecycle()
    val p = pending ?: return
    val r = p.request
    val title = when {
        r.newQuantity == 0 -> "Retirer du panier ?"
        r.currentQuantity == 0 -> "Ajouter au panier ?"
        else -> "Modifier la quantité ?"
    }
    AlertDialog(
        onDismissRequest = { broker.answer(false) },
        title = { Text(title) },
        text = {
            Column {
                DishArt(r.productName, modifier = Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(20.dp)), emojiSize = 36)
                Spacer(Modifier.size(12.dp))
                Text(r.productName, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatChip(if (r.currentQuantity == 0) "Quantité : ${r.newQuantity}" else "${r.currentQuantity} → ${r.newQuantity}", emphasized = true)
                    r.price?.let { Text("  ${String.format(java.util.Locale.FRANCE, "%.2f €", it)} l'unité", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = { Button(onClick = { broker.answer(true) }) { Text("Confirmer") } },
        dismissButton = { TextButton(onClick = { broker.answer(false) }) { Text("Annuler") } },
    )
}
