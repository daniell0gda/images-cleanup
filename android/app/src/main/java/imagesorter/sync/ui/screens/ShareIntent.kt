package imagesorter.sync.ui.screens

import android.content.Context
import android.content.Intent

/**
 * Open the Android system share sheet for a link. Shared by the album share menu
 * and the Photos-timeline "Share now" selection action so both hand off a link to
 * other apps identically. The URL is server-built and passed verbatim (§3.11).
 */
fun shareLinkViaChooser(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(send, null))
}
