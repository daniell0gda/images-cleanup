package eu.caiq.imagesorter.sync.domain.model

/**
 * Local view of where a media item sits in the sync lifecycle. This is a *cache*
 * status derived from server truth + the local upload queue; it is not
 * authoritative and can be rebuilt by re-running reconcile.
 */
enum class SyncStatus {
    /** Discovered locally, not yet reconciled or uploaded. */
    PENDING,

    /** Currently uploading (one or more chunks in flight or queued). */
    IN_PROGRESS,

    /** Server confirmed the file is placed at its destination. */
    SYNCED,

    /** Server reported a failure. See [FailureReason] for the cause. */
    FAILED,
}
