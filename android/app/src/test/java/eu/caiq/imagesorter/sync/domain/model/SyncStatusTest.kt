package eu.caiq.imagesorter.sync.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTest {

    @Test
    fun hasAnUnclassifiedMember() {
        // The "Not People" set is backed by a distinct cache status the server
        // never confirms as synced, so it must be its own SyncStatus member.
        assertEquals(SyncStatus.UNCLASSIFIED, SyncStatus.valueOf("UNCLASSIFIED"))
    }
}
