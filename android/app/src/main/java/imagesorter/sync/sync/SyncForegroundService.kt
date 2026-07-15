package imagesorter.sync.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import imagesorter.sync.R
import imagesorter.sync.SyncApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs a [SyncEngine] pass in the foreground with an ongoing progress
 * notification so the run survives the Activity being backgrounded and any
 * failures stay visible.
 *
 * This is the v1 execution surface. [ManualSyncTrigger] starts it; a future
 * background trigger (WorkManager) would start the same service unchanged.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    private var silent = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        silent = isSilent(intent)
        startForegroundCompat(buildNotification(getString(R.string.sync_notification_idle), 0, 0))
        startRunIfIdle(isIncremental(intent))
        return START_NOT_STICKY
    }

    private fun startRunIfIdle(incremental: Boolean) {
        if (runJob?.isActive == true) return
        val engine = (application as SyncApp).serviceLocator.syncEngine
        runJob = scope.launch {
            launch { collectProgress(engine) }
            engine.run(incremental = incremental)
            stopSelf()
        }
    }

    private suspend fun collectProgress(engine: SyncEngine) {
        engine.progress.collect { progress ->
            updateNotification(progress)
        }
    }

    private fun updateNotification(progress: SyncProgress) {
        val notification = buildNotification(
            SyncNotification.textFor(progress), progress.totalFiles, progress.completedFiles,
        )
        notificationManager().notify(SyncNotification.ID, notification)
    }

    internal fun buildNotification(text: String, max: Int, progress: Int): Notification =
        SyncNotification.build(this, text, max, progress, silent)

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SyncNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SyncNotification.ID, notification)
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {

        /**
         * Intent extra selecting the engine run mode. `true` = incremental
         * (watermark-based), used by the capture job; absent/`false` = full pass,
         * used by the manual/app-open path.
         */
        const val EXTRA_INCREMENTAL = "imagesorter.sync.EXTRA_INCREMENTAL"

        /**
         * Intent extra marking an automatic (app-open) run the user didn't initiate,
         * so its notification surfaces silently. Absent/`false` = user-initiated
         * (manual "Back up now"), which keeps its normal alerting.
         */
        const val EXTRA_SILENT = "imagesorter.sync.EXTRA_SILENT"

        /**
         * Start the service. [incremental] `true` runs the engine incrementally
         * (capture trigger); the default `false` runs a full pass (manual path via
         * [ManualSyncTrigger]). [silent] `true` routes the notification through the
         * silent channel for automatic app-open runs.
         */
        fun start(context: Context, incremental: Boolean = false, silent: Boolean = false) {
            val intent = Intent(context, SyncForegroundService::class.java)
                .putExtra(EXTRA_INCREMENTAL, incremental)
                .putExtra(EXTRA_SILENT, silent)
            context.startForegroundService(intent)
        }

        /** Pure intent → run-mode seam so the mapping is unit-testable. */
        internal fun isIncremental(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_INCREMENTAL, false) ?: false

        /** Pure intent → silent-run seam so the mapping is unit-testable. */
        internal fun isSilent(intent: Intent?): Boolean =
            intent?.getBooleanExtra(EXTRA_SILENT, false) ?: false
    }
}
