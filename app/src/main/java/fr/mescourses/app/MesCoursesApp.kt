package fr.mescourses.app

import android.app.Application
import android.content.ComponentCallbacks2

class MesCoursesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * MagicOS ferme agressivement les applications gourmandes : quand le système manque de mémoire
     * ou que l'app passe en arrière-plan, on libère le modèle (plusieurs Go). Il est rechargé à la demande.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            container.model.releaseIfIdle()
        }
    }
}
