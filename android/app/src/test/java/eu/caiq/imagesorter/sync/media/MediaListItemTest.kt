package eu.caiq.imagesorter.sync.media

import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import eu.caiq.imagesorter.sync.data.db.entity.MediaEntity
import eu.caiq.imagesorter.sync.data.media.MediaListItem
import eu.caiq.imagesorter.sync.data.media.dayHeaderBetween
import eu.caiq.imagesorter.sync.data.media.insertDayHeaders
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaListItemTest {

    private fun media(id: Long, day: String) =
        MediaEntity(id = id, kind = "image", dateTaken = "${day}T12:00:00", orderKey = id)

    @Test
    fun headerBeforeFirstItemAndAtEachDayBoundary() {
        val a = media(3, "2024-03-03")
        val b = media(2, "2024-03-03")
        val c = media(1, "2024-03-02")

        assertEquals(MediaListItem.Header("2024-03-03"), dayHeaderBetween(null, a))
        assertNull(dayHeaderBetween(a, b)) // same day → no header
        assertEquals(MediaListItem.Header("2024-03-02"), dayHeaderBetween(b, c)) // boundary
        assertNull(dayHeaderBetween(c, null)) // no trailing header
    }

    @Test
    fun insertDayHeadersProducesHeaderAtEachBoundaryNewestFirst() = runTest {
        val data = PagingData.from(
            listOf(
                media(3, "2024-03-03"),
                media(2, "2024-03-03"),
                media(1, "2024-03-02"),
            ),
        )

        val snapshot = flowOf(data.insertDayHeaders()).asSnapshot()

        assertEquals(
            listOf(
                MediaListItem.Header("2024-03-03"),
                MediaListItem.Media(media(3, "2024-03-03")),
                MediaListItem.Media(media(2, "2024-03-03")),
                MediaListItem.Header("2024-03-02"),
                MediaListItem.Media(media(1, "2024-03-02")),
            ),
            snapshot,
        )
    }
}
