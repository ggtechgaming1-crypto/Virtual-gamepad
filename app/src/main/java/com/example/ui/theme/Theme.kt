package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PadForgeDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = PrecisionAmber,
    onSecondary = ObsidianVoid,
    secondaryContainer = PrecisionAmberContainer,
    onSecondaryContainer = PrecisionAmber,
    tertiary = TelemetryCobalt,
    onTertiary = ObsidianVoid,
    tertiaryContainer = TelemetryCobaltContainer,
    onTertiaryContainer = TelemetryCobalt,
    background = ObsidianVoid,
    onBackground = InkPrimary,
    surface = ObsidianSurface,
    onSurface = InkPrimary,
    surfaceVariant = ObsidianElevated,
    onSurfaceVariant = InkSecondary,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineSubtle,
    error = AlertCrimson,
    onError = ObsidianVoid,
    errorContainer = AlertCrimsonContainer,
    onErrorContainer = AlertCrimson
)

private val PadForgeShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PadForgeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PadForgeDarkColorScheme,
        typography = Typography,
        shapes = PadForgeShapes,
        content = content
    )
}
