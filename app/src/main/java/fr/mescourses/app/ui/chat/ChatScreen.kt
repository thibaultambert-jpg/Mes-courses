package fr.mescourses.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.mescourses.app.ui.common.ScreenScaffold
import fr.mescourses.app.ui.common.containerViewModel

private val suggestions = listOf(
    "Planifie 4 dîners pour 2 cette semaine",
    "Une idée de plat végétarien rapide ?",
    "Trouve une recette de blanquette sur internet",
    "Qu'y a-t-il sur ma liste de courses ?",
)

@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val vm = containerViewModel { ChatViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    val subtitle = when (val m = state.model) {
        is ModelStatus.Ready -> "${m.modelName} · ${m.backend}" + (state.tokensPerSecond?.let { " · %.1f tok/s".format(it) } ?: "")
        ModelStatus.Loading -> "Chargement du modèle…"
        ModelStatus.Absent -> "Aucun modèle"
        is ModelStatus.Failed -> "Erreur de chargement"
    }

    ScreenScaffold(
        title = "Assistant · $subtitle",
        onOpenSettings = onOpenSettings,
        actions = {
            IconButton(onClick = vm::newConversation) { Icon(Icons.Filled.AddComment, "Nouvelle conversation") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when (val m = state.model) {
                ModelStatus.Absent -> ModelBanner(
                    "Aucun modèle local n'est installé. Télécharge Gemma 4 dans les Réglages (≈ 2 à 4 Go, une seule fois).",
                    "Ouvrir les réglages", onOpenSettings,
                )
                is ModelStatus.Failed -> ModelBanner("Chargement impossible : ${m.message}", "Réessayer") { vm.load() }
                ModelStatus.Loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("Chargement du modèle en mémoire (quelques secondes)…", Modifier.padding(start = 12.dp))
                }
                is ModelStatus.Ready -> Unit
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { MessageBubble(it) }
            }

            if (state.messages.isEmpty() && state.model is ModelStatus.Ready) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    suggestions.forEach { s ->
                        AssistChip(onClick = { vm.send(s) }, label = { Text(s) }, modifier = Modifier.padding(4.dp))
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Que veux-tu manger cette semaine ?") },
                    maxLines = 4,
                    enabled = state.model is ModelStatus.Ready,
                )
                if (state.generating) {
                    IconButton(onClick = vm::stop) { Icon(Icons.Filled.Stop, "Arrêter") }
                } else {
                    IconButton(
                        onClick = { vm.send(input); input = "" },
                        enabled = input.isNotBlank() && state.model is ModelStatus.Ready,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Envoyer") }
                }
            }
        }
    }
}

@Composable
private fun ModelBanner(text: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(text)
            Spacer(Modifier.size(8.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val isUser = message.role == Role.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        when (message.role) {
            Role.TOOL -> Text(
                message.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            else -> Text(
                message.text.trim(),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp),
                    )
                    .padding(12.dp),
            )
        }
    }
}
