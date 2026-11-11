package fr.mescourses.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Une pop-in « Annuler » : reste affichée jusqu'à ce qu'on la ferme ou qu'on annule, même si on change d'écran. */
data class UndoEntry(val id: Long, val message: String, val undo: suspend () -> Unit)

/**
 * Pop-in d'annulation à l'échelle de l'application : elles s'empilent (les plus récentes en bas) et ne dépendent
 * d'aucun écran, donc changer d'onglet ne les ferme pas.
 */
class UndoBar(private val scope: CoroutineScope) {
    private val ids = AtomicLong()
    private val _entries = MutableStateFlow<List<UndoEntry>>(emptyList())
    val entries: StateFlow<List<UndoEntry>> = _entries

    fun push(message: String, undo: suspend () -> Unit) {
        _entries.update { (it + UndoEntry(ids.incrementAndGet(), message, undo)).takeLast(MAX) }
    }

    fun dismiss(id: Long) = _entries.update { list -> list.filterNot { it.id == id } }

    /** Annule l'action puis ferme la pop-in. */
    fun undo(entry: UndoEntry) {
        dismiss(entry.id)
        scope.launch { runCatching { entry.undo() } }
    }

    private companion object { const val MAX = 5 }
}

@Composable
fun UndoBarHost(bar: UndoBar, modifier: Modifier = Modifier) {
    val entries by bar.entries.collectAsStateWithLifecycle()
    if (entries.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { e ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(e.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(onClick = { bar.undo(e) }) { Text("Annuler", color = MaterialTheme.colorScheme.inversePrimary) }
                    IconButton(onClick = { bar.dismiss(e.id) }) { Icon(Icons.Filled.Close, "Fermer") }
                }
            }
        }
    }
}
