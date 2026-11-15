package fr.yannickuhrig.vyzo

import android.app.Application
import fr.yannickuhrig.vyzo.cast.CastSupport

/** Vyzo TV ne caste pas : c'est elle qu'on vise (« Envoyer vers mes écrans Vyzo »). */
object FlavorCast {
    fun factory(app: Application): ((AppContainer) -> CastSupport?)? = null
}
