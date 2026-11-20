package si.marko.step.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import si.marko.step.MainViewModel
import si.marko.step.R
import si.marko.step.core.Goal
import java.text.NumberFormat

/** Green that reads well on both light and dark surfaces. */
val GoalReachedGreen = Color(0xFF2E9E4F)

@Composable
fun TodayScreen(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onAllowBackground: () -> Unit,
) {
    val steps by vm.todaySteps.collectAsState(initial = 0L)
    val hours by vm.todayHours.collectAsState(initial = List(24) { 0L })
    val running by vm.serviceRunning.collectAsState()
    val tick = vm.refreshTick
    val numbers = remember { NumberFormat.getIntegerInstance() }
    var selectedHour by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.today), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = numbers.format(steps),
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (Goal.reached(steps, vm.goal)) {
                            Text(
                                stringResource(R.string.goal_reached_mark),
                                style = MaterialTheme.typography.displayMedium,
                                color = GoalReachedGreen,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { (steps.toFloat() / vm.goal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.goal_progress, Goal.percent(steps, vm.goal), numbers.format(vm.goal)))
                    Text(
                        stringResource(if (running) R.string.status_counting else R.string.status_not_counting),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (running) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (!vm.hasSensor) {
            item { Text(stringResource(R.string.no_sensor_message), color = MaterialTheme.colorScheme.error) }
        }

        // "tick" is read here so these blocks are re-evaluated after every return to the app.
        if (tick >= 0 && vm.missingPermissions().isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.permission_explanation))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestPermissions) { Text(stringResource(R.string.grant_permissions)) }
                        OutlinedButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.open_app_settings)) }
                    }
                }
            }
        }

        if (tick >= 0 && !vm.isExemptFromBatteryOptimisation()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.battery_explanation))
                    Button(onClick = onAllowBackground) { Text(stringResource(R.string.allow_background)) }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.by_hour), style = MaterialTheme.typography.titleMedium)
                    BarChart(
                        values = hours,
                        labels = List(24) { if (it % 6 == 0) it.toString() else "" },
                        selected = selectedHour,
                        onSelect = { selectedHour = it },
                    )
                    val hour = selectedHour
                    Text(
                        text = if (hour == null) {
                            stringResource(R.string.chart_hint)
                        } else {
                            stringResource(R.string.hour_value, hour, (hour + 1) % 24, numbers.format(hours.getOrElse(hour) { 0L }))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
