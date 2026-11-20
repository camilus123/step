package si.marko.step

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import si.marko.step.ui.MainScreen
import si.marko.step.ui.SettingsActions
import si.marko.step.ui.StepTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionResult(it) }

    // The system file picker. The result is null when the user backs out.
    private val exportJsonLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportJson) }
    private val exportCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::exportCsv) }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importJson) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val actions = SettingsActions(
            requestPermissions = { permissionLauncher.launch(vm.missingPermissions()) },
            openAppSettings = { open("app settings", appDetails()) },
            allowBackground = ::requestBatteryExemption,
            openAutostart = ::openAutostart,
            openBatterySaver = ::openBatterySaver,
            openNotificationSettings = ::openNotificationSettings,
            exportJson = { exportJsonLauncher.launch(vm.suggestedBackupName("json")) },
            exportCsv = { exportCsvLauncher.launch(vm.suggestedBackupName("csv")) },
            // Some file managers do not label .json files as JSON, so everything is offered.
            importJson = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            copy = ::copyToClipboard,
        )
        setContent {
            StepTheme {
                MainScreen(vm = vm, actions = actions)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Starts the counting service if it is not running, and picks up permissions or
        // settings that were changed in system settings while we were away.
        vm.onForeground()
    }

    // ---- system settings pages -----------------------------------------------------------

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    /**
     * Opens the first of [candidates] that the phone accepts. The Xiaomi pages are not part of
     * Android and may be missing or closed to other apps on some versions, so each attempt can
     * fail; the last candidate is always a standard Android page. Which one opened goes to the
     * event log, because that is the only way to learn it from a phone nobody can look at.
     */
    private fun open(what: String, vararg candidates: Intent) {
        candidates.forEachIndexed { index, intent ->
            try {
                startActivity(intent)
                vm.note("Opened $what (option ${index + 1} of ${candidates.size})")
                return
            } catch (e: Exception) {
                // Try the next one.
            }
        }
        vm.note("Could not open $what")
    }

    /** Shows the system dialog that exempts the app from battery optimisation. */
    @SuppressLint("BatteryLife") // Sideloaded personal app; counting all day is its whole purpose.
    private fun requestBatteryExemption() = open(
        "battery optimisation dialog",
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(),
    )

    /** Xiaomi's list of apps allowed to start by themselves. */
    private fun openAutostart() = open(
        "autostart settings",
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        appDetails(),
    )

    /** Xiaomi's per-app battery saver page ("No restrictions"). */
    private fun openBatterySaver() = open(
        "battery saver settings",
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", packageName)
            .putExtra("package_label", getString(R.string.app_name)),
        appDetails(),
    )

    private fun openNotificationSettings() = open(
        "notification settings",
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        appDetails(),
    )

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Step diagnostics", text))
        // Android 13 and later show their own confirmation.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
