package dev.anicloud.sovereign.prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

private const val InferenceChannelId = "sovereign_inference"
private const val InferenceNotificationId = 4107
private const val ActionStart = "dev.anicloud.sovereign.action.START_INFERENCE"
private const val ActionStop = "dev.anicloud.sovereign.action.STOP_INFERENCE"

/**
 * Keeps a user-started native generation alive while the cockpit window is not focused.
 * The ViewModel remains the owner of model state; this service raises process importance
 * and exposes a second, Android-native STOP path.
 */
class InferenceForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L

    private val updateElapsed = object : Runnable {
        override fun run() {
            if (startedAt == 0L) return
            notificationManager().notify(InferenceNotificationId, notification(stopping = false))
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            InferenceChannelId,
            "Sovereign Core generation",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Visible state and cancellation for local model generation"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            notificationManager().notify(InferenceNotificationId, notification(stopping = true))
            GenerationStopBridge.requestStop()
            return START_NOT_STICKY
        }

        startedAt = SystemClock.elapsedRealtime()
        val initial = notification(stopping = false)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                InferenceNotificationId,
                initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(InferenceNotificationId, initial)
        }
        handler.removeCallbacks(updateElapsed)
        handler.postDelayed(updateElapsed, 1_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        startedAt = 0L
        handler.removeCallbacks(updateElapsed)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(stopping: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, InferenceForegroundService::class.java).setAction(ActionStop),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val elapsedSeconds = if (startedAt == 0L) 0L else
            (SystemClock.elapsedRealtime() - startedAt) / 1_000L
        return Notification.Builder(this, InferenceChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (stopping) "Sovereign Core is stopping" else "Sovereign Core is reasoning")
            .setContentText(
                if (stopping) "Discarding the partial response safely"
                else "Local E4B · ${elapsedSeconds}s · generation continues across windows",
            )
            .setContentIntent(openIntent)
            .setOngoing(!stopping)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "STOP",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        fun begin(context: Context, stopRequest: () -> Unit) {
            GenerationStopBridge.register(stopRequest)
            context.startForegroundService(
                Intent(context, InferenceForegroundService::class.java).setAction(ActionStart),
            )
        }

        fun finish(context: Context) {
            GenerationStopBridge.clear()
            context.stopService(Intent(context, InferenceForegroundService::class.java))
        }
    }
}

private object GenerationStopBridge {
    @Volatile
    private var stopRequest: (() -> Unit)? = null

    fun register(callback: () -> Unit) {
        stopRequest = callback
    }

    fun requestStop() {
        stopRequest?.invoke()
    }

    fun clear() {
        stopRequest = null
    }
}
