package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.caiq.imagesorter.sync.data.api.dto.ProfileDto
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme

/** Test tag on the profile-filter trigger control. */
const val PROFILE_FILTER_TAG = "profileFilter"

/** The label of the always-present default option that clears the filter. */
const val ALL_PROFILES_LABEL = "All profiles"

/** One choice in the Photos timeline profile filter; [id] null = the all-profiles default. */
data class ProfileFilterOption(val id: String?, val label: String)

/**
 * Builds the filter choices for the Photos timeline: the "all profiles" default first,
 * then one entry per server sync profile (`GET /api/sync/profiles`), in server order.
 */
fun profileFilterOptions(profiles: List<ProfileDto>): List<ProfileFilterOption> =
    listOf(ProfileFilterOption(null, ALL_PROFILES_LABEL)) +
        profiles.map { ProfileFilterOption(it.profileId, it.displayName) }

/**
 * The Photos timeline profile filter: a compact trigger showing the active choice's
 * label that opens a dropdown of [options]. Picking one reports its id to [onSelect]
 * (null = the all-profiles default). Stateless apart from the menu's open/closed flag.
 */
@Composable
fun ProfileFilter(
    options: List<ProfileFilterOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.id == selected }?.label ?: ALL_PROFILES_LABEL

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.surfaceHigh)
            .clickable { expanded = true }
            .testTag(PROFILE_FILTER_TAG)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(currentLabel, color = c.text)
        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = c.text)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    },
                )
            }
        }
    }
}
