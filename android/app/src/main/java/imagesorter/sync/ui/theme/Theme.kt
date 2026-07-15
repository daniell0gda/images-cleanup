package imagesorter.sync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "Petrol vault" design system: a deep teal-petrol ground, a luminous mint accent
 * for *secured* state, amber for in-flight, coral reserved for the one destructive
 * surface. Monospace is the data face (codes, counts, filenames) — the homelab
 * vernacular this app lives in.
 *
 * Material3's [androidx.compose.material3.ColorScheme] has no slots for amber /
 * coral / muted / hairline, so those extra tokens live in [VaultColors], provided
 * via [LocalVaultColors]. Components read Material roles; screens read VaultColors
 * for the bespoke tokens.
 */
@Immutable
data class VaultColors(
    val ground: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentPress: Color,
    val onAccent: Color,
    val amber: Color,
    val coral: Color,
)

private val DarkVault = VaultColors(
    ground = Color(0xFF0E1417),
    surface = Color(0xFF151D21),
    surfaceHigh = Color(0xFF1B262B),
    line = Color(0xFF25333A),
    text = Color(0xFFE8EDEE),
    muted = Color(0xFF8A999F),
    accent = Color(0xFF54E0C0),
    accentPress = Color(0xFF3FC9AB),
    onAccent = Color(0xFF06231D),
    amber = Color(0xFFFFB661),
    coral = Color(0xFFFF7A6B),
)

private val LightVault = VaultColors(
    ground = Color(0xFFEDF2F0),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF3F7F5),
    line = Color(0xFFDCE5E1),
    text = Color(0xFF101A1B),
    muted = Color(0xFF5C6B6E),
    accent = Color(0xFF0E9C80),
    accentPress = Color(0xFF0A7E68),
    onAccent = Color(0xFFFFFFFF),
    amber = Color(0xFFB7780F),
    coral = Color(0xFFD8453A),
)

val LocalVaultColors = staticCompositionLocalOf { DarkVault }

/** Convenience accessor: `VaultTheme.colors.accent`. */
object VaultTheme {
    val colors: VaultColors
        @Composable get() = LocalVaultColors.current
}

private fun darkScheme(v: VaultColors) = darkColorScheme(
    primary = v.accent,
    onPrimary = v.onAccent,
    secondary = v.amber,
    onSecondary = v.onAccent,
    background = v.ground,
    onBackground = v.text,
    surface = v.surface,
    onSurface = v.text,
    surfaceVariant = v.surfaceHigh,
    onSurfaceVariant = v.muted,
    outline = v.line,
    error = v.coral,
    onError = v.onAccent,
)

private fun lightScheme(v: VaultColors) = lightColorScheme(
    primary = v.accent,
    onPrimary = v.onAccent,
    secondary = v.amber,
    onSecondary = v.onAccent,
    background = v.ground,
    onBackground = v.text,
    surface = v.surface,
    onSurface = v.text,
    surfaceVariant = v.surfaceHigh,
    onSurfaceVariant = v.muted,
    outline = v.line,
    error = v.coral,
    onError = v.onAccent,
)

private val sans = FontFamily.SansSerif

/** Tight, confident display + readable body; the mono face is applied inline. */
private val VaultTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontFamily = sans, fontWeight = FontWeight.W800, letterSpacing = (-0.02).sp),
        headlineSmall = headlineSmall.copy(fontFamily = sans, fontWeight = FontWeight.W700, letterSpacing = (-0.01).sp),
        titleMedium = titleMedium.copy(fontFamily = sans, fontWeight = FontWeight.SemiBold),
        bodyMedium = bodyMedium.copy(fontFamily = sans, lineHeight = 21.sp),
        bodySmall = bodySmall.copy(fontFamily = sans),
        labelLarge = labelLarge.copy(fontFamily = sans, fontWeight = FontWeight.SemiBold),
    )
}

/** Monospace style for codes, counts and filenames — the data vernacular. */
val MonoLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.W500,
    letterSpacing = 0.04.sp,
)

@Composable
fun ImageSorterSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val vault = if (darkTheme) DarkVault else LightVault
    val scheme = if (darkTheme) darkScheme(vault) else lightScheme(vault)
    CompositionLocalProvider(LocalVaultColors provides vault) {
        MaterialTheme(
            colorScheme = scheme,
            typography = VaultTypography,
            shapes = VaultShapes,
            content = content,
        )
    }
}
