package fr.yannickuhrig.vyzo.ui.player

import androidx.compose.ui.input.key.Key

/** Ce qu'une touche peut faire pendant le direct. */
enum class RemoteAction(val label: String) {
    NONE("Rien"),
    ZAP_UP("Zapper vers le haut"),
    ZAP_DOWN("Zapper vers le bas"),
    CHANNEL_LIST("Liste des chaînes"),
    GUIDE("Guide de la chaîne"),
    BANNER("Infos du programme"),
    BANNER_THEN_GUIDE("Infos, puis guide au 2e appui"),
    OPTIONS("Options de lecture"),
    STREAM_INFO("Informations du flux"),
    PLAY_PAUSE("Pause du direct"),
    PREVIOUS_CHANNEL("Chaîne regardée juste avant"),
}

enum class RemoteButton(val label: String, val held: RemoteButton? = null) {
    LEFT_HELD("◀ maintenu"),
    RIGHT_HELD("▶ maintenu"),
    UP("▲ Haut"),
    DOWN("▼ Bas"),
    LEFT("◀ Gauche", LEFT_HELD),
    RIGHT("▶ Droite", RIGHT_HELD),
    MENU("Menu"),
    INFO("Info"),
    GUIDE("Guide"),
    PLAY_PAUSE("Lecture / pause"),
    RED("Touche rouge"),
    GREEN("Touche verte"),
    YELLOW("Touche jaune"),
    BLUE("Touche bleue"),
}

/** Touches du direct. Les réglages ne gardent que ce qui diffère des touches par défaut. */
object RemoteKeymap {
    private val DEFAULTS = mapOf(
        RemoteButton.UP to RemoteAction.ZAP_UP,
        RemoteButton.DOWN to RemoteAction.ZAP_DOWN,
        RemoteButton.LEFT to RemoteAction.CHANNEL_LIST,
        RemoteButton.RIGHT to RemoteAction.BANNER_THEN_GUIDE,
        RemoteButton.MENU to RemoteAction.OPTIONS,
        RemoteButton.INFO to RemoteAction.STREAM_INFO,
        RemoteButton.GUIDE to RemoteAction.GUIDE,
        RemoteButton.PLAY_PAUSE to RemoteAction.PLAY_PAUSE,
    )

    /** ◀ : liste, ◀ maintenu : guide, ▶ : infos du programme. */
    val TIVIMATE_STYLE: Map<String, String> = mapOf(
        RemoteButton.LEFT_HELD.name to RemoteAction.GUIDE.name,
        RemoteButton.RIGHT.name to RemoteAction.BANNER.name,
    )

    fun defaultFor(button: RemoteButton): RemoteAction = DEFAULTS[button] ?: RemoteAction.NONE

    fun action(overrides: Map<String, String>, button: RemoteButton): RemoteAction =
        overrides[button.name]?.let { saved -> RemoteAction.entries.firstOrNull { it.name == saved } } ?: defaultFor(button)

    fun assign(overrides: Map<String, String>, button: RemoteButton, action: RemoteAction): Map<String, String> =
        if (action == defaultFor(button)) overrides - button.name else overrides + (button.name to action.name)

    fun isDefault(overrides: Map<String, String>): Boolean = RemoteButton.entries.all { action(overrides, it) == defaultFor(it) }
}

fun remoteButtonOf(key: Key): RemoteButton? = when (key) {
    Key.DirectionUp -> RemoteButton.UP
    Key.DirectionDown -> RemoteButton.DOWN
    Key.DirectionLeft -> RemoteButton.LEFT
    Key.DirectionRight -> RemoteButton.RIGHT
    Key.Menu -> RemoteButton.MENU
    Key.Info -> RemoteButton.INFO
    Key.Guide -> RemoteButton.GUIDE
    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> RemoteButton.PLAY_PAUSE
    Key.ProgramRed -> RemoteButton.RED
    Key.ProgramGreen -> RemoteButton.GREEN
    Key.ProgramYellow -> RemoteButton.YELLOW
    Key.ProgramBlue -> RemoteButton.BLUE
    else -> null
}
