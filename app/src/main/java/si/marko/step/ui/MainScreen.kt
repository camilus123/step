package si.marko.step.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import si.marko.step.MainViewModel
import si.marko.step.R

private enum class Tab(val label: Int, val icon: Int) {
    TODAY(R.string.tab_today, R.drawable.ic_stat_steps),
    STATS(R.string.tab_stats, R.drawable.ic_tab_stats),
    SETTINGS(R.string.tab_settings, R.drawable.ic_tab_settings),
}

/** The app's frame: a title, the three screens and the bar to switch between them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    actions: SettingsActions,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[tabIndex]

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = entry == tab,
                        onClick = { tabIndex = entry.ordinal },
                        icon = { Icon(painterResource(entry.icon), contentDescription = null) },
                        label = { Text(stringResource(entry.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.TODAY -> TodayScreen(vm, actions.requestPermissions, actions.openAppSettings, actions.allowBackground)
                Tab.STATS -> StatsScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm, actions)
            }
        }
    }
}
