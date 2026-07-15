package imagesorter.sync.sync

/** Coarse phase of a sync run, surfaced to the notification and UI. */
enum class SyncPhase { IDLE, DISCOVERING, RECONCILING, UPLOADING, REPORTING, DONE, ERROR }

/**
 * Immutable snapshot of an in-flight sync run. Emitted by [SyncEngine] so the
 * foreground service and any UI observers render the same state.
 */
data class SyncProgress(
    val phase: SyncPhase = SyncPhase.IDLE,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    val message: String? = null,
) {
    val isFinished: Boolean get() = phase == SyncPhase.DONE || phase == SyncPhase.ERROR
}
