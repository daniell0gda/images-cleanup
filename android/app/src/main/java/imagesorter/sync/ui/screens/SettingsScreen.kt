package imagesorter.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import imagesorter.sync.data.prefs.SyncNetworkType
import imagesorter.sync.ui.components.Eyebrow
import imagesorter.sync.ui.components.clickableScale
import imagesorter.sync.ui.theme.VaultTheme

/**
 * Settings tab. [selected] is the persisted [SyncNetworkType]; picking an option
 * reports it via [onNetworkTypeSelected], which persists it and re-arms the capture
 * job. [batteryOptimizationIgnored] reflects whether the OS currently exempts the
 * app from battery optimization; when false, a button offers to request the
 * exemption via [onRequestBatteryOptimizationExemption] — OEM battery managers
 * (Samsung in particular) otherwise tend to kill the background capture-sync work.
 */
@Composable
fun SettingsScreen(
    selected: SyncNetworkType,
    onNetworkTypeSelected: (SyncNetworkType) -> Unit,
    batteryOptimizationIgnored: Boolean,
    onRequestBatteryOptimizationExemption: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.ground)
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Eyebrow("Settings")
        Spacer(Modifier.height(10.dp))
        Text(
            "Sync network",
            style = MaterialTheme.typography.headlineLarge,
            color = c.text,
            fontSize = 27.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Choose which connections automatic sync is allowed to use.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
        )
        Spacer(Modifier.height(22.dp))

        NetworkOption(
            title = "Wi-Fi only",
            description = "Back up only on an unmetered connection. Never uses mobile data.",
            isSelected = selected == SyncNetworkType.WIFI_ONLY,
            onClick = { onNetworkTypeSelected(SyncNetworkType.WIFI_ONLY) },
        )
        Spacer(Modifier.height(12.dp))
        NetworkOption(
            title = "Any network",
            description = "Back up on any connection, including mobile data. May use your data plan.",
            isSelected = selected == SyncNetworkType.ANY,
            onClick = { onNetworkTypeSelected(SyncNetworkType.ANY) },
        )

        if (!batteryOptimizationIgnored) {
            Spacer(Modifier.height(28.dp))
            Text(
                "Background reliability",
                style = MaterialTheme.typography.headlineLarge,
                color = c.text,
                fontSize = 27.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Some phones (Samsung in particular) pause background back-up unless " +
                    "the app is exempted from battery optimization.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.muted,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRequestBatteryOptimizationExemption,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text("Disable battery optimization", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun NetworkOption(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val c = VaultTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickableScale(onClick = onClick)
            .background(if (isSelected) c.surfaceHigh else c.surface)
            .border(
                width = 1.dp,
                color = if (isSelected) c.accent else c.line,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
        }
        Spacer(Modifier.width(14.dp))
        RadioDot(isSelected = isSelected)
    }
}

/** A minimal radio indicator: an accent-filled ring when selected, a hollow line ring otherwise. */
@Composable
private fun RadioDot(isSelected: Boolean) {
    val c = VaultTheme.colors
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(width = 2.dp, color = if (isSelected) c.accent else c.line, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            )
        }
    }
}
