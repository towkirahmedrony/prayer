package io.github.towkirahmedrony.salah

import android.os.Bundle
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSyncTrigger
import io.github.towkirahmedrony.salah.core.domain.dataset.sync.DatasetSynchronizer
import io.github.towkirahmedrony.salah.core.domain.location.LocationSetupState
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSyncTrigger
import io.github.towkirahmedrony.salah.core.domain.prayerconfig.sync.PrayerConfigSynchronizer
import io.github.towkirahmedrony.salah.feature.app.AppViewModel
import io.github.towkirahmedrony.salah.navigation.SalahApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.context.GlobalContext

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

        // A foreground trigger for the remote dataset check. Entirely non-blocking and off the main
        // thread: the synchroniser's own throttle suppresses it if a check ran recently, and everything
        // it can do is optional — the app is fully usable while it runs and if it fails. It is started
        // here rather than awaited anywhere because nothing on the screen depends on it.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                GlobalContext.get().get<DatasetSynchronizer>().synchronize(DatasetSyncTrigger.FOREGROUND)
            }
        }

        // The same foreground trigger for the remote prayer configuration. It is the counterpart of the
        // dataset check above and of the trigger model the synchronisers document
        // (`docs/DATA_RELIABILITY.md` §5): the published calculation configuration is re-checked when the
        // app comes to the foreground, not only at the next cold start, so a revision that became current
        // while the app was backgrounded is adopted on the next foreground. Shaped exactly like the dataset
        // trigger — off the main thread, `runCatching`, not awaited anywhere — because the
        // prayer-configuration synchroniser owns its own throttle, consults the same network gate before any
        // request, and can only leave the active configuration unchanged when anything fails. Nothing on
        // screen depends on it, so the first frame and the location gate never wait on it.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                GlobalContext.get()
                    .get<PrayerConfigSynchronizer>()
                    .synchronize(PrayerConfigSyncTrigger.FOREGROUND)
            }
        }

        // Temporary: record when the location gate stops being undecided. It is written to Logcat
        // (`adb logcat -s SalahStartup`) with every other mark, not to a dialog.
        lifecycleScope.launch {
            val setup = appViewModel.locationSetup.first { it !is LocationSetupState.Checking }
            StartupTrace.mark("gate_open")
            // `gate_open` is true for every state that is not `Checking`, which includes the two that
            // send the user to location setup. Only `Complete` reaches the Home screen, so which one
            // it was is recorded separately rather than being read into `gate_open`.
            StartupTrace.mark(
                if (setup is LocationSetupState.Complete) "gate_complete" else "gate_requires_setup",
            )
        }
    }
}
