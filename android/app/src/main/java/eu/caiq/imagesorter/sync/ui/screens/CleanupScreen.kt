package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** UI phase of the cleanup flow. */
enum class CleanupPhase { IDLE, VERIFYING, READY_TO_REMOVE }

/**
 * PLACEHOLDER cleanup screen — the only destructive surface.
 *
 * Start cleanup → verify progress → "Remove synced files" (which launches the
 * Android system delete dialog via the caller). The remove button is enabled
 * only when at least one verified-present item remains on the phone
 * ([verifiedPresentCount] > 0).
 *
 * TODO(designer): final visuals, per-item review, progress detail.
 */
@Composable
fun CleanupScreen(
    phase: CleanupPhase,
    verifiedPresentCount: Int,
    onStartCleanup: () -> Unit,
    onRemoveSynced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Remove synced files",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Only files the NAS confirms it still has are eligible for " +
                "removal. Nothing is deleted without your confirmation.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
        )

        when (phase) {
            CleanupPhase.IDLE ->
                Button(onClick = onStartCleanup, modifier = Modifier.fillMaxWidth()) {
                    Text("Start cleanup")
                }

            CleanupPhase.VERIFYING -> {
                Text("Verifying files still exist on the NAS…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            CleanupPhase.READY_TO_REMOVE -> {
                Text("$verifiedPresentCount file(s) confirmed on the NAS.")
                Button(
                    onClick = onRemoveSynced,
                    enabled = verifiedPresentCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove synced files")
                }
            }
        }
    }
}
