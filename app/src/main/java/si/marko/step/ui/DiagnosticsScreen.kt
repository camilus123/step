package si.marko.step.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import si.marko.step.MainViewModel
import si.marko.step.R
import si.marko.step.diag.DiagSection
import si.marko.step.diag.EventLog
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    vm: MainViewModel,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onCopy: (String) -> Unit,
) {
    // Reading these makes the report rebuild whenever any of them changes.
    val reading = vm.reading
    val tick = vm.refreshTick
    val events = vm.events
    val listening = vm.listening
    val report = remember(reading, tick, events, listening) { vm.buildReport() }
    val numbers = remember { NumberFormat.getIntegerInstance() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name) + " · " + stringResource(R.string.stage1_title)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.raw_value_label), style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = when {
                                !vm.hasSensor -> stringResource(R.string.raw_value_no_sensor)
                                !vm.canReadSensor -> stringResource(R.string.raw_value_no_permission)
                                reading == null -> stringResource(R.string.raw_value_waiting)
                                else -> numbers.format(reading.rawSteps)
                            },
                            style = if (reading != null) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val first = vm.firstRawThisSession
                        if (reading != null && first != null) {
                            Text(stringResource(R.string.session_steps, numbers.format(reading.rawSteps - first)))
                            Text(
                                stringResource(
                                    R.string.last_reading,
                                    EventLog.TIME_FORMAT.format(
                                        Instant.ofEpochMilli(reading.eventWallMillis).atZone(ZoneId.systemDefault())
                                    ),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (!vm.hasSensor) {
                item { Text(stringResource(R.string.no_sensor_message), color = MaterialTheme.colorScheme.error) }
            }

            // "tick" is read above, so this is re-evaluated after every permission change.
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

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onCopy(vm.buildReport().toText()) }) { Text(stringResource(R.string.copy_diagnostics)) }
                    OutlinedButton(onClick = vm::refresh) { Text(stringResource(R.string.refresh)) }
                }
            }

            item { Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleLarge) }
            items(report.sections) { SectionCard(it) }

            item { Text(stringResource(R.string.events_title), style = MaterialTheme.typography.titleLarge) }
            if (report.events.isEmpty()) {
                item { Text(stringResource(R.string.events_empty)) }
            } else {
                // Newest first on screen; the copied text keeps chronological order.
                items(report.events.asReversed()) {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(section: DiagSection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium)
                for ((key, value) in section.rows) {
                    Text(key, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
