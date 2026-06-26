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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import eu.caiq.imagesorter.sync.ServiceLocator
import eu.caiq.imagesorter.sync.SyncApp
import eu.caiq.imagesorter.sync.ui.screens.CleanupScreen
import eu.caiq.imagesorter.sync.ui.screens.MainStatusScreen
import eu.caiq.imagesorter.sync.ui.screens.PairingScreen
import eu.caiq.imagesorter.sync.ui.screens.ProfilePickerScreen
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

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()

    // Request media + notification permissions on entry. The result is not
    // gated on here (placeholder); production should block sync until granted.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* TODO(designer/impl): reflect grant state in the UI. */ }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermissions())
        if (screen == AppScreen.PAIRING) viewModel.startPairing()
    }

    // System delete dialog launcher for the cleanup flow.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { viewModel.onDeleteCompleted() }

    when (screen) {
        AppScreen.PAIRING -> {
            val state by viewModel.pairingState.collectAsStateWithLifecycle()
            PairingScreen(state = state)
        }

        AppScreen.PROFILE_PICKER -> {
            val profiles by viewModel.profiles.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { if (profiles.isEmpty()) viewModel.loadProfiles() }
            ProfilePickerScreen(profiles = profiles, onProfileChosen = viewModel::chooseProfile)
        }

        AppScreen.MAIN -> {
            val rows by viewModel.rows.collectAsStateWithLifecycle()
            val filter by viewModel.filter.collectAsStateWithLifecycle()
            MainStatusScreen(
                rows = rows,
                selectedFilter = filter,
                onFilterChange = viewModel::setFilter,
                onSyncNow = viewModel::syncNow,
                onCleanup = viewModel::openCleanup,
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
                    launchDelete(viewModel, deletableIds) { request ->
                        deleteLauncher.launch(request)
                    }
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
    mediaIds: List<Long>,
    launch: (IntentSenderRequest) -> Unit,
) {
    val sender: IntentSender = viewModel.buildDeleteRequest(mediaIds) ?: return
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
