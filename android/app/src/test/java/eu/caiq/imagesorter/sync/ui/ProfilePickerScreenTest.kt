package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.data.api.dto.ProfileDto
import eu.caiq.imagesorter.sync.ui.screens.ProfilePickerScreen
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilePickerScreenTest {

    private val profiles = listOf(
        ProfileDto(profileId = "p-home", displayName = "Home photos"),
        ProfileDto(profileId = "p-work", displayName = "Work snaps"),
        ProfileDto(profileId = "p-trip", displayName = "Trips"),
    )

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersEveryProfileDisplayName() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ProfilePickerScreen(profiles = profiles, onProfileChosen = {})
            }
        }
        profiles.forEach { composeRule.onNodeWithText(it.displayName).assertExists() }
    }

    @Test
    fun tappingProfileInvokesCallbackOnceWithThatProfile() {
        val chosen = mutableListOf<ProfileDto>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ProfilePickerScreen(profiles = profiles, onProfileChosen = { chosen.add(it) })
            }
        }
        composeRule.onNodeWithText("Work snaps").performClick()
        assertEquals(listOf(profiles[1]), chosen)
    }
}
