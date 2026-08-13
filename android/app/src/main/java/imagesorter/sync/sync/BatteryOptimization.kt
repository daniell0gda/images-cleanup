package imagesorter.sync.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether the OS is currently free to defer/kill the app's background capture-sync
 * work for battery saving. OEM battery managers (Samsung One UI in particular) are
 * far more aggressive than stock Android about this, so the app offers an explicit
 * exemption request rather than relying on the user finding it in system settings.
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/** Launches the system dialog that lets the user grant the battery-optimization exemption. */
fun batteryOptimizationExemptionIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
