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

/**
 * The ongoing notification that shows today's steps. Never audible.
 *
 * What it takes to be visible on a locked Xiaomi 17T (HyperOS 3), found by trying on the phone:
 *
 * - Normal importance. As a "silent" (low importance) notification it is hidden on the lock
 *   screen, whatever the lock screen settings say. So the channel has normal importance with
 *   sound and vibration switched off on the channel itself.
 * - An Android 16 "live update" request. That is what puts it on the always-on display (clock on
 *   a black screen); it also turns the status bar icon into a chip with the step count.
 *
 * Posting it afresh whenever the screen turns off was tried as well and made no difference.
 */
object StepNotification {
    const val CHANNEL_ID = "steps_lockscreen"
    const val NOTIFICATION_ID = 1

    /** Channel of the first builds (low importance). Removed on start if it still exists. */
    private const val OLD_SILENT_CHANNEL_ID = "steps"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_steps_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_steps_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        // Android refuses while a foreground service still shows a notification on it; then
        // the next start gets it.
        runCatching { manager.deleteNotificationChannel(OLD_SILENT_CHANNEL_ID) }
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
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Show at once instead of after the default delay for foreground services.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Live update: lock screen and always-on display. The chip text is just the number.
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(NumberFormat.getIntegerInstance().format(steps))
            .build()
    }
}
