package imagesorter.sync.media

import imagesorter.sync.data.api.AlbumApi
import imagesorter.sync.data.api.dto.AlbumDto
import imagesorter.sync.data.api.dto.AlbumItemsBody
import imagesorter.sync.data.api.dto.AlbumNameBody
import imagesorter.sync.data.api.dto.CreateAlbumBody
import imagesorter.sync.data.api.dto.DeletedDto
import imagesorter.sync.data.api.dto.MediaItemDto
import imagesorter.sync.data.api.dto.ShareDto
import imagesorter.sync.data.media.AlbumRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records calls and returns canned responses so the repo mapping can be asserted. */
private class FakeAlbumApi : AlbumApi {
    var listResponse: List<AlbumDto> = emptyList()
    var entryResponse: AlbumDto = AlbumDto(1, "x", null, "2024-01-01T00:00:00", 0, null, false, null)
    var itemsResponse: List<MediaItemDto> = emptyList()
    var shareResponse: ShareDto = ShareDto("tok", "https://s/share/tok")
    var deletedResponse: DeletedDto = DeletedDto(true)

    var lastCreate: CreateAlbumBody? = null
    var lastRename: AlbumNameBody? = null
    var lastAdd: AlbumItemsBody? = null
    var lastRemove: AlbumItemsBody? = null
    var lastDeletedId: Long? = null
    var lastSharedId: Long? = null
    var lastRevokedId: Long? = null

    override suspend fun albums(): List<AlbumDto> = listResponse
    override suspend fun create(body: CreateAlbumBody): AlbumDto { lastCreate = body; return entryResponse }
    override suspend fun rename(id: Long, body: AlbumNameBody): AlbumDto { lastRename = body; return entryResponse }
    override suspend fun delete(id: Long): DeletedDto { lastDeletedId = id; return deletedResponse }
    override suspend fun addItems(id: Long, body: AlbumItemsBody): AlbumDto { lastAdd = body; return entryResponse }
    override suspend fun removeItems(id: Long, body: AlbumItemsBody): AlbumDto { lastRemove = body; return entryResponse }
    override suspend fun items(id: Long): List<MediaItemDto> = itemsResponse
    override suspend fun share(id: Long): ShareDto { lastSharedId = id; return shareResponse }
    override suspend fun revoke(id: Long) { lastRevokedId = id }
}

class AlbumRepositoryTest {

    @Test
    fun listMapsAlbumEntries() = runTest {
        val api = FakeAlbumApi().apply {
            listResponse = listOf(
                AlbumDto(5, "Trip", "alice", "2024-03-02T10:00:00", 12, 99, true, "https://host/share/abc"),
            )
        }
        val repo = AlbumRepository(api)

        val album = repo.albums().single()

        assertEquals(5L, album.id)
        assertEquals("Trip", album.name)
        assertEquals("alice", album.createdBy)
        assertEquals(12, album.itemCount)
        assertEquals(99L, album.coverMediaId)
        assertTrue(album.shared)
    }

    @Test
    fun shareUrlIsPassedThroughVerbatimFromServer() = runTest {
        val serverUrl = "https://photos.example.com/share/Xq7zZ-token"
        val api = FakeAlbumApi().apply { shareResponse = ShareDto("Xq7zZ-token", serverUrl) }
        val repo = AlbumRepository(api)

        val result = repo.share(7)

        assertEquals(serverUrl, result.shareUrl)
        assertEquals(7L, api.lastSharedId)
    }

    @Test
    fun createPassesNameMediaIdsAndCreatedBy() = runTest {
        val api = FakeAlbumApi()
        val repo = AlbumRepository(api)

        repo.create("Album 2024", listOf(1, 2, 3), "bob")

        assertEquals("Album 2024", api.lastCreate?.name)
        assertEquals(listOf(1L, 2L, 3L), api.lastCreate?.mediaIds)
        assertEquals("bob", api.lastCreate?.createdBy)
    }

    @Test
    fun addAndRemoveItemsMapToMembershipBodies() = runTest {
        val api = FakeAlbumApi()
        val repo = AlbumRepository(api)

        repo.addItems(4, listOf(10, 11))
        repo.removeItems(4, listOf(11))

        assertEquals(listOf(10L, 11L), api.lastAdd?.mediaIds)
        assertEquals(listOf(11L), api.lastRemove?.mediaIds)
    }

    @Test
    fun renameAndDeleteMapToEndpoints() = runTest {
        val api = FakeAlbumApi()
        val repo = AlbumRepository(api)

        repo.rename(8, "New name")
        val deleted = repo.delete(8)

        assertEquals("New name", api.lastRename?.name)
        assertEquals(8L, api.lastDeletedId)
        assertTrue(deleted)
    }

    @Test
    fun itemsMapsToTimelineMediaItems() = runTest {
        val api = FakeAlbumApi().apply {
            itemsResponse = listOf(MediaItemDto(id = 3, kind = "video", dateTaken = "2024-03-02T10:00:00", width = 1920, height = 1080))
        }
        val repo = AlbumRepository(api)

        val item = repo.items(2).single()

        assertEquals(3L, item.id)
        assertEquals("video", item.kind)
        assertEquals(1920, item.width)
    }

    @Test
    fun revokeCallsApiWithAlbumId() = runTest {
        val api = FakeAlbumApi()
        val repo = AlbumRepository(api)

        repo.revoke(9)
        assertEquals(9L, api.lastRevokedId)
    }

    @Test
    fun unsharedAlbumExposesNullShareUrl() = runTest {
        val api = FakeAlbumApi().apply {
            listResponse = listOf(AlbumDto(1, "Empty", null, "2024-01-01T00:00:00", 0, null, false, null))
        }
        val repo = AlbumRepository(api)

        val album = repo.albums().single()

        assertNull(album.shareUrl)
        assertFalse(album.shared)
        assertNull(album.coverMediaId)
    }
}
