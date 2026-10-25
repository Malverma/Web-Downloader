package com.webdownloader.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.webdownloader.data.prefs.ThemeChoice

private val Accent = Color(0xFFFF6B4A)
private val Navy = Color(0xFF1E2A44)

/** Builds a light scheme from a few key tones; the surface containers get a faint tint of the accent. */
private fun light(
    primary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    secondary: Long, secondaryContainer: Long, tertiary: Long,
    background: Long, containers: List<Long>,
) = lightColorScheme(
    primary = Color(primary), onPrimary = Color.White,
    primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondary = Color(secondary), onSecondary = Color.White,
    secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onPrimaryContainer),
    tertiary = Color(tertiary), onTertiary = Color.White,
    background = Color(background), surface = Color(background),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(containers[0]), surfaceContainer = Color(containers[1]),
    surfaceContainerHigh = Color(containers[2]), surfaceContainerHighest = Color(containers[3]),
    surfaceVariant = Color(containers[3]),
)

/** Builds a dark scheme; [containers] go from lowest to highest elevation. */
private fun dark(
    primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    secondary: Long, tertiary: Long,
    background: Long, surface: Long, containers: List<Long>,
    onSurface: Long = 0xFFE8E4F0, onSurfaceVariant: Long = 0xFFC4BFD0, outline: Long = 0xFF8A8597,
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

private val PurpleLight = light(0xFF6B3FA0, 0xFFEBDDFF, 0xFF250059, 0xFF7A5C99, 0xFFEEDCFF, 0xFF9C4174, 0xFFFEF7FF, listOf(0xFFF8F1FC, 0xFFF2EAF8, 0xFFECE3F3, 0xFFE6DDEE))
private val PurpleDark = dark(0xFFD3BBFF, 0xFF3F1A70, 0xFF573088, 0xFFEBDDFF, 0xFFD6BEF0, 0xFFFFAFD6, 0xFF151218, 0xFF151218, listOf(0xFF1D1A21, 0xFF221E26, 0xFF2C2831, 0xFF37333C))

private val PinkLight = light(0xFFB0306A, 0xFFFFD9E3, 0xFF3E0021, 0xFF8C4A62, 0xFFFFD9E3, 0xFF8F4C34, 0xFFFFF8F8, listOf(0xFFFEF0F3, 0xFFF9E9ED, 0xFFF4E3E7, 0xFFEEDDE1))
private val PinkDark = dark(0xFFFFB0C9, 0xFF650035, 0xFF8E1752, 0xFFFFD9E3, 0xFFE3BDC7, 0xFFF0BC95, 0xFF1A1113, 0xFF1A1113, listOf(0xFF22191B, 0xFF271D1F, 0xFF322729, 0xFF3D3134),
    onSurface = 0xFFF0DEE1, onSurfaceVariant = 0xFFD6C2C6, outline = 0xFF9F8C90)

private val OceanLight = light(0xFF006493, 0xFFCAE6FF, 0xFF001E30, 0xFF50606E, 0xFFD3E5F5, 0xFF65587B, 0xFFF7F9FF, listOf(0xFFF1F4F9, 0xFFEBEEF3, 0xFFE5E8ED, 0xFFDFE3E8))
private val OceanDark = dark(0xFF8DCDFF, 0xFF00344F, 0xFF004B70, 0xFFCAE6FF, 0xFFB7C9D9, 0xFFD0BFE7, 0xFF0F1418, 0xFF0F1418, listOf(0xFF171C20, 0xFF1B2024, 0xFF262A2F, 0xFF31353A),
    onSurface = 0xFFDFE3E8, onSurfaceVariant = 0xFFC1C7CE, outline = 0xFF8B9198)

/** Deep space violet with pink and cyan starlight. */
private val Nebula = dark(0xFFB69CFF, 0xFF2A0F66, 0xFF4B2FA0, 0xFFE8DDFF, 0xFFFF8AD8, 0xFF7FE3FF, 0xFF0E0B1F, 0xFF120E26, listOf(0xFF17122E, 0xFF1C1736, 0xFF241D45, 0xFF2D2554),
    onSurface = 0xFFEAE4FF, onSurfaceVariant = 0xFFC3B9E6, outline = 0xFF8578B3)

/** Dark woods with glowing emerald and fairy gold. */
private val Enchanted = dark(0xFF7EE0A8, 0xFF00391F, 0xFF0F5233, 0xFFA0F7C3, 0xFFE6C76A, 0xFFC9A8FF, 0xFF08130E, 0xFF0C1913, listOf(0xFF10201A, 0xFF152A21, 0xFF1B3329, 0xFF223D31),
    onSurface = 0xFFDDEFE3, onSurfaceVariant = 0xFFB4CCBD, outline = 0xFF6F8F7C)

/** Midnight sea under a silver moon, with aqua glow. */
private val Moonlit = dark(0xFF8FE3E0, 0xFF003735, 0xFF0E4F52, 0xFFB5FFFB, 0xFFC7D3E8, 0xFF9C9CFF, 0xFF060D17, 0xFF0A1420, listOf(0xFF0F1A29, 0xFF132033, 0xFF19293F, 0xFF20324B),
    onSurface = 0xFFDCE6F2, onSurfaceVariant = 0xFFAFC0D4, outline = 0xFF6E849E)

/** True black (saves battery on OLED screens) with neutral grays. */
private val PureDark = dark(0xFFE0E0E0, 0xFF1A1A1A, 0xFF3A3A3A, 0xFFF0F0F0, 0xFFB0B0B0, 0xFFA0A0A0, 0xFF000000, 0xFF000000, listOf(0xFF0D0D0D, 0xFF141414, 0xFF1C1C1C, 0xFF262626),
    onSurface = 0xFFEDEDED, onSurfaceVariant = 0xFFBDBDBD, outline = 0xFF7A7A7A)

/** The scheme for [choice]; themes without a fixed mode follow the system's light/dark setting. */
@Composable
fun colorSchemeFor(choice: ThemeChoice): ColorScheme {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    return when (choice) {
        ThemeChoice.CLASSIC -> when {
            Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> darkColorScheme(primary = Accent, secondary = Color(0xFF8FA4D6))
            else -> lightColorScheme(primary = Navy, secondary = Accent)
        }
        ThemeChoice.PURPLE -> if (dark) PurpleDark else PurpleLight
        ThemeChoice.PINK -> if (dark) PinkDark else PinkLight
        ThemeChoice.OCEAN -> if (dark) OceanDark else OceanLight
        ThemeChoice.NEBULA -> Nebula
        ThemeChoice.ENCHANTED -> Enchanted
        ThemeChoice.MOONLIT -> Moonlit
        ThemeChoice.DARK -> PureDark
    }
}

/** Colors for a theme's Settings swatch: background, main accent, second accent. */
fun swatchColors(choice: ThemeChoice): Triple<Color, Color, Color> = when (choice) {
    ThemeChoice.CLASSIC -> Triple(Color(0xFFF5F6FA), Navy, Accent)
    ThemeChoice.PURPLE -> Triple(PurpleLight.background, PurpleLight.primary, PurpleLight.primaryContainer)
    ThemeChoice.PINK -> Triple(PinkLight.background, PinkLight.primary, PinkLight.primaryContainer)
    ThemeChoice.OCEAN -> Triple(OceanLight.background, OceanLight.primary, OceanLight.primaryContainer)
    ThemeChoice.NEBULA -> Triple(Nebula.background, Nebula.primary, Nebula.secondary)
    ThemeChoice.ENCHANTED -> Triple(Enchanted.background, Enchanted.primary, Enchanted.secondary)
    ThemeChoice.MOONLIT -> Triple(Moonlit.background, Moonlit.primary, Moonlit.secondary)
    ThemeChoice.DARK -> Triple(PureDark.background, PureDark.primary, PureDark.surfaceContainerHighest)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppTheme(choice: ThemeChoice = ThemeChoice.CLASSIC, content: @Composable () -> Unit) {
    val scheme = colorSchemeFor(choice)
    val view = LocalView.current
    if (!view.isInEditMode) {
        val lightBars = scheme.background.luminance() > 0.5f
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
