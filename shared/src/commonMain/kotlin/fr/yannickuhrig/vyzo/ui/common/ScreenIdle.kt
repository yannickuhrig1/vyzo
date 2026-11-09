package fr.yannickuhrig.vyzo.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Inactivité de l'utilisateur et état de l'économiseur d'écran. Appelé depuis le fil principal. */
class ScreenIdle {
    private val lastActivity = MutableStateFlow(System.currentTimeMillis())
    val lastActivityAt: StateFlow<Long> = lastActivity.asStateFlow()

    private val shown = MutableStateFlow(false)
    val saverShown: StateFlow<Boolean> = shown.asStateFlow()

    private var swallowUntilKeyUp = false

    fun touch() {
        lastActivity.value = System.currentTimeMillis()
    }

    fun showSaver() {
        shown.value = true
    }

    fun hideSaver() {
        shown.value = false
    }

    /**
     * À appeler pour chaque touche. Renvoie true si la touche ne doit pas aller plus loin :
     * celle qui réveille l'écran ne doit ni ouvrir un titre ni quitter l'appli.
     * Une touche [audio] (volume, sourdine) agit normalement et laisse l'économiseur affiché.
     */
    fun onKey(isDown: Boolean, audio: Boolean): Boolean {
        touch()
        if (audio) return false
        if (shown.value) {
            hideSaver()
            swallowUntilKeyUp = isDown
            return true
        }
        if (swallowUntilKeyUp) {
            if (!isDown) swallowUntilKeyUp = false
            return true
        }
        return false
    }
}
