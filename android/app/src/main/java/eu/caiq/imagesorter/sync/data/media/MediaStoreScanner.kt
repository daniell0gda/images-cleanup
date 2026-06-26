package eu.caiq.imagesorter.sync.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import eu.caiq.imagesorter.sync.domain.model.Identity
import eu.caiq.imagesorter.sync.domain.model.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Enumerates photos and videos from MediaStore and maps them to domain
 * [MediaItem]s.
 *
 * Discovery is incremental: the caller passes the last processed generation
 * watermark; rows with a generation <= the watermark are skipped. A watermark of
 * [eu.caiq.imagesorter.sync.data.prefs.SecurePrefs.NO_WATERMARK] forces a full
 * enumerate (first run / lost watermark). Reconcile, by contrast, is always run
 * over the full enumerate elsewhere — this class only narrows *discovery*.
 *
 * `created_on` is rendered as ISO-8601 local date-time to match the server's
 * `datetime.fromisoformat` parsing.
 */
class MediaStoreScanner(context: Context) {

    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    /**
     * Enumerate images + videos.
     *
     * @param sinceGeneration only return items with `GENERATION_MODIFIED` greater
     *   than this; pass a negative value for a full enumerate.
     * @param folders optional set of relative paths to restrict to (e.g.
     *   "DCIM/Camera/"); empty means all images/videos.
     */
    fun enumerate(sinceGeneration: Long, folders: Set<String> = emptySet()): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        items += query(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, imageCollection(), sinceGeneration, folders)
        items += query(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, videoCollection(), sinceGeneration, folders)
        // Newest-first by created_on epoch (DATE_TAKEN), then by id as a tiebreak.
        return items.sortedByDescending { it.identity.createdOn }
    }

    /**
     * The current MediaStore generation, the watermark to persist after a
     * successful run. Returns 0 below the API level that exposes generations.
     */
    fun currentGeneration(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.getGeneration(appContext, VOLUME)
        } else {
            0L
        }

    private fun query(
        mediaType: Int,
        collection: Uri,
        sinceGeneration: Long,
        folders: Set<String>,
    ): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )

        val (selection, args) = buildSelection(sinceGeneration, folders)

        val out = ArrayList<MediaItem>()
        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val size = cursor.getLong(sizeCol)
                val mime = cursor.getString(mimeCol) ?: defaultMime(mediaType)
                val createdOn = isoCreatedOn(
                    dateTakenMillis = if (cursor.isNull(takenCol)) null else cursor.getLong(takenCol),
                    dateAddedSeconds = if (cursor.isNull(addedCol)) null else cursor.getLong(addedCol),
                )
                out += MediaItem(
                    mediaStoreId = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    identity = Identity(name = name, createdOn = createdOn, size = size),
                    mimeType = mime,
                )
            }
        }
        return out
    }

    private fun buildSelection(
        sinceGeneration: Long,
        folders: Set<String>,
    ): Pair<String?, Array<String>?> {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()

        if (sinceGeneration >= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            clauses += "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
            args += sinceGeneration.toString()
        }

        if (folders.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val placeholders = folders.joinToString(" OR ") {
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            }
            clauses += "($placeholders)"
            folders.forEach { args += "$it%" }
        }

        return if (clauses.isEmpty()) {
            null to null
        } else {
            clauses.joinToString(" AND ") to args.toTypedArray()
        }
    }

    /**
     * Render the file's creation time as ISO-8601. Prefer DATE_TAKEN (epoch
     * millis); fall back to DATE_ADDED (epoch seconds) when unset (common for
     * videos / screenshots).
     */
    private fun isoCreatedOn(dateTakenMillis: Long?, dateAddedSeconds: Long?): String {
        val instant = when {
            dateTakenMillis != null && dateTakenMillis > 0 -> Instant.ofEpochMilli(dateTakenMillis)
            dateAddedSeconds != null && dateAddedSeconds > 0 -> Instant.ofEpochSecond(dateAddedSeconds)
            else -> Instant.EPOCH
        }
        return ISO_LOCAL.format(instant.atZone(ZoneId.systemDefault()))
    }

    private fun defaultMime(mediaType: Int): String =
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) "video/*" else "image/*"

    private fun imageCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(VOLUME)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(VOLUME)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    companion object {
        private const val VOLUME = MediaStore.VOLUME_EXTERNAL
        // ISO-8601 without offset, matching Python's datetime.fromisoformat.
        private val ISO_LOCAL: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}
