package si.marko.step.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** The themes the app offers. Only the system one exists for now; add entries here later. */
enum class AppTheme { SYSTEM }

@Composable
fun StepTheme(theme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when (theme) {
        AppTheme.SYSTEM -> when {
            // Follows the wallpaper-based system colours on Android 12 and later.
            Build.VERSION.SDK_INT >= 31 ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
