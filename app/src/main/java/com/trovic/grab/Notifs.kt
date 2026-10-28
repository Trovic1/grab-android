package com.trovic.grab

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifs {
    private const val CH_PROGRESS = "progress"
    private const val CH_DONE = "done"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloading", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(ctx: Context, text: String, percent: Int?): Notification =
        NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_grab)
            .setContentTitle(if (percent == null) "Grabbing…" else "Downloading $percent%")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(openApp(ctx))
            .build()

    fun done(ctx: Context, id: Int, saved: List<Saved>) {
        val first = saved.first()
        val tap = if (first.uri.scheme == "content") {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(first.uri, first.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(ctx, id, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else openApp(ctx)
        val what = if (saved.size == 1) {
            if (first.mime.startsWith("video")) "Video" else "Picture"
        } else "${saved.size} files"
        post(
            ctx, id,
            NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_grab)
                .setContentTitle("$what saved to Gallery")
                .setContentText("Tap to open")
                .setAutoCancel(true)
                .setContentIntent(tap)
                .build(),
        )
    }

    fun failed(ctx: Context, id: Int, url: String, reason: String) {
        post(
            ctx, id,
            NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(R.drawable.ic_grab)
                .setContentTitle("Couldn't download")
                .setContentText(Links.short(url))
                .setStyle(NotificationCompat.BigTextStyle().bigText("${Links.short(url)}\n$reason"))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
            // Notifications not allowed; the in-app history still shows the result.
        }
    }
}
