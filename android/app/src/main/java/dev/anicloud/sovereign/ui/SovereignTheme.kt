package dev.anicloud.sovereign.prototype.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.anicloud.sovereign.prototype.Appearance

val Obsidian = Color(0xFF070A12)
val Smoked = Color(0xD90C1422)
val SmokedDeep = Color(0xCC09101C)
val HorizonCyan = Color(0xFF39D5FF)
val CognitionViolet = Color(0xFFA970FF)
val SoftViolet = Color(0xFFC39AFF)
val ResonanceMint = Color(0xFF67E8C2)
val WaitingAmber = Color(0xFFFFCA6B)
val InterventionCoral = Color(0xFFFF6F91)
val PrimaryText = Color(0xFFE7EDF5)
val MutedText = Color(0xFF8492A8)

private val SovereignDarkScheme = darkColorScheme(
    primary = HorizonCyan,
    onPrimary = Obsidian,
    secondary = CognitionViolet,
    onSecondary = Obsidian,
    tertiary = ResonanceMint,
    background = Obsidian,
    onBackground = PrimaryText,
    surface = Smoked,
    onSurface = PrimaryText,
    surfaceVariant = SmokedDeep,
    onSurfaceVariant = MutedText,
    error = InterventionCoral,
    onError = Obsidian,
    outline = Color(0x993A5477),
)

private val SovereignShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val SovereignLightScheme = lightColorScheme(
    primary = Color(0xFF006A92),
    onPrimary = Color.White,
    secondary = Color(0xFF60449A),
    onSecondary = Color.White,
    tertiary = Color(0xFF006C5C),
    background = Color(0xFFEAF6FF),
    onBackground = Color(0xFF0B2239),
    surface = Color(0xFFF8FCFF),
    onSurface = Color(0xFF0B2239),
    surfaceVariant = Color(0xFFDCEEFF),
    onSurfaceVariant = Color(0xFF405A72),
    error = Color(0xFFB3264A),
    onError = Color.White,
    outline = Color(0xFF7895AD),
)

@Composable
fun SovereignTheme(
    appearance: Appearance,
    content: @Composable () -> Unit,
) {
    val useDark = when (appearance) {
        Appearance.Obsidian -> true
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
    }
    MaterialTheme(
        colorScheme = if (useDark) SovereignDarkScheme else SovereignLightScheme,
        shapes = SovereignShapes,
        content = content,
    )
}
