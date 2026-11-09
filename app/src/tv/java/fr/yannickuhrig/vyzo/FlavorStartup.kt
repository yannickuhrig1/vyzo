package fr.yannickuhrig.vyzo

import android.app.Application
import androidx.activity.ComponentActivity
import fr.yannickuhrig.vyzo.tv.HomeChannels
import fr.yannickuhrig.vyzo.tv.HomeChannelsWorker

/** Rangées Vyzo de l'écran d'accueil de la TV. */
object FlavorStartup {
    fun onAppStart(app: Application) {
        HomeChannelsWorker.schedule(app)
        HomeChannelsWorker.refreshSoon(app)
    }

    fun onAppBackground(app: Application) = HomeChannelsWorker.refreshSoon(app)

    fun onActivityCreated(activity: ComponentActivity) = HomeChannels.offerFavoritesOnce(activity)
}
