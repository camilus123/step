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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import si.marko.step.MainViewModel
import si.marko.step.R
import si.marko.step.diag.DiagSection

/**
 * For now this tab holds the diagnostics. The goal setting, export and import and the HyperOS
 * setup guide join it in stage 5.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onCopy: (String) -> Unit) {
    val steps by vm.todaySteps.collectAsState(initial = 0L)
    val running by vm.serviceRunning.collectAsState()
    val scope = rememberCoroutineScope()

    // Keep the diagnostics in step with the count and the service state.
    LaunchedEffect(steps, running) { vm.refresh() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleLarge) }
        item { Text(stringResource(R.string.diagnostics_explanation), style = MaterialTheme.typography.bodyMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { onCopy(vm.buildReport().toText()) } }) {
                    Text(stringResource(R.string.copy_diagnostics))
                }
                OutlinedButton(onClick = vm::refresh) { Text(stringResource(R.string.refresh)) }
            }
        }
        items(vm.report.sections) { SectionCard(it) }

        item { Text(stringResource(R.string.events_title), style = MaterialTheme.typography.titleLarge) }
        if (vm.report.events.isEmpty()) {
            item { Text(stringResource(R.string.events_empty)) }
        } else {
            // Newest first on screen; the copied text keeps chronological order.
            items(vm.report.events.asReversed()) {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
