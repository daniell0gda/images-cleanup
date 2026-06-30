package eu.caiq.imagesorter.sync.ui

import android.Manifest
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.caiq.imagesorter.sync.ServiceLocator
import eu.caiq.imagesorter.sync.SyncApp
import eu.caiq.imagesorter.sync.ui.screens.AlbumsScreen
import eu.caiq.imagesorter.sync.ui.screens.CleanupScreen
import eu.caiq.imagesorter.sync.ui.screens.MainStatusScreen
import eu.caiq.imagesorter.sync.ui.screens.PairingScreen
import eu.caiq.imagesorter.sync.ui.screens.PhotosScreen
import eu.caiq.imagesorter.sync.ui.screens.ProfilePickerScreen
import eu.caiq.imagesorter.sync.ui.screens.ServerSetupScreen
import eu.caiq.imagesorter.sync.ui.theme.ImageSorterSyncTheme

/**
 * Single-activity host. Owns Android-side concerns the ViewModel must not: the
 * runtime permission prompt and launching the system delete dialog via an
 * [IntentSender]. Screen routing comes from [MainViewModel].
 *
 * Navigation is a simple when-over-[AppScreen]; there is no Navigation-Compose
 * dependency because the skeleton's flow is linear. The professional designer can
 * introduce richer navigation later.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val locator = (application as SyncApp).serviceLocator
        MainViewModelFactory(locator)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ImageSorterSyncTheme {
                Surface { AppRoot(viewModel) }
            }
        }
    }
}

/**
 * The pairing poll must start on entry only for [AppScreen.PAIRING]; in
 * particular it must not fire while the user is still on [AppScreen.SERVER_SETUP]
 * (no server is configured yet, so pairing cannot succeed).
 */
internal fun shouldStartPairing(screen: AppScreen): Boolean = screen == AppScreen.PAIRING

/**
 * The post-pairing screens render inside the home shell (a [Scaffold] with the
 * Photos | Sync bottom [NavigationBar]); [AppScreen.SERVER_SETUP] and
 * [AppScreen.PAIRING] stay full-screen with no bottom bar.
 */
internal fun showsBottomBar(screen: AppScreen): Boolean = when (screen) {
    AppScreen.SERVER_SETUP, AppScreen.PAIRING -> false
    AppScreen.PROFILE_PICKER, AppScreen.MAIN, AppScreen.CLEANUP -> true
}

/**
 * The post-pairing home shell: a [Scaffold] with a Photos | Sync bottom
 * [NavigationBar]. The selected [HomeTab] picks which slot is rendered in the
 * content area; tapping a destination reports the new tab via [onTabSelected].
 */
@Composable
internal fun HomeShell(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    photos: @Composable () -> Unit,
    albums: @Composable () -> Unit,
    sync: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == HomeTab.PHOTOS,
                    onClick = { onTabSelected(HomeTab.PHOTOS) },
                    icon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                    label = { Text("Photos") },
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.ALBUMS,
                    onClick = { onTabSelected(HomeTab.ALBUMS) },
                    icon = { Icon(Icons.Rounded.Collections, contentDescription = null) },
                    label = { Text("Albums") },
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.SYNC,
                    onClick = { onTabSelected(HomeTab.SYNC) },
                    icon = { Icon(Icons.Rounded.Sync, contentDescription = null) },
                    label = { Text("Sync") },
                )
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                HomeTab.PHOTOS -> photos()
                HomeTab.ALBUMS -> albums()
                HomeTab.SYNC -> sync()
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()

    // Request media + notification permissions on entry. The result is not
    // gated on here (placeholder); production should block sync until granted.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onMediaPermissionResult() }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions())
    }

    // Keyed on screen (not Unit) so pairing also starts when the user *arrives*
    // at the pairing screen by connecting — not only when the app opens there.
    LaunchedEffect(screen) {
        if (shouldStartPairing(screen)) viewModel.startPairing()
    }

    // System delete dialog launcher for the cleanup flow.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { viewModel.onDeleteCompleted() }

    // System delete dialog launcher for the Not People "Delete from phone" flow.
    val notPeopleDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { viewModel.onNotPeopleDeleteCompleted() }

    when (screen) {
        AppScreen.SERVER_SETUP -> {
            val error by viewModel.serverSetupError.collectAsStateWithLifecycle()
            val connecting by viewModel.connecting.collectAsStateWithLifecycle()
            ServerSetupScreen(error = error, onConnect = viewModel::connect, connecting = connecting)
        }

        AppScreen.PAIRING -> {
            val state by viewModel.pairingState.collectAsStateWithLifecycle()
            PairingScreen(state = state)
        }

        // Post-pairing screens live inside the home shell (Photos | Sync). The
        // Sync tab owns the profile/main/cleanup sub-flow; Photos is independent.
        AppScreen.PROFILE_PICKER, AppScreen.MAIN, AppScreen.CLEANUP -> {
            val homeTab by viewModel.homeTab.collectAsStateWithLifecycle()
            HomeShell(
                selectedTab = homeTab,
                onTabSelected = viewModel::selectHomeTab,
                photos = { PhotosScreen() },
                albums = { AlbumsScreen() },
                sync = {
                    SyncTabContent(
                        viewModel = viewModel,
                        screen = screen,
                        onLaunchNotPeopleDelete = { request -> notPeopleDeleteLauncher.launch(request) },
                        onLaunchCleanupDelete = { request -> deleteLauncher.launch(request) },
                    )
                },
            )
        }
    }
}

