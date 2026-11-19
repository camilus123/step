package si.marko.step.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.MutableStateFlow
import si.marko.step.AppGraph
import si.marko.step.MainActivity
import si.marko.step.R
import si.marko.step.core.Goal
import java.text.NumberFormat

/**
 * Home screen widget with today's steps.
 *
 * - Smallest size (one cell): only the number.
 * - Wider: the number, the goal percentage and a progress bar.
 * - Taller as well: the same with a larger number.
 *
 * The layout adapts to the size it is given. The HyperOS launcher of the 17T does not let the
 * widget be resized, so the same widget is also offered in fixed sizes (see the subclasses at
 * the end of this file); each appears as its own entry in the widget picker.
 *
 * Colours come from the system theme (wallpaper colours on Android 12 and later) and follow
 * light and dark mode. Tapping it opens the app.
 */
open class StepWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // A fresh look at the database every time the widget (re)starts, so that a stale number
        // from a process whose service has died cannot survive, e.g. across midnight.
        loadFromDatabase(context)
        provideContent {
            val steps by today.collectAsState()
            GlanceTheme { Content(steps ?: 0, Goal.DEFAULT) }
        }
    }

    @Composable
    private fun Content(steps: Long, goal: Int) {
        val context = LocalContext.current
        val size = LocalSize.current
        val numbers = NumberFormat.getIntegerInstance()
        val reached = Goal.reached(steps, goal)
        val showProgress = size.width >= WIDE.width
        val large = showProgress && size.height >= LARGE.height

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(horizontal = if (showProgress) 14.dp else 6.dp, vertical = 8.dp)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = if (showProgress) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = numbers.format(steps),
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = when {
                            large -> 34.sp
                            showProgress -> 24.sp
                            else -> 18.sp
                        },
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (reached) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = context.getString(R.string.goal_reached_mark),
                        style = TextStyle(
                            color = ColorProvider(GOAL_REACHED_GREEN),
                            fontSize = if (large) 28.sp else if (showProgress) 20.sp else 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                if (showProgress) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = context.getString(R.string.widget_steps_label),
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                    )
                }
            }
            if (showProgress) {
                Spacer(GlanceModifier.height(if (large) 8.dp else 4.dp))
                LinearProgressIndicator(
                    progress = (steps.toFloat() / goal).coerceIn(0f, 1f),
                    modifier = GlanceModifier.fillMaxWidth().height(if (large) 8.dp else 6.dp),
                    color = if (reached) ColorProvider(GOAL_REACHED_GREEN) else GlanceTheme.colors.primary,
                    backgroundColor = GlanceTheme.colors.surfaceVariant,
                )
                Spacer(GlanceModifier.height(if (large) 6.dp else 3.dp))
                Text(
                    text = context.getString(R.string.goal_progress, Goal.percent(steps, goal), numbers.format(goal)),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = if (large) 14.sp else 12.sp),
                )
            }
        }
    }

    companion object {
        private val SMALL = DpSize(40.dp, 40.dp)
        private val WIDE = DpSize(110.dp, 40.dp)
        private val LARGE = DpSize(110.dp, 110.dp)

        private val GOAL_REACHED_GREEN = Color(0xFF2E9E4F)

        /** Today's steps as the widget shows them. Null until first loaded in this process. */
        private val today = MutableStateFlow<Long?>(null)

        private suspend fun loadFromDatabase(context: Context) {
            val repository = AppGraph.get(context).repository
            today.value = repository.dayTotalOnce(repository.todayEpochDay())
        }

        /** Called by the counting service with the number it has just put in the notification. */
        suspend fun show(context: Context, steps: Long) {
            today.value = steps
            updateEverySize(context)
        }

        /** For when the service is not there to push numbers: read the stored count and show it. */
        suspend fun refreshFromDatabase(context: Context) {
            loadFromDatabase(context)
            updateEverySize(context)
        }

        private fun everySize(): List<StepWidget> = listOf(StepWidget(), StepWidget1x1(), StepWidget2x2(), StepWidget4x1())

        private suspend fun updateEverySize(context: Context) {
            for (widget in everySize()) runCatching { widget.updateAll(context) }
        }

        /** How many widgets of each size are on the home screen, e.g. "2x1: 1, 1x1: 0, ...". */
        suspend fun countText(context: Context): String {
            val manager = GlanceAppWidgetManager(context)
            val parts = mutableListOf<String>()
            for ((name, widget) in listOf("2x1", "1x1", "2x2", "4x1").zip(everySize())) {
                val count = try {
                    manager.getGlanceIds(widget.javaClass).size.toString()
                } catch (e: Exception) {
                    "?"
                }
                parts += "$name: $count"
            }
            return parts.joinToString()
        }
    }
}

// One class per size: Glance keeps track of placed widgets by widget class.
class StepWidget1x1 : StepWidget()
class StepWidget2x2 : StepWidget()
class StepWidget4x1 : StepWidget()

/** 2 x 1 cells, the original. */
class StepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepWidget()
}

class StepWidget1x1Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepWidget1x1()
}

class StepWidget2x2Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepWidget2x2()
}

class StepWidget4x1Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepWidget4x1()
}
