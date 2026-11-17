package si.marko.step.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import si.marko.step.MainActivity
import si.marko.step.R
import si.marko.step.core.Goal
import java.text.NumberFormat

/** The silent ongoing notification that shows today's steps, also on the lock screen. */
object StepNotification {
    const val CHANNEL_ID = "steps"
    const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_steps_name),
            NotificationManager.IMPORTANCE_LOW, // no sound, no vibration, no peeking
        ).apply {
            description = context.getString(R.string.channel_steps_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun title(context: Context, steps: Long, goal: Int): String {
        val text = context.getString(R.string.steps_count, NumberFormat.getIntegerInstance().format(steps))
        return if (Goal.reached(steps, goal)) context.getString(R.string.goal_reached_prefix, text) else text
    }

    fun subtitle(context: Context, steps: Long, goal: Int): String =
        context.getString(
            R.string.goal_progress,
            Goal.percent(steps, goal),
            NumberFormat.getIntegerInstance().format(goal),
        )

    fun build(context: Context, steps: Long, goal: Int): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setContentTitle(title(context, steps, goal))
            .setContentText(subtitle(context, steps, goal))
            .setProgress(goal, steps.coerceAtMost(goal.toLong()).toInt(), false)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Show at once instead of after the default delay for foreground services.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
