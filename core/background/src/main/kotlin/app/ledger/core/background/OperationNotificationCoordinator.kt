package app.ledger.core.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

data class OperationNotificationContent(
    val channelName: String,
    val title: String,
    val progressText: String,
    val smallIcon: Int = android.R.drawable.stat_sys_upload,
)

enum class NotificationPermissionStatus { GRANTED, REQUIRED }

/** Single notification and deep-link policy for all long-running ledger operations. */
object OperationNotificationCoordinator {
    const val CHANNEL_ID: String = "ledger-operations"

    fun permissionStatus(context: Context): NotificationPermissionStatus = if (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    ) {
        NotificationPermissionStatus.GRANTED
    } else {
        NotificationPermissionStatus.REQUIRED
    }

    fun create(context: Context, content: OperationNotificationContent): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, content.channelName, NotificationManager.IMPORTANCE_LOW),
        )
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("ledger://screen/G-007"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            OPERATIONS_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(content.smallIcon)
            .setContentTitle(content.title)
            .setContentText(content.progressText)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    private const val OPERATIONS_REQUEST_CODE = 33_007
}
