// Dérivé de Look.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : couleurs et noms de Vyzo, libellés en français.
package fr.yannickuhrig.vyzo.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Thème de couleur : nom, aplat (boutons, barres) et accent (texte et cadres sur fond sombre). */
class AccentPreset(val name: String, val fill: Int, val highlight: Int)

/** Couleurs d'un thème (fond, surfaces, aplat, accent, texte sur l'aplat). */
class LookPalette(val bg: Color, val surf: Color, val surf2: Color, val fill: Color, val high: Color, val onFill: Color)

/** Thème de fond : nom, fond, surface, surface 2. */
class BgPreset(val name: String, val bg: Int, val surf: Int, val surf2: Int)

/** État central de l'apparence : les écrans lisent ces couleurs, un changement de thème s'applique donc partout. */
object Look {
    /** Couleurs de Vyzo, les mêmes que VyzoColors et le thème Material partagé. */
    const val BRAND_FILL = 0xFFBE0B
    const val BRAND_HIGHLIGHT = 0xFFE19A
    const val BRAND_BG = 0x0A0E1A
    const val DEFAULT_ACCENT = 0
    const val DEFAULT_BG = 0

    val ACCENTS = listOf(
        AccentPreset("Vyzo", BRAND_FILL, BRAND_HIGHLIGHT),
        AccentPreset("Violet et turquoise", 0x7C5CFF, 0x00D4C8),
        AccentPreset("Océan", 0x2F6FE0, 0x5CD6FF),
        AccentPreset("Émeraude", 0x0A7F58, 0x4ADE80),
        AccentPreset("Coucher de soleil", 0xD93F46, 0xFFB454),
        AccentPreset("Rosé", 0xC72F8F, 0xFF9ED2),
        AccentPreset("Or", 0x9A6414, 0xFFD166),
        AccentPreset("Orange", 0xC2410C, 0xFFA64D),
        AccentPreset("Bleu glacier", 0x0E7490, 0x67E8F9),
        AccentPreset("Citron vert", 0x4D7C0F, 0xBEF264),
        AccentPreset("Pourpre", 0x7E22CE, 0xE879F9),
        AccentPreset("Ardoise", 0x475569, 0xE2E8F0),
    )

    /** Nuancier de la couleur personnalisée (2 rangées de 10). */
    val SWATCHES = listOf(
        0xE53935, 0xF4511E, 0xFB8C00, 0xFFB300, 0xFDD835, 0x9CCC65, 0x4ADE80, 0x00C98D, 0x00BFA5, 0x26C6DA,
        0x29B6F6, 0x3D7BFF, 0x5C6BFF, 0x8E5CFF, 0xB44CF0, 0xE040C0, 0xFF4F8B, 0xFF6F61, 0xCFD8DC, 0x78909C,
    )

    /** Index suivant les thèmes prédéfinis : couleur personnalisée (voir [custom]). */
    val CUSTOM get() = ACCENTS.size

    val BGS = listOf(
        BgPreset("Vyzo Nuit", BRAND_BG, 0x121830, 0x1A2140),
        BgPreset("Minuit", 0x07080C, 0x12141C, 0x1B1E2A),
        BgPreset("Noir OLED", 0x000000, 0x0B0C10, 0x15171E),
        BgPreset("Graphite", 0x121316, 0x1C1E24, 0x272A32),
        BgPreset("Bleu profond", 0x070B14, 0x101828, 0x1A2540),
    )

    /** Par défaut 85 % (index 1) : plus de contenu à l'écran, tout reste lisible. */
    const val DEFAULT_SCALE = 1

    val MOTION = listOf("Aucune", "Discrètes", "Normales")
    val SCALES = listOf("Automatique", "85 %", "100 %", "115 %", "130 %")

    var accentIdx by mutableIntStateOf(DEFAULT_ACCENT)

    /** Couleur personnalisée 1 (surfaces, boutons, barres). */
    var custom by mutableIntStateOf(BRAND_FILL)

    /** Couleur personnalisée 2 (texte et cadres sur le fond) ; -1 = déduite de la couleur 1. */
    var custom2 by mutableIntStateOf(-1)
    var bgIdx by mutableIntStateOf(DEFAULT_BG)
    var motion by mutableIntStateOf(2)
    var uiScale by mutableIntStateOf(DEFAULT_SCALE)

    private fun bgRgb(): Int = BGS[bgIdx.coerceIn(0, BGS.lastIndex)].bg

    /** Couleur 1 : thème prédéfini ou couleur personnalisée (le texte posé dessus s'adapte, voir [onAccent]). */
    fun fillRgb(): Int = if (accentIdx in ACCENTS.indices) ACCENTS[accentIdx].fill else custom

    /** Couleur 2 automatique : déduite de la couleur 1 pour rester lisible sur le fond. */
    fun autoHighRgb(): Int = ThemeMath.accentFromRgb(custom, bgRgb()).second

    /** Couleur 2 : thème prédéfini, couleur personnalisée 2 ou automatique. */
    fun highRgb(): Int = when {
        accentIdx in ACCENTS.indices -> ACCENTS[accentIdx].highlight
        custom2 >= 0 -> custom2
        else -> autoHighRgb()
    }

    private fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)

    val bg: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].bg)
    val surf: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].surf)
    val surf2: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].surf2)
    val accent1: Color get() = c(fillRgb())
    val accent2: Color get() = c(highRgb())

    /** Texte sur les aplats de la couleur 1 (blanc ou sombre, selon la lisibilité). */
    val onAccent: Color get() = c(ThemeMath.onAccent(fillRgb()))

    /** Couleurs d'un thème, même seulement essayé (aperçu des réglages) : ne change rien à l'appli. */
    fun palette(accent: Int = accentIdx, cust: Int = custom, cust2: Int = custom2, bgI: Int = bgIdx): LookPalette {
        val b = BGS[bgI.coerceIn(0, BGS.lastIndex)]
        val fill = if (accent in ACCENTS.indices) ACCENTS[accent].fill else cust
        val high = when {
            accent in ACCENTS.indices -> ACCENTS[accent].highlight
            cust2 >= 0 -> cust2
            else -> ThemeMath.accentFromRgb(cust, b.bg).second
        }
        return LookPalette(c(b.bg), c(b.surf), c(b.surf2), c(fill), c(high), c(ThemeMath.onAccent(fill)))
    }

    fun reset() {
        accentIdx = DEFAULT_ACCENT
        custom = BRAND_FILL
        custom2 = -1
        bgIdx = DEFAULT_BG
        motion = 2
        uiScale = DEFAULT_SCALE
    }
}

/** Agrandissement au focus, selon le niveau d'animation choisi. */
fun focusScale(target: Float): Float = ThemeMath.focusScale(target, Look.motion)
