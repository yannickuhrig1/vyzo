package fr.yannickuhrig.vyzo

import android.app.Application
import androidx.activity.ComponentActivity

/** Travaux de démarrage propres à une variante : rien sur téléphone. */
object FlavorStartup {
    fun onAppStart(app: Application) = Unit
    fun onAppBackground(app: Application) = Unit
    fun onActivityCreated(activity: ComponentActivity) = Unit
}
