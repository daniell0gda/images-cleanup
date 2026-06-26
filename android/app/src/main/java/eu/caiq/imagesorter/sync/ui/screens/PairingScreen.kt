package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.caiq.imagesorter.sync.pairing.PairingState

/**
 * PLACEHOLDER pairing screen. Shows the 6-digit code while waiting for the
 * launcher operator to approve this device. State is driven by the caller's
 * [PairingState]; this composable only renders it.
 *
 * TODO(designer): final visual treatment (code styling, illustration, polish).
 */
@Composable
fun PairingScreen(
    state: PairingState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pair this device",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Open Trusted Devices on the launcher and approve this device " +
                "after matching the code below.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )

        when (state) {
            is PairingState.Pending -> {
                Text(
                    text = state.pairingCode.ifBlank { "------" },
                    style = MaterialTheme.typography.displayMedium,
                )
                CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                Text(
                    text = "Waiting for approval…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            PairingState.Trusted -> Text("Paired.", style = MaterialTheme.typography.titleMedium)

            PairingState.Revoked -> Text(
                text = "This device was revoked. Re-pair to continue.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
