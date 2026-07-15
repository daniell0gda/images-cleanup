package imagesorter.sync.data.prefs

/**
 * Which networks automatic sync is allowed to use.
 *
 * [WIFI_ONLY] restricts every automatic path (the capture-trigger job, its
 * WorkManager upload, and app-open auto-sync) to an unmetered connection — the
 * default, so a backup never burns mobile data. [ANY] lifts that restriction so
 * sync runs on any connected network, including cellular.
 */
enum class SyncNetworkType { WIFI_ONLY, ANY }
