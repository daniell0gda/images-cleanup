package eu.caiq.imagesorter.sync.sync

/**
 * Pluggable seam for *what* starts a sync run.
 *
 * v1 ships only [ManualSyncTrigger] (user taps "Sync now" → foreground service).
 * The engine depends on this interface, never on the trigger implementation, so a
 * WorkManager-based background trigger can be added later — gated on the
 * trusted-network constraint — without touching [SyncEngine].
 *
 * TODO(background): add a `WorkManagerSyncTrigger` that enqueues a constrained
 *  periodic work request and starts the same foreground service. Do NOT implement
 *  now (background is out of scope for v1 per the design doc).
 */
interface SyncTrigger {
    /** Request a sync run. Implementations decide how the engine is actually run. */
    fun requestSync()
}
