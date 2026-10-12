package io.github.towkirahmedrony.salah

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState
import io.github.towkirahmedrony.salah.feature.app.AppViewModel
import io.github.towkirahmedrony.salah.navigation.SalahApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/** Single-activity host for the Compose UI. */
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupTrace.mark("activity_onCreate")
        enableEdgeToEdge()
        setContent {
            SalahApp()
        }
        StartupTrace.mark("setContent_called")

        // Temporary: show the full startup timeline once the location gate opens.
        lifecycleScope.launch {
            appViewModel.locationSetup.first { it !is LocationSetupState.Checking }
            StartupTrace.mark("gate_open")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Startup (ms since process start)")
                .setMessage(StartupTrace.summary())
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
