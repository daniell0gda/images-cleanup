package eu.caiq.imagesorter.sync.domain.model

/**
 * Mirror of the server's per-file failure taxonomy.
 *
 * Each reason carries its own [retryable] flag so the engine never needs a
 * separate lookup table: retryable failures (transient) are re-queued; terminal
 * failures are surfaced to the user and left alone.
 *
 * Wire values (the `reason` string in an outcome) are matched via [fromWire].
 */
enum class FailureReason(val wire: String, val retryable: Boolean) {
    SIZE_MISMATCH("size_mismatch", retryable = true),
    PLACEMENT_ERROR("placement_error", retryable = true),
    INTERNAL_ERROR("internal_error", retryable = true),

    UNREADABLE("unreadable", retryable = false),
    NO_VIDEO_DESTINATION("no_video_destination", retryable = false),

    /** Fallback for any reason string the server adds that this build predates. */
    UNKNOWN("unknown", retryable = false);

    companion object {
        fun fromWire(value: String?): FailureReason =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}
