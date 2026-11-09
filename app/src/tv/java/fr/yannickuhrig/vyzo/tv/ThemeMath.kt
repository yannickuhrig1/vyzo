// Repris de ThemeMath.kt d'IPVIO Stream, Copyright (C) 2026 IPVIO Stream contributors, GNU GPL 3.
// Modifié pour Vyzo le 2026-11-08 : nom du paquet.
package fr.yannickuhrig.vyzo.tv

/**
 * Reine Rechenlogik für Farben und Skalierung (ohne Android/Compose, daher einzeln testbar).
 * Farben sind als 0xRRGGBB-Zahl angegeben.
 */
object ThemeMath {
    /** Relative Helligkeit nach WCAG 2.x (0 = schwarz, 1 = weiß). */
    fun luminance(rgb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch((rgb shr 16) and 0xFF) + 0.7152 * ch((rgb shr 8) and 0xFF) + 0.0722 * ch(rgb and 0xFF)
    }

    /** Kontrastverhältnis zweier Farben (1 = gleich, 21 = schwarz auf weiß). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** HSV (h 0..360, s und v 0..1) nach 0xRRGGBB. */
    fun hsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        fun c(x: Float) = (x * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /**
     * Aus einem frei gewählten Farbton werden zwei Akzentfarben gebildet, die IMMER lesbar bleiben:
     * - Fläche: darunter steht weiße Schrift, Kontrast mindestens 4,5 : 1.
     * - Highlight: liegt als Schrift oder Rahmen auf dem dunklen Hintergrund, Kontrast mindestens 4,5 : 1.
     */
    fun accentFromHue(hue: Int, bg: Int = 0x07080C): Pair<Int, Int> {
        var v = 0.92f
        var fill = hsv(hue.toFloat(), 0.62f, v)
        while (contrast(0xFFFFFF, fill) < 4.5 && v > 0.2f) { v -= 0.02f; fill = hsv(hue.toFloat(), 0.62f, v) }
        // Highlight: erst heller machen, wenn das nicht reicht (z. B. bei Blau), zusätzlich entsättigen.
        val hh = hue + 150f
        var w = 0.80f
        var hi = hsv(hh, 0.70f, w)
        while (contrast(hi, bg) < 4.5 && w < 1f) { w = (w + 0.02f).coerceAtMost(1f); hi = hsv(hh, 0.70f, w) }
        var sat = 0.70f
        while (contrast(hi, bg) < 4.5 && sat > 0f) { sat = (sat - 0.05f).coerceAtLeast(0f); hi = hsv(hh, sat, 1f) }
        return fill to hi
    }

    /** 0xRRGGBB -> floatArrayOf(h 0..360, s 0..1, v 0..1) */
    fun rgbToHsv(rgb: Int): FloatArray {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
    }

    /**
     * Aus einer frei gewählten Farbe werden Fläche (weiße Schrift darauf, Kontrast >= 4,5) und
     * Highlight (auf dunklem Grund, Kontrast >= 4,5) abgeleitet. Der Farbton bleibt erhalten.
     */
    fun accentFromRgb(rgb: Int, bg: Int = 0x07080C): Pair<Int, Int> {
        val hsv = rgbToHsv(rgb)
        val h = hsv[0]
        val s = hsv[1]
        var v = hsv[2].coerceAtLeast(0.25f)
        var fill = hsv(h, s, v)
        while (contrast(0xFFFFFF, fill) < 4.5 && v > 0.05f) { v -= 0.02f; fill = hsv(h, s, v) }
        var w = maxOf(hsv[2], 0.80f)
        val hs = minOf(s, 0.75f)
        var hi = hsv(h, hs, w)
        while (contrast(hi, bg) < 4.5 && w < 1f) { w = (w + 0.02f).coerceAtMost(1f); hi = hsv(h, hs, w) }
        var sat = hs
        while (contrast(hi, bg) < 4.5 && sat > 0f) { sat = (sat - 0.05f).coerceAtLeast(0f); hi = hsv(h, sat, 1f) }
        return fill to hi
    }

    /** Dunkle Schrift für sehr helle Akzentfarben (z. B. Neon-Grün): Firmenfarbe Navy. */
    const val INK = 0x0B0F19

    /**
     * Schriftfarbe auf einer Akzent-Fläche: Weiß, solange es gut lesbar ist (Kontrast >= 4), sonst die besser lesbare
     * von Weiß und dunklem Navy. So bleiben auch sehr helle Farben wie #39FF13 als Fläche benutzbar.
     */
    fun onAccent(fill: Int): Int {
        val w = contrast(0xFFFFFF, fill)
        if (w >= 4.0) return 0xFFFFFF
        return if (contrast(INK, fill) > w) INK else 0xFFFFFF
    }

    /** "#2F6FE0", "2f6fe0" oder "26E" -> 0xRRGGBB, sonst null. */
    fun parseHex(text: String): Int? {
        var t = text.trim().removePrefix("#")
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6) return null
        return t.toIntOrNull(16)
    }

    fun hexOf(rgb: Int): String = "#" + String.format("%06X", rgb and 0xFFFFFF)

    /** Skalierung beim Fokussieren je nach Animationsstufe (0 = aus, 1 = dezent, 2 = normal). */
    fun focusScale(target: Float, motion: Int): Float = when (motion) {
        0 -> 1f
        1 -> 1f + (target - 1f) * 0.4f
        else -> target
    }

    /**
     * Faktor für die gesamte Oberfläche. Auto: Geräte mit sehr breitem dp-Bereich (z. B. Fire TV mit 1280 dp)
     * werden auf die Entwurfsbreite von 960 dp gebracht, damit nichts zu klein wirkt.
     */
    fun uiFactor(mode: Int, screenWidthDp: Int): Float = when (mode) {
        1 -> 0.85f
        2 -> 1f
        3 -> 1.15f
        4 -> 1.3f
        else -> if (screenWidthDp > 1000) (screenWidthDp / 960f).coerceIn(1f, 2f) else 1f
    }
}
