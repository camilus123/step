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

/** The ongoing notification that shows today's steps, also on the lock screen. Never audible. */
object StepNotification {
    /** Low importance: what Android calls a silent notification. */
    const val CHANNEL_SILENT = "steps"

    /** Normal importance with sound and vibration switched off on the channel itself. */
    const val CHANNEL_NORMAL = "steps_lockscreen"

    /** The service alternates between these two ids when it posts the notification afresh. */
    const val NOTIFICATION_ID_A = 1
    const val NOTIFICATION_ID_B = 2

    fun channelFor(options: LockScreenOptions) = if (options.normalImportance) CHANNEL_NORMAL else CHANNEL_SILENT

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                context.getString(R.string.channel_steps_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_steps_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NORMAL,
                context.getString(R.string.channel_steps_lock_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_steps_lock_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
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

    fun build(context: Context, steps: Long, goal: Int, options: LockScreenOptions): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channelFor(options))
            .setSmallIcon(R.drawable.ic_stat_steps)
            .setContentTitle(title(context, steps, goal))
            .setContentText(subtitle(context, steps, goal))
            .setProgress(goal, steps.coerceAtMost(goal.toLong()).toInt(), false)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (options.normalImportance) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            // Show at once instead of after the default delay for foreground services.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (!options.normalImportance) builder.setSilent(true)
        if (options.liveUpdate) {
            builder.setRequestPromotedOngoing(true)
            // The text of the status bar chip: just the number.
            builder.setShortCriticalText(NumberFormat.getIntegerInstance().format(steps))
        }
        return builder.build()
    }
}
