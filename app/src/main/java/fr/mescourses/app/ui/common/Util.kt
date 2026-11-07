package fr.mescourses.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.mescourses.app.AppContainer
import fr.mescourses.app.MesCoursesApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MesCoursesApp).container

/** Crée un ViewModel à partir du conteneur de l'application. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
