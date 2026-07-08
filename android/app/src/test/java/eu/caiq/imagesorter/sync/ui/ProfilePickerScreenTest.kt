package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import eu.caiq.imagesorter.sync.data.api.dto.ProfileDto
import eu.caiq.imagesorter.sync.ui.screens.CREATE_PROFILE_CONFIRM_TAG
import eu.caiq.imagesorter.sync.ui.screens.CREATE_PROFILE_CTA_TAG
import eu.caiq.imagesorter.sync.ui.screens.PROFILE_NAME_FIELD_TAG
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

    @Test
    fun createAffordanceRevealsInlineFieldAndCreateInvokesCallbackWithTypedName() {
        val created = mutableListOf<String>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ProfilePickerScreen(profiles = profiles, onProfileChosen = {}, onCreateProfile = { created.add(it) })
            }
        }
        // The field is hidden until the CTA is tapped.
        composeRule.onNodeWithTag(PROFILE_NAME_FIELD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CREATE_PROFILE_CTA_TAG).performClick()
        composeRule.onNodeWithTag(PROFILE_NAME_FIELD_TAG).performTextInput("Beach")
        composeRule.onNodeWithTag(CREATE_PROFILE_CONFIRM_TAG).performClick()
        assertEquals(listOf("Beach"), created)
    }

    @Test
    fun createCtaIsPresentAsTheEmptyStateWhenNoProfiles() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ProfilePickerScreen(profiles = emptyList(), onProfileChosen = {})
            }
        }
        composeRule.onNodeWithTag(CREATE_PROFILE_CTA_TAG).assertExists()
    }

    @Test
    fun creationErrorMessageIsSurfacedToTheUser() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ProfilePickerScreen(
                    profiles = profiles,
                    onProfileChosen = {},
                    createError = "A profile with that name already exists — pick a different one.",
                )
            }
        }
        composeRule.onNodeWithText("A profile with that name already exists — pick a different one.").assertExists()
    }

    @Test
    fun profileRemovedNoticeIsSurfacedToTheUser() {
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ProfilePickerScreen(
                    profiles = profiles,
                    onProfileChosen = {},
                    noticeMessage = "Your sync profile was removed. Choose another.",
                )
            }
        }
        composeRule.onNodeWithText("Your sync profile was removed. Choose another.").assertExists()
    }
}
