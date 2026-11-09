package fr.yannickuhrig.vyzo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object VyzoColors {
    // Fonds bleu nuit, dans la continuité de la bannière TV.
    val Background = Color(0xFF0A0E1A)
    val Surface = Color(0xFF121830)
    val SurfaceVariant = Color(0xFF1A2140)
    val SurfaceHigh = Color(0xFF252D50)
    val Outline = Color(0xFF323B5E)
    // Accents tirés du logo : la pointe jaune et la pointe cyan du V.
    val Amber = Color(0xFFFFBE0B)
    val OnAmber = Color(0xFF261A00)
    val Cyan = Color(0xFF22D9F2)
    val Violet = Color(0xFFA27BFF)
    val Magenta = Color(0xFFFF3D8B)
    val Red = Color(0xFFFF5D5D)
    val Text = Color(0xFFE9ECF5)
    val TextDim = Color(0xFF9BA4BE)
    val Past = Color(0xFF141A2E)
}

private val scheme = darkColorScheme(
    primary = VyzoColors.Amber,
    onPrimary = VyzoColors.OnAmber,
    primaryContainer = Color(0xFF4A3700),
    onPrimaryContainer = Color(0xFFFFE19A),
    secondary = VyzoColors.Cyan,
    onSecondary = Color(0xFF00313A),
    secondaryContainer = Color(0xFF0F3A4A),
    onSecondaryContainer = Color(0xFFB5F1FB),
    tertiary = VyzoColors.Magenta,
    background = VyzoColors.Background,
    onBackground = VyzoColors.Text,
    surface = VyzoColors.Surface,
    onSurface = VyzoColors.Text,
    surfaceVariant = VyzoColors.SurfaceVariant,
    onSurfaceVariant = VyzoColors.TextDim,
    surfaceContainerLowest = VyzoColors.Background,
    surfaceContainerLow = VyzoColors.Surface,
    surfaceContainer = VyzoColors.Surface,
    surfaceContainerHigh = VyzoColors.SurfaceVariant,
    surfaceContainerHighest = VyzoColors.SurfaceHigh,
    outline = VyzoColors.Outline,
    outlineVariant = Color(0xFF262E4C),
    error = VyzoColors.Red,
)

private val base = Typography()

private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun VyzoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
