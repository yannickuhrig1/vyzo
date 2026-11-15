package fr.yannickuhrig.vyzo

import android.app.Application
import fr.yannickuhrig.vyzo.cast.CastSupport
import fr.yannickuhrig.vyzo.cast.MobileCast

/** Vyzo mobile : Google Cast (Chromecast, TV et box avec Chromecast intégré). */
object FlavorCast {
    fun factory(app: Application): ((AppContainer) -> CastSupport?) = { MobileCast(app) }
}
