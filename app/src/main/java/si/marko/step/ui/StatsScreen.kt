package si.marko.step.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import si.marko.step.MainViewModel
import si.marko.step.R
import si.marko.step.core.Period
import si.marko.step.core.PeriodKind
import si.marko.step.core.PeriodStats
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_SHORT = DateTimeFormatter.ofPattern("EEE")
private val DAY_AND_MONTH = DateTimeFormatter.ofPattern("d MMM")
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy")
private val WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM")
private val MONTH_AND_YEAR = DateTimeFormatter.ofPattern("LLLL yyyy")
private val MONTH_FULL = DateTimeFormatter.ofPattern("LLLL")
private val MONTH_NARROW = DateTimeFormatter.ofPattern("LLLLL")

private fun title(period: Period): String = when (period.kind) {
    PeriodKind.WEEK -> DAY_AND_MONTH.format(period.start) + " – " + DAY_MONTH_YEAR.format(period.end)
    PeriodKind.MONTH -> MONTH_AND_YEAR.format(period.start).replaceFirstChar(Char::titlecase)
    PeriodKind.YEAR -> period.start.year.toString()
}

private fun barLabel(kind: PeriodKind, start: LocalDate): String = when (kind) {
    PeriodKind.WEEK -> DAY_SHORT.format(start)
    // Every fifth day, so the numbers do not run into each other.
    PeriodKind.MONTH -> if (start.dayOfMonth == 1 || start.dayOfMonth % 5 == 0) start.dayOfMonth.toString() else ""
    PeriodKind.YEAR -> MONTH_NARROW.format(start)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: MainViewModel) {
    val stats: PeriodStats? by vm.stats.collectAsState(initial = null)
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val current = stats ?: return
    val period = current.period
    var selected by remember(period) { mutableStateOf<Int?>(null) }
    val isLatest = LocalDate.now() in period

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val kinds = listOf(
                PeriodKind.WEEK to R.string.stats_week,
                PeriodKind.MONTH to R.string.stats_month,
                PeriodKind.YEAR to R.string.stats_year,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { index, (kind, label) ->
                    SegmentedButton(
                        selected = period.kind == kind,
                        onClick = { vm.showStats(kind) },
                        shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                    ) { Text(stringResource(label)) }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val earlier = stringResource(R.string.stats_earlier)
                val later = stringResource(R.string.stats_later)
                TextButton(onClick = { vm.moveStats(-1) }, modifier = Modifier.semantics { contentDescription = earlier }) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    title(period),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(
                    onClick = { vm.moveStats(1) },
                    enabled = !isLatest,
                    modifier = Modifier.semantics { contentDescription = later },
                ) {
                    Text("›", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BarChart(
                        values = current.bars.map { it.steps },
                        labels = current.bars.map { barLabel(period.kind, it.start) },
                        // The daily goal only means something next to daily bars.
                        goal = if (period.kind == PeriodKind.YEAR) null else current.goalLine.toLong(),
                        selected = selected,
                        onSelect = { selected = it },
                    )
                    val bar = selected?.let { current.bars.getOrNull(it) }
                    Text(
                        text = when {
                            bar == null -> stringResource(R.string.chart_hint)
                            period.kind == PeriodKind.YEAR -> stringResource(
                                R.string.bar_value,
                                MONTH_FULL.format(bar.start).replaceFirstChar(Char::titlecase),
                                numbers.format(bar.steps),
                            )
                            else -> stringResource(R.string.bar_value, WEEKDAY_DAY_MONTH.format(bar.start), numbers.format(bar.steps))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryRow(stringResource(R.string.stats_total), numbers.format(current.total))
                    SummaryRow(stringResource(R.string.stats_average), numbers.format(current.dailyAverage))
                    SummaryRow(
                        stringResource(R.string.stats_best_day),
                        current.bestDay?.let {
                            stringResource(R.string.bar_value, WEEKDAY_DAY_MONTH.format(it.date), numbers.format(it.steps))
                        } ?: "–",
                    )
                    SummaryRow(
                        stringResource(R.string.stats_goal_days),
                        pluralStringResource(R.plurals.stats_goal_days_value, current.countedDays, current.goalDays, current.countedDays),
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
