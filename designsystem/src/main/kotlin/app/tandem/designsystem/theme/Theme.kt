// SPDX-License-Identifier: GPL-3.0-or-later
package app.tandem.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Tandem theme. On Android 12 and newer with [dynamicColor], colors follow the wallpaper.
 * Otherwise the seed palette is used.
 */
@Composable
fun TandemTheme(
    dynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            else -> seedScheme(darkTheme)
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = TandemTypography,
        shapes = TandemShapes,
        content = content,
    )
}

private fun seedScheme(dark: Boolean): ColorScheme {
    val c = if (dark) DarkColors else LightColors
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.secondary,
        onSecondary = c.onSecondary,
        secondaryContainer = c.secondaryContainer,
        onSecondaryContainer = c.onSecondaryContainer,
        tertiary = c.tertiary,
        onTertiary = c.onTertiary,
        tertiaryContainer = c.tertiaryContainer,
        onTertiaryContainer = c.onTertiaryContainer,
        error = c.error,
        onError = c.onError,
        errorContainer = c.errorContainer,
        onErrorContainer = c.onErrorContainer,
        background = c.background,
        onBackground = c.onBackground,
        surface = c.surface,
        onSurface = c.onSurface,
        surfaceVariant = c.surfaceVariant,
        onSurfaceVariant = c.onSurfaceVariant,
        outline = c.outline,
        outlineVariant = c.outlineVariant,
        surfaceContainerLowest = c.surfaceContainerLowest,
        surfaceContainerLow = c.surfaceContainerLow,
        surfaceContainer = c.surfaceContainer,
        surfaceContainerHigh = c.surfaceContainerHigh,
        surfaceContainerHighest = c.surfaceContainerHighest,
        surfaceDim = c.surfaceDim,
        surfaceBright = c.surfaceBright,
        inverseSurface = c.inverseSurface,
        inverseOnSurface = c.inverseOnSurface,
        inversePrimary = c.inversePrimary,
    )
}
