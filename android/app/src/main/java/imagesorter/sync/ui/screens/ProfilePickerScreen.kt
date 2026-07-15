package imagesorter.sync.ui.screens

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import imagesorter.sync.data.api.dto.ProfileDto
import imagesorter.sync.ui.components.AppearOnEntry
import imagesorter.sync.ui.components.Eyebrow
import imagesorter.sync.ui.components.clickableScale
import imagesorter.sync.ui.theme.MonoLabel
import imagesorter.sync.ui.theme.VaultTheme

/** Test tag on the collapsed "+ Create profile" card / empty-state CTA. */
const val CREATE_PROFILE_CTA_TAG = "createProfileCta"

/** Test tag on the inline profile-name text field revealed after tapping the CTA. */
const val PROFILE_NAME_FIELD_TAG = "profileNameField"

/** Test tag on the confirm button that submits the entered profile name. */
const val CREATE_PROFILE_CONFIRM_TAG = "createProfileConfirm"

/**
 * Step 2 — shown once after pairing. Lists the server's DB profiles and offers a
 * "+ Create profile" affordance (also the empty-state CTA) whose inline field
 * creates a new profile. The choice is stored and never asked again. Cards rise in
 * with a stagger and spring on press.
 */
@Composable
fun ProfilePickerScreen(
    profiles: List<ProfileDto>,
    onProfileChosen: (ProfileDto) -> Unit,
    modifier: Modifier = Modifier,
    onCreateProfile: (String) -> Unit = {},
    createError: String? = null,
    noticeMessage: String? = null,
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
        if (noticeMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(noticeMessage, style = MaterialTheme.typography.bodyMedium, color = c.amber)
        }
        Spacer(Modifier.height(22.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            itemsIndexed(profiles, key = { _, p -> p.profileId }) { index, profile ->
                AppearOnEntry(delayMs = 70 * index, key = profile.profileId) {
                    ProfileCard(profile, onClick = { onProfileChosen(profile) })
                }
            }
        }
        // The create affordance lives outside the scrolling list so it is always
        // present — as the persistent "+ Create profile" card and the empty-state CTA.
        Spacer(Modifier.height(12.dp))
        CreateProfileCard(createError = createError, onCreate = onCreateProfile)
    }
}

/**
 * Persistent create affordance: a collapsed "+ Create profile" card that also acts
 * as the empty-state CTA. Tapping it reveals an inline name field + Create button;
 * a rejected name is surfaced via [createError] while staying on the picker.
 */
@Composable
private fun CreateProfileCard(createError: String?, onCreate: (String) -> Unit) {
    val c = VaultTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        if (!expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickableScale(onClick = { expanded = true })
                    .testTag(CREATE_PROFILE_CTA_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = c.accent)
                Spacer(Modifier.size(10.dp))
                Text("Create profile", style = MaterialTheme.typography.titleMedium, color = c.text, fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth().testTag(PROFILE_NAME_FIELD_TAG),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onCreate(name) },
                modifier = Modifier.fillMaxWidth().testTag(CREATE_PROFILE_CONFIRM_TAG),
            ) { Text("Create") }
        }
        if (createError != null) {
            Spacer(Modifier.height(10.dp))
            Text(createError, style = MonoLabel.copy(fontSize = 12.sp), color = c.coral)
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
