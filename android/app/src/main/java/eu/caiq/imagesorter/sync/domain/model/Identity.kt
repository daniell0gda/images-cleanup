package eu.caiq.imagesorter.sync.domain.model

/**
 * The global, server-shared identity of a media file.
 *
 * Identity = [name] + [createdOn] + [size]. The phone is **authoritative** for
 * these values and sends them with every reconcile, upload and verify request.
 * Two files with the same identity are considered the same file by the server,
 * so [size] is deliberately part of the key to stop a same-name/same-second
 * collision from producing a false "already synced".
 *
 * - [name]      MediaStore `DISPLAY_NAME`.
 * - [createdOn] MediaStore `DATE_TAKEN` rendered as ISO-8601, matching the
 *               server's `datetime.fromisoformat` parsing.
 * - [size]      MediaStore `SIZE` in bytes.
 */
data class Identity(
    val name: String,
    val createdOn: String,
    val size: Long,
)
