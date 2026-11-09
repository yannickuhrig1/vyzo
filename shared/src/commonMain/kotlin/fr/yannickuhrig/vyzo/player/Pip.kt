package fr.yannickuhrig.vyzo.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * État partagé de l'incrustation (PiP) entre l'activité (qui entre/sort du mode) et l'écran du lecteur
 * (qui dit quand une vidéo plein écran est affichée et masque ses surcouches dans la petite fenêtre).
 * Sur PC, l'équivalent est le mini-lecteur : seul [screenActive] y sert.
 */
class PipState {
    private val _screenActive = MutableStateFlow(false)
    /** Le lecteur plein écran est affiché. */
    val screenActive: StateFlow<Boolean> = _screenActive.asStateFlow()

    private val _inPip = MutableStateFlow(false)
    /** L'activité est actuellement dans la petite fenêtre système (Android PiP). */
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    private val _aspect = MutableStateFlow(16f / 9f)
    /** Format de la vidéo en cours (largeur/hauteur), pour la forme de la fenêtre. */
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    private val _isMiniPlayerMode = MutableStateFlow(false)
    /** Mini-lecteur interne flottant actif dans l'application. */
    val isMiniPlayerMode: StateFlow<Boolean> = _isMiniPlayerMode.asStateFlow()

    private val _miniPlayerTitle = MutableStateFlow("")
    val miniPlayerTitle: StateFlow<String> = _miniPlayerTitle.asStateFlow()

    private val _miniPlayerSubtitle = MutableStateFlow("")
    val miniPlayerSubtitle: StateFlow<String> = _miniPlayerSubtitle.asStateFlow()

    var lastPlayerRoute: String? = null
    var onNextChannel: (() -> Unit)? = null

    fun setMiniPlayerInfo(title: String, subtitle: String = "") {
        _miniPlayerTitle.value = title
        _miniPlayerSubtitle.value = subtitle
    }

    fun setScreenActive(active: Boolean) {
        _screenActive.value = active
    }

    fun setInPip(pip: Boolean) {
        _inPip.value = pip
    }

    fun setMiniPlayerMode(mini: Boolean) {
        _isMiniPlayerMode.value = mini
        if (!mini) {
            _screenActive.value = true
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // Android refuse les formats plus étirés que 2,39:1.
        _aspect.value = (width.toFloat() / height).coerceIn(1 / 2.39f, 2.39f)
    }
}
