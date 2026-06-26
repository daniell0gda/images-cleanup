package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.caiq.imagesorter.sync.data.api.dto.ProfileDto
import eu.caiq.imagesorter.sync.ui.components.AppearOnEntry
import eu.caiq.imagesorter.sync.ui.components.Eyebrow
import eu.caiq.imagesorter.sync.ui.components.clickableScale
import eu.caiq.imagesorter.sync.ui.theme.MonoLabel
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme

/**
 * Step 2 — shown once after pairing. Lists the server's GroupByTags profiles; the
 * choice is stored and never asked again. Cards rise in with a stagger and spring
 * on press.
 */
@Composable
fun ProfilePickerScreen(
    profiles: List<ProfileDto>,
    onProfileChosen: (ProfileDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.ground)
            .padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Eyebrow("Step 2 — once only")
        Spacer(Modifier.height(10.dp))
        Text("Pick a sync profile", style = MaterialTheme.typography.headlineLarge, color = c.text, fontSize = 27.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "Sorting rules live on your server. Choose where this phone's photos land — you won't be asked again.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
        )
        Spacer(Modifier.height(22.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(profiles, key = { _, p -> p.profileId }) { index, profile ->
                AppearOnEntry(delayMs = 70 * index, key = profile.profileId) {
                    ProfileCard(profile, onClick = { onProfileChosen(profile) })
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: ProfileDto, onClick: () -> Unit) {
    val c = VaultTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickableScale(onClick = onClick)
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(c.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName, style = MaterialTheme.typography.titleMedium, color = c.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(profile.profileId, style = MonoLabel.copy(fontSize = 12.sp), color = c.muted)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.muted)
    }
}
