package eu.caiq.imagesorter.sync.data.media

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import eu.caiq.imagesorter.sync.data.api.MediaApi
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity

/**
 * Server-backed paging source for a single date segment (year / month / day). A distinct
 * data path from the Room-cached timeline (mediator + keyset): it never reads or writes the
 * `media` cache, so segment mode cannot leak adjacent-segment rows the cache happens to hold.
 *
 * A segment maps to a date-range query as [fromDate] + [datePrefix]: the server page starts at
 * the newest photo on or before [fromDate] (the segment's latest day), and only items whose
 * capture date begins with [datePrefix] belong to the segment (`"2024-"` for a year, `"2024-03-"`
 * for a month, `"2024-03-10"` for a day). Since the server returns rows newest-first, the first
 * item that falls outside the prefix is older than the segment, so paging stops there.
 */
class MediaSegmentPagingSource(
    private val api: MediaApi,
    private val fromDate: String,
    private val datePrefix: String,
) : PagingSource<String, MediaEntity>() {

    override suspend fun load(params: LoadParams<String>): LoadResult<String, MediaEntity> {
        return try {
            val cursor = params.key
            val page = if (cursor == null) {
                api.media(cursor = null, limit = params.loadSize, fromDate = fromDate)
            } else {
                api.media(cursor = cursor, limit = params.loadSize)
            }
            // Newest-first: everything up to the first out-of-segment row is inside the segment;
            // that first older row (and all after it) marks the segment's lower edge.
            val inSegment = page.items.takeWhile { it.dateTaken.startsWith(datePrefix) }
            val reachedOlder = inSegment.size < page.items.size
            Log.d(TAG, "segment load: cursor=$cursor got=${page.items.size} inSegment=${inSegment.size} reachedOlder=$reachedOlder")
            LoadResult.Page(
                data = inSegment.mapIndexed { index, dto ->
                    MediaEntity(
                        id = dto.id,
                        kind = dto.kind,
                        dateTaken = dto.dateTaken,
                        width = dto.width,
                        height = dto.height,
                        orderKey = index.toLong(),
                    )
                },
                prevKey = null,
                nextKey = if (reachedOlder || page.nextCursor == null) null else page.nextCursor,
            )
        } catch (e: Exception) {
            Log.e(TAG, "segment load failed", e)
            LoadResult.Error(e)
        }
    }

    /** Segment mode always presents from the top, so a refresh restarts at the segment head. */
    override fun getRefreshKey(state: PagingState<String, MediaEntity>): String? = null

    companion object {
        private const val TAG = "GOTODATE"
    }
}
