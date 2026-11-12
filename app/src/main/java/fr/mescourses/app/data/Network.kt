package fr.mescourses.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Vrai s'il y a une connexion capable d'accéder à internet (en cas de doute, on suppose que oui). */
fun isOnline(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(true)
