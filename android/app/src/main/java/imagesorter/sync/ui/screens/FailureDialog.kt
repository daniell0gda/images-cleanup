package imagesorter.sync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import imagesorter.sync.domain.model.FailureReason
import imagesorter.sync.ui.components.Eyebrow
import imagesorter.sync.ui.theme.MonoLabel
import imagesorter.sync.ui.theme.VaultTheme

/** Test tag on the modal's scrollable list of failures. */
const val FAILURE_LIST_TAG = "failureList"

/**
 * One failed item as shown in the failures modal. Carries just enough to explain
 * *which* file failed and *why* — the raw server detail isn't surfaced because the
 * [reason] taxonomy already maps to plain-language copy via [failureCopy].
 */
data class FailureDetail(
    val name: String,
    val reason: FailureReason,
    val retryable: Boolean,
)

/**
 * Modal that answers "which files didn't back up, and why". Opened from the header
 * failed count. Each row names the file, explains the failure in plain language,
 * and says whether the app will retry it on its own or whether it needs attention.
 */
@Composable
fun FailureDialog(
    failures: List<FailureDetail>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VaultTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(18.dp))
                .padding(20.dp),
        ) {
            Eyebrow("Didn't back up")
            Spacer(Modifier.height(8.dp))
            Text(
                text = summaryLine(failures.size),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp).testTag(FAILURE_LIST_TAG),
            ) {
                items(failures, key = { it.name + it.reason.name }) { item ->
                    FailureItemRow(item)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun FailureItemRow(item: FailureDetail) {
    val c = VaultTheme.colors
    val copy = failureCopy(item.reason)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = item.name,
            style = MonoLabel.copy(fontSize = 13.sp, fontWeight = FontWeight.W600),
            color = c.text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = copy.title,
            style = MonoLabel.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
            color = c.coral,
        )
        Spacer(Modifier.height(3.dp))
        Text(text = copy.detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
        Spacer(Modifier.height(8.dp))
        RetryChip(item.retryable)
    }
}

@Composable
private fun RetryChip(retryable: Boolean) {
    val c = VaultTheme.colors
    val tint = if (retryable) c.accent else c.coral
    val label = if (retryable) "Will retry automatically" else "Needs attention"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, tint, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(999.dp)).background(tint))
        Spacer(Modifier.size(6.dp))
        Text(label, style = MonoLabel.copy(fontSize = 11.sp), color = tint)
    }
}

private data class FailureCopy(val title: String, val detail: String)

/** Plain-language title + explanation for each failure reason. */
private fun failureCopy(reason: FailureReason): FailureCopy = when (reason) {
    FailureReason.SIZE_MISMATCH -> FailureCopy(
        "Size mismatch",
        "The copy that reached the server didn't match the file on your phone.",
    )
    FailureReason.PLACEMENT_ERROR -> FailureCopy(
        "Couldn't be filed",
        "The server received it but couldn't move it into its destination folder.",
    )
    FailureReason.INTERNAL_ERROR -> FailureCopy(
        "Server error",
        "Something went wrong on the server while handling this file.",
    )
    FailureReason.UNREADABLE -> FailureCopy(
        "Unreadable file",
        "The server couldn't read this file — it may be corrupt or unsupported.",
    )
    FailureReason.NO_VIDEO_DESTINATION -> FailureCopy(
        "No video folder",
        "The server has no destination configured for videos.",
    )
    FailureReason.UNKNOWN -> FailureCopy(
        "Unknown error",
        "An unrecognized problem stopped this file from backing up.",
    )
}

private fun summaryLine(count: Int): String =
    if (count == 1) "1 item didn't make it to the server." else "$count items didn't make it to the server."
