package imagesorter.sync.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import imagesorter.sync.ui.screens.shouldShowSyncSpinner
import imagesorter.sync.ServiceLocator
import imagesorter.sync.SyncApp
import imagesorter.sync.ui.screens.AlbumsScreen
import imagesorter.sync.ui.screens.CleanupScreen
import imagesorter.sync.ui.screens.MainStatusScreen
import imagesorter.sync.ui.screens.PairingScreen
import imagesorter.sync.ui.screens.PhotosScreen
import imagesorter.sync.ui.screens.ProfilePickerScreen
import imagesorter.sync.ui.screens.ServerSetupScreen
import imagesorter.sync.ui.screens.SettingsScreen
import imagesorter.sync.data.prefs.SyncNetworkType
import imagesorter.sync.ui.theme.ImageSorterSyncTheme

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
        handleDeepLink(intent)
        setContent {
            ImageSorterSyncTheme {
                Surface { AppRoot(viewModel) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: while the app is open, a notification tap re-delivers here
        // instead of re-creating the activity. Adopt it as the current intent so a
        // later getIntent() reads the deep link, then route.
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Route a Sync-tab deep-link intent to the ViewModel, then strip the extra so a
     * config-change recreation (which re-reads getIntent()) can't re-navigate — the
     * tab switch fires once per intent.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (homeTabFromIntent(intent) == HomeTab.SYNC) {
            viewModel.goToSyncTab()
            intent?.removeExtra(EXTRA_HOME_TAB)
        }
    }

    companion object {
        /** Intent extra naming the home tab a deep link should open. */
        const val EXTRA_HOME_TAB = "imagesorter.sync.EXTRA_HOME_TAB"

        /** [EXTRA_HOME_TAB] value selecting the Sync tab. */
        const val EXTRA_HOME_TAB_SYNC = "SYNC"

        /** Pure intent → [HomeTab] seam so the deep-link mapping is unit-testable. */
        internal fun homeTabFromIntent(intent: Intent?): HomeTab? =
            if (intent?.getStringExtra(EXTRA_HOME_TAB) == EXTRA_HOME_TAB_SYNC) HomeTab.SYNC else null
    }
}

/**
 * The pairing poll must start on entry only for [AppScreen.PAIRING]; in
 * particular it must not fire while the user is still on [AppScreen.SERVER_SETUP]
 * (no server is configured yet, so pairing cannot succeed).
 */
internal fun shouldStartPairing(screen: AppScreen): Boolean = screen == AppScreen.PAIRING

/** testTag for the bottom-nav Sync-tab active-sync spinner. */
const val SYNC_TAB_SPINNER_TAG = "syncTabSpinner"

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
    settings: @Composable () -> Unit = {},
    syncActive: Boolean = false,
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
                    // While a sync runs, the icon becomes a small spinner so the
                    // activity is visible from the Photos and Albums tabs too.
                    icon = {
                        if (syncActive) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).testTag(SYNC_TAB_SPINNER_TAG),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = null)
                        }
                    },
                    label = { Text("Sync") },
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.SETTINGS,
                    onClick = { onTabSelected(HomeTab.SETTINGS) },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                HomeTab.PHOTOS -> photos()
                HomeTab.ALBUMS -> albums()
                HomeTab.SYNC -> sync()
                HomeTab.SETTINGS -> settings()
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val context = LocalContext.current

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

    // App-open auto-sync: once the app reaches the post-pairing MAIN state, consult
    // the Wi-Fi/throttle gate and start a full sync if eligible — regardless of the
    // active bottom-nav tab (this runs in AppRoot, above the tab switch). The VM's
    // throttle guards against re-firing when the screen re-enters MAIN.
    LaunchedEffect(screen) {
        if (screen == AppScreen.MAIN) {
            viewModel.maybeAutoSyncOnOpen(
                isNetworkAllowedForSync(context, viewModel.syncNetworkType.value),
            )
        }
    }

    // System delete dialog launcher for the cleanup flow.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { viewModel.onDeleteCompleted() }

    // System delete dialog launcher for the Not People "Delete from phone" flow.
    val notPeopleDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onNotPeopleDeleteFinished(result.resultCode == android.app.Activity.RESULT_OK) }

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
            val pendingAlbumId by viewModel.pendingAlbumId.collectAsStateWithLifecycle()
            val pendingTab by viewModel.pendingTab.collectAsStateWithLifecycle()
            val shellSyncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
            // The deep link's tab switch is applied via homeTab already; reset the
            // one-shot marker so it doesn't linger.
            LaunchedEffect(pendingTab) {
                if (pendingTab != null) viewModel.consumePendingTab()
            }
            HomeShell(
                selectedTab = homeTab,
                onTabSelected = viewModel::selectHomeTab,
                syncActive = shouldShowSyncSpinner(shellSyncProgress),
                photos = { PhotosScreen(onGoToAlbum = viewModel::goToAlbum) },
                albums = {
                    AlbumsScreen(
                        openAlbumId = pendingAlbumId,
                        onAlbumConsumed = viewModel::consumePendingAlbum,
                    )
                },
                sync = {
                    SyncTabContent(
                        viewModel = viewModel,
                        screen = screen,
                        onLaunchNotPeopleDelete = { request -> notPeopleDeleteLauncher.launch(request) },
                        onLaunchCleanupDelete = { request -> deleteLauncher.launch(request) },
                    )
                },
                settings = {
                    val networkType by viewModel.syncNetworkType.collectAsStateWithLifecycle()
                    SettingsScreen(
                        selected = networkType,
                        onNetworkTypeSelected = viewModel::setSyncNetworkType,
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
            val createError by viewModel.createProfileError.collectAsStateWithLifecycle()
            val notice by viewModel.profileNotice.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { if (profiles.isEmpty()) viewModel.loadProfiles() }
            ProfilePickerScreen(
                profiles = profiles,
                onProfileChosen = viewModel::chooseProfile,
                onCreateProfile = viewModel::createProfile,
                createError = createError,
                noticeMessage = notice,
            )
        }

        AppScreen.MAIN -> {
            // Refresh the working set on entry so "still to back up" reflects the
            // device before the user taps "Back up now".
            LaunchedEffect(Unit) { viewModel.discoverNow() }
            val rows by viewModel.rows.collectAsStateWithLifecycle()
            val discovering by viewModel.discovering.collectAsStateWithLifecycle()
            val filter by viewModel.filter.collectAsStateWithLifecycle()
            val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
            val totals by viewModel.totals.collectAsStateWithLifecycle()
            val failures by viewModel.failures.collectAsStateWithLifecycle()
            val syncingIds by viewModel.syncingNow.collectAsStateWithLifecycle()

            // Fire the system delete dialog when the Not People grid requests it.
            val notPeopleDeleteIds by viewModel.notPeopleDeleteIds.collectAsStateWithLifecycle()
            LaunchedEffect(notPeopleDeleteIds) {
                if (notPeopleDeleteIds.isNotEmpty()) {
                    launchDelete(viewModel, notPeopleDeleteIds, onLaunchNotPeopleDelete)
                }
            }

            MainStatusScreen(
                rows = rows,
                isDiscovering = discovering,
                selectedFilter = filter,
                onFilterChange = viewModel::setFilter,
                onSyncNow = viewModel::syncNow,
                onCleanup = viewModel::openCleanup,
                syncProgress = syncProgress,
                totals = totals,
                failures = failures,
                onOverride = viewModel::overrideSelected,
                onDelete = viewModel::requestNotPeopleDelete,
                onSyncItem = viewModel::syncItemNow,
                syncingIds = syncingIds,
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

/**
 * Whether the active network satisfies the user's sync-network setting, gating
 * app-open auto-sync. For [SyncNetworkType.WIFI_ONLY] the network must be unmetered
 * (Wi-Fi-like); for [SyncNetworkType.ANY] any internet-capable network qualifies.
 * Absent connectivity or capabilities read as ineligible (do not sync).
 */
private fun isNetworkAllowedForSync(context: Context, networkType: SyncNetworkType): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = connectivity.activeNetwork ?: return false
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return when (networkType) {
        SyncNetworkType.WIFI_ONLY -> capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        SyncNetworkType.ANY -> capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
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
