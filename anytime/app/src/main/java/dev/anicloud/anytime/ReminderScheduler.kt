package dev.anicloud.anytime

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.anicloud.anytime.domain.OccurrenceResolver
import java.time.Instant

/** Inexact, user-opted reminders. No exact-alarm privilege is required. */
object ReminderScheduler {
    private const val channelId = "anytime-events"
    private const val eventId = "eventId"

    fun reschedule(context: Context, store: AnytimeStore) {
        store.events().forEach { event ->
            if (event.reminderMinutes == 0) return@forEach
            val next = nextAlert(event, store, Instant.now()) ?: return@forEach
            val alarm = context.getSystemService(AlarmManager::class.java)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pending(context, event.id))
        }
    }

    fun cancel(context: Context, id: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pending(context, id))
    }

    private fun pending(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(), Intent(context, ReminderReceiver::class.java).putExtra(eventId, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun nextAlert(event: AnytimeEvent, store: AnytimeStore, now: Instant): Instant? {
        val advance = event.reminderMinutes.toLong() * 60
        val occurrence = OccurrenceResolver.nextAfter(event.instant, event.zone, event.recurrence,
            store.calendar(), now.plusSeconds(advance)) ?: return null
        return occurrence.minusSeconds(advance)
    }

    fun show(context: Context, event: AnytimeEvent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channelId, "Anytime events",
            NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(event.id.toInt(), Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_anytime)
            .setContentTitle(event.title)
            .setContentText("A moment you chose is approaching")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    fun eventId(intent: Intent): Long = intent.getLongExtra(eventId, -1L)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = AnytimeStore(context)
        try {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                ReminderScheduler.reschedule(context, store)
                return
            }
            store.events().firstOrNull { it.id == ReminderScheduler.eventId(intent) }
                ?.let { ReminderScheduler.show(context, it) }
            ReminderScheduler.reschedule(context, store)
        } finally { store.close() }
    }
}
