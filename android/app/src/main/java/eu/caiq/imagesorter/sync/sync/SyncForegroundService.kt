package eu.caiq.imagesorter.sync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import eu.caiq.imagesorter.sync.R
import eu.caiq.imagesorter.sync.SyncApp
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(getString(R.string.sync_notification_idle), 0, 0))
        startRunIfIdle()
        return START_NOT_STICKY
    }

    private fun startRunIfIdle() {
        if (runJob?.isActive == true) return
        val engine = (application as SyncApp).serviceLocator.syncEngine
        runJob = scope.launch {
            launch { collectProgress(engine) }
            engine.run()
            stopSelf()
        }
    }

    private suspend fun collectProgress(engine: SyncEngine) {
        engine.progress.collect { progress ->
            updateNotification(progress)
        }
    }

    private fun updateNotification(progress: SyncProgress) {
        val text = progress.message ?: when (progress.phase) {
            SyncPhase.UPLOADING -> "Uploading ${progress.completedFiles}/${progress.totalFiles}"
            else -> progress.phase.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        val notification = buildNotification(text, progress.totalFiles, progress.completedFiles)
        notificationManager().notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, max: Int, progress: Int): Notification {
        ensureChannel()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (max > 0) builder.setProgress(max, progress, false)
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.sync_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "sync_progress"
        private const val NOTIFICATION_ID = 1001

        /** Start the service (used by [ManualSyncTrigger]). */
        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }
}
