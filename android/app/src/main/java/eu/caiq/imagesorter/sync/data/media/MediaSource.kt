package eu.caiq.imagesorter.sync.data.media

import eu.caiq.imagesorter.sync.domain.model.MediaItem

/**
 * Discovery seam, implemented by [MediaStoreScanner]. Lets
 * [eu.caiq.imagesorter.sync.sync.SyncEngine] be unit tested with canned items
 * instead of a real MediaStore content provider.
 */
interface MediaSource {
    fun enumerate(sinceGeneration: Long, folders: Set<String> = emptySet()): List<MediaItem>
    fun currentGeneration(): Long
}
