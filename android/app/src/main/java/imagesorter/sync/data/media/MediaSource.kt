package imagesorter.sync.data.media

import imagesorter.sync.domain.model.MediaItem

/**
 * Discovery seam, implemented by [MediaStoreScanner]. Lets
 * [imagesorter.sync.sync.SyncEngine] be unit tested with canned items
 * instead of a real MediaStore content provider.
 */
interface MediaSource {
    fun enumerate(sinceGeneration: Long, folders: Set<String> = emptySet()): List<MediaItem>
    fun currentGeneration(): Long
}
