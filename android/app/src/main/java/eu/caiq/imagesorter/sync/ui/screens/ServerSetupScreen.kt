package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.caiq.imagesorter.sync.ui.components.Eyebrow
import eu.caiq.imagesorter.sync.ui.components.clickableScale
import eu.caiq.imagesorter.sync.ui.theme.MonoLabel
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme

/**
 * One-time setup — point this phone at the launcher. The user enters the
 * launcher's IP/host and port; Connect probes reachability before advancing to
 * pairing. An [error] string (from a failed probe / invalid input) renders below
 * the fields in coral. While [connecting] is true the action is disabled and shows
 * a "Connecting…" label so a fast double-tap cannot fire a second probe.
 */
@Composable
fun ServerSetupScreen(
    error: String?,
    onConnect: (host: String, port: String) -> Unit,
    connecting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7000") }
    val canConnect = host.isNotBlank() && port.isNotBlank() && !connecting

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.ground)
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Eyebrow("Step 1 — connect")
        Spacer(Modifier.height(10.dp))
        Text(
            "Enter your server's address",
            style = MaterialTheme.typography.headlineLarge,
            color = c.text,
            fontSize = 27.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "This is the launcher's IP address and port on your network.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Default port 7000",
            style = MonoLabel.copy(fontSize = 12.sp),
            color = c.muted,
        )
        Spacer(Modifier.height(22.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium, color = c.coral)
        }

        Spacer(Modifier.height(22.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .then(
                    if (canConnect) Modifier.clickableScale { onConnect(host, port) }
                    else Modifier,
                )
                .background(if (canConnect) c.accent else c.line)
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (connecting) "Connecting…" else "Connect",
                style = MaterialTheme.typography.labelLarge,
                color = if (canConnect) c.onAccent else c.muted,
            )
        }
    }
}
