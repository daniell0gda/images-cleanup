package eu.caiq.imagesorter.sync.domain.model

import android.net.Uri

/**
 * A single media file discovered on the device via MediaStore.
 *
 * [mediaStoreId] is the local `MediaStore._ID`, used to build the content [uri]
 * for reading bytes and for `MediaStore.createDeleteRequest`. The server never
 * sees this id — only the [identity] and [mimeType] cross the wire.
 */
data class MediaItem(
    val mediaStoreId: Long,
    val uri: Uri,
    val identity: Identity,
    val mimeType: String,
)
