package fr.mescourses.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import fr.mescourses.app.ui.AppRoot
import fr.mescourses.app.ui.theme.MesCoursesTheme

class MainActivity : ComponentActivity() {

    /** URL reçue via « Partager » depuis le navigateur (import de recette). */
    private val sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Après une recréation (rotation, retour de l'arrière-plan), le partage a déjà été traité.
        if (savedInstanceState == null) handle(intent)
        setContent {
            MesCoursesTheme {
                AppRoot(sharedUrl = sharedUrl.value, onSharedUrlConsumed = { sharedUrl.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            sharedUrl.value = Regex("https?://\\S+").find(text)?.value
            // L'intent est consommé : il ne doit pas relancer l'import une seconde fois.
            setIntent(Intent(this, MainActivity::class.java))
        }
    }
}
