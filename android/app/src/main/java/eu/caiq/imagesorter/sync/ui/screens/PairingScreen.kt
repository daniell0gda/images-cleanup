package eu.caiq.imagesorter.sync.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.caiq.imagesorter.sync.pairing.PairingState
import eu.caiq.imagesorter.sync.ui.components.AppearOnEntry
import eu.caiq.imagesorter.sync.ui.components.Eyebrow
import eu.caiq.imagesorter.sync.ui.theme.MonoLabel
import eu.caiq.imagesorter.sync.ui.theme.VaultTheme

/**
 * Step 1 — pair. Shows the 6-digit code while the launcher operator approves this
 * phone. The code reveals digit-by-digit; a soft amber pulse signals "waiting".
 * State transitions (pending → trusted / revoked) cross-fade.
 */
@Composable
fun PairingScreen(
    state: PairingState,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.ground)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WaitingChip()
        Spacer(Modifier.height(22.dp))
        Eyebrow("Step 1 — pair")
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Approve this phone\non your server",
            style = MaterialTheme.typography.headlineLarge,
            color = c.text,
            textAlign = TextAlign.Center,
            fontSize = 27.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Open Trusted devices on the launcher and approve this phone after matching the code.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
            label = "pairingState",
        ) { s ->
            when (s) {
                is PairingState.Pending -> PendingBlock(s.pairingCode)
                PairingState.Trusted -> ResultBlock(c.accent, Icons.Rounded.CheckCircle, "Paired", c.text)
                PairingState.Revoked -> ResultBlock(
                    c.coral, Icons.Rounded.CheckCircle,
                    "This device was revoked.\nRe-pair to continue.", c.text,
                )
            }
        }
    }
}

@Composable
private fun PendingBlock(code: String) {
    val c = VaultTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val digits = code.ifBlank { "------" }.take(6).padEnd(6, '-').toCharArray()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            digits.forEachIndexed { i, ch ->
                AppearOnEntry(delayMs = 60 * i, key = code) {
                    CodeCell(ch)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = c.accent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(10.dp))
            Text("Waiting for approval…", style = MonoLabel.copy(fontSize = 13.sp), color = c.muted)
        }
    }
}

@Composable
private fun CodeCell(ch: Char) {
    val c = VaultTheme.colors
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ch.toString(),
            style = MonoLabel.copy(fontSize = 30.sp, fontWeight = FontWeight.W600),
            color = if (ch == '-') c.muted else c.text,
        )
    }
}

@Composable
private fun ResultBlock(
    tint: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WaitingChip() {
    val c = VaultTheme.colors
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseDot",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .scale(pulse)
                .size(7.dp)
                .clip(CircleShape)
                .background(c.amber),
        )
        Spacer(Modifier.size(8.dp))
        Text("This phone", style = MonoLabel.copy(fontSize = 12.sp), color = c.muted)
    }
}
