package eu.caiq.imagesorter.sync.data.media

import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity

/** A timeline row: either a day-section header or a media cell. */
sealed interface MediaListItem {
    /** Section header for a calendar day, e.g. `"2024-03-03"`. */
    data class Header(val day: String) : MediaListItem

    data class Media(val entity: MediaEntity) : MediaListItem
}

/** The calendar day (date portion of the ISO `date_taken`) for grouping. */
fun mediaDay(entity: MediaEntity): String = entity.dateTaken.substringBefore('T')

/**
 * Pure separator rule for [PagingData.insertSeparators] (newest-first order):
 * returns a [MediaListItem.Header] when the day changes between adjacent items,
 * including before the very first item ([before] == null), else null.
 */
fun dayHeaderBetween(before: MediaEntity?, after: MediaEntity?): MediaListItem.Header? {
    if (after == null) return null
    val afterDay = mediaDay(after)
    if (before == null) return MediaListItem.Header(afterDay)
    return if (mediaDay(before) != afterDay) MediaListItem.Header(afterDay) else null
}

/** Wraps a media-entity [PagingData] into [MediaListItem]s with day headers. */
fun PagingData<MediaEntity>.insertDayHeaders(): PagingData<MediaListItem> =
    map<MediaEntity, MediaListItem> { MediaListItem.Media(it) }
        .insertSeparators { before, after ->
            dayHeaderBetween(
                (before as? MediaListItem.Media)?.entity,
                (after as? MediaListItem.Media)?.entity,
            )
        }
