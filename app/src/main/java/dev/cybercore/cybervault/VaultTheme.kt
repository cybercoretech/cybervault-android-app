package dev.cybercore.cybervault

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

val Mono = FontFamily(Font(R.font.jetbrains_mono), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

/** CYBERGRID neons on the Cybercore near-black. Fixed: the app has no network to fetch themes. */
object Neon {
    val Bg = Color(0xFF07070D)
    val Panel = Color(0xFF0F0F1A)
    val PanelHigh = Color(0xFF15152A)
    val Line = Color(0xFF262640)
    val Text = Color(0xFFE6E6F0)
    val Muted = Color(0xFF9A9AB8)
    val Cyan = Color(0xFF00F0FF)
    val Pink = Color(0xFFFF2BD6)
    val Purple = Color(0xFF9B5CFF)
    val Lime = Color(0xFFC6FF45)
    val Amber = Color(0xFFFFB020)
    val Red = Color(0xFFFF3860)
}

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon.Cyan, onPrimary = Neon.Bg,
            secondary = Neon.Pink, onSecondary = Neon.Bg,
            tertiary = Neon.Lime, onTertiary = Neon.Bg,
            background = Neon.Bg, onBackground = Neon.Text,
            surface = Neon.Panel, onSurface = Neon.Text,
            surfaceVariant = Neon.PanelHigh, onSurfaceVariant = Neon.Muted,
            surfaceContainer = Neon.Panel, surfaceContainerHigh = Neon.PanelHigh,
            surfaceContainerHighest = Neon.PanelHigh,
            outline = Neon.Line, outlineVariant = Neon.Line,
            error = Neon.Red, onError = Neon.Bg,
        ),
        typography = base.copy(
            headlineLarge = base.headlineLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
            headlineMedium = base.headlineMedium.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
            headlineSmall = base.headlineSmall.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontFamily = Mono),
            titleSmall = base.titleSmall.copy(fontFamily = Mono),
            bodyLarge = base.bodyLarge.copy(fontFamily = Mono),
            bodyMedium = base.bodyMedium.copy(fontFamily = Mono),
            bodySmall = base.bodySmall.copy(fontFamily = Mono),
            labelLarge = base.labelLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
            labelMedium = base.labelMedium.copy(fontFamily = Mono),
            labelSmall = base.labelSmall.copy(fontFamily = Mono),
        ),
        content = content,
    )
}

/** Plain-language strength for an entropy estimate, shared by the generator and setup. */
enum class Strength(val label: String, val color: Color) {
    Weak("WEAK", Neon.Red),
    Fair("FAIR", Neon.Amber),
    Strong("STRONG", Neon.Cyan),
    Fortress("FORTRESS", Neon.Lime);

    companion object {
        fun of(bits: Double): Strength = when {
            bits < 50 -> Weak
            bits < 70 -> Fair
            bits < 100 -> Strong
            else -> Fortress
        }
    }
}
