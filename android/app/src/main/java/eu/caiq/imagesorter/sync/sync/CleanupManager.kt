package eu.caiq.imagesorter.sync.sync

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import eu.caiq.imagesorter.sync.data.api.SyncApi
import eu.caiq.imagesorter.sync.data.api.dto.IdentityDto
import eu.caiq.imagesorter.sync.data.db.dao.SyncedCacheDao
import eu.caiq.imagesorter.sync.data.db.entity.SyncedCacheEntity
import eu.caiq.imagesorter.sync.domain.model.Identity

/**
 * The only destructive surface — and even here nothing is deleted without two
 * gates: a fresh server `/verify` confirming the file still physically exists at
 * its destination, **and** the Android system delete dialog.
 *
 * Flow: [verifySyncedItems] → (the returned items are confirmed present) →
 * [buildDeleteRequest] → caller launches the returned [IntentSender] via an
 * Activity result → the OS shows its confirmation and performs the deletion.
 */
class CleanupManager(
    private val api: SyncApi,
    private val syncedCacheDao: SyncedCacheDao,
    private val contentResolver: ContentResolver,
) {
    /**
     * Run `/verify` for the locally-synced candidate set and mark each
     * confirmed-present row as verified in the cache.
     *
     * @param scope optional pre-scoping (e.g. only "synced today"); null = all
     *   synced items, matching the design's "delete from any filter routes into
     *   the same pipeline, pre-scoped to that filter".
     * @return the identities the server confirmed still exist on the NAS.
     */
    suspend fun verifySyncedItems(scope: List<Identity>? = null): List<Identity> {
        val candidates = scope ?: syncedCacheDao.syncedItems().map { it.toIdentity() }
        if (candidates.isEmpty()) return emptyList()

        val present = ArrayList<Identity>()
        val now = System.currentTimeMillis()
        candidates.chunked(VERIFY_BATCH).forEach { batch ->
            val response = api.verify(batch.map { it.toDto() })
            response.results.forEach { result ->
                if (result.present) {
                    val identity = Identity(result.name, result.createdOn, result.size)
                    present += identity
                    syncedCacheDao.markVerified(identity.name, identity.createdOn, identity.size, now)
                }
            }
        }
        return present
    }

    /**
     * Build a system delete request for [mediaStoreIds] (the local ids of
     * verified-present items). The caller launches the returned [IntentSender];
     * the OS shows its own confirmation dialog. Returns null if the list is empty.
     *
     * Only the *local copy* is removed; the NAS copy is the backup and is never
     * touched. `createDeleteRequest` is always available at minSdk 33.
     */
    fun buildDeleteRequest(uris: List<Uri>): IntentSender? {
        if (uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(contentResolver, uris).intentSender
    }

    private fun SyncedCacheEntity.toIdentity() = Identity(name, createdOn, size)

    private fun Identity.toDto() = IdentityDto(name = name, createdOn = createdOn, size = size)

    companion object {
        private const val VERIFY_BATCH = 500
    }
}
