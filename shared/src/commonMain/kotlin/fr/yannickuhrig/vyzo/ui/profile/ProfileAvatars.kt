package fr.yannickuhrig.vyzo.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import fr.yannickuhrig.vyzo.data.profile.ProfileManager

/**
 * Catalogue d'avatars vectoriels partagé avec le portail Web (web/src/avatars.ts).
 * Chaque entrée dessine ses tracés dans une grille 24x24, zéro ressource externe requise.
 */
data class AvatarSpec(
    val id: String,
    val label: String,
    val paths: List<Path>,
)

object ProfileAvatars {
    val all: List<AvatarSpec> = listOf(
        spec("popcorn", "Ciné", "M6 10h12l-1.5 10h-9z", "M7 10a3 3 0 0 1 2.5-4.6A3 3 0 0 1 14.5 5 3 3 0 0 1 17 10", "M10 10v10", "M14 10v10"),
        spec("clapper", "Clap", "M4 9h16v11H4z", "M4 9l2-5h12l2 5", "M9 4l2 5", "M14 4l2 5"),
        spec("headphones", "Audio", "M5 13a7 7 0 0 1 14 0", "M5 13v4a2 2 0 0 0 2 2h1v-6H5z", "M19 13v4a2 2 0 0 1-2 2h-1v-6h3z"),
        spec("gamepad", "Jeux", "M6 10h12a3 3 0 0 1 2.8 4l-1 3.5a2 2 0 0 1-3.5 0.7L14 16h-4l-2.3 2.2a2 2 0 0 1-3.5-0.7l-1-3.5A3 3 0 0 1 6 10z", "M8 13h2", "M9 12v2", "M15.5 12.5h0.01", "M17 14h0.01"),
        spec("ticket", "Séance", "M4 8h16v3a1.5 1.5 0 0 0 0 3v3H4v-3a1.5 1.5 0 0 0 0-3z", "M10 8v9", "M14 11.5h3", "M14 13.5h2"),
        spec("cat", "Chat", "M5 9l1.5-5 3.5 2.5h4L17.5 4 19 9a6.5 6.5 0 0 1-14 0z", "M9.5 11.5h0.01", "M14.5 11.5h0.01", "M10.5 14.5l1.5 1 1.5-1"),
        spec("fox", "Renard", "M4 5l4 4h8l4-4-1 8-7 6-7-6z", "M9.5 11.5h0.01", "M14.5 11.5h0.01", "M11 16h2"),
        spec("bear", "Ours", "M5 8a2.5 2.5 0 1 1 3.5-2.3", "M15.5 5.7A2.5 2.5 0 1 1 19 8", "M6 13a6 6 0 1 1 12 0 6 6 0 0 1-12 0z", "M10 12h0.01", "M14 12h0.01", "M10.5 15h3"),
        spec("owl", "Hibou", "M6 7l3 2h6l3-2 1 6a7 7 0 0 1-14 0z", "M9.5 12a1.5 1.5 0 1 0 0 0.01", "M14.5 12a1.5 1.5 0 1 0 0 0.01", "M12 14l-1 1.5h2z"),
        spec("astronaut", "Espace", "M6 10a6 6 0 1 1 12 0v3a6 6 0 0 1-12 0z", "M8 10h8v3a4 4 0 0 1-8 0z", "M4 19c2-2 14-2 16 0"),
        spec("robot", "Robot", "M6 8h12v10H6z", "M12 4v4", "M9.5 12h0.01", "M14.5 12h0.01", "M9 15.5h6", "M4 12h2", "M18 12h2"),
        spec("ghost", "Fantôme", "M6 19v-7a6 6 0 0 1 12 0v7l-2-1.5L14 19l-2-1.5L10 19l-2-1.5z", "M10 11.5h0.01", "M14 11.5h0.01"),
        spec("dino", "Dino", "M7 19v-8a5 5 0 0 1 9-3l3 2-2 2v7h-3v-3h-4v3z", "M14 9h0.01", "M7 10l-2-1", "M7 13l-2-1"),
        spec("rocket", "Fusée", "M12 3c4 3 5 7 5 11l-5 3-5-3c0-4 1-8 5-11z", "M12 10a1.5 1.5 0 1 0 0 0.01", "M7 14l-3 3h3", "M17 14l3 3h-3"),
        spec("star", "Étoile", "M12 3l2.6 5.3 5.9 0.9-4.3 4.2 1 5.9L12 16.5l-5.2 2.8 1-5.9-4.3-4.2 5.9-0.9z"),
        spec("sun", "Soleil", "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M12 3v2", "M12 19v2", "M4.9 4.9l1.4 1.4", "M17.7 17.7l1.4 1.4", "M3 12h2", "M19 12h2", "M4.9 19.1l1.4-1.4", "M17.7 6.3l1.4-1.4"),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): AvatarSpec? = byId[ProfileManager.cleanAvatar(id)]

    private fun spec(id: String, label: String, vararg data: String): AvatarSpec =
        AvatarSpec(id, label, data.map { PathParser().parsePathString(it).toPath() })
}

@Composable
fun AvatarGlyph(
    spec: AvatarSpec,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        withTransform({ scale(sx, sy, Offset.Zero) }) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            spec.paths.forEach { path ->
                drawPath(path = path, color = tint, style = stroke)
            }
        }
    }
}
