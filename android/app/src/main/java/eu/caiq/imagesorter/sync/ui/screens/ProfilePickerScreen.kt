package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.caiq.imagesorter.sync.data.api.dto.ProfileDto

/**
 * PLACEHOLDER profile picker. Shown once after pairing; the choice is stored and
 * never asked again. Lists the GroupByTags profiles the server offers.
 *
 * TODO(designer): final list/selection design.
 */
@Composable
fun ProfilePickerScreen(
    profiles: List<ProfileDto>,
    onProfileChosen: (ProfileDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Choose a sync profile",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LazyColumn {
            items(profiles, key = { it.profileId }) { profile ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { onProfileChosen(profile) },
                ) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
