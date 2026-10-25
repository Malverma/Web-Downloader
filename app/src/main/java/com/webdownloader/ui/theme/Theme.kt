package com.webdownloader.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.webdownloader.data.prefs.ThemeChoice

/**
 * Every theme follows the same recipe as Nebula: a deep, tinted night background,
 * a glowing main accent, and a contrasting "starlight" second accent.
 * [containers] go from lowest to highest elevation.
 */
private fun night(
    primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    secondary: Long, tertiary: Long,
    background: Long, surface: Long, containers: List<Long>,
    onSurface: Long, onSurfaceVariant: Long, outline: Long,
) = darkColorScheme(
    primary = Color(primary), onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary), onSecondary = Color(onPrimary),
    secondaryContainer = Color(containers[3]), onSecondaryContainer = Color(onPrimaryContainer),
    tertiary = Color(tertiary), onTertiary = Color(onPrimary),
    background = Color(background), onBackground = Color(onSurface),
    surface = Color(surface), onSurface = Color(onSurface),
    surfaceVariant = Color(containers[2]), onSurfaceVariant = Color(onSurfaceVariant),
    surfaceContainerLowest = Color(background),
    surfaceContainerLow = Color(containers[0]), surfaceContainer = Color(containers[1]),
    surfaceContainerHigh = Color(containers[2]), surfaceContainerHighest = Color(containers[3]),
    outline = Color(outline), outlineVariant = Color(containers[3]),
)

/** Deep space violet with pink and cyan starlight. */
private val Nebula = night(0xFFB69CFF, 0xFF2A0F66, 0xFF4B2FA0, 0xFFE8DDFF, 0xFFFF8AD8, 0xFF7FE3FF,
    0xFF0E0B1F, 0xFF120E26, listOf(0xFF17122E, 0xFF1C1736, 0xFF241D45, 0xFF2D2554),
    onSurface = 0xFFEAE4FF, onSurfaceVariant = 0xFFC3B9E6, outline = 0xFF8578B3)

/** Royal purple night with orchid glow and golden stars. */
private val PurpleHaze = night(0xFFDDA0FF, 0xFF45106A, 0xFF64289A, 0xFFF5DDFF, 0xFFFFCB6B, 0xFFFF9CC2,
    0xFF12081C, 0xFF170C24, listOf(0xFF1E112E, 0xFF241537, 0xFF2D1B45, 0xFF372255),
    onSurface = 0xFFF1E3FA, onSurfaceVariant = 0xFFCFB8DF, outline = 0xFF8F74A6)

/** Rose-tinted galaxy with lavender and ice-blue stars. */
private val PinkGalaxy = night(0xFFFF8FC8, 0xFF5E0A3A, 0xFF8A1F5C, 0xFFFFD8EA, 0xFFC3A6FF, 0xFF8FE6FF,
    0xFF180A14, 0xFF1E0D1A, listOf(0xFF261222, 0xFF2D1629, 0xFF381C33, 0xFF44233F),
    onSurface = 0xFFFBE4EF, onSurfaceVariant = 0xFFE0BCD0, outline = 0xFFA77791)

/** Dark woods with glowing emerald and fairy gold. */
private val Enchanted = night(0xFF7EE0A8, 0xFF00391F, 0xFF0F5233, 0xFFA0F7C3, 0xFFE6C76A, 0xFFC9A8FF,
    0xFF08130E, 0xFF0C1913, listOf(0xFF10201A, 0xFF152A21, 0xFF1B3329, 0xFF223D31),
    onSurface = 0xFFDDEFE3, onSurfaceVariant = 0xFFB4CCBD, outline = 0xFF6F8F7C)

/** Midnight sea under a silver moon, with aqua glow. */
private val Moonlit = night(0xFF8FE3E0, 0xFF003735, 0xFF0E4F52, 0xFFB5FFFB, 0xFFC7D3E8, 0xFF9C9CFF,
    0xFF060D17, 0xFF0A1420, listOf(0xFF0F1A29, 0xFF132033, 0xFF19293F, 0xFF20324B),
    onSurface = 0xFFDCE6F2, onSurfaceVariant = 0xFFAFC0D4, outline = 0xFF6E849E)

/** The bottom of the sea: electric blue with bioluminescent green. */
private val DeepOcean = night(0xFF5FD4FF, 0xFF00344D, 0xFF004C70, 0xFFC4ECFF, 0xFF6FF2C4, 0xFFA9B4FF,
    0xFF03101A, 0xFF061622, listOf(0xFF0A1D2C, 0xFF0D2435, 0xFF122D42, 0xFF183750),
    onSurface = 0xFFD8EEF8, onSurfaceVariant = 0xFFA8C6D6, outline = 0xFF64899C)

/** Smouldering coals with flame orange and spark gold. */
private val Ember = night(0xFFFF9466, 0xFF5A1A00, 0xFF80300F, 0xFFFFDBCC, 0xFFFFD36E, 0xFFFF7A9A,
    0xFF160907, 0xFF1C0D0A, listOf(0xFF26120E, 0xFF2E1712, 0xFF391D17, 0xFF45241D),
    onSurface = 0xFFF8E4DC, onSurfaceVariant = 0xFFDDBCAF, outline = 0xFFA47B6C)

/** True black (saves battery on OLED screens) with pale starlight accents. */
private val Void = night(0xFFC9D1FF, 0xFF1B2250, 0xFF2E3566, 0xFFE3E7FF, 0xFF9AA8D6, 0xFFE0C3FF,
    0xFF000000, 0xFF000000, listOf(0xFF0B0B12, 0xFF111119, 0xFF181823, 0xFF20202E),
    onSurface = 0xFFE9EAF2, onSurfaceVariant = 0xFFB5B8CC, outline = 0xFF6F7290)

fun colorSchemeFor(choice: ThemeChoice): ColorScheme = when (choice) {
    ThemeChoice.NEBULA -> Nebula
    ThemeChoice.PURPLE -> PurpleHaze
    ThemeChoice.PINK -> PinkGalaxy
    ThemeChoice.ENCHANTED -> Enchanted
    ThemeChoice.MOONLIT -> Moonlit
    ThemeChoice.OCEAN -> DeepOcean
    ThemeChoice.CLASSIC -> Ember
    ThemeChoice.DARK -> Void
}

/** Colors for a theme's Settings swatch: background, main accent, second accent. */
fun swatchColors(choice: ThemeChoice): Triple<Color, Color, Color> =
    colorSchemeFor(choice).let { Triple(it.background, it.primary, it.secondary) }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppTheme(choice: ThemeChoice = ThemeChoice.NEBULA, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        // All themes are dark, so the status and navigation bar icons are always light.
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }
    MaterialTheme(colorScheme = colorSchemeFor(choice), content = content)
}
