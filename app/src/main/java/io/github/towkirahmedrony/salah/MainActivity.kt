package io.github.towkirahmedrony.salah

import android.app.AlertDialog
import android.os.Bundle
import android.view.Choreographer
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

        // The first frame the framework is about to run after setContent — the closest thing to an
        // "opened" signal available without adding a tracing dependency.
        //
        // It is NOT proof that Compose content has been drawn. It marks the start of the first
        // frame's traversal, and the first frame here is the skeleton, not the Home screen; nothing in
        // this trace separates "the skeleton was drawn" from "the Home content was drawn". Read it as
        // "the UI was ready to draw its first frame", nothing stronger.
        Choreographer.getInstance().postFrameCallback {
            StartupTrace.mark("first_frame_callback")
        }

        // Temporary: show the full startup timeline once the location gate opens.
        lifecycleScope.launch {
            val setup = appViewModel.locationSetup.first { it !is LocationSetupState.Checking }
            StartupTrace.mark("gate_open")
            // `gate_open` is true for every state that is not `Checking`, which includes the two that
            // send the user to location setup. Only `Complete` reaches the Home screen, so which one
            // it was is recorded separately rather than being read into `gate_open`.
            StartupTrace.mark(
                if (setup is LocationSetupState.Complete) "gate_complete" else "gate_requires_setup",
            )
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Startup (ms since process start)")
                .setMessage(StartupTrace.summary())
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
