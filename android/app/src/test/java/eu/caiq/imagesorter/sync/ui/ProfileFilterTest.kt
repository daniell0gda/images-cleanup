package eu.caiq.imagesorter.sync.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.caiq.imagesorter.sync.data.api.dto.ProfileDto
import eu.caiq.imagesorter.sync.ui.screens.PROFILE_FILTER_TAG
import eu.caiq.imagesorter.sync.ui.screens.ProfileFilter
import eu.caiq.imagesorter.sync.ui.screens.profileFilterOptions
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
class ProfileFilterTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val profiles = listOf(
        ProfileDto(profileId = "p1", displayName = "Vacation"),
        ProfileDto(profileId = "p2", displayName = "Work"),
    )

    @Test
    fun optionsLeadWithAllProfilesDefaultThenOneEntryPerServerProfile() {
        val options = profileFilterOptions(profiles)

        assertEquals(listOf<String?>(null, "p1", "p2"), options.map { it.id })
        assertEquals("Taken by", options.first().label)
        assertEquals(listOf("Taken by", "Vacation", "Work"), options.map { it.label })
    }

    @Test
    fun selectingAProfileReportsItsId() {
        val picked = mutableListOf<String?>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ProfileFilter(
                    options = profileFilterOptions(profiles),
                    selected = null,
                    onSelect = { picked.add(it) },
                )
            }
        }

        composeRule.onNodeWithTag(PROFILE_FILTER_TAG).performClick()
        composeRule.onNodeWithText("Vacation").performClick()

        assertEquals(listOf<String?>("p1"), picked)
    }

    @Test
    fun clearingBackToAllProfilesReportsNull() {
        val picked = mutableListOf<String?>()
        composeRule.setContent {
            ImageSorterSyncTheme(darkTheme = false) {
                ProfileFilter(
                    options = profileFilterOptions(profiles),
                    selected = "p1",
                    onSelect = { picked.add(it) },
                )
            }
        }

        composeRule.onNodeWithTag(PROFILE_FILTER_TAG).performClick()
        composeRule.onNodeWithText("Taken by").performClick()

        assertEquals(listOf<String?>(null), picked)
    }
}
