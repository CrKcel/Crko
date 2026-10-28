package com.crko.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.crko.state.DarkModeSetting
import com.crko.state.PaletteSetting
import com.crko.storage.platformSupportsMonet
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/** Palette actually in effect after platform fallbacks. */
enum class EffectivePalette { Monet, Monochrome, Custom }

val LocalEffectivePalette = compositionLocalOf { EffectivePalette.Monet }
val LocalIsDark = compositionLocalOf { false }

val SeedPalette: List<Color> = listOf(
    Color(0xFF3B6CF5),
    Color(0xFF7C4DFF),
    Color(0xFFD81B60),
    Color(0xFFFF7043),
    Color(0xFFFFB300),
    Color(0xFF43A047),
    Color(0xFF00897B),
    Color(0xFF00ACC1),
    Color(0xFF546E7A),
)

/**
 * Resolves the palette setting against platform capabilities: wallpaper-based Monet colors are
 * only available on Android; every other platform uses a seed-generated palette.
 */
fun resolvePalette(setting: PaletteSetting, monetAvailable: Boolean): EffectivePalette = when (setting) {
    PaletteSetting.Monet -> if (monetAvailable) EffectivePalette.Monet else EffectivePalette.Custom
    PaletteSetting.Monochrome -> EffectivePalette.Monochrome
    PaletteSetting.Custom -> EffectivePalette.Custom
}

private fun createThemeController(
    effective: EffectivePalette,
    seed: Color,
    isDark: Boolean,
): ThemeController = when (effective) {
    EffectivePalette.Monet -> ThemeController(ColorSchemeMode.MonetSystem, keyColor = null, isDark = isDark)
    EffectivePalette.Monochrome -> ThemeController(
        colorSchemeMode = ColorSchemeMode.MonetSystem,
        keyColor = Color(0xFF808080),
        paletteStyle = ThemePaletteStyle.Monochrome,
        isDark = isDark,
    )
    EffectivePalette.Custom -> ThemeController(
        colorSchemeMode = ColorSchemeMode.MonetSystem,
        keyColor = seed,
        paletteStyle = ThemePaletteStyle.Fidelity,
        colorSpec = ThemeColorSpec.Spec2021,
        isDark = isDark,
    )
}

@Composable
fun CrkoTheme(
    darkMode: DarkModeSetting,
    palette: PaletteSetting,
    keyColor: Long,
    content: @Composable () -> Unit,
) {
    val isDark = when (darkMode) {
        DarkModeSetting.System -> isSystemInDarkTheme()
        DarkModeSetting.Light -> false
        DarkModeSetting.Dark -> true
    }
    val effective = resolvePalette(palette, platformSupportsMonet())
    val seed = Color(keyColor)
    // The resolved brightness is passed explicitly: a manual override must not depend on the
    // platform configuration, which can lag the user's choice.
    val controller = remember(effective, seed, isDark) { createThemeController(effective, seed, isDark) }
    CompositionLocalProvider(
        LocalEffectivePalette provides effective,
        LocalIsDark provides isDark,
    ) {
        MiuixTheme(controller = controller) {
            ApplySystemBarsAppearance(isDark)
            MaterialColorBridge {
                content()
            }
        }
    }
}

/** Mirrors the miuix palette onto Material 3, used by the Material time pickers. */
@Composable
private fun MaterialColorBridge(content: @Composable () -> Unit) {
    val miuix = MiuixTheme.colorScheme
    val dark = LocalIsDark.current
    val colors = remember(
        dark,
        miuix.primary,
        miuix.primaryContainer,
        miuix.surfaceContainerHigh,
        miuix.surfaceContainerHighest,
        miuix.onSurface,
        miuix.onSurfaceVariantSummary,
    ) {
        if (dark) {
            androidx.compose.material3.darkColorScheme(
                primary = miuix.primary,
                onPrimary = miuix.onPrimary,
                primaryContainer = miuix.primaryContainer,
                onPrimaryContainer = miuix.onPrimaryContainer,
                secondaryContainer = miuix.secondaryContainer,
                onSecondaryContainer = miuix.onSecondaryContainer,
                surface = miuix.surfaceContainerHigh,
                surfaceContainerHigh = miuix.surfaceContainerHighest,
                onSurface = miuix.onSurface,
                onSurfaceVariant = miuix.onSurfaceVariantSummary,
                outline = miuix.outline,
                error = miuix.error,
            )
        } else {
            androidx.compose.material3.lightColorScheme(
                primary = miuix.primary,
                onPrimary = miuix.onPrimary,
                primaryContainer = miuix.primaryContainer,
                onPrimaryContainer = miuix.onPrimaryContainer,
                secondaryContainer = miuix.secondaryContainer,
                onSecondaryContainer = miuix.onSecondaryContainer,
                surface = miuix.surfaceContainerHigh,
                surfaceContainerHigh = miuix.surfaceContainerHighest,
                onSurface = miuix.onSurface,
                onSurfaceVariant = miuix.onSurfaceVariantSummary,
                outline = miuix.outline,
                error = miuix.error,
            )
        }
    }
    androidx.compose.material3.MaterialTheme(colorScheme = colors, content = content)
}
