package com.limelight.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager

private val LightColors = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = md_theme_light_outline,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
)

private val DarkColors = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
)

// Red Themes
private val LightRedColors = lightColorScheme(
    primary = md_theme_light_primary_red,
    onPrimary = md_theme_light_onPrimary_red,
    primaryContainer = md_theme_light_primaryContainer_red,
    onPrimaryContainer = md_theme_light_onPrimaryContainer_red,
    secondary = md_theme_light_secondary_red,
    onSecondary = md_theme_light_onSecondary_red,
    secondaryContainer = md_theme_light_secondaryContainer_red,
    onSecondaryContainer = md_theme_light_onSecondaryContainer_red,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = md_theme_light_outline,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
)

private val DarkRedColors = darkColorScheme(
    primary = md_theme_dark_primary_red,
    onPrimary = md_theme_dark_onPrimary_red,
    primaryContainer = md_theme_dark_primaryContainer_red,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer_red,
    secondary = md_theme_dark_secondary_red,
    onSecondary = md_theme_dark_onSecondary_red,
    secondaryContainer = md_theme_dark_secondaryContainer_red,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer_red,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
)

// Green Themes
private val LightGreenColors = lightColorScheme(
    primary = md_theme_light_primary_green,
    onPrimary = md_theme_light_onPrimary_green,
    primaryContainer = md_theme_light_primaryContainer_green,
    onPrimaryContainer = md_theme_light_onPrimaryContainer_green,
    secondary = md_theme_light_secondary_green,
    onSecondary = md_theme_light_onSecondary_green,
    secondaryContainer = md_theme_light_secondaryContainer_green,
    onSecondaryContainer = md_theme_light_onSecondaryContainer_green,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = md_theme_light_outline,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
)

private val DarkGreenColors = darkColorScheme(
    primary = md_theme_dark_primary_green,
    onPrimary = md_theme_dark_onPrimary_green,
    primaryContainer = md_theme_dark_primaryContainer_green,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer_green,
    secondary = md_theme_dark_secondary_green,
    onSecondary = md_theme_dark_onSecondary_green,
    secondaryContainer = md_theme_dark_secondaryContainer_green,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer_green,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
)

// Blue Themes
private val LightBlueColors = lightColorScheme(
    primary = md_theme_light_primary_blue,
    onPrimary = md_theme_light_onPrimary_blue,
    primaryContainer = md_theme_light_primaryContainer_blue,
    onPrimaryContainer = md_theme_light_onPrimaryContainer_blue,
    secondary = md_theme_light_secondary_blue,
    onSecondary = md_theme_light_onSecondary_blue,
    secondaryContainer = md_theme_light_secondaryContainer_blue,
    onSecondaryContainer = md_theme_light_onSecondaryContainer_blue,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = md_theme_light_outline,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
)

private val DarkBlueColors = darkColorScheme(
    primary = md_theme_dark_primary_blue,
    onPrimary = md_theme_dark_onPrimary_blue,
    primaryContainer = md_theme_dark_primaryContainer_blue,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer_blue,
    secondary = md_theme_dark_secondary_blue,
    onSecondary = md_theme_dark_onSecondary_blue,
    secondaryContainer = md_theme_dark_secondaryContainer_blue,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer_blue,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
)

// Purple Themes
private val LightPurpleColors = lightColorScheme(
    primary = md_theme_light_primary_purple,
    onPrimary = md_theme_light_onPrimary_purple,
    primaryContainer = md_theme_light_primaryContainer_purple,
    onPrimaryContainer = md_theme_light_onPrimaryContainer_purple,
    secondary = md_theme_light_secondary_purple,
    onSecondary = md_theme_light_onSecondary_purple,
    secondaryContainer = md_theme_light_secondaryContainer_purple,
    onSecondaryContainer = md_theme_light_onSecondaryContainer_purple,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = md_theme_light_outline,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
)

private val DarkPurpleColors = darkColorScheme(
    primary = md_theme_dark_primary_purple,
    onPrimary = md_theme_dark_onPrimary_purple,
    primaryContainer = md_theme_dark_primaryContainer_purple,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer_purple,
    secondary = md_theme_dark_secondary_purple,
    onSecondary = md_theme_dark_onSecondary_purple,
    secondaryContainer = md_theme_dark_secondaryContainer_purple,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer_purple,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
)

@Composable
fun MassFusionTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Legacy parameter, kept for compatibility if passed
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
    val themeChoice = sharedPrefs.getString("list_app_theme", "system_dynamic")

    val colors = when (themeChoice) {
        "system_dynamic" -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (useDarkTheme) DarkColors else LightColors
            }
        }
        "crimson_red" -> if (useDarkTheme) DarkRedColors else LightRedColors
        "forest_green" -> if (useDarkTheme) DarkGreenColors else LightGreenColors
        "ocean_blue" -> if (useDarkTheme) DarkBlueColors else LightBlueColors
        "royal_purple" -> if (useDarkTheme) DarkPurpleColors else LightPurpleColors
        else -> if (useDarkTheme) DarkColors else LightColors // "expressive_default" or fallback
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
