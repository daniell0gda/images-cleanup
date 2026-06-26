package eu.caiq.imagesorter.sync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * PLACEHOLDER theme. This is intentionally the stock Material3 baseline with no
 * custom palette, typography or shapes.
 *
 * TODO(designer): replace with the professional design system (color tokens,
 *  type scale, shapes, motion). Do not invest in polish here — this exists only
 *  so the functional screens render with sane defaults.
 */
@Composable
fun ImageSorterSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
