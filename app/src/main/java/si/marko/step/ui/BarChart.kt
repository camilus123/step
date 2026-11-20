package si.marko.step.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A plain bar chart drawn directly, without a chart library.
 *
 * @param values one bar each; zero is drawn as a faint stub so that an empty day is visibly
 *        an empty day and not a missing one.
 * @param labels one per bar, empty where no label should be shown.
 * @param goal draws a dashed line at this height, e.g. the daily goal.
 * @param selected index of the highlighted bar, if any.
 * @param onSelect called with the tapped bar, or null when the selected bar is tapped again.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    goal: Long? = null,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {},
) {
    val barColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.outline
    val top = maxOf(values.maxOrNull() ?: 0L, goal ?: 0L, 1L)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(values.size, selected) {
                    detectTapGestures { position ->
                        if (values.isEmpty()) return@detectTapGestures
                        val index = (position.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                        onSelect(if (index == selected) null else index)
                    }
                }
        ) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val barWidth = slot * 0.7f
            val stub = 2.dp.toPx()
            val radius = CornerRadius(minOf(barWidth / 2, 4.dp.toPx()))
            values.forEachIndexed { index, value ->
                val barHeight = if (value <= 0) stub else maxOf(stub, size.height * value / top)
                drawRoundRect(
                    color = when {
                        index == selected -> selectedColor
                        value <= 0 -> emptyColor
                        else -> barColor
                    },
                    topLeft = Offset(index * slot + (slot - barWidth) / 2, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius,
                )
            }
            if (goal != null && goal > 0) {
                val y = size.height - size.height * goal / top
                drawLine(
                    color = goalColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            // Labels may be wider than a thin bar's slot (month view).
                            modifier = Modifier.wrapContentWidth(unbounded = true),
                        )
                    }
                }
            }
        }
    }
}
