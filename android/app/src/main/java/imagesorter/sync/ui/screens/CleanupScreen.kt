package imagesorter.sync.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import imagesorter.sync.ui.components.Eyebrow
import imagesorter.sync.ui.theme.MonoLabel
import imagesorter.sync.ui.theme.VaultTheme

/** UI phase of the cleanup flow. */
enum class CleanupPhase { IDLE, VERIFYING, READY_TO_REMOVE }

/**
 * The only destructive surface. Start cleanup → verify against the server →
 * "Remove from phone" (which hands off to the Android system delete dialog). The
 * three phases morph with a slide-and-fade so the flow reads as one continuous act.
 */
@Composable
fun CleanupScreen(
    phase: CleanupPhase,
    verifiedPresentCount: Int,
    onStartCleanup: () -> Unit,
    onRemoveSynced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.ground)
            .padding(horizontal = 22.dp)
            .padding(top = 24.dp, bottom = 24.dp),
    ) {
        Eyebrow("The only place files leave your phone")
        Spacer(Modifier.height(10.dp))
        Text("Free up space", style = MaterialTheme.typography.headlineLarge, color = c.text, fontSize = 27.sp)
        Spacer(Modifier.height(20.dp))

        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 8 })
                    .togetherWith(fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 8 })
            },
            label = "cleanupPhase",
        ) { p ->
            when (p) {
                CleanupPhase.IDLE -> IdlePhase(onStartCleanup)
                CleanupPhase.VERIFYING -> VerifyingPhase()
                CleanupPhase.READY_TO_REMOVE -> ReadyPhase(verifiedPresentCount, onRemoveSynced)
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    val c = VaultTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) { content() }
}

@Composable
private fun IdlePhase(onStart: () -> Unit) {
    val c = VaultTheme.colors
    Column {
        Panel {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Shield, null, tint = c.accent, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.size(12.dp))
                Text(
                    buildString {
                        append("Nothing is removed until your server confirms it still has the file — ")
                        append("and the system asks you one more time before anything is deleted.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
        ) { Text("Start cleanup", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(14.dp))
        Text(
            "Checks your backed-up files against the server",
            style = MonoLabel.copy(fontSize = 12.sp),
            color = c.muted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VerifyingPhase() {
    val c = VaultTheme.colors
    Panel {
        Column {
            Text("Checking the server still has each file…", style = MaterialTheme.typography.bodyMedium, color = c.text)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)),
                color = c.accent,
                trackColor = c.surfaceHigh,
            )
            Spacer(Modifier.height(10.dp))
            Text("Verifying…", style = MonoLabel.copy(fontSize = 13.sp), color = c.muted)
        }
    }
}

@Composable
private fun ReadyPhase(count: Int, onRemove: () -> Unit) {
    val c = VaultTheme.colors
    Column {
        Panel {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("confirmed safe on the server", style = MonoLabel.copy(fontSize = 13.sp), color = c.accent)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "%,d".format(count),
                    style = MonoLabel.copy(fontSize = 40.sp, fontWeight = FontWeight.W700, letterSpacing = (-0.5).sp),
                    color = c.text,
                )
                Spacer(Modifier.height(4.dp))
                Text("files can leave this phone", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRemove,
            enabled = count > 0,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = c.coral,
                contentColor = MaterialTheme.colorScheme.onError,
                disabledContainerColor = c.surfaceHigh,
                disabledContentColor = c.muted,
            ),
        ) { Text(if (count > 0) "Remove %,d files from phone".format(count) else "Nothing to remove", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(14.dp))
        Text(
            "The system will ask you to confirm before anything is deleted",
            style = MonoLabel.copy(fontSize = 12.sp),
            color = c.muted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
