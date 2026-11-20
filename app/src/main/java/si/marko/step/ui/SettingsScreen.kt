package si.marko.step.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import si.marko.step.BuildConfig
import si.marko.step.MainViewModel
import si.marko.step.R
import si.marko.step.core.Goal
import si.marko.step.diag.DiagSection
import java.text.NumberFormat

/** Everything the settings screen can ask the activity to do. */
class SettingsActions(
    val requestPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val allowBackground: () -> Unit,
    val openAutostart: () -> Unit,
    val openBatterySaver: () -> Unit,
    val openNotificationSettings: () -> Unit,
    val exportJson: () -> Unit,
    val exportCsv: () -> Unit,
    val importJson: () -> Unit,
    val copy: (String) -> Unit,
)

@Composable
fun SettingsScreen(vm: MainViewModel, actions: SettingsActions) {
    val steps by vm.todaySteps.collectAsState(initial = 0L)
    val running by vm.serviceRunning.collectAsState()
    val goal by vm.goalToday.collectAsState(initial = Goal.DEFAULT)
    val tick = vm.refreshTick
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val scope = rememberCoroutineScope()
    var editingGoal by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    // Keep the diagnostics in step with the count and the service state.
    LaunchedEffect(steps, running) { vm.refresh() }

    if (editingGoal) {
        GoalDialog(
            current = goal,
            onSave = {
                vm.setGoal(it)
                editingGoal = false
            },
            onDismiss = { editingGoal = false },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- goal ----
        item {
            SettingsCard(stringResource(R.string.goal_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.goal_value, numbers.format(goal)),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { editingGoal = true }) { Text(stringResource(R.string.goal_change)) }
                }
                Hint(stringResource(R.string.goal_explanation))
            }
        }

        // ---- export and import ----
        item {
            SettingsCard(stringResource(R.string.backup_title)) {
                Hint(stringResource(R.string.backup_explanation))
                Button(onClick = actions.exportJson, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_json)) }
                OutlinedButton(onClick = actions.importJson, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_json)) }
                OutlinedButton(onClick = actions.exportCsv, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_csv)) }
                vm.backupMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (vm.backupFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // ---- setup guide ----
        item {
            // "tick" is read so the statuses are re-evaluated after every return to the app.
            val permissionsOk = tick >= 0 && vm.missingPermissions().isEmpty()
            val backgroundOk = tick >= 0 && vm.isExemptFromBatteryOptimisation()
            SettingsCard(stringResource(R.string.setup_title)) {
                Hint(stringResource(R.string.setup_explanation))
                SetupItem(
                    title = stringResource(R.string.setup_permissions_title),
                    text = stringResource(R.string.setup_permissions_text),
                    status = permissionsOk,
                    button = if (permissionsOk) null else stringResource(R.string.grant_permissions),
                    onClick = actions.requestPermissions,
                    secondButton = if (permissionsOk) null else stringResource(R.string.open_app_settings),
                    onSecondClick = actions.openAppSettings,
                )
                HorizontalDivider()
                SetupItem(
                    title = stringResource(R.string.setup_background_title),
                    text = stringResource(R.string.setup_background_text),
                    status = backgroundOk,
                    button = if (backgroundOk) null else stringResource(R.string.allow_background),
                    onClick = actions.allowBackground,
                )
                HorizontalDivider()
                SetupItem(
                    title = stringResource(R.string.setup_autostart_title),
                    text = stringResource(R.string.setup_autostart_text),
                    status = null,
                    button = stringResource(R.string.setup_autostart_button),
                    onClick = actions.openAutostart,
                )
                HorizontalDivider()
                SetupItem(
                    title = stringResource(R.string.setup_battery_title),
                    text = stringResource(R.string.setup_battery_text),
                    status = null,
                    button = stringResource(R.string.setup_battery_button),
                    onClick = actions.openBatterySaver,
                )
                HorizontalDivider()
                SetupItem(
                    title = stringResource(R.string.setup_lockscreen_title),
                    text = stringResource(R.string.setup_lockscreen_text),
                    status = null,
                    button = stringResource(R.string.setup_lockscreen_button),
                    onClick = actions.openNotificationSettings,
                )
                HorizontalDivider()
                SetupItem(
                    title = stringResource(R.string.setup_recents_title),
                    text = stringResource(R.string.setup_recents_text),
                    status = null,
                )
            }
        }

        // ---- about and diagnostics ----
        item {
            SettingsCard(stringResource(R.string.about_title)) {
                Hint(stringResource(R.string.about_text, BuildConfig.VERSION_NAME))
                Hint(stringResource(R.string.diagnostics_explanation))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { actions.copy(vm.buildReport().toText()) } }) {
                        Text(stringResource(R.string.copy_diagnostics))
                    }
                    OutlinedButton(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(stringResource(if (showDiagnostics) R.string.hide_diagnostics else R.string.show_diagnostics))
                    }
                }
            }
        }

        if (showDiagnostics) {
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
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * One line of the setup guide.
 *
 * @param status true: done, false: not set, null: the app cannot tell, check by hand.
 */
@Composable
private fun SetupItem(
    title: String,
    text: String,
    status: Boolean?,
    button: String? = null,
    onClick: () -> Unit = {},
    secondButton: String? = null,
    onSecondClick: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    when (status) {
                        true -> R.string.setup_status_ok
                        false -> R.string.setup_status_missing
                        null -> R.string.setup_status_manual
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = when (status) {
                    true -> GoalReachedGreen
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Hint(text)
        if (button != null || secondButton != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (button != null) OutlinedButton(onClick = onClick) { Text(button) }
                if (secondButton != null) TextButton(onClick = onSecondClick) { Text(secondButton) }
            }
        }
    }
}

@Composable
private fun GoalDialog(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val parsed = Goal.parse(text)
    val numbers = remember { NumberFormat.getIntegerInstance() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter(Char::isDigit).take(7) },
                label = { Text(stringResource(R.string.goal_dialog_label)) },
                singleLine = true,
                isError = parsed == null,
                supportingText = {
                    if (parsed == null) {
                        Text(stringResource(R.string.goal_dialog_error, numbers.format(Goal.MIN), numbers.format(Goal.MAX)))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
