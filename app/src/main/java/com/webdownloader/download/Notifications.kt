package com.webdownloader.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.webdownloader.MainActivity

object Notifications {
    const val CHANNEL_PROGRESS = "downloads"
    const val CHANNEL_ALERTS = "alerts"
    const val ID_DOWNLOAD = 1001
    const val ID_RECOMPRESS = 1002
    const val ID_ALERT = 1003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Download alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, title: String, text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .setContentIntent(openApp(context))
            .build()

    fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun alert(context: Context, title: String, text: String) {
        notify(
            context, ID_ALERT,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }
}
