package dev.anicloud.sovereign.prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.work.ForegroundInfo

private const val UpdateChannelId = "intermix_release_updates"
private const val UpdateProgressNotificationId = 4201
private const val UpdateReadyNotificationId = 4202
private const val UpdateResultNotificationId = 4203

object UpdateNotifications {
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                UpdateChannelId,
                "Intermix release updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Verified release downloads and Android installation confirmation"
                setShowBadge(true)
            },
        )
    }

    fun foreground(context: Context, downloaded: Long, total: Long): ForegroundInfo = ForegroundInfo(
        UpdateProgressNotificationId,
        progress(context, downloaded, total),
    )

    fun publishProgress(context: Context, downloaded: Long, total: Long) {
        manager(context).notify(UpdateProgressNotificationId, progress(context, downloaded, total))
    }

    fun ready(context: Context, manifest: ReleaseManifest) {
        val install = installPendingIntent(
            context,
            manifest.releaseId,
            manifest.versionCode,
        )
        val notification = Notification.Builder(context, UpdateChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Intermix ${manifest.versionName} is verified")
            .setContentText("Local state is checkpointed. Tap to continue with Android installation.")
            .setContentIntent(install)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SYSTEM)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.stat_sys_download_done),
                    "INSTALL",
                    install,
                ).build(),
            )
            .build()
        manager(context).cancel(UpdateProgressNotificationId)
        manager(context).notify(UpdateReadyNotificationId, notification)
    }

    fun installSourceApproval(
        context: Context,
        settingsAction: Intent,
        state: UpdateSnapshot,
    ) {
        val settings = PendingIntent.getActivity(
            context,
            4204,
            settingsAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val retry = installPendingIntent(context, state.releaseId, state.versionCode)
        manager(context).notify(
            UpdateReadyNotificationId,
            Notification.Builder(context, UpdateChannelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Allow Intermix as an update source")
                .setContentText("Allow this source, return here, then tap INSTALL.")
                .setContentIntent(settings)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .addAction(notificationAction(context, "ALLOW", settings))
                .addAction(notificationAction(context, "INSTALL", retry))
                .build(),
        )
    }

    fun installationAction(context: Context, action: Intent, detail: String) {
        val pending = PendingIntent.getActivity(
            context,
            4204,
            action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager(context).notify(
            UpdateReadyNotificationId,
            Notification.Builder(context, UpdateChannelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Confirm the Intermix update")
                .setContentText(detail)
                .setContentIntent(pending)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .addAction(notificationAction(context, "CONTINUE", pending))
                .build(),
        )
    }

    fun retry(context: Context, state: UpdateSnapshot, detail: String) {
        val install = installPendingIntent(context, state.releaseId, state.versionCode)
        manager(context).notify(
            UpdateReadyNotificationId,
            Notification.Builder(context, UpdateChannelId)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Intermix update paused")
                .setContentText(detail.take(180))
                .setContentIntent(install)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_ERROR)
                .addAction(notificationAction(context, "RETRY", install))
                .build(),
        )
    }

    fun result(context: Context, title: String, detail: String, warning: Boolean = false) {
        manager(context).cancel(UpdateProgressNotificationId)
        manager(context).cancel(UpdateReadyNotificationId)
        manager(context).notify(
            UpdateResultNotificationId,
            Notification.Builder(context, UpdateChannelId)
                .setSmallIcon(
                    if (warning) android.R.drawable.stat_notify_error
                    else android.R.drawable.stat_sys_download_done,
                )
                .setContentTitle(title)
                .setContentText(detail.take(180))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build(),
        )
    }

    private fun progress(context: Context, downloaded: Long, total: Long): Notification {
        val maximum = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        val current = downloaded.coerceAtMost(maximum.toLong()).toInt()
        return Notification.Builder(context, UpdateChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading a verified Intermix update")
            .setContentText("${downloaded / (1024 * 1024)} / ${total / (1024 * 1024)} MiB")
            .setProgress(maximum, current, total <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun installPendingIntent(
        context: Context,
        releaseId: String,
        versionCode: Long,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        (versionCode and Int.MAX_VALUE.toLong()).toInt(),
        Intent(context, UpdateInstallReceiver::class.java)
            .setAction(UpdateInstallReceiver.ActionBeginInstall)
            .putExtra(UpdateInstallReceiver.ExtraReleaseId, releaseId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationAction(
        context: Context,
        title: String,
        pendingIntent: PendingIntent,
    ): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(context, android.R.drawable.stat_sys_download_done),
        title,
        pendingIntent,
    ).build()
}
