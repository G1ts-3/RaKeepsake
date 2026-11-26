package id.rakeepsake.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import id.rakeepsake.MainActivity
import id.rakeepsake.R
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Notifier {
    const val CH_MONITOR = "monitor"
    const val CH_RECORD = "record"
    const val CH_DOWNLOAD = "download"
    const val CH_CLOUD_UPLOAD = "cloud_upload"
    const val ID_MONITOR = 100
    const val ID_RECORD_BASE = 200
    const val ID_DOWNLOAD_BASE = 300
    const val ID_CLOUD_UPLOAD_BASE = 400

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, context.getString(R.string.notif_channel_monitor),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_monitor_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORD, context.getString(R.string.notif_channel_record),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_record_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DOWNLOAD, context.getString(R.string.notif_channel_download),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_download_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CLOUD_UPLOAD, context.getString(R.string.notif_channel_cloud_upload),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_cloud_upload_desc)
            },
        )
    }

    fun downloadDone(context: Context, author: String, caption: String) {
        val text = "Unduhan selesai dari @$author" +
            if (caption.isNotBlank()) "\n${caption.take(80)}" else ""
        notify(context, CH_DOWNLOAD, ID_DOWNLOAD_BASE + (author.hashCode() and 0x3FF),
            "Unduhan selesai", text)
    }

    fun downloadFailed(context: Context, author: String, error: String) {
        notify(context, CH_DOWNLOAD, ID_DOWNLOAD_BASE + (author.hashCode() and 0x3FF),
            "Unduhan gagal (@$author)", error.take(160))
    }

    fun notify(
        context: Context,
        channel: String,
        id: Int,
        title: String,
        text: String,
        openTab: Int? = null,
        actionLabel: String? = null,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (openTab != null) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("tab", openTab)
            }
            val pi = PendingIntent.getActivity(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pi)
            if (!actionLabel.isNullOrBlank()) {
                builder.addAction(0, actionLabel, pi)
            }
        }
        runCatching { nm.notify(id, builder.build()) }
    }
}

object Scheduling {
    /** Jadwalkan pemantau postingan baru global (per plan FR-14). */
    fun schedulePostWatch(context: Context) {
        val request = PeriodicWorkRequestBuilder<PostWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag("post-watch")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "post-watch", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    fun cancelPostWatch(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("post-watch")
    }
}
