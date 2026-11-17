package si.marko.step

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
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
import si.marko.step.ui.DiagnosticsScreen
import si.marko.step.ui.StepTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionResult(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            StepTheme {
                DiagnosticsScreen(
                    vm = vm,
                    onRequestPermissions = { permissionLauncher.launch(vm.missingPermissions()) },
                    onOpenAppSettings = ::openAppSettings,
                    onAllowBackground = ::requestBatteryExemption,
                    onCopy = ::copyToClipboard,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Starts the counting service if it is not running, and picks up permissions or
        // settings that were changed in system settings while we were away.
        vm.onForeground()
    }

    /** Shows the system dialog that exempts the app from battery optimisation. */
    @SuppressLint("BatteryLife") // Sideloaded personal app; counting all day is its whole purpose.
    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
        runCatching { startActivity(direct) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { openAppSettings() }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Step diagnostics", text))
        // Android 13 and later show their own confirmation.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