/**
 * The Sync tab's sub-flow: profile picker until a profile is chosen, then the main
 * status screen, with the cleanup screen as a deeper sub-state. Mirrors the
 * pre-shell routing exactly so the sync experience is unchanged.
 */
@Composable
private fun SyncTabContent(
    viewModel: MainViewModel,
    screen: AppScreen,
    onLaunchNotPeopleDelete: (IntentSenderRequest) -> Unit,
    onLaunchCleanupDelete: (IntentSenderRequest) -> Unit,
) {
    when (screen) {
        // Reachable only via the home-screen branch in AppRoot.
        AppScreen.SERVER_SETUP, AppScreen.PAIRING -> Unit

        AppScreen.PROFILE_PICKER -> {
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { if (profiles.isEmpty()) viewModel.loadProfiles() }
            ProfilePickerScreen(profiles = profiles, onProfileChosen = viewModel::chooseProfile)
        }

        AppScreen.MAIN -> {
            // Refresh the working set on entry so "still to back up" reflects the
            // device before the user taps "Back up now".
            LaunchedEffect(Unit) { viewModel.discoverNow() }
            val rows by viewModel.rows.collectAsStateWithLifecycle()
            val filter by viewModel.filter.collectAsStateWithLifecycle()
            val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
            val totals by viewModel.totals.collectAsStateWithLifecycle()
            val failures by viewModel.failures.collectAsStateWithLifecycle()

            // Fire the system delete dialog when the Not People grid requests it.
            val notPeopleDeleteIds by viewModel.notPeopleDeleteIds.collectAsStateWithLifecycle()
            LaunchedEffect(notPeopleDeleteIds) {
                if (notPeopleDeleteIds.isNotEmpty()) {
                    launchDelete(viewModel, notPeopleDeleteIds, onLaunchNotPeopleDelete)
                }
            }

            MainStatusScreen(
                rows = rows,
                selectedFilter = filter,
                onFilterChange = viewModel::setFilter,
                onSyncNow = viewModel::syncNow,
                onCleanup = viewModel::openCleanup,
                syncProgress = syncProgress,
                totals = totals,
                failures = failures,
                onOverride = viewModel::overrideSelected,
                onDelete = viewModel::requestNotPeopleDelete,
            )
        }

        AppScreen.CLEANUP -> {
            val phase by viewModel.cleanupPhase.collectAsStateWithLifecycle()
            val deletableIds by viewModel.deletableMediaIds.collectAsStateWithLifecycle()
            CleanupScreen(
                phase = phase,
                verifiedPresentCount = deletableIds.size,
                onStartCleanup = viewModel::startCleanup,
                onRemoveSynced = {
                    launchDelete(viewModel, deletableIds, onLaunchCleanupDelete)
                },
            )
        }
    }
}

/**
 * Build the delete IntentSender via CleanupManager and hand it to the launcher;
 * the OS then shows its system delete dialog.
 */
private fun launchDelete(
    viewModel: MainViewModel,
    uris: List<android.net.Uri>,
    launch: (IntentSenderRequest) -> Unit,
) {
    val sender: IntentSender = viewModel.buildDeleteRequest(uris) ?: return
    launch(IntentSenderRequest.Builder(sender).build())
}

private fun requiredPermissions(): Array<String> =
    arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

/** Minimal factory so the ViewModel can take the [ServiceLocator]. */
class MainViewModelFactory(
    private val locator: ServiceLocator,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MainViewModel(locator) as T
}
